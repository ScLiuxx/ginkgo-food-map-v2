"""单字A/B工具的结构、越界写入防护和查询口径回归，不访问真实服务。"""
import unittest
from benchmark_single_character_index import definition, require_owned, TEXT_FIELDS, search_body, sql_statements, cases
from setup_restaurant_search_index import load_spec


class SingleCharacterBenchmarkTest(unittest.TestCase):
    def test_baseline_only_removes_alias_and_disables_import_refresh(self):
        expected=load_spec("002");expected['aliases']={};expected['settings']['refresh_interval']='-1'
        self.assertEqual(definition(False),expected)

    def test_only_four_auxiliary_fields_are_added(self):
        a,b=definition(False),definition(True)
        self.assertEqual(a['mappings']['properties'].keys(),b['mappings']['properties'].keys())
        for name,field in b['mappings']['properties'].items():
            if name in TEXT_FIELDS:
                self.assertEqual(field['fields']['chars']['index_options'],'docs')
                self.assertFalse(field['fields']['chars']['norms'])
                field['fields'].pop('chars')
                if not field['fields']:field.pop('fields')
            self.assertEqual(field,a['mappings']['properties'][name])
        tokenizer=b['settings']['analysis']['tokenizer']['single_character']
        self.assertEqual((tokenizer['min_gram'],tokenizer['max_gram']),(1,1))

    def test_mutation_guard_rejects_official_names_wildcards_and_unowned_uuid(self):
        good='ginkgo-char-ab-'+'a'*32+'-b'
        require_owned(good,{good})
        for name in ('restaurant-search','restaurant-search-v002','ginkgo-char-ab-*',good):
            with self.assertRaises(ValueError):require_owned(name,set())

    def test_single_character_search_is_four_field_or_with_exact_total_and_bounded_page(self):
        body=search_body({'params':{'keyword':'茶','page':2}})
        condition=body['query']['bool']['filter'][1]['bool']
        self.assertEqual(condition['minimum_should_match'],1)
        self.assertEqual(len(condition['should']),4)
        self.assertEqual((body['from'],body['size']),(8,8))
        self.assertTrue(body['track_total_hits'])
        self.assertEqual(body['sort'][-1],{'id':'asc'})

    def test_sql_keeps_input_bound_and_uses_current_rating_precision(self):
        count,page=sql_statements({'params':{'keyword':"%' OR 1=1",'sort':'recommend'}})
        self.assertNotIn("OR 1=1",count[0])
        self.assertIn("!%",count[1][0])
        self.assertIn('ROUND(',page[0]);self.assertIn(',3)',page[0])
        self.assertEqual(page[1][-2:],[8,0])

    def test_fixed_query_cases_include_nonempty_and_empty_filter_controls(self):
        self.assertEqual(len(cases()),22)
        self.assertEqual(len({c['name'] for c in cases()}),22)
        self.assertTrue(all(len(c['params']['keyword'])==1 for c in cases()))


if __name__=='__main__':unittest.main()
