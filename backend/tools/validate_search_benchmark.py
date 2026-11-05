"""对评测产物作独立资料核对：硬筛选、排序/分页以及单字完整原字命中集合。

只读本机资料，不创建数据、不调用ES。多字相关度仅检查对同次全量排名应用过滤后的
结果，词法目标质量由冻结目标指标衡量，不将其描述为独立人工语义金标准。
"""
import argparse
import json
import math
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

from benchmark_es_search import digest, literal_search_match, save, single_han_query
from evaluate_restaurant_search import read_snapshot


def distance(row, query):
    point = row.get('location')
    if query.get('latitude') is None or point is None or row['needsLocation']:
        return None
    lat, lon = point['lat'], point['lon']
    x, y = query['latitude'], query['longitude']
    a = (math.sin(math.radians(lat - x) / 2) ** 2
         + math.cos(math.radians(x)) * math.cos(math.radians(lat)) * math.sin(math.radians(lon - y) / 2) ** 2)
    return 6371000 * 2 * math.asin(math.sqrt(min(1, max(0, a))))


def permitted(row, query):
    """仅使用冻结资料的原始值判定条件，避免复用业务DSL或SQL片段验证自身。"""
    for parameter, field in (('category', 'categoryCode'), ('restaurantType', 'restaurantType')):
        if query.get(parameter) and row[field] != query[parameter]:return False
    if query.get('diningMode') and row['supportsDineIn' if query['diningMode'] == 'dineIn' else 'supportsDelivery'] is not True:return False
    if query.get('hideClosed') and row['businessStatus'] != 'OPERATING':return False
    if query.get('maxPrice') is not None and (row['priceKind'] == 'UNKNOWN' or row['pricePerPerson'] is None or row['pricePerPerson'] > query['maxPrice']):return False
    if query.get('west') is not None:
        point = row.get('location')
        if row['needsLocation'] or point is None or not(query['west'] <= point['lon'] <= query['east'] and query['south'] <= point['lat'] <= query['north']):return False
    if query.get('maxDistanceMeters') is not None:
        meters = distance(row, query)
        if meters is None or meters > query['maxDistanceMeters']:return False
    return True


def sort_key(row, query, sort, keyword):
    if sort == 'score':
        value = None if row['ratingScore'] is None else Decimal(str(row['ratingScore'])).quantize(Decimal('.1'), rounding=ROUND_HALF_UP)
    elif sort == 'recommend':
        value = None if row['recommendRate'] is None else Decimal(str(row['recommendRate'])).quantize(Decimal('.001'), rounding=ROUND_HALF_UP)
    elif sort == 'price':value = row['pricePerPerson']
    elif sort == 'reviews':value = row['reviewCount']
    else:value = distance(row, query)
    primary = 0 if value is None else (-value if sort in ('score', 'recommend', 'reviews') else value)
    suffix = (row['name'], row['id']) if not keyword and sort in ('price', 'reviews', 'distance') else (row['id'],)
    return value is None, primary, *suffix


def validate(report_path):
    report = json.loads(report_path.read_text(encoding='utf-8'))
    corpus, _, _ = read_snapshot([])
    if digest(corpus) != report['environment']['corpus_sha256']:raise RuntimeError('Corpus fingerprint changed')
    by_id = {row['id']: row for row in corpus}
    accuracy = {row['query']: row for row in report['accuracy']}
    checks, single_checks = [], []
    for word, row in accuracy.items():
        if single_han_query(word):
            expected = {item['id'] for item in corpus if literal_search_match(item, word)}
            if set(row['search']['ids']) != expected:raise RuntimeError('Literal single-character full IDs differ')
            single_checks.append({'query': word, 'literal_count': len(expected),
                                  'sql_count': row['mysql']['total'], 'sql_set_equal': set(row['mysql']['ids']) == expected})
    for case in report['cases']:
        query = case['params'];keyword = query.get('keyword', '').strip()
        for mode in ('mysql', 'search'):
            base = accuracy[keyword][mode]['ids'] if keyword else list(by_id)
            eligible = [identity for identity in base if permitted(by_id[identity], query)]
            actual = case['modes'][mode]['signature']
            if len(eligible) != actual['total']:raise RuntimeError('Independent total mismatch: ' + case['name'])
            sort = actual['search']['effectiveSort']
            if sort != 'relevance':eligible.sort(key=lambda identity: sort_key(by_id[identity], query, sort, keyword))
            start = 0 if 'path' in case else (query.get('page', 1) - 1) * query.get('pageSize', 8)
            size = 100 if 'path' in case else query.get('pageSize', 8)
            if eligible[start:start + size] != actual['ids']:raise RuntimeError('Independent order/page mismatch: ' + case['name'])
            checks.append({'case': case['name'], 'mode': mode, 'total': len(eligible), 'ordered_page_matches': True})
    result = {'checks': checks, 'passed': len(checks), 'single_character_checks': single_checks,
              'corpus_sha256': digest(corpus)}
    save(report_path.with_name('validation.json'), result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    result = validate(parser.parse_args().report)
    print(json.dumps({'passed': result['passed'], 'single_character_sets': len(result['single_character_checks'])}))
