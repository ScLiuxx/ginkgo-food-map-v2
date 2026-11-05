"""独立核验器的边界验证，不连接数据库或ES。"""
import unittest
from validate_search_benchmark import distance, permitted, sort_key


class IndependentValidationTest(unittest.TestCase):
    def test_unknown_price_or_dining_support_is_not_a_positive_filter_match(self):
        row = {'priceKind': 'UNKNOWN', 'pricePerPerson': None, 'supportsDineIn': None}
        self.assertFalse(permitted(row, {'maxPrice': 50}))
        self.assertFalse(permitted(row, {'diningMode': 'dineIn'}))
        row.update(priceKind='EXACT', pricePerPerson=50, supportsDineIn=True)
        self.assertTrue(permitted(row, {'maxPrice': 50, 'diningMode': 'dineIn'}))

    def test_geo_boundary_is_inclusive_and_unknown_location_is_excluded(self):
        row = {'location': {'lat': 30.75, 'lon': 103.93}, 'needsLocation': False}
        bounds = dict(west=103.93, east=104, south=30.75, north=31)
        self.assertTrue(permitted(row, bounds))
        self.assertEqual(distance(row, {'latitude': 30.75, 'longitude': 103.93}), 0)
        row['needsLocation'] = True
        self.assertFalse(permitted(row, bounds))

    def test_score_rounding_uses_id_for_ties_and_retains_real_zero(self):
        rows = [{'id': 3, 'ratingScore': None}, {'id': 2, 'ratingScore': 8.34},
                {'id': 1, 'ratingScore': 8.31}, {'id': 4, 'ratingScore': 0}]
        actual = sorted(rows, key=lambda row: sort_key(row, {}, 'score', '茶'))
        self.assertEqual([row['id'] for row in actual], [1, 2, 4, 3])


if __name__ == '__main__':unittest.main()
