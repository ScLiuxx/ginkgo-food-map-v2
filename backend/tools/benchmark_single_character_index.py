"""单字辅助索引隔离A/B实验：完整真实投影、四个精简字符子字段、空间/写入/检索对照。

只读本机正式索引与固定开发库；写入、flush、merge、删除严格限定本次UUID实验索引。
不绑定别名、不切换网站、不修改真实资料。ES/SQL计时均是底层查询，不冒充Java接口耗时。
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
import http.client
import json
from pathlib import Path
import random
import re
import time
import uuid

from benchmark_es_search import database_state, digest, save
from benchmark_mysql_search import summary
from evaluate_restaurant_search import ROOT, connect
from setup_restaurant_search_index import load_spec, LocalElasticsearch

PREFIX = 'ginkgo-char-ab-'
TEXT_FIELDS = ('name', 'address', 'categoryName', 'tags')


def require_owned(index, owned):
    """变更与清理必须同时通过精确UUID格式及当前进程拥有集合，拒绝通配符或正式别名。"""
    if not re.fullmatch(PREFIX + r'[a-f0-9]{32}-[ab]', index) or index not in owned:
        raise ValueError('Not an owned experimental index')


def definition(characters):
    """A保留v002；B只新增1-gram分析器和四个子字段，不增加_source内容。"""
    spec = deepcopy(load_spec("002"))
    spec['aliases'] = {}
    # 两组导入期间都关闭自动refresh，随后恢复1s；不修改正式索引设置。
    spec['settings']['refresh_interval'] = '-1'
    if characters:
        analysis = spec['settings']['analysis']
        analysis['tokenizer'] = {'single_character': {
            'type': 'ngram', 'min_gram': 1, 'max_gram': 1, 'token_chars': ['letter', 'digit']}}
        analysis['analyzer'] = {'single_character': {'type': 'custom', 'tokenizer': 'single_character'}}
        for field in TEXT_FIELDS:
            spec['mappings']['properties'][field].setdefault('fields', {})['chars'] = {
                'type': 'text', 'analyzer': 'single_character', 'search_analyzer': 'keyword',
                'index_options': 'docs', 'norms': False}
    return spec


class Transport:
    """固定本机、持续HTTP连接；每次记录发请求至读完响应体，不含JSON解析。"""
    def __init__(self):
        self.connection = http.client.HTTPConnection('127.0.0.1', 9200, timeout=40)

    def request(self, method, path, body=None, ndjson=False):
        payload = body if isinstance(body, bytes) else (None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'))
        begin = time.perf_counter_ns()
        try:
            self.connection.request(method, path, payload, {'Content-Type': 'application/x-ndjson' if ndjson else 'application/json'})
            response = self.connection.getresponse()
            raw = response.read()
        except Exception:
            # 不重试原写入；重建连接仅让finally能清理已确认拥有的临时索引。
            self.connection.close()
            self.connection = http.client.HTTPConnection('127.0.0.1', 9200, timeout=40)
            raise
        elapsed = (time.perf_counter_ns() - begin) / 1e6
        if response.status != 200:
            raise RuntimeError(f'ES HTTP {response.status}')
        return elapsed, json.loads(raw)

    def mutate(self, method, index, suffix, body, owned, ndjson=False):
        require_owned(index, owned)
        return self.request(method, '/' + index + suffix, body, ndjson)

    def close(self):
        self.connection.close()


def documents(client):
    """完整复制正式21字段_source和每店版本；不组装一套精简正文稀释空间基线。"""
    alias = client.request('GET', '/_alias/restaurant-search')
    if len(alias) != 1 or not set(alias).issubset({'restaurant-search-v002', 'restaurant-search-v003'}):
        raise RuntimeError('Unexpected official alias')
    response = client.request('POST', '/restaurant-search/_search', {
        'size': 10000, 'track_total_hits': True, 'version': True, 'sort': [{'id': 'asc'}], 'query': {'match_all': {}}})
    hits = response['hits']['hits']
    if response.get('timed_out') or response['_shards']['failed'] or response['hits']['total']['relation'] != 'eq':
        raise RuntimeError('Incomplete official snapshot')
    if response['hits']['total']['value'] != len(hits) or not 0 < len(hits) <= 10000:
        raise RuntimeError('Snapshot exceeds bounded full validation')
    if any(h['_source'].get('deleted') or str(h['_source']['id']) != h['_id']
           or h['_source']['projectionVersion'] != h['_version'] for h in hits):
        raise RuntimeError('Official identity/version/tombstone precondition failed')
    return [h['_source'] for h in hits], {'alias': alias, 'documents_sha256': digest(hits), 'count': len(hits)}


def batches(docs):
    """预先编码，导入计时不含JSON生成；固定500条/批并保留external版本语义。"""
    result = []
    for offset in range(0, len(docs), 500):
        rows = docs[offset:offset + 500]
        lines = []
        for row in rows:
            lines.extend((json.dumps({'index': {'_id': str(row['id']), 'version': row['projectionVersion'], 'version_type': 'external'}}),
                          json.dumps(row, ensure_ascii=False)))
        result.append((rows, ('\n'.join(lines) + '\n').encode('utf-8')))
    return result


def write(transport, index, payloads, owned):
    """确认逐项目标/状态；相同文本覆盖仍完整重建索引，不能用_update的noop当更新成本。"""
    elapsed, took = [], []
    start = time.perf_counter()
    for rows, payload in payloads:
        duration, response = transport.mutate('POST', index, '/_bulk', payload, owned, True)
        items = response.get('items', [])
        if response.get('errors') or len(items) != len(rows):
            raise RuntimeError('Partial bulk failure')
        for row, item in zip(rows, items):
            stored = item['index']
            if stored.get('_index') != index or stored.get('_id') != str(row['id']) or stored.get('status') not in (200, 201):
                raise RuntimeError('Bulk identity/status mismatch')
        elapsed.append(duration)
        took.append(response['took'])
    return {'wall_ms': (time.perf_counter() - start) * 1000, 'batch_http_ms': elapsed,
            'server_took_ms': took, 'documents': sum(len(rows) for rows, _ in payloads)}


def settle(transport, index, owned, merge=False):
    """仅在实验索引上refresh/flush；单段对照是只读稳定布局，不当作生产持续写入状态。"""
    transport.mutate('POST', index, '/_refresh', None, owned)
    transport.mutate('POST', index, '/_flush?wait_if_ongoing=true', None, owned)
    if merge:
        transport.mutate('POST', index, '/_forcemerge?max_num_segments=1&flush=true', None, owned)
    _, result = transport.request('GET', '/' + index + '/_stats/docs,store,segments,translog')
    primary = result['indices'][index]['primaries']
    return {key: primary[key] for key in ('docs', 'store', 'segments', 'translog')}


DISTANCE_SQL = """CASE WHEN r.needs_location=0 AND r.latitude BETWEEN -90 AND 90 AND r.longitude BETWEEN -180 AND 180
    THEN 6371000*2*ASIN(SQRT(LEAST(1.0,GREATEST(0.0,
    POWER(SIN(RADIANS(r.latitude-%s)/2),2)+COS(RADIANS(%s))*COS(RADIANS(r.latitude))
    *POWER(SIN(RADIANS(r.longitude-%s)/2),2))))) ELSE NULL END"""


def sql_statements(case, full=False):
    """原单字LIKE的COUNT与全局排序/ID分页路径；所有输入绑定，SQL只读，不含Java回查/图片。"""
    q = case['params']
    text = q['keyword']
    pattern = '%' + text.replace('!', '!!').replace('%', '!%').replace('_', '!_') + '%'
    where = ["r.deleted_at IS NULL", """(r.name LIKE %s ESCAPE '!' OR r.address LIKE %s ESCAPE '!'
        OR c.name LIKE %s ESCAPE '!' OR EXISTS(SELECT 1 FROM restaurant_tags t WHERE t.restaurant_id=r.id AND t.tag LIKE %s ESCAPE '!'))"""]
    values = [pattern] * 4
    for parameter, column in [('category', 'r.category_code'), ('restaurantType', 'r.restaurant_type')]:
        if q.get(parameter):
            where.append(column + '=%s');values.append(q[parameter])
    if q.get('diningMode'):
        where.append('r.supports_dine_in=TRUE' if q['diningMode'] == 'dineIn' else 'r.supports_delivery=TRUE')
    if q.get('hideClosed'):
        where.append("r.business_status='OPERATING'")
    if q.get('maxPrice') is not None:
        where.append("r.price_kind!='UNKNOWN' AND r.price_per_person<=%s");values.append(q['maxPrice'])
    if q.get('west') is not None:
        where.append('r.needs_location=0 AND r.longitude BETWEEN %s AND %s AND r.latitude BETWEEN %s AND %s')
        values.extend(q[key] for key in ('west', 'east', 'south', 'north'))
    geo = [q.get('latitude'), q.get('latitude'), q.get('longitude')]
    if q.get('maxDistanceMeters') is not None:
        where.append('(' + DISTANCE_SQL + ')<=%s');values.extend(geo + [q['maxDistanceMeters']])
    from_where = ''' FROM restaurants r LEFT JOIN restaurant_categories c ON c.code=r.category_code
        LEFT JOIN restaurant_review_statistics s ON s.restaurant_id=r.id WHERE ''' + ' AND '.join(where)
    rating = 'ROUND(CAST(s.overall_score_sum AS DECIMAL(40,20))/NULLIF(s.participant_count,0),1)'
    recommend = 'ROUND(CAST(s.recommend_count AS DECIMAL(40,20))/NULLIF(s.participant_count,0),3)'
    select = 'SELECT r.id,' + rating + ' AS score,' + recommend + ' AS recommend_rate'
    sort = q.get('sort', 'score')
    selected_values = []
    if sort == 'distance':
        select += ',(' + DISTANCE_SQL + ') AS distance_meters';selected_values = geo
    order = {'score': 'score IS NULL,score DESC,r.id', 'recommend': 'recommend_rate IS NULL,recommend_rate DESC,r.id',
             'reviews': 'COALESCE(s.review_count,0) DESC,r.id', 'price': 'r.price_per_person IS NULL,r.price_per_person,r.id',
             'distance': 'distance_meters IS NULL,distance_meters,r.id'}[sort]
    if full:
        order = 'r.id';limit, offset = 10000, 0
    else:
        limit = q.get('pageSize', 8);offset = (q.get('page', 1) - 1) * limit
    return ('SELECT COUNT(*) AS n' + from_where, values), (
        select + from_where + ' ORDER BY ' + order + ' LIMIT %s OFFSET %s', selected_values + values + [limit, offset])


def sql_query(cursor, case, full=False):
    count, page = sql_statements(case, full)
    start = time.perf_counter_ns()
    cursor.execute(*count);total = cursor.fetchone()['n']
    offset = 0 if full else (case['params'].get('page', 1) - 1) * case['params'].get('pageSize', 8)
    rows = []
    if total > offset:
        cursor.execute(*page);rows = cursor.fetchall()
    elapsed = (time.perf_counter_ns() - start) / 1e6
    return elapsed, {'total': total, 'ids': [row['id'] for row in rows]}


def search_body(case, chars=True, full=False):
    """单字只做包含判断：四字段OR硬匹配，业务筛选/全局排序/准确总数在ES完成。"""
    q = case['params'];word = q['keyword']
    if chars:
        match = {'bool': {'should': [{'term': {field + '.chars': word}} for field in TEXT_FIELDS], 'minimum_should_match': 1}}
    else:
        match = {'multi_match': {'query': word, 'type': 'cross_fields', 'analyzer': 'ik_smart',
                                'operator': 'and', 'fields': ['name^6', 'categoryName^3', 'tags^2', 'address']}}
    filters = [{'term': {'deleted': False}}, match]
    for parameter, field in [('category', 'categoryCode'), ('restaurantType', 'restaurantType')]:
        if q.get(parameter):filters.append({'term': {field: q[parameter]}})
    if q.get('diningMode'):
        filters.append({'term': {'supportsDineIn' if q['diningMode'] == 'dineIn' else 'supportsDelivery': True}})
    if q.get('hideClosed'):filters.append({'term': {'businessStatus': 'OPERATING'}})
    if q.get('maxPrice') is not None:
        filters.extend([{'bool': {'must_not': [{'term': {'priceKind': 'UNKNOWN'}}]}}, {'range': {'pricePerPerson': {'lte': q['maxPrice']}}}])
    runtime = {}
    if q.get('west') is not None:
        for axis, low, high in [('lat', 'south', 'north'), ('lon', 'west', 'east')]:
            field = 'query_' + axis
            runtime[field] = {'type': 'double', 'script': {'source': 'if(params._source.needsLocation==false && params._source.location!=null) emit((double)params._source.location[params.axis]);', 'params': {'axis': axis}}}
            filters.append({'range': {field: {'gte': q[low], 'lte': q[high]}}})
    if q.get('maxDistanceMeters') is not None or q.get('sort') == 'distance':
        runtime['query_distance'] = {'type': 'double', 'script': {'source': '''
            if(params._source.needsLocation==false && params._source.location!=null) {
              double lat=params._source.location.lat; double lon=params._source.location.lon;
              double a=Math.pow(Math.sin(Math.toRadians(lat-(double)params.lat)/2),2)
                +Math.cos(Math.toRadians((double)params.lat))*Math.cos(Math.toRadians(lat))
                *Math.pow(Math.sin(Math.toRadians(lon-(double)params.lon)/2),2);
              emit(6371000*2*Math.asin(Math.sqrt(Math.min(1.0,Math.max(0.0,a)))));
            }''', 'params': {'lat': q['latitude'], 'lon': q['longitude']}}}
    if q.get('maxDistanceMeters') is not None:
        filters.append({'range': {'query_distance': {'lte': q['maxDistanceMeters']}}})
    sort = q.get('sort', 'score')
    if sort in ('score', 'recommend'):
        runtime['query_rating'] = {'type': 'long', 'script': {'source': "if(doc[params.field].size()!=0) emit(Math.round(doc[params.field].value*params.factor));", 'params': {
            'field': 'ratingScore' if sort == 'score' else 'recommendRate', 'factor': 10 if sort == 'score' else 1000}}}
    field, direction = {'score': ('query_rating', 'desc'), 'recommend': ('query_rating', 'desc'),
                        'reviews': ('reviewCount', 'desc'), 'price': ('pricePerPerson', 'asc'), 'distance': ('query_distance', 'asc')}[sort]
    limit = 10000 if full else q.get('pageSize', 8)
    offset = 0 if full else (q.get('page', 1) - 1) * limit
    body = {'query': {'bool': {'filter': filters}}, 'track_total_hits': True, '_source': ['id', 'projectionVersion'],
            'from': offset, 'size': limit, 'timeout': '2s',
            'sort': [{'id': 'asc'}] if full else [{field: {'order': direction, 'missing': '_last'}}, {'id': 'asc'}]}
    if runtime:body['runtime_mappings'] = runtime
    return body


def es_query(transport, index, body):
    elapsed, response = transport.request('POST', '/' + index + '/_search?allow_partial_search_results=false', body)
    total = response['hits']['total']
    if response.get('timed_out') or response['_shards']['failed'] or total['relation'] != 'eq':
        raise RuntimeError('Incomplete experimental search')
    ids = [int(hit['_id']) for hit in response['hits']['hits']]
    expected = min(body['size'], max(0, total['value'] - body['from']))
    if len(ids) != expected or len(ids) != len(set(ids)):
        raise RuntimeError('Incomplete/duplicate result IDs')
    return elapsed, {'total': total['value'], 'ids': ids}


def cases():
    result = [{'name': 'single:' + word, 'params': {'keyword': word}} for word in '面茶粉饭锅店路河大小餐西']
    for label, params in [
        ('tea_dineIn', {'keyword': '茶', 'diningMode': 'dineIn'}),
        ('meal_canteen', {'keyword': '餐', 'restaurantType': 'CANTEEN', 'diningMode': 'dineIn'}),
        ('noodle_recommend', {'keyword': '面', 'sort': 'recommend'}),
        ('tea_reviews', {'keyword': '茶', 'sort': 'reviews'}),
        ('meal_price', {'keyword': '饭', 'sort': 'price'}),
        ('meal_maxPrice', {'keyword': '饭', 'maxPrice': 30}),
        ('tea_distance', {'keyword': '茶', 'sort': 'distance', 'latitude': 30.75, 'longitude': 103.93, 'maxDistanceMeters': 3000}),
        ('noodle_viewport100', {'keyword': '面', 'west': 103, 'east': 105, 'south': 30, 'north': 32, 'pageSize': 100}),
        ('noodle_page100', {'keyword': '面', 'page': 100}),
        ('meal_hideClosed', {'keyword': '饭', 'hideClosed': True}),
    ]:result.append({'name': label, 'params': params})
    return result


def run(args):
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents or output.exists():
        raise ValueError('New output directory outside repository required')
    output.mkdir(parents=True)
    client, transport = LocalElasticsearch(), Transport()
    before, _ = database_state()
    docs, official_before = documents(client)
    report = {'started_utc': datetime.now(timezone.utc).isoformat(), 'database_before': before,
              'official_before': official_before, 'document_count': len(docs), 'source_sha256': digest(docs),
              'rounds': args.rounds, 'repetitions': args.repetitions, 'cases': cases(), 'repeats': []}
    report['text_population'] = {field: {'nonempty_documents': sum(bool(d.get(field)) for d in docs),
        'characters': sum(sum(map(len, value)) if isinstance(value, list) else len(value or '') for value in (d.get(field) for d in docs))}
        for field in TEXT_FIELDS}
    save(output / 'plan.json', {**report, 'definitions': {'a': definition(False), 'b': definition(True)}})
    owned = set()
    rng = random.Random(20261105)
    connection = connect()
    try:
        with connection.cursor() as cursor:
            cursor.execute('SET SESSION MAX_EXECUTION_TIME=10000')
            cursor.execute('START TRANSACTION READ ONLY')
            payloads = batches(docs)
            for repetition in range(args.repetitions):
                prefix = PREFIX + uuid.uuid4().hex
                indexes = {'a': prefix + '-a', 'b': prefix + '-b'}
                order = ['a', 'b'] if repetition % 2 == 0 else ['b', 'a']
                item = {'repetition': repetition + 1, 'indexes': indexes, 'import_order': order, 'modes': {}, 'queries': []}
                report['repeats'].append(item)
                for mode in order:
                    index = indexes[mode]
                    created = client.request('PUT', '/' + index, definition(mode == 'b'))
                    if created.get('acknowledged') is not True:raise RuntimeError('Experimental create not acknowledged')
                    owned.add(index)
                    item['modes'][mode] = {'import': write(transport, index, payloads, owned)}
                    transport.mutate('PUT', index, '/_settings', {'index': {'refresh_interval': '1s'}}, owned)
                    item['modes'][mode]['fresh'] = settle(transport, index, owned)
                    item['modes'][mode]['merged'] = settle(transport, index, owned, merge=True)
                    _, actual = transport.request('POST', '/' + index + '/_search', {'size': 10000, 'track_total_hits': True, 'sort': [{'id': 'asc'}]})
                    if [hit['_source'] for hit in actual['hits']['hits']] != docs:
                        raise RuntimeError('Imported full source differs')
                    print(json.dumps({'phase': 'import', 'repeat': repetition + 1, 'mode': mode,
                        'wall_ms': item['modes'][mode]['import']['wall_ms'],
                        'merged_bytes': item['modes'][mode]['merged']['store']['size_in_bytes']}), flush=True)
                for mode, index in indexes.items():
                    _, usage = transport.mutate('POST', index, '/_disk_usage?run_expensive_tasks=true', None, owned)
                    item['modes'][mode]['disk_usage'] = usage[index]
                # 核对普通词的原IK路径没有变化；此处只检查索引增量影响，不调权重。
                item['ik_regression'] = []
                for word in ('火锅', '蜜雪冰城', '清水河咖啡', '建设路螺蛳粉', '龙湖天街蜜雪冰城'):
                    case = {'params': {'keyword': word}}
                    body = search_body(case, chars=False, full=True)
                    a = es_query(transport, indexes['a'], body)[1]
                    b = es_query(transport, indexes['b'], body)[1]
                    if a != b:raise RuntimeError('Original IK result changed')
                    item['ik_regression'].append({'query': word, 'total': a['total'], 'same': True})
                for case in report['cases']:
                    _, sql_all = sql_query(cursor, case, full=True)
                    _, b_all = es_query(transport, indexes['b'], search_body(case, full=True))
                    _, a_all = es_query(transport, indexes['a'], search_body(case, chars=False, full=True))
                    if sql_all != b_all:raise RuntimeError('Character full-ID set differs from real LIKE: ' + case['name'])
                    _, sql_page = sql_query(cursor, case)
                    _, b_page = es_query(transport, indexes['b'], search_body(case))
                    if sql_page != b_page:raise RuntimeError('Character ordered page differs: ' + case['name'])
                    result = {'name': case['name'], 'params': case['params'], 'sql_total': sql_all['total'],
                              'a_ik_total': a_all['total'], 'b_character_total': b_all['total'],
                              'a_missing_ids': sorted(set(sql_all['ids']) - set(a_all['ids'])),
                              'b_full_ids_sha256': digest(b_all['ids']), 'expected_page': sql_page,
                              'same_full_set': True, 'same_ordered_page': True, 'raw_ms': {'sql': [], 'b': []}}
                    item['queries'].append(result)
                    for _ in range(3):
                        sql_query(cursor, case);es_query(transport, indexes['b'], search_body(case))
                for turn in range(args.rounds):
                    positions = list(range(len(report['cases'])));rng.shuffle(positions)
                    for position in positions:
                        case = report['cases'][position];result = item['queries'][position]
                        modes = ['sql', 'b'];rng.shuffle(modes)
                        for mode in modes:
                            elapsed, actual = sql_query(cursor, case) if mode == 'sql' else es_query(transport, indexes['b'], search_body(case))
                            if actual != result['expected_page']:raise RuntimeError('Timed result drift')
                            result['raw_ms'][mode].append(elapsed)
                for result in item['queries']:
                    result['summary'] = {mode: summary(values) for mode, values in result['raw_ms'].items()}
                # 选定同一1000家真实商户，只推进实验正文版本，完整覆盖写而非noop。
                rewritten = deepcopy(docs[:1000])
                for document in rewritten:document['projectionVersion'] += 1
                for mode in reversed(order):
                    index = indexes[mode]
                    transport.mutate('PUT', index, '/_settings', {'index': {'refresh_interval': '-1'}}, owned)
                    item['modes'][mode]['rewrite1000'] = write(transport, index, batches(rewritten), owned)
                    item['modes'][mode]['after_rewrite'] = settle(transport, index, owned)
                    item['modes'][mode]['after_rewrite_merged'] = settle(transport, index, owned, merge=True)
                save(output / f'repeat-{repetition+1}.json', item)
                for index in indexes.values():
                    transport.mutate('DELETE', index, '', None, owned);owned.remove(index)
                print(json.dumps({'phase': 'repeat_complete', 'repeat': repetition + 1, 'checked_cases': len(item['queries'])}), flush=True)
        connection.rollback()
        report['database_after'], _ = database_state()
        _, report['official_after'] = documents(client)
        if before != report['database_after'] or official_before != report['official_after']:
            raise RuntimeError('Official data/index drifted')
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        save(output / 'results.json', report)
        print(json.dumps({'phase': 'complete', 'repetitions': len(report['repeats']), 'official_unchanged': True}), flush=True)
    except Exception as error:
        report['failure_type'] = type(error).__name__;save(output / 'incomplete.json', report);raise
    finally:
        connection.rollback();connection.close()
        try:
            for index in list(owned):
                transport.mutate('DELETE', index, '', None, owned);owned.remove(index)
        finally:
            transport.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rounds', type=int, default=20)
    parser.add_argument('--repetitions', type=int, default=3)
    args = parser.parse_args()
    if not 5 <= args.rounds <= 50 or not 1 <= args.repetitions <= 5:parser.error('Bounded rounds/repetitions required')
    run(args)
