"""九类餐饮的原始POI类型映射；只使用明确的源类型，不根据店名猜测经营品类。"""


def category_from_poi_type(type_text: str) -> str | None:
    """只采用源数据的首个主营类型；未知返回None，由调用方保留原分类。"""
    parts = str(type_text or '').split('|')[0].split(';')
    if len(parts) < 3 or parts[0] != '餐饮服务':
        return None
    group, detail = parts[1], parts[2]
    if group in ('咖啡厅', '冷饮店', '茶艺馆'):
        return 'DRINK'
    if group in ('糕饼店', '甜品店'):
        return 'DESSERT'
    if group == '外国餐厅':
        return 'INTERNATIONAL'
    if group == '快餐厅':
        return 'FAST_FOOD'
    if group == '中餐厅':
        if any(word in detail for word in ('火锅', '串串')):
            return 'HOT_POT'
        if any(word in detail for word in ('烧烤', '烤肉')):
            return 'BARBECUE'
        if any(word in detail for word in ('香锅', '烤鱼', '干锅')):
            return 'DRY_POT_FISH'
        if any(word in detail for word in ('小吃', '面馆', '冒菜', '麻辣烫', '轻食')):
            return 'FAST_FOOD'
        return 'CHINESE'
    return None
