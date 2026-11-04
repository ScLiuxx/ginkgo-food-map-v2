"""九类主营品类的名称辅助判断；返回证据，冲突保留待核，不把名称推断视为实地核实。"""
import re
import unicodedata


# 品类词表达主营方向；避免仅凭“面、茶、饭、烤”等单字归类。
KEYWORDS = {
    'FAST_FOOD': ('快餐', '简餐', '小吃', '冒菜', '麻辣烫', '炸鸡', '汉堡', '鸡排', '肉夹馍',
                  '面馆', '面庄', '面店', '牛肉面', '羊肉面', '燃面', '豌杂面', '杂酱面', '炸酱面',
                  '拉面', '刀削面', '重庆小面', '板凳面', '担担面', '热干面', '拌面', '烩面',
                  '肥肠面', '蹄花面', '多味面', '阳春面', '鸡丝面', '鸡汤面', '豆花面',
                  '清汤面', '手工面', '臊子面', '素椒面', '酸菜面', '牛杂面', '脆绍面',
                  '米线', '米粉', '牛肉粉', '羊肉粉', '肥肠粉', '螺蛳粉', '酸辣粉', '肠粉', '河粉', '凉皮', '凉粉',
                  '抄手', '馄饨', '水饺', '饺子', '蒸饺', '包子', '小笼包', '生煎包', '灌汤包', '馒头',
                  '锅盔', '锅魁', '煎饼', '手抓饼', '葱油饼', '烧饼', '烤饼', '牛肉饼',
                  '猪脚饭', '卤肉饭', '盖饭', '盖浇饭', '炒饭', '石锅饭', '煲仔饭', '豆花饭',
                  '黄焖鸡', '便当', '饭团', '粥铺', '粥店', '砂锅粥', '轻食', '沙拉',
                  '卤味', '卤菜', '热卤', '现捞', '鸭脖', '熟食', '钵钵鸡', '棒棒鸡', '炸串', '炸洋芋', '兔头'),
    'HOT_POT': ('火锅', '串串', '涮羊肉', '涮肉', '小郡肝'),
    'BARBECUE': ('烧烤', '烤肉', '烤串', '烤五花', '烧肉'),
    'DRY_POT_FISH': ('干锅', '香锅', '烤鱼', '纸包鱼'),
    'INTERNATIONAL': ('西餐', '披萨', '比萨', '意面', '意大利面', '牛排', '日料', '日本料理',
                      '日式料理', '韩国料理', '韩式料理', '韩食料理', '韩餐', '寿司', '刺身',
                      '寿喜烧', '寿喜锅', '居酒屋', '泰国菜', '泰餐', '泰式料理', '越南菜',
                      '东南亚菜', '墨西哥餐', '印度餐', '巴基斯坦餐', '法式餐厅', '西班牙餐'),
    'DESSERT': ('蛋糕', '面包', '烘焙', '甜品', '甜点', '糕点', '糕饼', '米糕', '冰粉', 'bakery', 'cake',
                '冰淇淋', '冰激凌', '雪糕', '蛋挞', '泡芙', '糖水铺', '糖水店', '双皮奶'),
    'DRINK': ('咖啡', '奶茶', '果茶', '茶饮', '饮品', '果汁', '柠檬茶', '茶楼', '茶府',
              '茶馆', '茶坊', '茶舍', '茶社', '茶房', '茶居', '茶艺', 'coffee', 'cafe'),
}

# 品牌仅匹配去除分店括号后的名称前缀，避免地址中的品牌名污染判断。
BRANDS = {
    'FAST_FOOD': ('肯德基', '麦当劳', '华莱士', '德克士', '汉堡王', '正新鸡排', '大米先生',
                  '乡村基', '老乡鸡', '真功夫', '杨铭宇', '沙县小吃', '绝味鸭脖', '周黑鸭',
                  '廖记棒棒鸡', '久久丫', '吉野家', '牛约堡'),
    'INTERNATIONAL': ('必胜客', '达美乐', '尊宝披萨'),
    'DESSERT': ('好利来', '爱达乐', '麦的多', '安德鲁森', '仟吉', '泸溪河', '鲍师傅',
                '幸福西饼', '元祖', '85度c', '面包新语'),
    'DRINK': ('蜜雪冰城', '茶百道', '书亦烧仙草', '益禾堂', '喜茶', '奈雪的茶', '古茗',
              '沪上阿姨', '茶话弄', '霸王茶姬', '一点点', 'coco都可', '瑞幸', '库迪', '星巴克',
              '幸运咖', '甜啦啦', '悸动烧仙草', '柠季'),
}

