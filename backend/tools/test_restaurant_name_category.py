"""主营名称判断的正例和歧义边界；不接触数据库。"""
import unittest

from restaurant_name_category import classify_name


class RestaurantNameCategoryTest(unittest.TestCase):
    def test_explicit_business_words(self):
        samples = {
            '兰州牛肉面': 'FAST_FOOD', '红满堂干锅烤鱼江湖菜家常菜': 'DRY_POT_FISH',
            '夜深人烧烤': 'BARBECUE', '韩悦烤肉': 'BARBECUE', '老成都串串香': 'HOT_POT',
            '老成都家常菜馆': 'CHINESE', '秘制烤肉拌饭': 'FAST_FOOD', '小龙门火锅冒菜': 'FAST_FOOD',
            '冒烤鱼专门店': 'FAST_FOOD', '现卤串串店': 'FAST_FOOD', '一期会日式拉面': 'INTERNATIONAL',
            '校园寿司': 'INTERNATIONAL', '木林西餐厅': 'INTERNATIONAL', '手工蛋糕面包': 'DESSERT',
            'APOLLO CAFE': 'DRINK', '巷子咖啡': 'DRINK', '正宗肉夹馍': 'FAST_FOOD',
            '台湾铁路便当': 'FAST_FOOD', '经典黄焖鸡米饭': 'FAST_FOOD',
            '越南米粉': 'INTERNATIONAL', '普叽普叽泰式茶餐厅': 'INTERNATIONAL',
            '牛排老火锅': 'HOT_POT', '幺妹蹄花面': 'FAST_FOOD', '如意面馆': 'FAST_FOOD',
            '韩式烧肉饭': 'FAST_FOOD', '特色火锅米线': 'FAST_FOOD',
            '红烧肉饭': 'FAST_FOOD', '烤肉拌面': 'FAST_FOOD',
        }
        for name, expected in samples.items():
            with self.subTest(name=name):
                self.assertEqual(classify_name(name)['category'], expected)

    def test_branch_location_does_not_override_brand(self):
        self.assertEqual(classify_name('库迪咖啡(合庆里华莱士店)')['category'], 'DRINK')
        self.assertEqual(classify_name('必胜客（大学城店）')['category'], 'INTERNATIONAL')
        self.assertEqual(classify_name('书亦烧仙草(万景峰店)')['category'], 'DRINK')

    def test_ambiguous_and_out_of_scope_names_are_not_auto_assigned(self):
        for name in ('咖啡西餐厅', '火锅烧烤自助', '寿司汉堡双拼店', '甜品咖啡店', '谷藏Bakery·coffee'):
            with self.subTest(name=name):
                self.assertEqual(classify_name(name)['reason'], 'mixed_categories')
        for name in ('火锅底料批发', '极客素养中心', '餐饮设备经营部'):
            self.assertEqual(classify_name(name)['reason'], 'scope_review')
        for name in ('有缘见面', '老王饭店', '食全食美', '高新第一食堂'):
            self.assertIsNone(classify_name(name)['category'])
        for name in ('红烧肉专卖', '老面包点', '小林烤鱼片', '无印良品咖啡餐厅', '粥公粥婆家常菜'):
            self.assertIsNone(classify_name(name)['category'])


if __name__ == '__main__':
    unittest.main()
