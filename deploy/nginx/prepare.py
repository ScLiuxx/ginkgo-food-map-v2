#!/usr/bin/env python3
"""首次安装项目Nginx配置；备份默认站点、检查后等待管理员显式reload。"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile


DIRECTORY = Path("/etc/nginx/foodmap")
SITE = Path("/etc/nginx/sites-available/foodmap")
ENABLED = Path("/etc/nginx/sites-enabled/foodmap")
DEFAULT = Path("/etc/nginx/sites-enabled/default")
DEFAULT_SITE = Path("/etc/nginx/sites-available/default")


class SetupError(RuntimeError):
    """可公开的固定诊断信息；不得拼接配置值或子进程输出。"""


def require_njs_module(modules):
    if not any("ngx_http_js_module.so" in path.read_text(encoding="utf-8")
               for path in modules.glob("*.conf")):
        raise SetupError(
            "Nginx njs module is not enabled; install a module matching this Nginx distribution. "
            "Ubuntu 22.04 does not provide libnginx-mod-http-js in its default repository"
        )


def credentials(settings):
    """固定格式避免凭据进入Nginx语法；错误不输出输入值。"""
    values = [settings["key"], settings["jscode"]]
    if any(not isinstance(value, str) or not re.fullmatch(r"[a-fA-F0-9]{32}", value)
           for value in values):
        raise SetupError("Expected 32-character hexadecimal AMap credentials")
    return (f'set $foodmap_amap_key "{values[0]}";\n'
            f'set $foodmap_amap_jscode "{values[1]}";\n')


def write_private(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", required=True, type=Path, help="Private AMap JSON file")
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise SetupError("Run on Ubuntu using sudo python3 prepare.py")
    for target in (DIRECTORY, SITE, ENABLED):
        if os.path.lexists(target):
            raise SetupError("Existing project Nginx configuration preserved")
    entries = list(ENABLED.parent.iterdir())
    if any(entry != DEFAULT for entry in entries):
        raise SetupError("Other enabled sites detected; inspect before installation")
    default_target = None
    if os.path.lexists(DEFAULT):
        if not DEFAULT.is_symlink() or DEFAULT.resolve() != DEFAULT_SITE:
            raise SetupError("Unexpected default site; preserved existing configuration")
        default_target = os.readlink(DEFAULT)
    require_njs_module(Path("/etc/nginx/modules-enabled"))
    source_stat = args.settings.lstat()
    if not stat.S_ISREG(source_stat.st_mode) or source_stat.st_mode & 0o077:
        raise SetupError("Private input must be a regular file with mode 600")
    secret = credentials(json.loads(args.settings.read_text(encoding="utf-8")))
    current = subprocess.run(["nginx", "-t"], capture_output=True, timeout=30)
    if current.returncode != 0:
        raise SetupError("Existing Nginx configuration failed validation before any changes")

    source = Path(__file__).resolve().parent
    backup = Path(tempfile.mkdtemp(prefix=".foodmap-before-", dir="/etc/nginx"))
    if DEFAULT_SITE.is_file():
        write_private(backup / "default-site.conf", DEFAULT_SITE.read_text(encoding="utf-8"))
    write_private(backup / "state.json", json.dumps({"defaultTarget": default_target}) + "\n")
    installed_directory = False
    installed_site = False
    installed_enabled = False
    disabled_default = False
    try:
        with tempfile.TemporaryDirectory(prefix=".foodmap-prepare-", dir="/etc/nginx") as temporary:
            staging = Path(temporary)
            configuration = staging / "foodmap"
            configuration.mkdir(mode=0o755)
            configuration.chmod(0o755)
            for name in ("amap-proxy.js", "amap-proxy-common.conf"):
                target = configuration / name
                shutil.copyfile(source / name, target)
                target.chmod(0o644)
            write_private(configuration / "amap-credentials.conf", secret)
            candidate = staging / "site.conf"
            shutil.copyfile(source.parent / "nginx.conf", candidate)
            candidate.chmod(0o644)
            configuration.rename(DIRECTORY)
            installed_directory = True
            os.link(candidate, SITE)
            installed_site = True
            ENABLED.symlink_to(SITE)
            installed_enabled = True
            if default_target is not None:
                DEFAULT.unlink()
                disabled_default = True
            validation = subprocess.run(["nginx", "-t"], capture_output=True, timeout=30)
            if validation.returncode != 0:
                # 仅root可读的诊断文件；不将可能包含私有配置的错误输出到终端。
                write_private(backup / "validation.log", validation.stderr.decode("utf-8", errors="replace"))
                raise SetupError("New Nginx configuration failed validation; see the root-only backup diagnostics")
    except Exception:
        if installed_enabled:
            ENABLED.unlink()
        if disabled_default:
            DEFAULT.symlink_to(default_target)
        if installed_site:
            SITE.unlink()
        if installed_directory:
            DIRECTORY.rename(backup / "rejected-foodmap")
        raise
    print("Nginx configuration ready; syntax validation passed.")
    print("Default site backup: " + str(backup))
    print("No reload performed. Run sudo nginx -t, then sudo systemctl reload nginx.")
    print("Before the first application release, HTTP 503 deployment pending is expected.")


if __name__ == "__main__":
    try:
        main()
    except SetupError as error:
        raise SystemExit("Nginx setup failed: " + str(error) + ". No reload performed.") from None
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
        raise SystemExit("Nginx setup failed. Existing files were not overwritten; check module, input permissions and root-only backup diagnostics. No reload performed.") from None
