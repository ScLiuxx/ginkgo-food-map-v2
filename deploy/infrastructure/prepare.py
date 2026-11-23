#!/usr/bin/env python3
"""只用于新服务器首次初始化；不覆盖配置、不启动服务、不输出凭证。"""

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile


DESTINATION = Path("/opt/foodmap-infra")


def write_file(path, content, mode=0o600):
    # staging目录仅root可访问，所有凭证默认以0600保存。
    path.write_text(content, encoding="utf-8")
    path.chmod(mode)


def main():
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run on the Ubuntu server using sudo python3 prepare.py.")
    if DESTINATION.exists() or DESTINATION.is_symlink():
        raise SystemExit(
            "/opt/foodmap-infra already exists; preserved all files and passwords. "
            "Do not rerun initialization or delete existing data."
        )

    # 已有容器或数据卷即使停止也不能按全新安装处理，避免密码和存量数据不一致。
    containers = subprocess.check_output(
        ["docker", "ps", "--all", "--format", "{{.Names}}"], text=True
    ).splitlines()
    volumes = subprocess.check_output(
        ["docker", "volume", "ls", "--format", "{{.Name}}"], text=True
    ).splitlines()
    if {"foodmap-mysql", "foodmap-redis"}.intersection(containers) or {
        "foodmap-infra_mysql_data", "foodmap-infra_redis_data"
    }.intersection(volumes):
        raise SystemExit("Existing infrastructure detected; preserved it. Inspect before setup.")

    source = Path(__file__).resolve().with_name("compose.yaml")
    if not source.is_file():
        raise SystemExit("Keep compose.yaml beside prepare.py before running setup.")

    staging = Path(tempfile.mkdtemp(prefix=".foodmap-infra-", dir=DESTINATION.parent))
    try:
        staging.chmod(0o700)
        (staging / "secrets").mkdir(mode=0o700)
        (staging / "config").mkdir(mode=0o700)
        mysql_password = secrets.token_hex(24)
        root_password = secrets.token_hex(24)
        redis_password = secrets.token_hex(24)
        write_file(staging / "secrets/mysql_password", mysql_password + "\n")
        write_file(staging / "secrets/mysql_root_password", root_password + "\n")
        write_file(staging / "secrets/redis_password", redis_password + "\n")
        write_file(
            staging / "config/redis.conf",
            "bind 0.0.0.0\n"
            "protected-mode yes\n"
            "port 6379\n"
            "dir /data\n"
            "appendonly yes\n"
            "appendfsync everysec\n"
            'save ""\n'
            "maxmemory 256mb\n"
            "maxmemory-policy noeviction\n"
            f"requirepass {redis_password}\n",
            mode=0o644,
        )
        write_file(
            staging / "backend-database.env",
            "DB_URL=jdbc:mysql://127.0.0.1:3306/foodmap?useUnicode=true&characterEncoding=UTF-8"
            "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true\n"
            "DB_USERNAME=foodmap\n"
            f"DB_PASSWORD={mysql_password}\n"
            "DB_POOL_SIZE=10\n"
            "REDIS_HOST=127.0.0.1\n"
            "REDIS_PORT=6379\n"
            f"REDIS_PASSWORD={redis_password}\n"
            "REDIS_DATABASE=4\n",
        )
        write_file(staging / "compose.yaml", source.read_text(encoding="utf-8"), mode=0o644)
        subprocess.run(
            ["docker", "compose", "-f", str(staging / "compose.yaml"), "config", "--quiet"],
            check=True,
        )
        write_file(staging / "initialized.json", json.dumps({"format": 1}) + "\n")
        # staging与目标位于同一目录；配置校验完成后才发布，失败不留下半套密码。
        staging.rename(DESTINATION)
    finally:
        if staging.exists():
            shutil.rmtree(staging)

    print("Infrastructure configuration ready at /opt/foodmap-infra.")
    print("Passwords were generated and saved privately; none are printed.")
    print("No containers have been started by this script.")


if __name__ == "__main__":
    main()
