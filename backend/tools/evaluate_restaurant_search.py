"""显式本地搜索实验：只读MySQL快照、随机隔离ES索引、固定查询集和可复查原始结果。

依赖requirements-data.txt。不安装/修改IK词库，不切换正式别名，不提供业务投影或同步功能。
相关集是事先固定的字段规则，只衡量这组词法目标，不冒充真实用户标注的语义相关性。
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import sys
import uuid
from urllib.parse import urlsplit
from urllib.request import Request

import pymysql
import yaml

from setup_restaurant_search_index import LocalElasticsearch, load_spec, setup

ROOT = Path(__file__).resolve().parents[2]
CASES = Path(__file__).with_name("search-evaluation-cases.json")
PREFIX = "ginkgo-search-eval-"
MODES = ("smart_or", "smart_and", "max_and")


def matches(row: dict, rule: dict) -> bool:
    """从结果产生前冻结的规则构建相关ID；不按ES排名反向挑选正例。"""
    if rule.get("none"):
        return False
    if "all" in rule:
        return all(matches(row, part) for part in rule["all"])
    if "ids" in rule:
        return row["id"] in rule["ids"]
    if "nameEquals" in rule:
        return row["name"].casefold() == rule["nameEquals"].casefold()
    fields = {"name": ("name",), "address": ("address",),
              "anyNameOrAddress": ("name", "address"),
              "anyNameOrCategory": ("name", "categoryName")}
    for key, names in fields.items():
        if key in rule:
            return any(rule[key].casefold() in (row.get(name) or "").casefold() for name in names)
    raise ValueError("查询缺少显式相关集规则")


def query_body(text: str, mode: str, size: int) -> dict:
    """三种探索策略共用权重与精确提升，只改变查询分析器和词项组合方式。

    cross_fields允许名称、地址、品类共同满足AND；默认OR作为噪声对照。
    精确/短语提升只是实验参数，尚未成为正式业务查询契约。
    """
    if mode not in MODES:
        raise ValueError("未知查询实验策略")
    analyzer = "ik_max_word" if mode == "max_and" else "ik_smart"
    operator = "or" if mode == "smart_or" else "and"
    exact = {"term": {"name.exact": {"value": text, "boost": 100}}}
    fulltext = {"multi_match": {"query": text, "type": "cross_fields", "operator": operator,
                               "analyzer": analyzer,
                               "fields": ["name^6", "categoryName^3", "tags^2", "address"]}}
    return {"size": size, "track_total_hits": True, "_source": ["id", "name", "address", "categoryName"],
            "query": {"bool": {"filter": [{"term": {"deleted": False}}],
                               "must": [{"bool": {"should": [exact, fulltext], "minimum_should_match": 1}}],
                               "should": [{"match_phrase": {"name": {"query": text, "analyzer": analyzer, "boost": 12}}}]}},
            "sort": ["_score", {"id": "asc"}]}


def connect():
    """只读本机固定开发库；读取忽略配置但不输出URL、账号或密码。"""
    datasource = yaml.safe_load((ROOT / "backend/config/application-local.yml").read_text(encoding="utf-8-sig"))["spring"]["datasource"]
    target = urlsplit(datasource["url"].removeprefix("jdbc:"))
    if target.hostname not in ("localhost", "127.0.0.1") or target.path != "/ginkgo_food_map":
        raise ValueError("评测只允许本机ginkgo_food_map开发库")
    return pymysql.connect(host=target.hostname, port=target.port or 3306, database=target.path[1:],
                           user=datasource["username"], password=str(datasource.get("password", "")),
                           charset="utf8mb4", autocommit=False, cursorclass=pymysql.cursors.DictCursor,
                           connect_timeout=5, read_timeout=30)


def read_snapshot(cases: list[dict]) -> tuple[list[dict], list[dict], dict]:
    """同一个REPEATABLE READ只读快照导出检索字段并执行现行LIKE/评分排序。

    无筛选/无用户原点、sort=score的SQL与现行目录条件一致；不把测试DSL称为已有ES入口。
    只导出公开商户与搜索字段，不读账号、私有审核、图片字节或密码等资料。
    """
    connection = connect()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
            cursor.execute("""SELECT r.id, r.name, c.name AS categoryName, r.address,
                r.category_code AS categoryCode, r.restaurant_type AS restaurantType,
                r.supports_dine_in AS supportsDineIn, r.supports_delivery AS supportsDelivery,
                r.price_per_person AS pricePerPerson, r.price_kind AS priceKind,
                r.business_status AS businessStatus, r.latitude, r.longitude, r.needs_location AS needsLocation,
                COALESCE(s.participant_count,0) AS participantCount, COALESCE(s.review_count,0) AS reviewCount,
                CAST(s.overall_score_sum AS DECIMAL(40,20))/NULLIF(s.participant_count,0) AS ratingScore,
                CAST(s.recommend_count AS DECIMAL(40,20))/NULLIF(s.participant_count,0) AS recommendRate
                FROM restaurants r LEFT JOIN restaurant_categories c ON c.code=r.category_code
                LEFT JOIN restaurant_review_statistics s ON s.restaurant_id=r.id
                WHERE r.deleted_at IS NULL ORDER BY r.id""")
            rows = cursor.fetchall()
            if not 0 < len(rows) <= 10000:
                raise ValueError("实验全量ID比较仅允许1–10000家商户，不提高ES默认窗口")
            cursor.execute("""SELECT t.restaurant_id,t.tag FROM restaurant_tags t JOIN restaurants r ON r.id=t.restaurant_id
                WHERE r.deleted_at IS NULL ORDER BY t.restaurant_id,t.position""")
            tags = {}
            for tag in cursor.fetchall():
                tags.setdefault(tag["restaurant_id"], []).append(tag["tag"])
            docs = []
            for row in rows:
                lat, lon = row.pop("latitude"), row.pop("longitude")
                row = {key: float(value) if isinstance(value, Decimal) else value for key, value in row.items()}
                for key in ("needsLocation", "supportsDineIn", "supportsDelivery"):
                    row[key] = None if row[key] is None else bool(row[key])
                row.update(tags=tags.get(row["id"], []), deleted=False)
                # 本实验不验证正式投影版本或相册链路，不伪造projectionVersion、coverPhotoId。
                row["location"] = {"lat": float(lat), "lon": float(lon)} if lat is not None and lon is not None and not row["needsLocation"] else None
                docs.append(row)
            baselines = []
            for case in cases:
                pattern = "%" + case["query"].strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
                cursor.execute("""SELECT r.id,r.name,r.address,c.name AS categoryName,
                    CAST(s.overall_score_sum AS DECIMAL(40,20))/NULLIF(s.participant_count,0) AS score
                    FROM restaurants r LEFT JOIN restaurant_categories c ON c.code=r.category_code
                    LEFT JOIN restaurant_review_statistics s ON s.restaurant_id=r.id
                    WHERE r.deleted_at IS NULL AND (r.name LIKE %s ESCAPE '!'
                    OR r.address LIKE %s ESCAPE '!' OR c.name LIKE %s ESCAPE '!'
                    OR EXISTS(SELECT 1 FROM restaurant_tags t WHERE t.restaurant_id=r.id AND t.tag LIKE %s ESCAPE '!'))
                    ORDER BY score IS NULL,score DESC,r.name,r.id""", (pattern,) * 4)
                result = cursor.fetchall()
                baselines.append({"total": len(result), "ids": [r["id"] for r in result],
                                  "top5": [{key: r[key] for key in ("id", "name", "address", "categoryName")} for r in result[:5]]})
            cursor.execute("SELECT VERSION() AS version")
            version = cursor.fetchone()["version"]
            return docs, baselines, {"mysqlVersion": version, "taggedRestaurants": len(tags),
                                     "tagCount": sum(map(len, tags.values()))}
    finally:
        connection.rollback()
        connection.close()


def write_json(path: Path, value):
    """证据保存到显式仓库外目录，使用UTF-8保留真实中文名称。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def analyze(client: LocalElasticsearch, index: str, text: str) -> dict:
    """同时记录索引与查询词项以及位置，便于解释未命中和相邻词边界。"""
    return {analyzer: client.request("POST", f"/{index}/_analyze", {"analyzer": analyzer, "text": text})["tokens"]
            for analyzer in ("ik_smart", "ik_max_word")}


