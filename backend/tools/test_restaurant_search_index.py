"""验证初始化边界及真实ES字段语义；真实测试只读写本次创建的随机隔离索引。"""
import copy
import os
import re
import unittest
from unittest.mock import Mock
import uuid

from setup_restaurant_search_index import (
    ALIAS, INDEX, ElasticsearchRequestError, LocalElasticsearch, load_spec, setup,
)


class IndexInitializationSafetyTest(unittest.TestCase):
    """运维工具遇到配置或资源冲突时必须停止，不能改写其他项目的数据。"""

    def test_rejects_remote_credentials_and_non_root_urls(self):
        for url in ("https://127.0.0.1:9200", "http://example.com:9200", "http://user:pass@localhost:9200",
                    "http://localhost:9200/other", "http://localhost:9200?x=1", "http://localhost:9200#x"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                LocalElasticsearch(url)

    def test_never_rebinds_an_existing_alias(self):
        client = Mock(spec=LocalElasticsearch)
        client.optional.side_effect = [None, {"another-project": {"aliases": {ALIAS: {}}}}]
        with self.assertRaisesRegex(RuntimeError, "别名已指向其他索引"):
            setup(client)
        client.request.assert_not_called()

    def test_check_does_not_create_missing_resources(self):
        client = Mock(spec=LocalElasticsearch)
        client.optional.return_value = None
        with self.assertRaisesRegex(RuntimeError, "尚未建立"):
            setup(client, check_only=True)
        client.request.assert_not_called()

    def test_mapping_drift_does_not_trigger_a_repair_write(self):
        client = Mock(spec=LocalElasticsearch)
        client.optional.side_effect = [{INDEX: {}}, {INDEX: {"aliases": {ALIAS: {"is_write_index": True}}}}]
        changed = copy.deepcopy(load_spec()["mappings"])
        changed["properties"]["pricePerPerson"]["type"] = "text"
        client.request.return_value = {INDEX: {"mappings": changed}}
        with self.assertRaisesRegex(RuntimeError, "mapping与版本定义不一致"):
            setup(client)
        self.assertTrue(all(call.args[0] == "GET" for call in client.request.call_args_list))

    def test_current_schema_adds_only_four_character_subfields_and_preserves_source_contract(self):
        current, previous = load_spec(), load_spec("002")
        self.assertEqual(current['mappings']['_meta']['schema_version'], '003')
        self.assertEqual(set(current['mappings']['properties']), set(previous['mappings']['properties']))
        for name in ('name', 'address', 'categoryName', 'tags'):
            field = current['mappings']['properties'][name]['fields']['chars']
            self.assertEqual(field['index_options'], 'docs')
            self.assertFalse(field['norms'])
        with self.assertRaises(ValueError):load_spec('../../other')


@unittest.skipUnless(os.environ.get("GINKGO_ES_SCHEMA_IT") == "true", "显式设置GINKGO_ES_SCHEMA_IT=true才创建隔离ES测试索引")
class ElasticsearchSchemaIntegrationTest(unittest.TestCase):
    """在安装IK的真实ES验证空值、分词、精确过滤、数字和地理字段，不依赖MySQL。"""

    @classmethod
    def setUpClass(cls):
        cls.client = LocalElasticsearch(os.environ.get("GINKGO_ES_URL", "http://127.0.0.1:9200"))
        cls.index = "ginkgo-restaurant-schema-it-" + uuid.uuid4().hex
        spec = load_spec()
        spec["aliases"] = {cls.index + "-read": {"is_write_index": True}}
        result = cls.client.request("PUT", "/" + cls.index, spec)
        if not result.get("acknowledged"):
            raise RuntimeError("测试索引创建未获确认")
        cls.addClassCleanup(cls.remove_owned_index)
        base = {
            "id": 1, "name": "CAFÉ火锅", "categoryCode": "HOT_POT", "categoryName": "火锅串串",
            "tags": ["学生聚餐"], "address": "清水河校区南门", "restaurantType": "REGULAR",
            "supportsDineIn": True, "supportsDelivery": False, "pricePerPerson": 25, "priceKind": "EXACT",
            "businessStatus": "OPERATING", "location": {"lat": 30.75, "lon": 103.93}, "needsLocation": False,
            "ratingScore": 8.0, "recommendRate": 0.5, "participantCount": 2, "reviewCount": 3,

            "deleted": False, "projectionVersion": 1, "coverPhotoId": "9007199254740993",
        }
        canteen = {**base, "id": 2, "name": "测试食堂", "restaurantType": "CANTEEN",
            "categoryCode": None, "categoryName": None, "tags": [], "pricePerPerson": None, "priceKind": "UNKNOWN",
            "supportsDineIn": None, "supportsDelivery": None, "location": {"lat": 30.7502, "lon": 103.9302},
            "ratingScore": None, "recommendRate": None, "participantCount": 0, "reviewCount": 0,
            "coverPhotoId": None}
        zero = {**base, "id": 10, "name": "测试面馆", "categoryCode": "FAST_FOOD", "categoryName": "小吃快餐",
            "supportsDineIn": False, "pricePerPerson": 40, "location": None, "needsLocation": True,
            "ratingScore": 0, "recommendRate": 0, "participantCount": 1, "reviewCount": 1,
            "coverPhotoId": None}
        deleted = {**base, "id": 20, "name": "已删除测试商户", "deleted": True}
        for document in (base, canteen, zero, deleted):
            cls.client.request("PUT", "/" + cls.index + "/_doc/" + str(document["id"]), document)
        cls.client.request("POST", "/" + cls.index + "/_refresh")

    @classmethod
    def remove_owned_index(cls):
        """仅清理本次已确认创建且精确匹配UUID前缀的索引，禁止通配删除及正式别名删除。"""
        if not re.fullmatch(r"ginkgo-restaurant-schema-it-[a-f0-9]{32}", cls.index):
            raise RuntimeError("Refusing to delete an unexpected index")
        cls.client.request("DELETE", "/" + cls.index)

    def search(self, query=None, sort=None):
        """显式排除删除文档；别名仅管理索引指向，不承担可见性过滤。"""
        body = {"query": {"bool": {"filter": [{"term": {"deleted": False}}],
                                   "must": [query or {"match_all": {}}]}}}
        if sort:
            body["sort"] = sort
        return self.client.request("POST", "/" + self.index + "/_search", body)["hits"]["hits"]

    def ids(self, query=None, sort=None):
        return [hit["_source"]["id"] for hit in self.search(query, sort)]

    def test_chinese_search_and_full_name_normalization(self):
        self.assertEqual(self.ids({"match": {"name": "火锅"}}), [1])
        exact = self.search({"term": {"name.exact": "CAFE火锅"}})
        self.assertEqual([hit["_source"]["id"] for hit in exact], [1])
        self.assertEqual(exact[0]["_source"]["name"], "CAFÉ火锅")

    def test_null_category_and_boolean_unknown_are_not_other_or_false(self):
        self.assertEqual(self.ids({"term": {"restaurantType": "CANTEEN"}}), [2])
        self.assertEqual(self.ids({"term": {"supportsDineIn": False}}), [10])
        self.assertEqual(self.ids({"term": {"supportsDineIn": True}}), [1])
        self.assertEqual(self.ids({"bool": {"must_not": {"exists": {"field": "supportsDineIn"}}}}), [2])
        self.assertEqual(set(self.ids({"exists": {"field": "categoryCode"}})), {1, 10})
        source = self.client.request("GET", "/" + self.index + "/_doc/2")["_source"]
        self.assertIsNone(source["categoryCode"])

    def test_integer_ranges_numeric_id_order_and_valid_zero_rating(self):
        self.assertEqual(self.ids({"range": {"pricePerPerson": {"lte": 30}}}), [1])
        self.assertEqual(self.ids(sort=[{"id": "asc"}]), [1, 2, 10])
        self.assertEqual(self.ids(sort=[{"ratingScore": {"order": "desc", "missing": "_last"}}]), [1, 10, 2])

    def test_geo_point_excludes_missing_location(self):
        query = {"geo_bounding_box": {"location": {
            "top_left": {"lat": 30.76, "lon": 103.92}, "bottom_right": {"lat": 30.74, "lon": 103.94}}}}
        self.assertEqual(set(self.ids(query)), {1, 2})
        with self.assertRaises(ElasticsearchRequestError) as caught:
            self.client.request("PUT", "/" + self.index + "/_doc/900", {"id": 900, "location": {"lat": 120, "lon": 103}})
        self.assertEqual(caught.exception.status, 400)

    def test_unknown_fields_and_numeric_coercion_are_rejected(self):
        for body in ({"id": 900, "unexpectedField": "typo"}, {"id": "900"},
                     {"id": 900, "pricePerPerson": "20"}, {"id": 900, "pricePerPerson": 20.5},
                     {"id": 900, "ratingScore": "8.0"}):
            with self.subTest(body=body), self.assertRaises(ElasticsearchRequestError) as caught:
                self.client.request("PUT", "/" + self.index + "/_doc/900", body)
            self.assertEqual(caught.exception.status, 400)
        self.assertEqual(self.client.request("GET", "/" + self.index + "/_count")["count"], 4)

    def test_both_ik_analyzers_and_keyword_normalizer_work(self):
        for analyzer in ("ik_max_word", "ik_smart"):
            result = self.client.request("POST", "/" + self.index + "/_analyze",
                                         {"analyzer": analyzer, "text": "电子科技大学火锅"})
            self.assertIn("火锅", [token["token"] for token in result["tokens"]])
        result = self.client.request("POST", "/" + self.index + "/_analyze", {"field": "name.exact", "text": "CAFÉ火锅"})
        self.assertEqual([token["token"] for token in result["tokens"]], ["cafe火锅"])

    def test_cover_reference_is_preserved_and_removed_fields_are_rejected(self):
        # 正式相册关联ID仅供展示，保留字符串大整数，未知封面保留null。
        source = self.client.request("GET", "/" + self.index + "/_doc/1")["_source"]
        self.assertEqual(source["coverPhotoId"], "9007199254740993")
        for field in ("tier", "createdAt", "updatedAt", "tasteScore", "valueScore", "serviceScore", "ambienceScore"):
            with self.subTest(field=field), self.assertRaises(ElasticsearchRequestError) as caught:
                self.client.request("PUT", "/" + self.index + "/_doc/900", {"id": 900, field: "unexpected"})
            self.assertEqual(caught.exception.status, 400)

    def test_character_subfields_cover_all_four_sources_and_single_codepoint_unicode(self):
        for field, word in [('name.chars','锅'),('address.chars','河'),('categoryName.chars','串'),('tags.chars','聚')]:
            with self.subTest(field=field):self.assertIn(1, self.ids({'term': {field: word}}))
        # 〇属于Han脚本的数字类，不能被letter/digit白名单误删；补充平面字不能被拆成代理项。
        result=self.client.request('POST','/'+self.index+'/_analyze',{'field':'name.chars','text':'A茶𠀀〇々〻1'})
        self.assertEqual([token['token'] for token in result['tokens']],list('茶𠀀〇々〻'))
        source=self.client.request('GET','/'+self.index+'/_doc/1')['_source']
        self.assertNotIn('name.chars',source)


if __name__ == "__main__":
    unittest.main()
