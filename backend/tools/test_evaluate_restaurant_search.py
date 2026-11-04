"""验证本地实验的危险边界和跨字段查询语义；不连接真实库或创建索引。"""
import io
import json
import unittest
from unittest.mock import Mock

from evaluate_restaurant_search import PREFIX, bulk_import, matches, query_body, remove_owned_index
from setup_restaurant_search_index import LocalElasticsearch


class SearchEvaluationSafetyTest(unittest.TestCase):
    """工具不得把实验写入/清理导向正式资源，bulk部分失败必须阻止有效结果发布。"""

    def test_formal_indices_aliases_and_wildcards_are_never_written_or_deleted(self):
        client = Mock(spec=LocalElasticsearch)
        for target in ("restaurant-search", "restaurant-search-v002", PREFIX + "*", PREFIX + "not-a-uuid"):
            with self.subTest(target=target):
                with self.assertRaises(ValueError):
                    bulk_import(client, target, [{"id": 1}])
                with self.assertRaises(ValueError):
                    remove_owned_index(client, target)
        client.request.assert_not_called()

    def test_owned_cleanup_uses_exact_index_path(self):
        client = Mock(spec=LocalElasticsearch)
        index = PREFIX + "a" * 32
        remove_owned_index(client, index)
        client.request.assert_called_once_with("DELETE", "/" + index)

    def test_bulk_checks_partial_failure_wrong_id_wrong_index_and_missing_items(self):
        index = PREFIX + "a" * 32
        valid = {"status": 201, "_index": index, "_id": "1"}
        for result in ({"errors": True, "items": [{"index": {**valid, "status": 400}}]},
                       {"errors": False, "items": []},
                       {"errors": False, "items": [{"index": {**valid, "_id": "2"}}]},
                       {"errors": False, "items": [{"index": {**valid, "_index": "restaurant-search-v002"}}]}):
            client = Mock()
            client.url = "http://127.0.0.1:9200"
            client.opener.open.return_value = io.BytesIO(json.dumps(result).encode())
            with self.subTest(result=result), self.assertRaises(RuntimeError):
                bulk_import(client, index, [{"id": 1}])

    def test_bulk_preserves_large_ids_and_uses_scoped_ndjson(self):
        index = PREFIX + "a" * 32
        client = Mock()
        client.url = "http://127.0.0.1:9200"
        result = {"errors": False, "items": [{"index": {"status": 201, "_index": index, "_id": "9007199254740993"}}]}
        client.opener.open.return_value = io.BytesIO(json.dumps(result).encode())
        bulk_import(client, index, [{"id": 9007199254740993, "name": "真实中文"}])
        request = client.opener.open.call_args.args[0]
        lines = request.data.decode("utf-8").splitlines()
        self.assertEqual(json.loads(lines[0]), {"index": {"_id": "9007199254740993"}})
        self.assertEqual(json.loads(lines[1])["id"], 9007199254740993)
        self.assertNotIn("_index", lines[0])
        self.assertTrue(request.full_url.endswith("/" + index + "/_bulk"))


class SearchEvaluationQueryTest(unittest.TestCase):
    """确保相关集来自预先的业务字段条件；AND可以在店名和地址之间共同满足。"""

    def test_expected_combination_requires_every_condition_and_handles_nulls(self):
        rule = {"all": [{"anyNameOrAddress": "清水河"}, {"name": "咖啡"}]}
        self.assertTrue(matches({"name": "咖啡店", "address": "清水河"}, rule))
        self.assertFalse(matches({"name": "咖啡店", "address": None}, rule))
        self.assertFalse(matches({"name": "面馆", "address": "清水河"}, rule))
        self.assertFalse(matches({"name": "清水河咖啡"}, {"none": True}))

    def test_and_uses_cross_fields_and_still_filters_deleted_documents(self):
        body = query_body("清水河咖啡", "smart_and", 100)
        boolean = body["query"]["bool"]
        self.assertEqual(boolean["filter"], [{"term": {"deleted": False}}])
        fulltext = boolean["must"][0]["bool"]["should"][1]["multi_match"]
        self.assertEqual(fulltext["type"], "cross_fields")
        self.assertEqual(fulltext["operator"], "and")
        self.assertEqual(fulltext["analyzer"], "ik_smart")
        self.assertEqual(query_body("词", "max_and", 1)["query"]["bool"]["must"][0]["bool"]["should"][1]["multi_match"]["analyzer"], "ik_max_word")
        with self.assertRaises(ValueError):
            query_body("词", "unknown", 1)


if __name__ == "__main__":
    unittest.main()
