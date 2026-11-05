"""显式建立或检查本地商户搜索结构；不导入商户，不修改或删除已有索引。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

INDEX = "restaurant-search-v003"
ALIAS = "restaurant-search"
SPEC_PATH = Path(__file__).resolve().parents[1] / "src/main/resources/elasticsearch/restaurant-search-v003.json"


class ElasticsearchRequestError(RuntimeError):
    """保留状态码用于明确区分不存在、校验失败和服务故障，不吞掉非404异常。"""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class LocalElasticsearch:
    """仅访问显式本地无认证实例，绕开系统代理；远端认证与部署另行配置。"""

    def __init__(self, url: str = "http://127.0.0.1:9200"):
        parsed = urlsplit(url)
        if (parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1")
                or parsed.username is not None or parsed.password is not None
                or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
            raise ValueError("初始化工具仅接受本地无认证HTTP根地址，不接收凭据或远程地址")
        self.url = url.rstrip("/")
        self.opener = build_opener(ProxyHandler({}))

    def request(self, method: str, path: str, body: dict | None = None) -> dict:
        """所有请求有超时；错误只报告ES原因，不输出请求正文或配置中的敏感信息。"""
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = Request(self.url + path, data=payload, method=method,
                          headers={"Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=8) as response:
                return json.load(response)
        except HTTPError as error:
            status = error.code
            try:
                # HTTPError也持有响应流，失败请求同样必须关闭，避免批量校验时泄漏连接资源。
                with error:
                    result = json.load(error)
                reason = result.get("error", {})
                message = reason.get("reason", "ES请求失败") if isinstance(reason, dict) else str(reason)
            except (ValueError, TypeError):
                message = "ES返回了非JSON错误"
            raise ElasticsearchRequestError(status, message) from None
        except (URLError, TimeoutError) as error:
            raise RuntimeError("本地ES连接或响应失败：" + str(error.reason if isinstance(error, URLError) else error)) from None

    def optional(self, path: str) -> dict | None:
        """只有404表示目标不存在；连接失败、认证失败等不能误判成可以创建。"""
        try:
            return self.request("GET", path)
        except ElasticsearchRequestError as error:
            if error.status == 404:
                return None
            raise


def load_spec(version: str = "003") -> dict:
    """当前默认v003；历史实验显式读取旧版本，不因正式升级悄悄改变A/B基线。"""
    if version not in ("001", "002", "003"):
        raise ValueError("Unsupported explicit search schema version")
    path = SPEC_PATH.with_name("restaurant-search-v" + version + ".json")
    return json.loads(path.read_text(encoding="utf-8"))


def verify_definition(client: LocalElasticsearch, spec: dict) -> None:
    """重复执行只核对结构，发现漂移直接失败，不覆盖已有文档或偷偷修改mapping。"""
    actual = client.request("GET", "/" + INDEX)
    if set(actual) != {INDEX}:
        raise RuntimeError("物理索引名称已被其他资源占用")
    index = actual[INDEX]
    if index["mappings"] != spec["mappings"]:
        raise RuntimeError("已有mapping与版本定义不一致；请设计新版本索引，不要覆盖")
    settings = index["settings"]["index"]
    for key in ("number_of_shards", "number_of_replicas", "refresh_interval"):
        if str(settings.get(key)) != str(spec["settings"][key]):
            raise RuntimeError("已有索引设置与版本定义不一致：" + key)
    if settings.get("analysis") != spec["settings"]["analysis"]:
        raise RuntimeError("已有分词/规范化配置与版本定义不一致")
    aliases = client.request("GET", "/_alias/" + ALIAS)
    if set(aliases) != {INDEX} or aliases[INDEX]["aliases"].get(ALIAS) != spec["aliases"][ALIAS]:
        raise RuntimeError("别名指向或写索引标记与版本定义不一致")


def setup(client: LocalElasticsearch, *, check_only: bool = False) -> dict:
    """创建空索引并原子绑定别名；冲突时保留原状态，不重绑别名、不删库重建。"""
    spec = load_spec()
    existing = client.optional("/" + INDEX)
    aliases = client.optional("/_alias/" + ALIAS)
    if aliases is not None and set(aliases) != {INDEX}:
        raise RuntimeError("商户搜索别名已指向其他索引，拒绝修改")
    created = False
    if existing is None:
        if check_only:
            raise RuntimeError("商户搜索索引尚未建立")
        if aliases is not None:
            raise RuntimeError("别名存在但物理索引不可读取，拒绝创建")
        # 索引和别名在同一次创建请求中提交，避免留下只有物理索引的半成品。
        result = client.request("PUT", "/" + INDEX, spec)
        if result.get("acknowledged") is not True:
            raise RuntimeError("ES尚未确认建索引，请使用--check核实，不自动重试写入")
        created = True
    verify_definition(client, spec)
    return {"index": INDEX, "alias": ALIAS, "created": created,
            "documentCount": client.request("GET", "/" + INDEX + "/_count")["count"]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:9200", help="本地ES根地址")
    parser.add_argument("--check", action="store_true", help="只核对结构及别名，不创建任何资源")
    arguments = parser.parse_args()
    try:
        print(json.dumps(setup(LocalElasticsearch(arguments.url), check_only=arguments.check), ensure_ascii=False))
        return 0
    except (ValueError, RuntimeError) as error:
        print("商户索引初始化失败：" + str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
