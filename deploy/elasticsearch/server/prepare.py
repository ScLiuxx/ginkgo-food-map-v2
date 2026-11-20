#!/usr/bin/env python3
"""新服务器ES首次配置；不改MySQL/Redis配置，不启动容器，不写业务索引。"""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.request
import zipfile


DESTINATION = Path("/opt/foodmap-search")
VERSION = "9.5.4"
BASE_IMAGE = (
    "docker.elastic.co/elasticsearch/elasticsearch:9.5.4@sha256:"
    "82ac14f43fe701992e601f4cc81e1c0d7dbc5a2576d8cd736006452925df4026"
)
PLUGIN_URL = "https://get.infini.cloud/elasticsearch/analysis-ik/9.5.4"
PLUGIN_SHA256 = "e6705018287c3e101233189a36d636bd7d1f00293cd02daa94d23e728ad6407c"


def verify_plugin(path):
    if path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("IK archive is larger than expected")
    if hashlib.sha256(path.read_bytes()).hexdigest() != PLUGIN_SHA256:
        raise ValueError("IK archive checksum mismatch; no installation performed")
    with zipfile.ZipFile(path) as archive:
        descriptor = archive.read("plugin-descriptor.properties").decode("utf-8")
        fields = dict(
            line.split("=", 1)
            for line in descriptor.splitlines()
            if line.strip() and not line.startswith("#") and "=" in line
        )
        if (
            fields.get("name") != "analysis-ik"
            or fields.get("version") != VERSION
            or fields.get("elasticsearch.version") != VERSION
            or "config/main.dic" not in archive.namelist()
        ):
            raise ValueError("IK descriptor or base dictionary does not match Elasticsearch")


def prepare_plugin(source, target):
    cached = source / "analysis-ik.zip"
    if cached.is_file():
        verify_plugin(cached)
        shutil.copyfile(cached, target)
    else:
        print("Downloading official IK 9.5.4 archive; this may take several minutes...", flush=True)
        started = time.monotonic()
        size = 0
        with urllib.request.urlopen(PLUGIN_URL, timeout=30) as response, target.open("xb") as output:
            while chunk := response.read(64 * 1024):
                size += len(chunk)
                if size > 32 * 1024 * 1024 or time.monotonic() - started > 300:
                    raise ValueError("IK download exceeded size or time limit")
                output.write(chunk)
    verify_plugin(target)
    target.chmod(0o644)


def configure_memory_maps():
    # ES所需的宿主机虚拟内存映射数量限制；不是分配相应大小的物理内存。
    current = int(Path("/proc/sys/vm/max_map_count").read_text().strip())
    target = max(current, 1048576)
    setting = f"vm.max_map_count={target}\n"
    path = Path("/etc/sysctl.d/99-foodmap-elasticsearch.conf")
    if path.exists() and path.read_text().strip() != setting.strip():
        raise ValueError("Existing ES sysctl configuration differs; preserved it for inspection")
    if not path.exists():
        with path.open("x", encoding="utf-8") as output:
            output.write(setting)
        path.chmod(0o644)
    subprocess.run(["sysctl", "-p", str(path)], check=True)


def main():
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run on the Ubuntu server using sudo python3 prepare.py.")
    if DESTINATION.exists() or DESTINATION.is_symlink():
        raise SystemExit("/opt/foodmap-search already exists; all configuration and data preserved.")
    containers = subprocess.check_output(
        ["docker", "ps", "--all", "--format", "{{.Names}}"], text=True
    ).splitlines()
    volumes = subprocess.check_output(
        ["docker", "volume", "ls", "--format", "{{.Name}}"], text=True
    ).splitlines()
    if "foodmap-elasticsearch" in containers or "foodmap-search_elasticsearch_data" in volumes:
        raise SystemExit("Existing Elasticsearch infrastructure detected; inspect before setup.")
    subprocess.run(["docker", "image", "inspect", BASE_IMAGE], stdout=subprocess.DEVNULL, check=True)

    source = Path(__file__).resolve().parent
    staging = Path(tempfile.mkdtemp(prefix=".foodmap-search-", dir=DESTINATION.parent))
    try:
        (staging / "build").mkdir(mode=0o755)
        (staging / "ik").mkdir(mode=0o755)
        shutil.copyfile(source / "Dockerfile", staging / "build/Dockerfile")
        prepare_plugin(source, staging / "build/analysis-ik.zip")
        for filename in ("IKAnalyzer.cfg.xml", "foodmap-food.dic"):
            target = staging / "ik" / filename
            shutil.copyfile(source.parent / "ik" / filename, target)
            target.chmod(0o644)
        shutil.copyfile(source / "compose.yaml", staging / "compose.yaml")
        subprocess.run(
            ["docker", "compose", "-f", str(staging / "compose.yaml"), "config", "--quiet"],
            check=True,
        )
        configure_memory_maps()
        (staging / "prepared.json").write_text(
            json.dumps({"baseImage": BASE_IMAGE, "pluginSha256": PLUGIN_SHA256}) + "\n",
            encoding="utf-8",
        )
        # 目录不包含凭证；保持0700只供管理员操作，Docker可挂载其中的只读词典。
        staging.rename(DESTINATION)
    finally:
        if staging.exists():
            shutil.rmtree(staging)

    print("Elasticsearch configuration ready at /opt/foodmap-search.")
    print("IK archive verified. Build and start Elasticsearch using Compose next.")
    print("No containers started; MySQL and Redis configuration were not changed.")


if __name__ == "__main__":
    main()