def bulk_import(client: LocalElasticsearch, index: str, docs: list[dict]):
    """只向UUID隔离索引批量写入，逐项确认目标、ID和状态，不能只检查HTTP 200。"""
    if not re.fullmatch(PREFIX + "[a-f0-9]{32}", index):
        raise ValueError("bulk仅允许随机实验索引")
    for offset in range(0, len(docs), 500):
        batch = docs[offset:offset + 500]
        lines = []
        for document in batch:
            lines.extend((json.dumps({"index": {"_id": str(document["id"])}}),
                          json.dumps(document, ensure_ascii=False)))
        request = Request(client.url + f"/{index}/_bulk", data=("\n".join(lines) + "\n").encode("utf-8"),
                          method="POST", headers={"Content-Type": "application/x-ndjson"})
        with client.opener.open(request, timeout=30) as response:
            result = json.load(response)
        items = result.get("items", [])
        if result.get("errors") or len(items) != len(batch):
            raise RuntimeError("bulk存在失败或缺少逐项结果")
        for document, item in zip(batch, items):
            written = item.get("index", {})
            if (written.get("status") not in (200, 201) or written.get("_index") != index
                    or written.get("_id") != str(document["id"])):
                raise RuntimeError("bulk返回目标、ID或状态不符")


def remove_owned_index(client: LocalElasticsearch, index: str):
    """清理只能精确指定实验UUID，拒绝正式物理索引、别名和通配符。"""
    if not re.fullmatch(PREFIX + "[a-f0-9]{32}", index):
        raise ValueError("拒绝删除不属于本次实验的索引")
    client.request("DELETE", "/" + index)


