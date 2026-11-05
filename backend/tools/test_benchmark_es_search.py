"""评测口径与实际引擎识别的快速回归，不连接真实库或ES。"""
import json
import unittest
from unittest.mock import patch

from benchmark_es_search import Endpoint, NEW_CASES, CASES, expected_engine, metrics, performance_cases, single_han_query, literal_search_match
from benchmark_final_es_search import accuracy_supplement, final_scenarios


class BenchmarkSearchTest(unittest.TestCase):
    def test_final_frozen_scenarios_and_supplement_do_not_overwrite_existing_targets(self):
        old = json.loads(CASES.read_text(encoding='utf-8'))['cases']
        scenarios = final_scenarios(old)
        self.assertEqual(len(scenarios), 109)
        self.assertEqual(len({row['name'] for row in scenarios}), 109)
        self.assertEqual(sum(expected_engine('search', row['params']) == 'ES' for row in scenarios), 101)
        original = {row['query'] for path in (CASES, NEW_CASES)
                    for row in json.loads(path.read_text(encoding='utf-8'))['cases']}
        added = accuracy_supplement()
        self.assertEqual(len(added), 12)
        self.assertFalse(original.intersection(row['query'] for row in added))

    def test_unicode_auto_sort_and_literal_targets_are_independent_of_mysql_collation(self):
        for word in ('茶', '𠀀', '〇', '々', '〻'):
            self.assertTrue(single_han_query(word))
        for word in ('', '茶店', '0', 'A'):
            self.assertFalse(single_han_query(word))
        self.assertFalse(literal_search_match({'name': '0号店'}, '〇'))
        self.assertTrue(literal_search_match({'tags': ['〇号推荐']}, '〇'))

    def test_metrics_separate_top_five_from_full_coverage(self):
        result = metrics([7, 1, 2, 8, 3, 4], [1, 2, 3, 4])
        self.assertEqual(result['target_precision_at_5'], 3 / 5)
        self.assertEqual(result['target_recall_at_5'], 3 / 4)
        self.assertEqual(result['full_target_coverage'], 1)
        self.assertEqual(result['first_target_rank'], 2)

    def test_short_results_use_actual_returned_precision_denominator(self):
        self.assertEqual(metrics([1], [1])['target_precision_at_5'], 1)

    def test_empty_targets_are_not_averaged_as_perfect_recall(self):
        result = metrics([], [])
        self.assertIsNone(result['full_target_coverage'])
        self.assertIsNone(result['target_recall_at_5'])
        self.assertFalse(result['hit_at_5'])

    def test_catalog_single_han_and_explicit_rating_routes(self):
        self.assertEqual(expected_engine('search', {'keyword': ''}), 'MYSQL')
        for keyword in ('茶', '𠀀'):
            self.assertEqual(expected_engine('search', {'keyword': keyword}), 'ES')
        for sort in ('score', 'recommend', 'relevance'):
            self.assertEqual(expected_engine('search', {'keyword': '咖啡', 'sort': sort}), 'ES')

    def test_fixed_cohorts_and_routed_performance_counts(self):
        old = json.loads(CASES.read_text(encoding='utf-8'))['cases']
        new = json.loads(NEW_CASES.read_text(encoding='utf-8'))['cases']
        self.assertEqual(len(old), 44)
        self.assertEqual(len(new), 20)
        self.assertFalse(set(c['query'] for c in old) & set(c['query'] for c in new))
        cases = performance_cases(old)
        self.assertEqual(len(cases), 60)
        self.assertEqual(sum(expected_engine('search', c['params']) == 'ES' for c in cases), 52)

    @patch('benchmark_es_search.http.client.HTTPConnection')
    def test_successful_http_cannot_hide_sql_fallback(self, connection):
        response = connection.return_value.getresponse.return_value
        response.status = 200
        response.read.return_value = json.dumps({'code': 'OK', 'data': {
            'search': {'engine': 'MYSQL', 'degraded': True, 'effectiveSort': 'score'}}}).encode()
        client = Endpoint(18081, 'search')
        try:
            with self.assertRaises(RuntimeError):
                client.request({'name': 'test', 'params': {'keyword': '咖啡'}})
        finally:
            client.close()


if __name__ == '__main__':
    unittest.main()
