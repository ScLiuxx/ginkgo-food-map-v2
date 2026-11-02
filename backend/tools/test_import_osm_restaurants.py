"""开放数据导入的纯清洗回归，不连接数据库或网络。"""
import unittest

from import_osm_restaurants import prepare, distance, CAMPUS_ID


class ImportCleaningTest(unittest.TestCase):
    def raw(self, *places):
        return {'elements': [dict(type='relation', id=CAMPUS_ID, center={'lat':30.6781776,'lon':104.0971288},
                                  tags={'amenity':'university','name':'电子科技大学（沙河校区）'}), *places]}

    def place(self, identifier, name='测试餐厅', **tags):
        return dict(type='node',id=identifier,lat=30.6794398,lon=104.0976118,
                    tags={'amenity':'restaurant','name':name,**tags})

    def test_duplicate_name_nearby_is_one_and_source_identity_preserved(self):
        _, rows = prepare(self.raw(self.place(1), self.place(2)))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['sourceId'], 'osm:node:1')

    def test_disused_unknown_name_and_outside_radius_are_excluded(self):
        far = self.place(4)
        far['lat'] = 30.75
        _, rows = prepare(self.raw(self.place(1,disused='yes'),self.place(2,name=''),far))
        self.assertEqual(rows, [])

    def test_unknown_address_and_cuisine_not_invented_and_gcj_is_converted(self):
        _, rows = prepare(self.raw(self.place(1)))
        row = rows[0]
        self.assertIsNone(row['address'])
        self.assertEqual(row['categoryCode'], 'OTHER')
        self.assertEqual(row['wgs84']['latitude'], 30.6794398)
        offset = distance(row['latitude'],row['longitude'],30.6794398,104.0976118)
        self.assertGreater(offset, 100)
        self.assertLess(offset, 1000)

    def test_wrong_campus_is_rejected(self):
        raw = self.raw(self.place(1))
        raw['elements'][0]['tags']['name'] = '其他大学'
        with self.assertRaises(ValueError):
            prepare(raw)


if __name__ == '__main__':
    unittest.main()
