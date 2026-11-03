"""沙河校区开放餐饮数据本地导入：预览默认零写入，显式apply后整批事务、重复执行不追加。"""

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from urllib.parse import urlsplit

import eviltransform
import pymysql
import yaml

CAMPUS_ID = 19816230
BATCH = '沙河导入20261031'
SOURCE_TAGS = ['OSM测试数据', '未经实地核实', '© OpenStreetMap', 'ODbL-1.0']


def distance(lat1, lon1, lat2, lon2):
    """球面直线距离，仅用于选取附近样本，不代表步行距离。"""
    a, b = math.radians(lat1), math.radians(lat2)
    h = math.sin((b-a)/2)**2 + math.cos(a)*math.cos(b)*math.sin(math.radians(lon2-lon1)/2)**2
    return 12742000 * math.asin(math.sqrt(min(1, max(0, h))))


def prepare(raw):
    """从原始OSM响应读取校区中心；拒绝无名、失效、越界和超出3公里的记录。"""
    campus = next(e for e in raw['elements'] if e['type'] == 'relation' and e['id'] == CAMPUS_ID)
    if campus['tags'].get('amenity') != 'university' or '沙河' not in campus['tags']['name']:
        raise ValueError('校区记录不匹配')
    center = campus['center']
    rows = []
    for e in raw['elements']:
        tags = e.get('tags', {})
        if tags.get('amenity') not in ('restaurant', 'fast_food', 'food_court'):
            continue
        if any(tags.get(k) in ('yes', 'true', '1') for k in ('disused', 'abandoned', 'demolished')) or tags.get('end_date'):
            continue
        name = (tags.get('name:zh') or tags.get('name', '')).strip()
        pos = e.get('center', e)
        if not 1 <= len(name) <= 40 or 'lat' not in pos or 'lon' not in pos:
            continue
        lat, lon = pos['lat'], pos['lon']
        if not (math.isfinite(lat) and math.isfinite(lon) and 3 <= lat <= 54 and 73 <= lon <= 136):
            continue
        meters = distance(center['lat'], center['lon'], lat, lon)
        if meters > 3000:
            continue
        gcj_lat, gcj_lon = eviltransform.wgs2gcj(lat, lon)
        address = tags.get('addr:full') or ''.join(tags.get(k, '') for k in ('addr:city', 'addr:district', 'addr:street', 'addr:housenumber'))
        address = address.strip() or None
        if address and len(address) > 80:
            address = None
        # 不从店名猜测菜系，只有明确的fast_food映射快餐，其余归其他并保留原始tags供后续核实。
        rows.append(dict(sourceId=f"osm:{e['type']}:{e['id']}", name=name,
                         categoryCode='FAST_FOOD' if tags['amenity'] == 'fast_food' else 'OTHER',
                         address=address, latitude=round(gcj_lat, 6), longitude=round(gcj_lon, 6),
                         wgs84={'latitude': lat, 'longitude': lon}, distanceToShaheMeters=round(meters, 2),
                         coordinateOrigin='node' if e['type'] == 'node' else 'OSM bounding-box center',
                         sourceUrl=f"https://www.openstreetmap.org/{e['type']}/{e['id']}", sourceTags=tags))
    rows.sort(key=lambda r: (r['distanceToShaheMeters'], r['sourceId']))
    unique = []
    for row in rows:
        if any(row['sourceId'] == old['sourceId'] or
               (row['name'].casefold() == old['name'].casefold() and
                distance(row['latitude'], row['longitude'], old['latitude'], old['longitude']) < 60)
               for old in unique):
            continue
        unique.append(row)
    return campus, unique


