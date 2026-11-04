"""在既有本地ES容器内开启独立限内存JVM，验证小词表的词项覆盖变化。

不修改服务进程词典或IK配置；仅复制四个本次UUID临时文件到/tmp并在finally精确清理。
此诊断不计算ES排名；基线必须与已保存的真实_analyze逐项一致，才能引用新增词效果。
"""
import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import uuid


def main():
    """读取上一步已冻结的快照，输出基线一致性及词项覆盖结果，不再读取MySQL。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--container", default="healthy-agent-elasticsearch")
    args = parser.parse_args()
    output = args.evidence.resolve()
    root = Path(__file__).resolve().parents[2]
    if output == root or root in output.parents:
        raise ValueError("词项证据必须在仓库外")
    result = json.loads((output / "results.json").read_text(encoding="utf-8"))
    docs = json.loads((output / "corpus.json").read_text(encoding="utf-8"))
    texts = {f"q{i}": x["case"]["query"] for i, x in enumerate(result["results"])}
    for row in docs:
        for field in ("name", "address", "categoryName"):
            texts[f"d{row['id']}-{field}"] = row.get(field) or ""
        for number, tag in enumerate(row.get("tags", [])):
            texts[f"d{row['id']}-tag{number}"] = tag
    encoded = "\n".join(key + "\t" + base64.b64encode(value.encode("utf-8")).decode("ascii") for key, value in texts.items()) + "\n"
    (output / "dictionary-input.tsv64").write_text(encoded, encoding="utf-8")
    # 第一步是已观察到漏召回的品类/商圈词，第二步是可选品牌整词；不混入简称或错别字。
    additions = [["冒菜", "螺蛳粉", "天街"],
                 ["蜜雪冰城", "瑞幸", "库迪", "茶百道", "塔斯汀", "袁记云饺", "书亦烧仙草", "桂苑", "科创", "合信路"]]
    (output / "dictionary-additions.txt").write_text("\n".join(",".join(group) for group in additions) + "\n", encoding="utf-8")
    prefix = "/tmp/ginkgo-ik-eval-" + uuid.uuid4().hex
    paths = [prefix + suffix for suffix in (".java", ".input", ".words", ".output")]
    if not all(re.fullmatch(r"/tmp/ginkgo-ik-eval-[a-f0-9]{32}\.(java|input|words|output)", p) for p in paths):
        raise ValueError("非法临时路径")
    def docker(*command, **kwargs):
        return subprocess.run(["docker", *command], check=True, **kwargs)
    try:
        for source, target in zip((Path(__file__).with_name("EvaluateIkDictionary.java"),
                                   output / "dictionary-input.tsv64", output / "dictionary-additions.txt"), paths):
            docker("cp", str(source), args.container + ":" + target, stdout=subprocess.DEVNULL)
        with (output / "dictionary-jvm.log").open("wb") as log:
            docker("exec", args.container, "/usr/share/elasticsearch/jdk/bin/java", "-Xmx384m", "-XX:ActiveProcessorCount=1",
                   "--class-path", "/usr/share/elasticsearch/lib/*:/usr/share/elasticsearch/plugins/analysis-ik/*",
                   paths[0], "/usr/share/elasticsearch/config/analysis-ik", paths[1], paths[2], paths[3],
                   stdout=log, stderr=subprocess.STDOUT)
        docker("cp", args.container + ":" + paths[3], str(output / "dictionary-output.tsv64"), stdout=subprocess.DEVNULL)
    finally:
        # 不递归、不用通配符，只删除已验证位于/tmp且属于本次UUID的四个文件。
        # docker cp创建的输入属root，sticky /tmp要求同属主删除；只在精确清理时使用root。
        docker("exec", "--user", "0", args.container, "rm", "-f", *paths, stdout=subprocess.DEVNULL)
    token_maps = [{}, {}, {}]
    for line in (output / "dictionary-output.tsv64").read_text(encoding="utf-8").splitlines():
        phase, key, analyzer, encoded_tokens = line.split("\t", 3)
        tokens = [base64.b64decode(t).decode("utf-8") for t in encoded_tokens.split(",") if t]
        token_maps[int(phase)].setdefault(key, {})[analyzer] = tokens
    parity = []
    checks = 0
    for number, item in enumerate(result["results"]):
        for analyzer, tokens in item["analysis"].items():
            checks += 1
            if token_maps[0][f"q{number}"][analyzer] != [t["token"] for t in tokens]:
                parity.append({"query": item["case"]["query"], "analyzer": analyzer})
    # 不仅核对查询端；漏召回商户的原始索引词项也必须与实际ES一致。
    for missing in json.loads((output / "miss-analysis.json").read_text(encoding="utf-8")):
        for field, tokens in missing["indexTokens"].items():
            checks += 1
            key = f"d{missing['document']['id']}-{field}"
            if token_maps[0][key]["ik_max_word"] != [token["token"] for token in tokens]:
                parity.append({"document": missing["document"]["id"], "field": field})
    if parity:
        raise ValueError("独立JVM与真实ES查询分词基线不一致，不能引用补词结论")
    diagnostics = []
    for number, item in enumerate(result["results"]):
        entry = {"query": item["case"]["query"], "kind": item["case"]["kind"],
                 "expectedCount": len(item["case"]["expectedIds"]), "phases": []}
        for token_map in token_maps:
            query = set(token_map[f"q{number}"]["ik_smart"])
            covered = []
            for row in docs:
                union = set()
                for field in ("name", "address", "categoryName"):
                    union.update(token_map[f"d{row['id']}-{field}"]["ik_max_word"])
                for tag_number in range(len(row.get("tags", []))):
                    union.update(token_map[f"d{row['id']}-tag{tag_number}"]["ik_max_word"])
                if query and query <= union:
                    covered.append(row["id"])
            relevant = set(item["case"]["expectedIds"])
            entry["phases"].append({"queryTokens": token_map[f"q{number}"]["ik_smart"],
                                     "tokenCoveredCount": len(covered),
                                     "expectedCoveredIds": sorted(relevant.intersection(covered)),
                                     "expectedMissingIds": sorted(relevant.difference(covered))})
        diagnostics.append(entry)
    report = {"analyzerParityChecks": checks, "analyzerParityFailures": parity,
              "additions": additions, "limitation": "仅验证查询词项在索引词项中的覆盖，不是ES重建后的实际BM25/短语排名或召回率。", "cases": diagnostics}
    (output / "dictionary-diagnostics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Independent JVM dictionary diagnostics completed; service dictionaries unchanged.")


if __name__ == "__main__":
    main()
