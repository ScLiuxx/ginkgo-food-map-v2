"""本机v002→v003受控重建：默认只读预检，--apply显式复制、校验并原子切换别名。

保留旧物理索引，不启用网站查询。短暂对商户投影相关MySQL表加READ锁，允许读取，
阻止复制期间的业务写入；要求Outbox已清空。任一核验失败都不提交别名切换。
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import json

from benchmark_es_search import database_state, digest
from evaluate_restaurant_search import connect, read_snapshot
from setup_restaurant_search_index import ALIAS, INDEX, LocalElasticsearch, load_spec, verify_definition

PREVIOUS = 'restaurant-search-v002'
LOCK_TABLES = ('restaurants', 'restaurant_categories', 'restaurant_tags',
               'restaurant_review_statistics', 'restaurant_photos',
               'restaurant_change_requests', 'restaurant_search_outbox')


def alias_target(client):
    """只接受单一已知写索引，防止将其他环境或多索引别名当成待迁移源。"""
    state = client.request('GET', '/_alias/' + ALIAS)
    if len(state) != 1 or next(iter(state)) not in (PREVIOUS, INDEX):
        raise RuntimeError('Unexpected alias target; no changes performed')
    target = next(iter(state))
    if state[target]['aliases'][ALIAS] != {'is_write_index': True}:
        raise RuntimeError('Unexpected alias options; no changes performed')
    return target


def snapshot(client, target):
    """有界读取全部正文及external版本；拒绝截断、部分分片和不一致身份。"""
    response = client.request('POST', '/' + target + '/_search', {
        'size': 10000, 'track_total_hits': True, 'version': True,
        'sort': [{'id': 'asc'}], 'query': {'match_all': {}}})
    hits = response['hits']['hits']
    total = response['hits']['total']
    if (response.get('timed_out') or response['_shards']['failed']
            or total['relation'] != 'eq' or total['value'] != len(hits) or not hits):
        raise RuntimeError('Incomplete source snapshot; no alias change')
    result = {}
    for hit in hits:
        source = hit['_source']
        identity = str(source['id'])
        version = source['projectionVersion']
        if identity != hit['_id'] or identity in result or version < 1 or hit['_version'] != version:
            raise RuntimeError('Document identity/version mismatch')
        result[identity] = {'source': source, 'version': version}
    return result


def verify_candidate(client):
    """候选只能具有当前完整定义且未绑定其他别名；已有候选绝不覆盖结构。"""
    spec = load_spec()
    actual = client.request('GET', '/' + INDEX)[INDEX]
    settings = actual['settings']['index']
    if actual['mappings'] != spec['mappings'] or settings['analysis'] != spec['settings']['analysis']:
        raise RuntimeError('Candidate schema drift; no alias change')
    for key in ('number_of_shards', 'number_of_replicas', 'refresh_interval'):
        if str(settings[key]) != str(spec['settings'][key]):
            raise RuntimeError('Candidate settings drift; no alias change')
    if actual.get('aliases'):
        raise RuntimeError('Candidate already has aliases; no alias change')


def copy_and_switch(client, expected):
    """只修改固定v003候选；保留版本，完整内容相等后一次remove/add，无删除操作。"""
    if alias_target(client) != PREVIOUS:
        raise RuntimeError('Alias changed before rebuild')
    if client.optional('/' + INDEX) is None:
        spec = deepcopy(load_spec())
        spec['aliases'] = {}
        if client.request('PUT', '/' + INDEX, spec).get('acknowledged') is not True:
            raise RuntimeError('Candidate creation not acknowledged; inspect before retry')
    verify_candidate(client)
    copied = client.request('POST', '/_reindex', {
        'source': {'index': PREVIOUS}, 'dest': {'index': INDEX, 'version_type': 'external'},
        'conflicts': 'proceed'})
    if copied.get('timed_out') or copied.get('failures') or copied.get('total') != len(expected):
        raise RuntimeError('Reindex incomplete; old alias retained')
    client.request('POST', '/' + INDEX + '/_refresh')
    if snapshot(client, INDEX) != expected or snapshot(client, PREVIOUS) != expected:
        raise RuntimeError('Full source/version mismatch; old alias retained')
    if alias_target(client) != PREVIOUS:
        raise RuntimeError('Alias changed during rebuild')
    switched = client.request('POST', '/_aliases', {'actions': [
        {'remove': {'index': PREVIOUS, 'alias': ALIAS, 'must_exist': True}},
        {'add': {'index': INDEX, 'alias': ALIAS, 'is_write_index': True}}]})
    if switched.get('acknowledged') is not True:
        raise RuntimeError('Alias acknowledgement uncertain; inspect alias before retry')
    verify_definition(client, load_spec())
    return copied


def run(client, apply=False):
    """默认只读。实际迁移期间短暂锁定相关表，始终释放；不写MySQL数据或配置。"""
    if alias_target(client) == INDEX:
        verify_definition(client, load_spec())
        return {'index': INDEX, 'changed': False, 'status': 'already-current'}
    connection = connect()
    try:
        if apply:
            with connection.cursor() as cursor:
                cursor.execute('SET SESSION lock_wait_timeout=5')
                cursor.execute('LOCK TABLES ' + ', '.join(name + ' READ' for name in LOCK_TABLES))
        before, versions = database_state()
        corpus, _, _ = read_snapshot([])
        if apply:
            client.request('POST', '/' + PREVIOUS + '/_refresh')
        expected = snapshot(client, PREVIOUS)
        if set(expected) != {str(row['id']) for row in corpus}:
            raise RuntimeError('Public corpus/source identities differ; rebuild not started')
        for row in corpus:
            stored = expected[str(row['id'])]
            if stored['version'] != versions[row['id']] or any(stored['source'].get(k) != v for k, v in row.items()):
                raise RuntimeError('Source is not current; drain synchronization before rebuild')
        result = {'from': PREVIOUS, 'to': INDEX, 'documents': len(expected),
                  'document_sha256': digest(expected), 'changed': False}
        if apply:
            result['reindex'] = copy_and_switch(client, expected)
            result['changed'] = True
        after, _ = database_state()
        if before != after:
            raise RuntimeError('Database fingerprint changed; inspect migration evidence')
        result['database_sha256'] = digest(before)
        return result
    finally:
        # 断开连接也会释放表锁；显式解锁便于确认异常路径没有遗留维护锁。
        try:
            if apply:
                with connection.cursor() as cursor:
                    cursor.execute('UNLOCK TABLES')
        finally:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(run(LocalElasticsearch(), args.apply), ensure_ascii=False))
        return 0
    except Exception as error:
        # 不输出连接配置、MySQL异常全文或资料正文。
        print(json.dumps({'error': type(error).__name__, 'status': 'inspect-before-retry'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