def digest(rows):
    """用于核对导入前的存量数据没有被改写，不将数据库凭据或私有字段写入报告。"""
    return hashlib.sha256(json.dumps(rows, default=str, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    campus, candidates = prepare(json.loads(args.input.read_text(encoding='utf-8')))
    backend = Path(__file__).resolve().parents[1]
    config = yaml.safe_load((backend/'config/application-local.yml').read_text(encoding='utf-8-sig'))['spring']['datasource']
    target = urlsplit(config['url'].removeprefix('jdbc:'))
    # 复用本地工具的目标约束，禁止远程主机、其他库或环境变量重定向。
    if target.hostname not in ('localhost', '127.0.0.1', '::1') or target.path != '/ginkgo_food_map':
        raise ValueError('仅允许本机开发库')
    connection = pymysql.connect(host=target.hostname, port=target.port or 3306, database='ginkgo_food_map',
                                 user=config['username'], password=str(config.get('password', '')),
                                 charset='utf8mb4', autocommit=False, connect_timeout=5)
    report = {'batch': BATCH, 'apply': args.apply, 'license': 'https://www.openstreetmap.org/copyright',
              'attribution': '© OpenStreetMap contributors; ODbL-1.0',
              'inputSha256': hashlib.sha256(args.input.read_bytes()).hexdigest(),
              'campus': campus, 'candidateCount': len(candidates), 'skippedExisting': [], 'inserted': []}
    lock = 'ginkgo_food_map:osm_shahe_seed'
    try:
        with connection.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute('SELECT GET_LOCK(%s,5) AS acquired', (lock,))
            if cur.fetchone()['acquired'] != 1:
                raise ValueError('其他导入仍在执行')
            cur.execute('SELECT * FROM restaurants ORDER BY id')
            existing = cur.fetchall()
            old_ids = [r['id'] for r in existing]
            cur.execute('SELECT * FROM restaurant_tags ORDER BY restaurant_id,position')
            old_tags = cur.fetchall()
            # 其他业务表只计算指纹；任何意外变化都回滚，不制造账号、点评或价格。
            cur.execute('SHOW TABLES')
            tables = [next(iter(r.values())) for r in cur.fetchall()]
            untouched = {}
            for table in tables:
                if table in ('restaurants', 'restaurant_tags'):
                    continue
                if not re.fullmatch(r'[a-zA-Z0-9_]+', table):
                    raise ValueError('非法表名')
                cur.execute(f'SELECT * FROM `{table}`')
                untouched[table] = digest(sorted(cur.fetchall(), key=lambda r: json.dumps(r, default=str, sort_keys=True)))
            prior = {t['restaurant_id'] for t in old_tags if t['tag'] == BATCH}
            report['priorBatchCount'] = len(prior)
            source_ids = {t['tag'] for t in old_tags if t['tag'].startswith('osm:')}
            needed = max(0, 100-len(prior))
            selected = []
            for row in candidates:
                duplicate = row['sourceId'] in source_ids or any(
                    row['name'].casefold() == e['name'].casefold() and
                    (e['latitude'] is None or distance(row['latitude'], row['longitude'], float(e['latitude']), float(e['longitude'])) < 100)
                    for e in existing)
                if duplicate:
                    report['skippedExisting'].append(row['sourceId'])
                    continue
                if len(selected) < needed:
                    selected.append(row)
            if len(selected) != needed:
                raise ValueError(f'去重后不足100家：缺{needed-len(selected)}家')
            for row in selected:
                item_tags = SOURCE_TAGS + [row['sourceId'], BATCH]
                if len(item_tags) > 8 or sum(map(len, item_tags)) > 80:
                    raise ValueError('标签超过上限')
                if args.apply:
                    cur.execute('''INSERT INTO restaurants (name,category_code,price_kind,price_per_person,address,
                        latitude,longitude,needs_location,business_status)
                        VALUES (%s,%s,'UNKNOWN',NULL,%s,%s,%s,FALSE,'OPERATING')''',
                                (row['name'],row['categoryCode'],row['address'],row['latitude'],row['longitude']))
                    row['restaurantId'] = cur.lastrowid
                    cur.executemany('INSERT INTO restaurant_tags (restaurant_id,tag,position) VALUES (%s,%s,%s)',
                                    [(row['restaurantId'],tag,i) for i,tag in enumerate(item_tags)])
                report['inserted'].append(row)
            if old_ids:
                placeholders = ','.join(['%s']*len(old_ids))
                cur.execute(f'SELECT * FROM restaurants WHERE id IN ({placeholders}) ORDER BY id', old_ids)
                if digest(cur.fetchall()) != digest(existing):
                    raise ValueError('存量餐厅发生变化')
                cur.execute(f'SELECT * FROM restaurant_tags WHERE restaurant_id IN ({placeholders}) ORDER BY restaurant_id,position',old_ids)
                if digest(cur.fetchall()) != digest(old_tags):
                    raise ValueError('存量标签发生变化')
            for table, before in untouched.items():
                cur.execute(f'SELECT * FROM `{table}`')
                if digest(sorted(cur.fetchall(), key=lambda r: json.dumps(r, default=str, sort_keys=True))) != before:
                    raise ValueError('其他业务表发生变化')
            report['existingRestaurantsUnchanged'] = True
            report['otherTablesUnchanged'] = True
            report['beforeRestaurantCount'] = len(existing)
            # 提交前先保存可追溯清单；失败不会提交数据库，也不会泄露连接配置。
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
            if args.apply:
                connection.commit()
            else:
                connection.rollback()
            print(json.dumps({'apply': args.apply, 'candidates': len(candidates), 'priorBatchCount': len(prior),
                              'newRows': len(selected), 'beforeCount': len(existing),
                              'maxDistanceMeters': max((r['distanceToShaheMeters'] for r in selected),default=None),
                              'report': str(args.report)}, ensure_ascii=True))
    except Exception:
        connection.rollback()
        raise
    finally:
        with connection.cursor() as cur:
            cur.execute('SELECT RELEASE_LOCK(%s)', (lock,))
        connection.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'OSM导入失败（{type(error).__name__}），本批数据未提交。')
        raise SystemExit(1) from None
