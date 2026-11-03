"""仅供本地联调的餐厅数据工具：显式执行、整批事务、重复运行不覆盖已有记录。"""

import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

import pymysql
import yaml


def main():
    """从被Git忽略的local配置读取连接；不接受远程目标、不打印凭据、不改账号。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='实际写入；省略时只预览数量')
    args = parser.parse_args()
    backend = Path(__file__).resolve().parents[1]
    config = yaml.safe_load((backend / 'config/application-local.yml').read_text(encoding='utf-8-sig'))
    datasource = config['spring']['datasource']
    target = urlsplit(datasource['url'].removeprefix('jdbc:'))
    # 开发夹具不能成为生产种子；固定只允许本机常规开发库，且不跟随环境变量重定向。
    if target.hostname not in ('localhost', '127.0.0.1', '::1') or target.path != '/ginkgo_food_map':
        raise ValueError('工具仅允许本机 ginkgo_food_map 开发库')
    fixtures = json.loads((backend / 'tools/restaurant-demo.json').read_text(encoding='utf-8'))['restaurants']
    connection = pymysql.connect(
        host=target.hostname, port=target.port or 3306, database=target.path[1:],
        user=datasource['username'], password=str(datasource.get('password', '')),
        charset='utf8mb4', autocommit=False, connect_timeout=5,
    )
    lock_name = 'ginkgo_food_map:restaurant_demo_seed'
    inserted = skipped = 0
    try:
        with connection.cursor() as cursor:
            # 命名锁覆盖整批检查与提交，避免同时运行两份脚本重复灌入。
            cursor.execute('SELECT GET_LOCK(%s, 5)', (lock_name,))
            if cursor.fetchone()[0] != 1:
                raise ValueError('另一个导入正在运行，请稍后重试')
            for item in fixtures:
                tags = item['tags']
                if '联调模拟' not in tags or len(tags) > 8 or sum(map(len, tags)) > 80:
                    raise ValueError('夹具标签不符合联调标识或长度规则')
                # 包括软删除记录；重复运行不能复活、覆盖或认领已存在的同名店。
                cursor.execute('SELECT id FROM restaurants WHERE name=%s LIMIT 1 FOR UPDATE', (item['name'],))
                if cursor.fetchone():
                    skipped += 1
                    continue
                if not args.apply:
                    # 只预览数量，不执行INSERT，避免回滚仍消耗AUTO_INCREMENT序号。
                    inserted += 1
                    continue
                cursor.execute('''INSERT INTO restaurants
                    (name, category_code, price_per_person, price_kind, address,
                     needs_location, business_status)
                    VALUES (%s, %s, %s, 'ESTIMATE', %s, TRUE, %s)''',
                    (item['name'], item['categoryCode'], item['pricePerPerson'], item['address'], item['businessStatus']))
                restaurant_id = cursor.lastrowid
                cursor.executemany('INSERT INTO restaurant_tags (restaurant_id, tag, position) VALUES (%s, %s, %s)',
                                   [(restaurant_id, tag, position) for position, tag in enumerate(tags)])
                inserted += 1
            if args.apply:
                connection.commit()
            else:
                connection.rollback()
        print(f'{"已写入" if args.apply else "预览（未保存）"}：{inserted} 家；跳过同名记录：{skipped} 家。未创建账号或评价。')
    except Exception:
        connection.rollback()
        raise
    finally:
        try:
            with connection.cursor() as cursor:
                cursor.execute('SELECT RELEASE_LOCK(%s)', (lock_name,))
        finally:
            connection.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # 数据库异常可能包含地址/账号；只输出类型，完整凭据与配置不得进入终端日志。
        print(f'联调数据导入失败（{type(error).__name__}），未提交本批数据。请核对本地配置、V3及夹具。')
        raise SystemExit(1) from None
