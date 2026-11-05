"""验证实际升级的关键拒绝路径，确保校验失败不切别名或删除旧索引。"""
import unittest
from unittest.mock import Mock, patch

from rebuild_restaurant_search_index import ALIAS, INDEX, PREVIOUS, alias_target, copy_and_switch, snapshot


class RebuildSafetyTest(unittest.TestCase):
    def test_rejects_multiple_or_unexpected_alias_targets(self):
        for state in ({'other': {}}, {PREVIOUS: {}, INDEX: {}}):
            client = Mock()
            client.request.return_value = state
            with self.assertRaises(RuntimeError):alias_target(client)
            self.assertTrue(all(call.args[0] == 'GET' for call in client.request.call_args_list))

    def test_rejects_filtered_alias(self):
        client = Mock()
        client.request.return_value = {PREVIOUS: {'aliases': {ALIAS: {'is_write_index': True, 'filter': {}}}}}
        with self.assertRaises(RuntimeError):alias_target(client)

    def test_rejects_partial_and_truncated_snapshots(self):
        client = Mock()
        client.request.return_value = {'timed_out': False, '_shards': {'failed': 0},
            'hits': {'total': {'relation': 'eq', 'value': 2}, 'hits': []}}
        with self.assertRaises(RuntimeError):snapshot(client, PREVIOUS)

    @patch('rebuild_restaurant_search_index.alias_target', return_value=PREVIOUS)
    @patch('rebuild_restaurant_search_index.verify_candidate')
    def test_partial_reindex_keeps_old_alias(self, verify, target):
        client = Mock()
        client.optional.return_value = {INDEX: {}}
        client.request.return_value = {'total': 1, 'failures': [{'reason': 'failed'}]}
        with self.assertRaises(RuntimeError):copy_and_switch(client, {'1': {}})
        self.assertFalse(any(call.args[1] == '/_aliases' or call.args[0] == 'DELETE'
                             for call in client.request.call_args_list))

    @patch('rebuild_restaurant_search_index.alias_target', return_value=PREVIOUS)
    @patch('rebuild_restaurant_search_index.verify_candidate')
    @patch('rebuild_restaurant_search_index.snapshot', return_value={'1': {'version': 9}})
    def test_source_version_mismatch_keeps_old_alias(self, snap, verify, target):
        client = Mock()
        client.optional.return_value = {INDEX: {}}
        client.request.return_value = {'total': 1, 'failures': []}
        with self.assertRaises(RuntimeError):copy_and_switch(client, {'1': {'version': 8}})
        self.assertFalse(any(call.args[1] == '/_aliases' for call in client.request.call_args_list))

    @patch('rebuild_restaurant_search_index.alias_target', return_value=PREVIOUS)
    @patch('rebuild_restaurant_search_index.verify_candidate')
    @patch('rebuild_restaurant_search_index.snapshot', return_value={'1': {'version': 8}})
    @patch('rebuild_restaurant_search_index.verify_definition')
    def test_preserves_external_versions_and_uses_one_atomic_switch(self, definition, snap, verify, target):
        client = Mock()
        client.optional.return_value = {INDEX: {}}
        client.request.side_effect = [{'total': 1, 'failures': []}, {}, {'acknowledged': True}]
        copy_and_switch(client, {'1': {'version': 8}})
        calls = client.request.call_args_list
        self.assertEqual(calls[0].args[2]['dest']['version_type'], 'external')
        self.assertEqual(calls[0].args[2]['conflicts'], 'proceed')
        self.assertEqual(calls[2].args[2]['actions'], [
            {'remove': {'index': PREVIOUS, 'alias': ALIAS, 'must_exist': True}},
            {'add': {'index': INDEX, 'alias': ALIAS, 'is_write_index': True}}])
        self.assertFalse(any(call.args[0] == 'DELETE' for call in calls))


if __name__ == '__main__':unittest.main()