def run(output: Path):
    """创建本次UUID索引，逐条校验bulk结果，finally仅清理确认拥有的实验索引。"""
    if output.resolve() == ROOT or ROOT in output.resolve().parents:
        raise ValueError("原始结果须保存在仓库外，避免提交完整商户快照")
    output.mkdir(parents=True, exist_ok=False)
    definition = json.loads(CASES.read_text(encoding="utf-8"))
    cases = definition["cases"]
    # 在第一次ES结果读取前冻结查询、相关ID和SQL基线，随后不按结果改相关集。
    docs, baselines, metadata = read_snapshot(cases)
    for case in cases:
        case["expectedIds"] = [d["id"] for d in docs if matches(d, case)]
        if not case.get("none") and not case.get("allowEmpty") and not case["expectedIds"]:
            raise ValueError("预期正例为空，请先核对用例与资料：" + case["query"])
    write_json(output / "corpus.json", docs)
    write_json(output / "frozen-cases.json", definition)
    client = LocalElasticsearch()
    before = setup(client, check_only=True)
    index = PREFIX + uuid.uuid4().hex
    spec = load_spec()
    spec["aliases"] = {}  # 禁止继承正式业务别名；实验只访问随机物理索引。
    created = False
    results = []
    try:
        creation = client.request("PUT", "/" + index, spec)
        if creation.get("acknowledged") is not True:
            raise RuntimeError("实验建索引未获确认，停止，不推断已拥有资源")
        created = True
        bulk_import(client, index, docs)
        client.request("POST", f"/{index}/_refresh")
        count = client.request("GET", f"/{index}/_count")["count"]
        if count != len(docs):
            raise RuntimeError("实验索引数量与快照不一致")
        words = {word: analyze(client, index, word) for word in definition["dictionaryCandidates"]}
        write_json(output / "dictionary-analysis.json", words)
        missed_details = []
        documents_by_id = {doc["id"]: doc for doc in docs}
        for number, (case, sql) in enumerate(zip(cases, baselines), 1):
            item = {"case": case, "analysis": analyze(client, index, case["query"]), "mysql": sql}
            for mode in MODES:
                body = query_body(case["query"], mode, len(docs))
                response = client.request("POST", f"/{index}/_search", body)
                if response.get("timed_out") or response["_shards"]["failed"]:
                    raise RuntimeError("查询超时或分片失败，不作为有效评测")
                hits = response["hits"]["hits"]
                if response["hits"]["total"]["relation"] != "eq" or len(hits) != response["hits"]["total"]["value"]:
                    raise RuntimeError("未获取完整结果，不能计算全量目标覆盖")
                item[mode] = {"total": response["hits"]["total"]["value"], "ids": [h["_source"]["id"] for h in hits],
                              "top5": [{**h["_source"], "score": h["_score"]} for h in hits[:5]], "dsl": body}
            results.append(item)
            if case["kind"] in ("category", "combined"):
                missed = sorted(set(case["expectedIds"]) - set(item["smart_and"]["ids"]))
                for position, missing_id in enumerate(missed):
                    document = documents_by_id[missing_id]
                    detail = {"query": case["query"], "document": document,
                              "indexTokens": {field: client.request("POST", f"/{index}/_analyze",
                                   {"analyzer": "ik_max_word", "text": document.get(field) or ""})["tokens"]
                                   for field in ("name", "address", "categoryName")}}
                    # 每类查询首个漏召回保留真实_explain，避免仅凭词项猜测ES是否匹配。
                    if position == 0:
                        detail["explain"] = client.request("POST", f"/{index}/_explain/{missing_id}",
                                                          {"query": query_body(case["query"], "smart_and", 1)["query"]})
                    missed_details.append(detail)
            print(f"Evaluated {number}/{len(cases)}", flush=True)
        write_json(output / "miss-analysis.json", missed_details)
        metadata.update(timestamp=datetime.now(timezone.utc).isoformat(), restaurants=len(docs), index=index,
                        esVersion=client.request("GET", "/")["version"]["number"],
                        corpusSha256=hashlib.sha256((output / "corpus.json").read_bytes()).hexdigest(),
                        casesSha256=hashlib.sha256((output / "frozen-cases.json").read_bytes()).hexdigest(),
                        formalBefore=before)
        write_json(output / "results.json", {"metadata": metadata, "results": results})
    finally:
        if created:
            remove_owned_index(client, index)
        after = setup(client, check_only=True)
        write_json(output / "cleanup.json", {"index": index, "deleted": created,
                                              "formalAfter": after, "formalUnchanged": before == after})
        if before != after:
            raise RuntimeError("正式索引状态发生变化，需单独核查")


def main():
    """只有显式--run才读取业务快照并创建实验索引；失败信息不输出本地凭据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.run:
        parser.error("必须显式--run开启本地隔离评测")
    try:
        run(args.output)
    except Exception as error:
        print(f"Evaluation failed ({type(error).__name__}); inspect isolated evidence, no credentials logged.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
