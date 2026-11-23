#!/usr/bin/env python3
"""首次准备服务器私有配置与JWT密钥；不启动应用、不连接数据库或云服务。"""

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import stat
import subprocess
import tempfile


DESTINATION = Path("/etc/foodmap")
DATABASE_ENV = Path("/opt/foodmap-infra/backend-database.env")
FILES = ("jwt-private.pem", "jwt-public.pem", "backend.env")
DATABASE_KEYS = {
    "DB_URL", "DB_USERNAME", "DB_PASSWORD", "DB_POOL_SIZE",
    "REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD", "REDIS_DATABASE",
}


def text_value(value):
    """禁止换行注入和未解析的本地环境占位符；错误不包含实际配置值。"""
    if not isinstance(value, str) or any(char in value for char in "\r\n\0"):
        raise ValueError("Invalid configuration value; expected single-line text")
    if "${" in value:
        raise ValueError("Resolve local environment placeholders before transfer")
    return value


def required_text(mapping, key):
    value = text_value(mapping.get(key, ""))
    if not value.strip():
        raise ValueError("Missing required configuration field: " + key)
    return value


def environment_line(key, value):
    # systemd EnvironmentFile使用双引号；这不是可供shell source的脚本。
    value = text_value(value).replace("\\", "\\\\").replace('"', '\\"')
    return key + '="' + value + '"\n'


def build_environment(database, settings):
    values = {}
    for line in database.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key not in DATABASE_KEYS or key in values:
            raise ValueError("Unexpected or duplicate database configuration field")
        values[key] = text_value(value)
    if set(values) != DATABASE_KEYS or not values["DB_PASSWORD"] or not values["REDIS_PASSWORD"]:
        raise ValueError("Incomplete server database configuration")

    mail = settings["mail"]
    cos = settings["cos"]
    port = mail["port"]
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError("Invalid SMTP port")
    properties = mail.get("properties", {})
    if not isinstance(properties, dict):
        raise ValueError("Invalid SMTP properties")
    normalized = {}
    for key, value in properties.items():
        key = text_value(key).removeprefix("[").removesuffix("]")
        if not key.startswith("mail.smtp.") or not isinstance(value, (str, bool, int)):
            raise ValueError("Unexpected SMTP property")
        if isinstance(value, str):
            text_value(value)
        normalized[key] = value
    values.update({
        "SERVER_ADDRESS": "127.0.0.1",
        "SERVER_PORT": "8080",
        "SMTP_HOST": required_text(mail, "host"),
        "SMTP_PORT": str(port),
        "SMTP_USERNAME": required_text(mail, "username"),
        "SMTP_PASSWORD": required_text(mail, "password"),
        "AUTH_MAIL_FROM": required_text(settings, "mailFrom"),
        "AUTH_ALLOWED_EMAIL_DOMAINS": "std.uestc.edu.cn",
        "AUTH_CODE_HMAC_SECRET": secrets.token_hex(32),
        "JWT_ISSUER": "foodmap",
        "JWT_AUDIENCE": "foodmap-api",
        "JWT_PRIVATE_KEY_LOCATION": "file:/etc/foodmap/jwt-private.pem",
        "JWT_PUBLIC_KEY_LOCATION": "file:/etc/foodmap/jwt-public.pem",
        # 保留已有SMTP的TLS／超时配置，JSON为Spring Boot原生配置入口。
        "SPRING_APPLICATION_JSON": json.dumps(
            {"spring": {"mail": {"properties": normalized}}}, separators=(",", ":")
        ),
        "COS_ENABLED": "true",
        "COS_REGION": required_text(cos, "region"),
        "COS_BUCKET": required_text(cos, "bucket"),
        "COS_SECRET_ID": required_text(cos, "secret-id"),
        "COS_SECRET_KEY": required_text(cos, "secret-key"),
        "OSS_ENABLED": "false",
        "ES_ENABLED": "true",
        "ES_ADDRESS": "127.0.0.1:9200",
        "ES_SYNC_ENABLED": "true",
        # 首次启动先核对同步及真实分词，再开启ES查询与统计采集。
        "ES_QUERY_ENABLED": "false",
        "ACTIVITY_STATISTICS_ENABLED": "false",
    })
    token = text_value(cos.get("session-token", ""))
    if token:
        raise ValueError("Temporary COS credentials need an explicit refresh plan before production")
    return "".join(environment_line(key, value) for key, value in values.items())


def ensure_new_targets(destination):
    for name in FILES:
        if os.path.lexists(destination / name):
            raise ValueError("Existing configuration or JWT key preserved; initialization refused")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, required=True, help="Private SMTP/COS JSON file")
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise ValueError("Run on the Ubuntu server using sudo python3 prepare.py")
    import grp
    import pwd

    group = grp.getgrnam("foodmap").gr_gid
    pwd.getpwnam("foodmap")
    pwd.getpwnam("foodmap-deploy")
    directory = DESTINATION.lstat()
    if (not stat.S_ISDIR(directory.st_mode) or directory.st_uid != 0
            or directory.st_gid != group or directory.st_mode & 0o027):
        raise ValueError("Expected root:foodmap private configuration directory")
    ensure_new_targets(DESTINATION)
    if not shutil.which("openssl"):
        raise ValueError("OpenSSL is required")
    source = args.settings.lstat()
    if not stat.S_ISREG(source.st_mode) or source.st_mode & 0o077:
        raise ValueError("Private input must be a regular file with mode 600")
    environment = build_environment(
        DATABASE_ENV.read_text(encoding="utf-8"),
        json.loads(args.settings.read_text(encoding="utf-8")),
    )

    # 同文件系统内暂存，硬链接以不覆盖的方式安装；env最后发布。
    with tempfile.TemporaryDirectory(prefix=".prepare-", dir=DESTINATION) as temporary:
        staging = Path(temporary)
        private = staging / "jwt-private.pem"
        public = staging / "jwt-public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
             "-out", str(private)], check=True, capture_output=True, timeout=60,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)],
            check=True, capture_output=True, timeout=10,
        )
        for path in (private, public):
            path.chmod(0o640)
            os.chown(path, 0, group)
        descriptor = os.open(staging / "backend.env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(environment)
        for name in FILES:
            os.link(staging / name, DESTINATION / name)
    print("Backend configuration and JWT keys ready at /etc/foodmap.")
    print("Existing server database passwords reused; no credentials printed.")
    print("No application started, no database or cloud requests made.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        # 不回显可能含凭证的解析错误、配置正文或子进程输出。
        raise SystemExit("Preparation failed. Check private input, directory permissions and existing files; no existing files overwritten.") from None
