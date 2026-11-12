"""分阶段榜单读取基准入口；可运行MySQL基线、Redis对照或MySQL/Redis/Caffeine三模式对照。

默认只从环境变量取测试数据库凭据；--local-config显式读取已忽略的本地配置。
Java测试只连接回环MySQL并创建随机隔离库，不向开发业务库写入模拟榜单。
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True,
                        help='证据目录，保存JSON原始样本、运行信息及测试日志')
    parser.add_argument('--local-config', action='store_true',
                        help='从backend/config/application-local.yml读取凭据，不打印内容')
    parser.add_argument('--integration-only', action='store_true', help='只验证功能，不运行计时基准')
    parser.add_argument('--redis', action='store_true', help='同时验证Redis规则并测量MySQL/Redis两种模式')
    parser.add_argument('--caffeine', action='store_true', help='需同时使用--redis，增加Caffeine验证及三模式对照')
    parser.add_argument('--redis-faults', action='store_true', help='仅限私有临时Redis：测试暂停和ACL拒绝删除，并自动恢复')
    args = parser.parse_args()
    evidence = args.output.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    if args.redis and 'RANKING_TEST_REDIS_PORT' not in env:
        parser.error('--redis须显式设置私有测试实例的RANKING_TEST_REDIS_PORT')
    if args.redis_faults and not args.redis:
        parser.error('--redis-faults须同时使用--redis，并仅针对私有临时实例')
    if args.caffeine and not args.redis:
        parser.error('--caffeine须同时使用--redis，三模式必须连接相同隔离存储')
    if args.local_config:
        import yaml
        config = yaml.safe_load((ROOT / 'backend/config/application-local.yml').read_text(encoding='utf-8'))
        source = config['spring']['datasource']
        match = re.match(r'^(jdbc:mysql://(?:127\.0\.0\.1|localhost):[0-9]{1,5}/)', source['url'])
        if not match:
            parser.error('本地配置必须是回环MySQL地址')
        env['RANKING_TEST_DB_SERVER'] = match.group(1)
        env['RANKING_TEST_DB_USERNAME'] = source['username']
        env['RANKING_TEST_DB_PASSWORD'] = str(source['password'])
    if 'RANKING_TEST_DB_PASSWORD' not in env:
        parser.error('请设置RANKING_TEST_DB_PASSWORD，或显式使用--local-config')
    maven = shutil.which('mvn.cmd') or shutil.which('mvn')
    if not maven:
        parser.error('未找到Maven')
    command = [maven, '-Dstyle.color=never', '-Dranking.read.integration=true',
               '-Dtest=RestaurantRankingReadServiceTest,RestaurantRankingCacheTest,RestaurantRankingReadMySqlIntegrationTest']
    if args.redis:
        command.append('-Dranking.redis.integration=true')
    if args.redis_faults:
        command.append('-Dranking.redis.faults=true')
    if args.caffeine:
        command.append('-Dranking.caffeine.integration=true')
    if not args.integration_only:
        name = 'mysql-redis-caffeine.json' if args.caffeine else 'mysql-redis.json' if args.redis else 'mysql-only.json'
        command += ['-Dranking.read.benchmark=true', f'-Dranking.benchmark.output={evidence / name}']
    command.append('test')
    started = datetime.now(timezone.utc).isoformat()
    with (evidence / 'maven.log').open('w', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=ROOT / 'backend', env=env,
                                stdout=log, stderr=subprocess.STDOUT, check=False)
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True,
                              text=True, check=True).stdout.strip()
    status = subprocess.run(['git', 'status', '--short'], cwd=ROOT, capture_output=True,
                            text=True, check=True).stdout.splitlines()
    metadata = {'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
                'gitRevision': revision, 'workingTreeStatus': status, 'exitCode': result.returncode,
                'benchmark': not args.integration_only,
                'stage': 'MYSQL_REDIS_CAFFEINE_COMPARISON' if args.caffeine else 'MYSQL_REDIS_COMPARISON' if args.redis else 'MYSQL_ONLY'}
    sources = sorted(p for p in (ROOT / 'backend/src').rglob('*Ranking*') if p.is_file())
    sources += [Path(__file__).resolve(), ROOT / 'backend/src/main/resources/application.yml',
                ROOT / 'backend/src/test/resources/application-test.yml', ROOT / 'backend/pom.xml']
    metadata['sourceSha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    (evidence / 'run.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'exitCode': result.returncode, 'evidence': str(evidence)}, ensure_ascii=False))
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
