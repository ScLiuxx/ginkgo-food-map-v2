"""v003＋查询熔断最终只读评测：沿用原64词，补充原字单字及Unicode边界。

调用共用评测实现，额外冻结109个计时场景；不注入故障、不修改业务数据或索引，
不执行降级后的MySQL承载测试。需要事先启动同JAR的SQL/ES回环实例。
"""
import argparse
import json
from pathlib import Path

from benchmark_es_search import CASES, NEW_CASES, performance_cases, run
from benchmark_single_character_index import cases as single_character_cases

BOUNDARIES = ('𠀀', '〇', '々', '〻')


def accuracy_supplement():
    """仅补已有64词未覆盖的原字目标；读取结果前由独立MySQL快照冻结目标ID。"""
    existing = {row['query'] for path in (CASES, NEW_CASES)
                for row in json.loads(path.read_text(encoding='utf-8'))['cases']}
    words = [row['params']['keyword'] for row in single_character_cases()[:12]] + list(BOUNDARIES)
    return [{'query': word, 'kind': 'single_boundary' if word in BOUNDARIES else 'single',
             'literalSearch': True, 'allowEmpty': word in BOUNDARIES}
            for word in words if word not in existing]


def final_scenarios(old):
    """按历史/新增/单字/边界/地图分组保留原始样本，不把重复意图当真实流量权重。"""
    result = [dict(row, cohort='historical60') for row in performance_cases(old)]
    result += [{'name': 'fresh:' + row['query'], 'kind': row['kind'], 'cohort': 'fresh20',
                'params': {'keyword': row['query']}}
               for row in json.loads(NEW_CASES.read_text(encoding='utf-8'))['cases']]
    result += [dict(row, cohort='single22') for row in single_character_cases()]
    result += [{'name': 'unicode:' + word, 'cohort': 'unicode4', 'params': {'keyword': word}}
               for word in BOUNDARIES]
    result += [{'name': 'map:single_' + word, 'cohort': 'mapSingle3',
                'path': '/api/restaurants/map-points',
                'params': {'keyword': word, 'west': 103, 'east': 105, 'south': 30, 'north': 32}}
               for word in ('面', '茶', '饭')]
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--mysql-port', default=18080, type=int)
    parser.add_argument('--search-port', default=18081, type=int)
    parser.add_argument('--rounds', default=20, type=int)
    args = parser.parse_args()
    if not 5 <= args.rounds <= 50 or not all(1 <= port <= 65535 for port in (args.mysql_port, args.search_port)) or args.mysql_port == args.search_port:
        parser.error('rounds must be 5–50, ports must be distinct valid loopback ports')
    args.skip_load = True
    run(args, supplemental_accuracy=accuracy_supplement(), scenario_factory=final_scenarios)