CHINESE_WORDS = ('川菜', '家常菜', '江湖菜', '湘菜', '粤菜', '川湘菜', '东北菜', '江浙菜',
                 '私房菜', '农家菜', '土菜馆', '炒菜', '小炒', '中餐', '川味馆', '盐帮菜',
                 '跷脚牛肉', '蹄花', '酸菜鱼', '茶餐厅')
EXCLUDED = ('素养中心', '培训', '教育', '幼儿园', '艺术中心', '酒店用品', '餐饮设备',
            '火锅底料', '底料批发', '食材配送', '食品加工', '食品厂', '仓库', '总部',
            '餐饮管理', '茶叶批发', '茶具', '酒水批发', '便利店', '超市', '生鲜', '商贸')


def classify_name(name: str) -> dict:
    """扫描所有名称，明确单一主营才返回类别；混合经营/非餐饮信号保留原因供人工核对。"""
    normalized = unicodedata.normalize('NFKC', str(name or '')).lower().strip()
    # 分店地点、商场名和邻近品牌不作为主营证据；信息不足时宁可保留现类。
    title = re.sub(r'\([^)]*\)|\[[^\]]*\]', '', normalized).strip()
    excluded = [word for word in EXCLUDED if word in title]
    if excluded:
        return {'category': None, 'reason': 'scope_review', 'evidence': excluded}
    uncertain = [word for word in ('老面包点', '老面包子', '烤鱼片', '咖啡餐厅', '火锅菜', '涮烤') if word in title]
    if '粥' in title and '家常菜' in title:
        uncertain.append('粥与家常菜兼营')
    if uncertain:
        return {'category': None, 'reason': 'ambiguous_phrase', 'evidence': uncertain}

    matches = {}
    # 明确的单人快餐组合不能因含“火锅/烤肉/烤鱼”被当作对应聚餐品类。
    international_phrases = ('日式拉面', '越南米粉', '泰式茶餐厅', '泰国茶餐厅')
    for phrase in ('火锅冒菜', '烤肉拌饭', '烤肉饭', '烤肉盖饭', '烤肉拌面', '烧肉饭', '烧肉面', '红烧肉饭',
                   '火锅米线', '火锅饭', '火锅面', '火锅粉', '冒烤鸭', '冒烤鱼',
                   '现卤串串', '卤串串', '炸串串', *international_phrases):
        if phrase in title:
            category = 'INTERNATIONAL' if phrase in international_phrases else 'FAST_FOOD'
            matches.setdefault(category, []).append(phrase)
            title = title.replace(phrase, '')
    # 菜名中的红烧肉不是烤肉；“如意面馆”等不能因跨词片段“意面”被判为西餐。
    for phrase in ('红烧肉', '叉烧肉', '东坡烧肉'):
        title = title.replace(phrase, '')
    for phrase in ('随意面馆', '如意面馆', '满意面馆', '得意面馆'):
        title = title.replace(phrase, '面馆')
    # “牛排老火锅”以火锅为明确经营品类，牛排在这里是食材，而非西餐主营证据。
    if '火锅' in title:
        title = title.replace('牛排', '')
    for category, words in KEYWORDS.items():
        evidence = [word for word in words if word in title]
        if evidence:
            matches.setdefault(category, []).extend(evidence)
    for category, brands in BRANDS.items():
        # 甜品站是品牌下独立经营形式，优先保留实际名称中的甜品信息。
        if category == 'FAST_FOOD' and ('甜品站' in normalized or '冰淇淋站' in normalized):
            continue
        evidence = [brand for brand in brands if title.startswith(brand)]
        if evidence:
            matches.setdefault(category, []).extend(evidence)

    if len(matches) > 1:
        return {'category': None, 'reason': 'mixed_categories', 'evidence': matches}
    if matches:
        category = next(iter(matches))
        return {'category': category, 'reason': 'name_evidence', 'evidence': matches[category]}
    evidence = [word for word in CHINESE_WORDS if word in title]
    if evidence:
        return {'category': 'CHINESE', 'reason': 'name_evidence', 'evidence': evidence}
    return {'category': None, 'reason': 'insufficient_evidence', 'evidence': []}
