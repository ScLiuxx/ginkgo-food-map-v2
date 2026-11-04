"""使用实际源类型验证主营类型优先及未知值保留，不操作数据库。"""
import unittest

from restaurant_category_mapping import category_from_poi_type


class RestaurantCategoryMappingTest(unittest.TestCase):
    def test_common_source_types(self):
        for source, expected in [
            ('餐饮服务;中餐厅;中餐厅', 'CHINESE'),
            ('餐饮服务;中餐厅;四川菜(川菜)', 'CHINESE'),
            ('餐饮服务;中餐厅;火锅店', 'HOT_POT'),
            ('餐饮服务;中餐厅;烤鱼店', 'DRY_POT_FISH'),
            ('餐饮服务;中餐厅;烧烤', 'BARBECUE'),
            ('餐饮服务;咖啡厅;咖啡厅', 'DRINK'),
            ('餐饮服务;冷饮店;冷饮店', 'DRINK'),
            ('餐饮服务;茶艺馆;茶艺馆', 'DRINK'),
            ('餐饮服务;糕饼店;糕饼店', 'DESSERT'),
            ('餐饮服务;外国餐厅;日本料理', 'INTERNATIONAL'),
            ('餐饮服务;快餐厅;麦当劳', 'FAST_FOOD'),
        ]:
            with self.subTest(source=source):
                self.assertEqual(category_from_poi_type(source), expected)

    def test_uses_first_business_type_instead_of_secondary_type(self):
        self.assertEqual(category_from_poi_type('餐饮服务;中餐厅;中餐厅|餐饮服务;咖啡厅;咖啡厅'), 'CHINESE')

    def test_unknown_source_does_not_guess_category(self):
        for source in ('', None, '餐饮服务;餐饮相关场所;餐饮相关', '购物服务;专卖店;茶叶店'):
            self.assertIsNone(category_from_poi_type(source))


if __name__ == '__main__':
    unittest.main()
