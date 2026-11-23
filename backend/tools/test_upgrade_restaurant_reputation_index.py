"""升级工具的版本/身份/墓碑及逐项Bulk边界，不连接业务库或真实ES。"""
from decimal import Decimal
import json
import unittest
from unittest.mock import MagicMock

from upgrade_restaurant_reputation_index import alias_target, with_reputation, write_batch, PREVIOUS, load_definition
from setup_restaurant_search_index import verify_definition, INDEX, ALIAS


class ReputationUpgradeTest(unittest.TestCase):
    def test_current_definition_and_alias_can_be_checked_without_writes(self):
        client = MagicMock()
        spec = load_definition()
        client.request.side_effect = [
            {INDEX: {"mappings": spec["mappings"], "settings": {"index": spec["settings"]}}},
            {INDEX: {"aliases": {ALIAS: {"is_write_index": True}}}},
        ]
        verify_definition(client, spec)
        self.assertTrue(all(call.args[0] == "GET" for call in client.request.call_args_list))

    def test_current_definition_indexes_reputation_without_changing_raw_rating(self):
        fields = load_definition()["mappings"]["properties"]
        self.assertEqual(fields["reputationScore"], {"type": "double", "coerce": False})
        self.assertEqual(fields["ratingScore"]["type"], "double")

    def test_preserves_original_document_and_external_version_and_keeps_tombstones_minimal(self):
        documents = {
            "1": {"source": {"id": 1, "deleted": False, "projectionVersion": 7}, "version": 7},
            "2": {"source": {"id": 2, "deleted": True, "projectionVersion": 3}, "version": 3},
        }
        rows = {
            "1": {"search_projection_version": 7, "deleted_at": None, "reputation_score": Decimal("3.75000000")},
            "2": {"search_projection_version": 3, "deleted_at": "deleted", "reputation_score": Decimal("4.3")},
        }
        result = with_reputation(documents, rows)
        self.assertEqual(result["1"]["source"]["reputationScore"], 3.75)
        self.assertEqual(result["1"]["version"], 7)
        self.assertNotIn("reputationScore", documents["1"]["source"])
        self.assertEqual(result["2"], documents["2"])
        rows["1"]["reputation_score"] = None
        self.assertNotIn("reputationScore", with_reputation(documents, rows)["1"]["source"])

    def test_refuses_missing_identity_or_unsynchronized_version(self):
        documents = {"1": {"source": {"id": 1, "deleted": False}, "version": 2}}
        with self.assertRaises(RuntimeError):
            with_reputation(documents, {})
        with self.assertRaises(RuntimeError):
            with_reputation(documents, {"1": {"search_projection_version": 3, "deleted_at": None}})

    def test_refuses_unknown_alias_and_nonwrite_alias(self):
        client = MagicMock()
        for state in ({"other": {}}, {PREVIOUS: {"aliases": {"restaurant-search": {}}}}):
            client.request.return_value = state
            with self.assertRaises(RuntimeError):
                alias_target(client)

    def test_rejects_partial_bulk_failure_and_does_not_call_alias_mutation(self):
        client = MagicMock()
        client.url = "http://127.0.0.1:9200"
        response = client.opener.open.return_value.__enter__.return_value
        response.read.return_value = json.dumps({"errors": True, "items": [{"index": {"status": 429}}]}).encode()
        with self.assertRaises(RuntimeError):
            write_batch(client, [("1", {"version": 2, "source": {"id": 1}})])
        client.request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
