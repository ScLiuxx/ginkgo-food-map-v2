"""当前本地MySQL检索只读基准：真实Mapper SQL、直连HTTP及有界并发。

不造数、不清缓存、不修改索引/配置。输出仅包含计数、SQL计划及耗时，不保存商户正文或凭据。
SQL由当前XML片段生成，逐场景与真实接口核对数量及有序ID后才计时；不模拟Java组装开销。
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import math
from pathlib import Path
import platform
import re
import time
from urllib.parse import urlencode
import xml.etree.ElementTree as ET

from evaluate_restaurant_search import connect, ROOT, CASES

MAPPER = ROOT / 'backend/src/main/resources/mapper/restaurant/RestaurantCatalogMapper.xml'


def summary(values):
    """分位数使用nearest-rank，原始毫秒样本另存便于复算。"""
    ordered = sorted(values)
    if not ordered:
        return {'samples': 0}
    return {'samples': len(values), 'mean_ms': round(sum(values) / len(values), 3),
            **{f'p{p}_ms': round(ordered[max(0, math.ceil(len(values) * p / 100) - 1)], 3)
               for p in (50, 95, 99)}, 'max_ms': round(ordered[-1], 3)}


def parameters(case):
    """显式对应DTO默认值与派生参数，避免把未提交字段当作非空筛选。"""
    q = dict(keyword='', category='', restaurantType='', diningMode='', sort='score',
             hideClosed=False, latitude=None, longitude=None, west=None, south=None,
             east=None, north=None, maxPrice=None, maxDistanceMeters=None, page=1, pageSize=8)
    q.update(case['params'])
    q['offset'] = (q['page'] - 1) * q['pageSize']
    q['keywordPattern'] = '%' + q['keyword'].strip().replace('!', '!!').replace('%', '!%').replace('_', '!_') + '%'
    return q


def condition(test, q):
    """只支持实际Mapper中的有限条件；遇到新语法立即报错，禁止静默生成不同SQL。"""
    if test == "_parameter.containsKey('query') and query.latitude != null and query.longitude != null":
        return q['latitude'] is not None and q['longitude'] is not None
    if test == 'query.hideClosed':
        return q['hideClosed']
    if test == "query.keyword.strip() != ''":
        return bool(q['keyword'].strip())
    match = re.fullmatch(r"query\.(\w+) (==|!=) (null|''|'[^']*')", test)
    if not match:
        raise ValueError('Unsupported Mapper condition: ' + test)
    key, operator, literal = match.groups()
    expected = None if literal == 'null' else literal[1:-1]
    return (q[key] == expected) if operator == '==' else (q[key] != expected)


def sql_for(statement, q):
    """直接展开当前Mapper的include/if/choose；值始终通过DB-API参数绑定。"""
    root = ET.parse(MAPPER).getroot()
    fragments = {node.attrib['id']: node for node in root if 'id' in node.attrib}

    def render(node):
        parts = [node.text or '']
        for child in node:
            if child.tag == 'include':
                parts.append(render(fragments[child.attrib['refid'].split('.')[-1]]))
            elif child.tag == 'if':
                if condition(child.attrib['test'], q):
                    parts.append(render(child))
            elif child.tag == 'choose':
                for option in child:
                    if option.tag == 'otherwise' or condition(option.attrib['test'], q):
                        parts.append(render(option))
                        break
            else:
                raise ValueError('Unsupported Mapper node: ' + child.tag)
            parts.append(child.tail or '')
        return ''.join(parts)

    bindings = []

    def bind(match):
        bindings.append(q[match[1]])
        return '%s'

    sql = re.sub(r'#\{query\.(\w+)\}', bind, render(fragments[statement]))
    return re.sub(r'\s+', ' ', sql).strip(), bindings


class Endpoint:
    """每个工作线程复用自己的直连HTTP连接，排除代理和每次重新建连带来的干扰。"""
    def __init__(self, port):
        self.connection = http.client.HTTPConnection('127.0.0.1', port, timeout=20)

    def request(self, case):
        path = case.get('path', '/api/restaurants')
        params = {k: str(v).lower() if isinstance(v, bool) else v for k, v in case['params'].items()}
        start = time.perf_counter_ns()
        self.connection.request('GET', path + '?' + urlencode(params))
        response = self.connection.getresponse()
        body = response.read()
        elapsed = (time.perf_counter_ns() - start) / 1e6
        if response.status != 200:
            raise RuntimeError(f'Unexpected HTTP {response.status}: {case["name"]}')
        data = json.loads(body)['data']
        return elapsed, data

    def close(self):
        self.connection.close()


def main():
    """默认在当前约一万行真实库上运行固定次数基准，不用单次最快结果代表性能。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--rounds', type=int, default=20)
    args = parser.parse_args()
    if not 5 <= args.rounds <= 50 or not 1 <= args.port <= 65535:
        parser.error('rounds must be 5–50; port must be valid')
    if args.output.exists():
        parser.error('output already exists')
    cases = [{'name': 'keyword:' + c['query'], 'kind': c['kind'], 'params': {'keyword': c['query']}}
             for c in json.loads(CASES.read_text(encoding='utf-8'))['cases']]
    bounds = dict(west=103, east=105, south=30, north=32)
    cases += [
        {'name': 'catalog:first', 'params': {}},
        {'name': 'catalog:page100', 'params': {'page': 100}},
        {'name': 'catalog:page1000', 'params': {'page': 1000}},
        {'name': 'catalog:pageSize100', 'params': {'pageSize': 100}},
        {'name': 'filter:canteen_dineIn', 'params': {'restaurantType': 'CANTEEN', 'diningMode': 'dineIn'}},
        {'name': 'filter:delivery', 'params': {'diningMode': 'delivery'}},
        {'name': 'filter:hotpot_price', 'params': {'keyword': '火锅', 'maxPrice': 50, 'hideClosed': True, 'sort': 'price'}},
        {'name': 'filter:distance', 'params': {'latitude': 30.75, 'longitude': 103.93, 'maxDistanceMeters': 3000, 'sort': 'distance'}},
        {'name': 'map:viewport', 'path': '/api/restaurants/map-points', 'params': bounds},
        {'name': 'map:hotpot', 'path': '/api/restaurants/map-points', 'params': dict(bounds, keyword='火锅')},
    ]
    report = {'started_utc': datetime.now(timezone.utc).isoformat(), 'environment': {
        'os': platform.platform(), 'python': platform.python_version(),
        'mapper_sha256': hashlib.sha256(MAPPER.read_bytes()).hexdigest()},
        'method': {'warmups_per_case': 2, 'rounds': args.rounds, 'percentile': 'nearest-rank',
                   'sql_scope': 'COUNT + current Mapper page/points only; excludes tags/covers/Java/HTTP',
                   'http_scope': 'GET direct loopback persistent HTTP; body read included; JSON decode excluded',
                   'cache': 'warm; no restart/flush; existing local real data; no writes'},
        'cases': [], 'load': [], 'plans': {}}
    conn, endpoint = connect(), Endpoint(args.port)
    try:
        with conn.cursor() as cursor:
            cursor.execute('SET SESSION MAX_EXECUTION_TIME=10000')
            cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            cursor.execute('START TRANSACTION READ ONLY')
            cursor.execute('SELECT VERSION() AS version, @@innodb_buffer_pool_size AS buffer_pool_bytes')
            report['environment'].update(cursor.fetchone())
            for table in ('restaurants', 'restaurant_tags', 'restaurant_review_statistics', 'restaurant_photos'):
                cursor.execute('SELECT COUNT(*) AS n FROM ' + table)
                report['environment'][table + '_rows'] = cursor.fetchone()['n']
            cursor.execute('SELECT COUNT(*) AS n FROM restaurants WHERE deleted_at IS NULL')
            report['environment']['visible_restaurants'] = cursor.fetchone()['n']
            cursor.execute('SHOW INDEX FROM restaurants')
            report['environment']['restaurant_indexes'] = [
                {k: row[k] for k in ('Key_name', 'Seq_in_index', 'Column_name', 'Index_type')} for row in cursor.fetchall()]
            cursor.execute('SELECT COUNT(*) AS n, SUM(participant_count > 0) AS rated FROM restaurant_review_statistics')
            report['environment']['statistics_population'] = cursor.fetchone()
            for i, case in enumerate(cases):
                q = parameters(case)
                count_sql = sql_for('countVisible', q)
                page_sql = sql_for('selectMapPoints' if 'path' in case else 'selectVisiblePage', q)

                def query():
                    start = time.perf_counter_ns()
                    cursor.execute(*count_sql)
                    total = cursor.fetchone()['COUNT(*)']
                    midpoint = time.perf_counter_ns()
                    rows = []
                    if total > (0 if 'path' in case else q['offset']):
                        cursor.execute(*page_sql)
                        rows = cursor.fetchall()
                    end = time.perf_counter_ns()
                    return total, [str(r['id']) for r in rows], (midpoint-start)/1e6, (end-midpoint)/1e6

                total, ids, _, _ = query()
                _, live = endpoint.request(case)
                actual_total = live['matchedCount'] if 'path' in case else live['total']
                actual_ids = [r['id'] for r in live['points' if 'path' in case else 'records']]
                if total != actual_total or ids != actual_ids:
                    raise RuntimeError('SQL/API count or ordered ID mismatch: ' + case['name'])
                for _ in range(2):
                    query()
                    endpoint.request(case)
                count_ms, page_ms, pair_ms, http_ms = [], [], [], []
                for _ in range(args.rounds):
                    n, result_ids, c, p = query()
                    elapsed, live = endpoint.request(case)
                    if n != total or result_ids != ids or (live.get('total', live.get('matchedCount'))) != total:
                        raise RuntimeError('Data changed during benchmark')
                    count_ms.append(c)
                    page_ms.append(p)
                    pair_ms.append(c+p)
                    http_ms.append(elapsed)
                result = dict(case, total=total, returned=len(ids),
                              sql_count=summary(count_ms), sql_page=summary(page_ms),
                              sql_pair=summary(pair_ms), http=summary(http_ms),
                              raw_ms=dict(sql_count=count_ms, sql_page=page_ms, sql_pair=pair_ms, http=http_ms))
                report['cases'].append(result)
                if case['name'] in ('keyword:火锅', 'keyword:量子独角兽餐厅987654321', 'catalog:page1000', 'filter:distance'):
                    plans = {}
                    for label, statement in [('count', count_sql), ('page', page_sql)]:
                        cursor.execute('EXPLAIN ANALYZE ' + statement[0], statement[1])
                        plans[label] = [list(row.values())[0] for row in cursor.fetchall()]
                    report['plans'][case['name']] = plans
                print(json.dumps({'case': i+1, 'of': len(cases), 'name': case['name'],
                                  'total': total, 'http_p95_ms': result['http']['p95_ms']}, ensure_ascii=False), flush=True)
        conn.rollback()
        # SQL微基准完成后再单独发HTTP混合负载，固定请求数且无客户端思考时间，不推断饱和容量。
        mix = [c for c in cases if c['name'] in ('keyword:火锅', 'keyword:蜜雪冰城', 'keyword:面',
               'keyword:量子独角兽餐厅987654321', 'catalog:first', 'filter:distance')]
        for concurrency in (1, 4, 8):
            def worker(worker_id):
                client = Endpoint(args.port)
                timings, errors = [], []
                try:
                    client.request(mix[worker_id % len(mix)])
                    for j in range(30):
                        try:
                            elapsed, _ = client.request(mix[(j+worker_id) % len(mix)])
                            timings.append(elapsed)
                        except Exception as error:
                            errors.append(type(error).__name__)
                finally:
                    client.close()
                return timings, errors

            start = time.perf_counter()
            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                outcomes = list(pool.map(worker, range(concurrency)))
            seconds = time.perf_counter() - start
            values = [v for timings, _ in outcomes for v in timings]
            errors = [e for _, errors in outcomes for e in errors]
            result = dict(concurrency=concurrency, requests=concurrency*30, warmup_requests=concurrency,
                          wall_seconds=seconds, successful_rps=len(values)/seconds,
                          errors=errors, latency=summary(values), raw_ms=values)
            report['load'].append(result)
            print(json.dumps({k:v for k,v in result.items() if k != 'raw_ms'}), flush=True)
    finally:
        conn.rollback()
        conn.close()
        endpoint.close()
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str)+'\n', encoding='utf-8')
    print('Report:', args.output)


if __name__ == '__main__':
    main()
