"""正式搜索入口只读评测：同JAR的SQL/ES实例、冻结词法目标、暖缓存延迟及有界并发。

不启动/停止业务服务，不修改数据库、词库、索引、别名或缓存。实例需预先在回环地址启动，
分别关闭/开启查询开关，并关闭Flyway、Outbox消费及图片清理。输出必须位于仓库外且不存在。
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import random
import time
import unicodedata
from urllib.parse import urlencode

from benchmark_mysql_search import summary
from evaluate_restaurant_search import ROOT, CASES, connect, matches, read_snapshot
from setup_restaurant_search_index import LocalElasticsearch, setup

NEW_CASES = Path(__file__).with_name('search-acceptance-cases-2026-11-05.json')
ORDINARY = {'exact', 'fragment', 'brand', 'category', 'single', 'address', 'combined'}


def single_han_query(word):
    """冻结查询集的自动排序预期：覆盖统一/兼容汉字、部首、Han数字及迭代符。"""
    if len(word) != 1:
        return False
    return (unicodedata.name(word, '').startswith(('CJK UNIFIED IDEOGRAPH', 'CJK COMPATIBILITY IDEOGRAPH',
                                                  'CJK RADICAL', 'KANGXI RADICAL'))
            or word in '々〇〻〡〢〣〤〥〦〧〨〩〸〹〺'
            or ord(word) in (0x16FE2, 0x16FE3, 0x16FF0, 0x16FF1))


def literal_search_match(row, word):
    """独立资料扫描只判断原字存在，不继承MySQL排序规则的字符等价扩展。"""
    return any(word in (value or '') for value in
               (row.get('name'), row.get('address'), row.get('categoryName'), *row.get('tags', [])))


def digest(value):
    """只持久化摘要、身份及指标；原始商户正文不写入仓库。"""
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    default=str, separators=(',', ':')).encode('utf-8')).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8')


def expected_engine(mode, params):
    """核对当前v003引擎契约；单字也必须实际走ES，不能把异常降级隐藏在成功HTTP中。"""
    text = params.get('keyword', '').strip()
    return 'MYSQL' if mode == 'mysql' or not text else 'ES'


def signature(data, map_points=False):
    key = 'points' if map_points else 'records'
    return {'total': data['matchedCount' if map_points else 'total'],
            'ids': [int(row['id']) for row in data[key]], 'search': data['search']}


class Endpoint:
    """工作线程独立复用HTTP/1.1；计时含完整响应读取，不含JSON解析或浏览器渲染。"""
    def __init__(self, port, mode):
        self.connection = http.client.HTTPConnection('127.0.0.1', port, timeout=15)
        self.mode = mode

    def request(self, case):
        params = case['params']
        query = {key: str(value).lower() if isinstance(value, bool) else value
                 for key, value in params.items() if value is not None}
        start = time.perf_counter_ns()
        self.connection.request('GET', case.get('path', '/api/restaurants') + '?' + urlencode(query))
        response = self.connection.getresponse()
        body = response.read()
        elapsed = (time.perf_counter_ns() - start) / 1e6
        if response.status != 200:
            raise RuntimeError(f'HTTP {response.status}: {case["name"]}')
        payload = json.loads(body)
        if payload.get('code') != 'OK':
            raise RuntimeError('Unexpected response code')
        data = payload['data']
        state = data.get('search', {})
        expected = expected_engine(self.mode, params)
        sort = params.get('sort', 'auto')
        if sort == 'auto':
            word = params.get('keyword', '').strip()
            single = single_han_query(word)
            sort = 'relevance' if word and not single else 'score'
        if sort == 'relevance' and expected == 'MYSQL':
            sort = 'score'
        if state.get('engine') != expected or state.get('degraded') is not False or state.get('effectiveSort') != sort:
            raise RuntimeError(f'Unexpected engine/fallback/sort: {case["name"]}')
        return elapsed, data

    def close(self):
        self.connection.close()


def database_state():
    """固定本地库只读事务；摘要覆盖资料、统计、标签、相册、Outbox及迁移历史，便于核对无写入。"""
    connection = connect()
    try:
        with connection.cursor() as cursor:
            cursor.execute('START TRANSACTION READ ONLY')
            cursor.execute('SELECT VERSION() AS mysql_version,@@innodb_buffer_pool_size AS buffer_pool_bytes')
            state = cursor.fetchone()
            state['tables'] = {}
            for table in ('restaurants', 'restaurant_tags', 'restaurant_review_statistics',
                          'restaurant_photos', 'restaurant_search_outbox', 'flyway_schema_history'):
                cursor.execute('SELECT * FROM ' + table)
                rows = cursor.fetchall()
                state['tables'][table] = {'rows': len(rows), 'sha256': digest(sorted(map(digest, rows)))}
            cursor.execute('SELECT id,search_projection_version FROM restaurants')
            versions = {row['id']: row['search_projection_version'] for row in cursor.fetchall()}
            cursor.execute('SELECT status,COUNT(*) AS n FROM restaurant_search_outbox GROUP BY status')
            state['outbox'] = cursor.fetchall()
            if any(row['status'] != 'SUCCEEDED' for row in state['outbox']):
                raise RuntimeError('Outbox not drained; benchmark would measure fallback')
            cursor.execute('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')
            state['flyway'] = cursor.fetchone()['version']
            cursor.execute('SELECT SUM(participant_count>0) AS rated FROM restaurant_review_statistics')
            state['rated_restaurants'] = cursor.fetchone()['rated']
            return state, versions
    finally:
        connection.rollback()
        connection.close()


def index_state(client, corpus, versions):
    """核对现行mapping/别名及所有公开文档身份、源版本和共同字段；不refresh或创建实验索引。"""
    definition = setup(client, check_only=True)
    response = client.request('POST', '/restaurant-search/_search', {
        'size': 10000, 'track_total_hits': True, 'version': True, 'sort': [{'id': 'asc'}],
        'query': {'match_all': {}}})
    hits = response['hits']['hits']
    if response.get('timed_out') or response['_shards']['failed'] or response['hits']['total']['relation'] != 'eq':
        raise RuntimeError('Incomplete index validation')
    if response['hits']['total']['value'] != len(hits) or len(hits) != len(corpus):
        raise RuntimeError('Index/corpus count mismatch')
    expected = {row['id']: row for row in corpus}
    for hit in hits:
        source = hit['_source']
        identity = source['id']
        if identity not in expected or hit['_id'] != str(identity) or source['projectionVersion'] != versions[identity] or hit['_version'] != versions[identity]:
            raise RuntimeError('Index identity/version mismatch')
        for field, value in expected[identity].items():
            if source.get(field) != value:
                raise RuntimeError('Index field mismatch: ' + field)
    return {'count': len(hits), 'document_sha256': digest(hits), 'definition': definition,
            'alias': client.request('GET', '/_alias/restaurant-search')}


def metrics(ids, expected):
    """仅评价冻结字段目标：Precision@5以实际返回的前五条为分母，全量覆盖不是Recall@5。"""
    target = set(expected)
    top = ids[:5]
    hits = len(target.intersection(top))
    return {'target_count': len(target), 'hit_at_5': bool(hits),
            'target_precision_at_5': hits / len(top) if top else 0,
            'target_recall_at_5': hits / len(target) if target else None,
            'full_target_coverage': len(target.intersection(ids)) / len(target) if target else None,
            'first_target_rank': next((i + 1 for i, identity in enumerate(ids) if identity in target), None)}


def all_results(endpoint, case):
    """经真实API以100条逐页获取全量ID；不提高窗口，重复ID/计数变化立即失败。"""
    ids, rows, total = [], [], None
    for page in range(1, 101):
        request = {'name': case['query'], 'params': {'keyword': case['query'], 'pageSize': 100, 'page': page}}
        _, data = endpoint.request(request)
        if total is None:
            total = data['total']
            if total > 10000:
                raise RuntimeError('Accuracy corpus exceeds accessible result window')
        elif total != data['total']:
            raise RuntimeError('Result total changed during pagination')
        ids.extend(int(row['id']) for row in data['records'])
        if page == 1:
            rows = [{key: row.get(key) for key in ('id', 'name', 'address', 'categoryName', 'score', 'recommendRate')}
                    for row in data['records'][:5]]
        if len(ids) >= total:
            break
    if len(ids) != total or len(set(ids)) != len(ids):
        raise RuntimeError('Incomplete or duplicate accuracy result IDs')
    return {'total': total, 'ids': ids, 'top5': rows, 'engine': data['search']['engine'],
            'metrics': metrics(ids, case['expectedIds'])}


def performance_cases(old_cases):
    cases = [{'name': 'keyword:' + case['query'], 'kind': case['kind'], 'params': {'keyword': case['query']}}
             for case in old_cases]
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
    for sort in ('score', 'recommend', 'price', 'reviews'):
        cases.append({'name': 'sort:hotpot_' + sort, 'params': {'keyword': '火锅', 'sort': sort}})
    cases += [
        {'name': 'filter:coffee_distance', 'params': {'keyword': '咖啡', 'latitude': 30.75, 'longitude': 103.93,
                                                   'maxDistanceMeters': 3000, 'sort': 'distance'}},
        {'name': 'map:coffee', 'path': '/api/restaurants/map-points', 'params': dict(bounds, keyword='咖啡')},
    ]
    return cases


def run(args, *, supplemental_accuracy=(), scenario_factory=performance_cases):
    """先冻结、校验、再按固定种子交错测量；并发单独运行，不混入串行分位数。"""
    output = args.output.resolve()
    if ROOT == output or ROOT in output.parents or output.exists():
        raise ValueError('Output must be a new directory outside repository')
    output.mkdir(parents=True)
    old = json.loads(CASES.read_text(encoding='utf-8'))['cases']
    new = json.loads(NEW_CASES.read_text(encoding='utf-8'))['cases']
    before, versions = database_state()
    corpus, _, metadata = read_snapshot([])
    frozen = []
    for label, cases in [('original44', old), ('new20', new), ('supplemental', supplemental_accuracy)]:
        for source in cases:
            case = dict(source, cohort=label)
            case['expectedIds'] = [row['id'] for row in corpus if (
                literal_search_match(row, case['query']) if case.get('literalSearch') else matches(row, case))]
            if not case['expectedIds'] and not case.get('none') and not case.get('allowEmpty'):
                raise RuntimeError('Frozen positive target empty: ' + case['query'])
            frozen.append(case)
    # 这一步在正式ES结果读取前落盘，后续不根据结果修改用例/目标/权重。
    save(output / 'frozen-cases.json', frozen)
    client = LocalElasticsearch()
    index_before = index_state(client, corpus, versions)
    endpoints = {'mysql': Endpoint(args.mysql_port, 'mysql'), 'search': Endpoint(args.search_port, 'search')}
    report = {'started_utc': datetime.now(timezone.utc).isoformat(), 'rounds': args.rounds,
              'method': {'warmups': 2, 'seed': 20261105, 'percentile': 'nearest-rank',
                         'scope': 'direct loopback HTTP including body read; excludes JSON decode/browser',
                         'accuracy': 'frozen lexical field targets, not complete user semantic labels',
                         'concurrency': ('skipped' if getattr(args, 'skip_load', False)
                                         else 'closed-loop fixed-count smoke; no saturation/capacity claim')},
              'environment': dict(os=platform.platform(), python=platform.python_version(), logical_cpus=os.cpu_count(),
                                  corpus_sha256=digest(corpus), frozen_cases_sha256=digest(frozen), **metadata),
              'database_before': before, 'index_before': index_before, 'accuracy': [], 'cases': [], 'load': []}
    report['environment']['source_sha256'] = {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in (
            Path(__file__), CASES, NEW_CASES,
            ROOT / 'backend/target/ginkgo-food-map-backend-0.1.0-SNAPSHOT.jar',
            ROOT / 'backend/src/main/java/com/ginkgo/foodmap/restaurant/search/RestaurantSearchReader.java',
            ROOT / 'backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantQueryService.java',
            ROOT / 'backend/src/main/java/com/ginkgo/foodmap/restaurant/config/RestaurantSearchCircuitBreakerConfiguration.java',
            ROOT / 'backend/src/main/resources/application.yml',
            ROOT / 'backend/src/main/resources/elasticsearch/restaurant-search-v003.json',
            ROOT / 'backend/src/main/resources/mapper/restaurant/RestaurantCatalogMapper.xml')}
    try:
        for case in frozen:
            item = {'query': case['query'], 'kind': case['kind'], 'cohort': case['cohort'], 'expectedIds': case['expectedIds']}
            for mode, endpoint in endpoints.items():
                item[mode] = all_results(endpoint, case)
            item['same_id_set'] = set(item['mysql']['ids']) == set(item['search']['ids'])
            report['accuracy'].append(item)
            print(json.dumps({'phase': 'accuracy', 'query': case['query'], 'mysql': item['mysql']['total'],
                              'search': item['search']['total'], 'engine': item['search']['engine']}, ensure_ascii=False), flush=True)
        save(output / 'accuracy.json', report['accuracy'])
        cases = scenario_factory(old)
        if len({case['name'] for case in cases}) != len(cases):
            raise ValueError('Scenario names must be unique')
        save(output / 'frozen-scenarios.json', cases)
        rng = random.Random(20261105)
        signatures = {}
        for case in cases:
            item = dict(case, modes={})
            for mode, endpoint in endpoints.items():
                for _ in range(2):
                    _, data = endpoint.request(case)
                sig = signature(data, 'path' in case)
                signatures[(case['name'], mode)] = sig
                item['modes'][mode] = dict(signature=sig, raw_ms=[])
            report['cases'].append(item)
        # 每轮打乱场景及引擎先后；每个场景仍恰好20轮，两端不同时压测。
        for turn in range(args.rounds):
            order = list(range(len(cases)))
            rng.shuffle(order)
            for position in order:
                case = cases[position]
                modes = list(endpoints)
                rng.shuffle(modes)
                for mode in modes:
                    elapsed, data = endpoints[mode].request(case)
                    if signature(data, 'path' in case) != signatures[(case['name'], mode)]:
                        raise RuntimeError('Timed response identity/engine changed: ' + case['name'])
                    report['cases'][position]['modes'][mode]['raw_ms'].append(elapsed)
            print(json.dumps({'phase': 'serial', 'round': turn + 1, 'of': args.rounds}), flush=True)
        for item in report['cases']:
            for value in item['modes'].values():
                value['http'] = summary(value['raw_ms'])
        save(output / 'serial.json', report['cases'])
        mixes = {
            'historical_mixed': ['keyword:火锅', 'keyword:蜜雪冰城', 'keyword:面',
                                 'keyword:量子独角兽餐厅987654321', 'catalog:first', 'filter:distance'],
            'es_only_intents': ['keyword:火锅', 'keyword:蜜雪冰城', 'keyword:冒菜',
                                'keyword:清水河咖啡', 'keyword:量子独角兽餐厅987654321', 'filter:coffee_distance']}
        if getattr(args, 'skip_load', False):
            mixes = {}
        by_name = {case['name']: case for case in cases}
        for mix_name, names in mixes.items():
            mix = [by_name[name] for name in names]
            for concurrency in (1, 4, 8):
                modes = list(endpoints)
                rng.shuffle(modes)
                for mode in modes:
                    def worker(identity):
                        endpoint = Endpoint(args.mysql_port if mode == 'mysql' else args.search_port, mode)
                        timings, failures, engines = [], [], {}
                        try:
                            endpoint.request(mix[identity % len(mix)])
                            for iteration in range(30):
                                case = mix[(iteration + identity) % len(mix)]
                                try:
                                    elapsed, data = endpoint.request(case)
                                    if signature(data) != signatures[(case['name'], mode)]:
                                        raise RuntimeError('Load response changed')
                                    timings.append(elapsed)
                                    engine = data['search']['engine']
                                    engines[engine] = engines.get(engine, 0) + 1
                                except Exception as error:
                                    failures.append(type(error).__name__)
                        finally:
                            endpoint.close()
                        return timings, failures, engines
                    started = time.perf_counter()
                    with ThreadPoolExecutor(max_workers=concurrency) as pool:
                        values = list(pool.map(worker, range(concurrency)))
                    duration = time.perf_counter() - started
                    raw = [sample for timings, _, _ in values for sample in timings]
                    errors = [error for _, failures, _ in values for error in failures]
                    engines = {}
                    for _, _, counts in values:
                        for engine, count in counts.items():
                            engines[engine] = engines.get(engine, 0) + count
                    item = dict(mix=mix_name, mode=mode, concurrency=concurrency, raw_ms=raw,
                                http=summary(raw), errors=errors, engines=engines, wall_seconds=duration,
                                successful_requests_per_second=len(raw) / duration)
                    report['load'].append(item)
                    print(json.dumps({'phase': 'load', **{k: item[k] for k in ('mix', 'mode', 'concurrency', 'http', 'engines')},
                                      'errors': len(errors)}, ensure_ascii=False), flush=True)
        report['database_after'], after_versions = database_state()
        report['index_after'] = index_state(client, corpus, after_versions)
        if report['database_after'] != before or after_versions != versions or report['index_after'] != index_before:
            raise RuntimeError('Database/index fingerprint changed; cannot publish stable-data result')
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        save(output / 'results.json', report)
        print(json.dumps({'phase': 'complete', 'accuracy_cases': len(frozen), 'performance_cases': len(cases)}), flush=True)
    except Exception as error:
        report['failure_type'] = type(error).__name__
        save(output / 'incomplete.json', report)
        raise
    finally:
        for endpoint in endpoints.values():
            endpoint.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--mysql-port', default=18080, type=int)
    parser.add_argument('--search-port', default=18081, type=int)
    parser.add_argument('--rounds', default=20, type=int)
    parser.add_argument('--skip-load', action='store_true', help='仅串行正常路径评测，不执行并发负载')
    args = parser.parse_args()
    if not 5 <= args.rounds <= 50 or not all(1 <= port <= 65535 for port in (args.mysql_port, args.search_port)) or args.mysql_port == args.search_port:
        parser.error('rounds must be 5–50, ports must be distinct valid loopback ports')
    run(args)
