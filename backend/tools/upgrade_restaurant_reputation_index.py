"""本机v003→v004口碑分升级：默认只读，--apply建立新索引并核验后原子切换，保留旧索引。

严格模式执行前停止本地后端并应用V44。迁移期间锁住投影来源表，要求Outbox已清空；不改业务行、
不改变文档external版本。候选仅允许空索引或本工具同一快照的部分结果，失败保留旧别名。
--bootstrap-pending用于历史同步未开启的维护恢复：强制检查本地ES查询已关闭，迁移后需
启动Outbox消费并全量核对数据库版本、公开字段及新分数，核对通过前不得启用ES查询。
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
from urllib.request import Request, ProxyHandler, build_opener

from evaluate_restaurant_search import connect
from rebuild_restaurant_search_index import snapshot
from setup_restaurant_search_index import ALIAS, LocalElasticsearch, mappings_match

PREVIOUS = "restaurant-search-v003"
CURRENT = "restaurant-search-v004"
TABLES = ("restaurants", "restaurant_review_statistics", "restaurant_search_outbox",
          "restaurant_categories", "restaurant_tags", "restaurant_photos", "restaurant_change_requests")


def load_definition():
    """独立读取新版本，不修改历史v003工具及评测的版本默认值。"""
    path = Path(__file__).resolve().parents[1] / "src/main/resources/elasticsearch/restaurant-search-v004.json"
    return json.loads(path.read_text(encoding="utf-8"))


def alias_target(client):
    """只接受本项目已知的单一写索引，拒绝未知或多索引别名。"""
    state = client.request("GET", "/_alias/" + ALIAS)
    if len(state) != 1 or next(iter(state)) not in (PREVIOUS, CURRENT):
        raise RuntimeError("Unexpected search alias")
    target = next(iter(state))
    if state[target]["aliases"][ALIAS] != {"is_write_index": True}:
        raise RuntimeError("Unexpected alias configuration")
    return target


def verify_definition(client, allow_alias=False):
    """新索引必须符合完整版本定义，不覆盖漂移结构或绑定其他别名的索引。"""
    actual = client.request("GET", "/" + CURRENT)[CURRENT]
    definition = load_definition()
    if not mappings_match(actual["mappings"], definition["mappings"]):
        raise RuntimeError("Candidate mapping differs")
    settings = actual["settings"]["index"]
    for key in ("number_of_shards", "number_of_replicas", "refresh_interval", "analysis"):
        if settings[key] != definition["settings"][key] and str(settings[key]) != str(definition["settings"][key]):
            raise RuntimeError("Candidate settings differ")
    expected_aliases = {ALIAS: {"is_write_index": True}} if allow_alias else {}
    if actual.get("aliases", {}) != expected_aliases:
        raise RuntimeError("Candidate alias differs")


def with_reputation(documents, rows, bootstrap_pending=False):
    """由MySQL同一受锁快照提供八位小数口碑分；无评价与删除墓碑不填参考分。"""
    if (not bootstrap_pending and set(documents) != set(rows)) or not set(documents).issubset(rows):
        raise RuntimeError("Database and index identities differ; drain synchronization first")
    result = deepcopy(documents)
    for identity, item in result.items():
        row = rows[identity]
        doc = item["source"]
        version_valid = item["version"] <= row["search_projection_version"] if bootstrap_pending else item["version"] == row["search_projection_version"]
        if not version_valid or (not bootstrap_pending and doc["deleted"] != (row["deleted_at"] is not None)):
            raise RuntimeError("Projection version differs; drain synchronization first")
        if not doc["deleted"] and row["reputation_score"] is not None:
            doc["reputationScore"] = float(row["reputation_score"])
    return result


def write_batch(client, documents):
    """逐项确认Bulk成功，保持原external版本；不把HTTP成功等同于每项写入成功。"""
    lines = []
    for identity, item in documents:
        lines.append(json.dumps({"index": {"_index": CURRENT, "_id": identity,
                                           "version": item["version"], "version_type": "external"}}))
        lines.append(json.dumps(item["source"], ensure_ascii=False))
    request = Request(client.url + "/_bulk", data=("\n".join(lines) + "\n").encode("utf-8"),
                      method="POST", headers={"Content-Type": "application/x-ndjson"})
    with client.opener.open(request, timeout=30) as response:
        result = json.load(response)
    items = result.get("items", [])
    if result.get("errors") or len(items) != len(documents):
        raise RuntimeError("Bulk incomplete; old alias retained")
    for actual, (identity, _) in zip(items, documents):
        written = actual.get("index", {})
        if written.get("_id") != identity or written.get("status") not in (200, 201):
            raise RuntimeError("Bulk item failed; old alias retained")


def run(client, apply=False, bootstrap_pending=False):
    """只在全部正文与版本核验通过后切换；源表锁在任何异常路径都显式释放。"""
    target = alias_target(client)
    if target == CURRENT:
        verify_definition(client, allow_alias=True)
        return {"status": "already-current", "index": CURRENT, "changed": False}
    if apply and bootstrap_pending:
        # 恢复模式不得将待同步的候选结果用于线上搜索，要求本地应用已显式关闭ES查询。
        opener = build_opener(ProxyHandler({}))
        with opener.open("http://127.0.0.1:8080/api/restaurants?pageSize=1", timeout=8) as response:
            search = json.load(response)["data"]["search"]
        if search.get("engine") != "MYSQL" or search.get("reason") != "DISABLED":
            raise RuntimeError("Disable ES queries before bootstrapping pending tasks")
    connection = connect()
    try:
        with connection.cursor() as cursor:
            if apply:
                cursor.execute("SET SESSION lock_wait_timeout=5")
                cursor.execute("LOCK TABLES " + ",".join(table + " READ" for table in TABLES))
            cursor.execute("SELECT COUNT(*) AS n FROM restaurant_search_outbox WHERE status IN ('PENDING','FAILED')")
            pending = cursor.fetchone()["n"]
            if pending and not bootstrap_pending:
                raise RuntimeError("Drain Outbox before upgrading")
            cursor.execute("""SELECT restaurants.id,restaurants.search_projection_version,restaurants.deleted_at,
                CASE WHEN restaurants.restaurant_type='CANTEEN' THEN
                    ROUND(restaurant_review_statistics.overall_score_sum/NULLIF(restaurant_review_statistics.participant_count,0)/2,8)
                    ELSE restaurant_review_statistics.reputation_score END AS reputation_score
                FROM restaurants LEFT JOIN restaurant_review_statistics ON restaurant_review_statistics.restaurant_id=restaurants.id
                ORDER BY restaurants.id""")
            rows = {str(row["id"]): row for row in cursor.fetchall()}
        if apply:
            client.request("POST", "/" + PREVIOUS + "/_refresh")
        original = snapshot(client, PREVIOUS)
        expected = with_reputation(original, rows, bootstrap_pending)
        if not apply:
            return {"status": "preflight", "from": PREVIOUS, "to": CURRENT,
                    "documents": len(expected), "pendingOutbox": pending, "changed": False}
        if client.optional("/" + CURRENT) is None:
            definition = load_definition()
            definition["aliases"] = {}
            if client.request("PUT", "/" + CURRENT, definition).get("acknowledged") is not True:
                raise RuntimeError("Candidate creation uncertain")
        verify_definition(client)
        client.request("POST", "/" + CURRENT + "/_refresh")
        count = client.request("GET", "/" + CURRENT + "/_count")["count"]
        partial = snapshot(client, CURRENT) if count else {}
        if any(expected.get(identity) != value for identity, value in partial.items()):
            raise RuntimeError("Candidate contains unrelated or outdated documents")
        missing = [(identity, value) for identity, value in expected.items() if identity not in partial]
        for offset in range(0, len(missing), 100):
            write_batch(client, missing[offset:offset + 100])
        client.request("POST", "/" + CURRENT + "/_refresh")
        if snapshot(client, CURRENT) != expected or snapshot(client, PREVIOUS) != original:
            raise RuntimeError("Full verification failed; old alias retained")
        if alias_target(client) != PREVIOUS:
            raise RuntimeError("Alias changed during upgrade")
        result = client.request("POST", "/_aliases", {"actions": [
            {"remove": {"index": PREVIOUS, "alias": ALIAS, "must_exist": True}},
            {"add": {"index": CURRENT, "alias": ALIAS, "is_write_index": True}}]})
        if result.get("acknowledged") is not True:
            raise RuntimeError("Alias switch uncertain; inspect before retry")
        verify_definition(client, allow_alias=True)
        return {"status": "awaiting-sync" if bootstrap_pending else "upgraded", "index": CURRENT,
                "documents": len(expected), "pendingOutbox": pending, "changed": True}
    finally:
        try:
            if apply:
                with connection.cursor() as cursor:
                    cursor.execute("UNLOCK TABLES")
        finally:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--bootstrap-pending", action="store_true",
                        help="维护恢复：ES查询须关闭；允许复制旧版本，切换后必须恢复Outbox并全量核对，再启用查询")
    args = parser.parse_args()
    try:
        print(json.dumps(run(LocalElasticsearch(), args.apply, args.bootstrap_pending), ensure_ascii=False))
        return 0
    except Exception as error:
        # 不输出本地凭据、业务正文或异常中可能携带的SQL参数。
        print(json.dumps({"error": type(error).__name__, "status": "inspect-before-retry"}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
