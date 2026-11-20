#!/usr/bin/env python3
"""更新现有高德代理JSONP响应处理；保留站点、凭据及应用配置。"""
import os
from pathlib import Path
import hashlib
import shutil
import stat
import subprocess
import tempfile


FILES = {
    "amap-proxy.js": "eea5614ac67ebba7acb46369ea7344beaa24aa22f265177e1d5ce6317362c34f",
    "amap-proxy-common.conf": "429e0d3f5bf94e73ecceb49b3f20aba819ddc3b5b52dac9570b7c07c1aafeeb9",
}


def digest(content):
    return hashlib.sha256(content.replace(b"\r\n", b"\n")).hexdigest()


def atomic_write(path, content, mode):
    descriptor, temporary = tempfile.mkstemp(prefix=".jsonp-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            if hasattr(os, "fchmod"):
                os.fchmod(output.fileno(), mode)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def update(source, destination, validate, reload):
    originals = {}
    replacements = {}
    # 检查全部文件之后才开始写入；遇到未知的人工修改时停止，不盲目覆盖。
    for name, expected in FILES.items():
        target = destination / name
        info = target.lstat()
        if not stat.S_ISREG(info.st_mode):
            raise RuntimeError("Expected regular proxy files; no update performed.")
        before = target.read_bytes()
        after = (source / name).read_bytes()
        if digest(before) not in (expected, digest(after)):
            raise RuntimeError("Existing proxy differs from the reviewed baseline; preserved it.")
        originals[name] = (before, stat.S_IMODE(info.st_mode))
        replacements[name] = after
    validate()
    if all(digest(originals[name][0]) == digest(replacements[name]) for name in FILES):
        return None
    backup = Path(tempfile.mkdtemp(prefix=".foodmap-before-jsonp-", dir=destination.parent))
    backup.chmod(0o700)
    for name, (content, _) in originals.items():
        atomic_write(backup / name, content, 0o600)
    try:
        for name, content in replacements.items():
            atomic_write(destination / name, content, originals[name][1])
        validate()
        reload()
    except Exception:
        for name, (content, mode) in originals.items():
            atomic_write(destination / name, content, mode)
        # 若reload已部分成功，恢复旧文件后再校验并reload，避免磁盘与运行配置分离。
        try:
            validate()
            reload()
        except Exception:
            raise RuntimeError("Update failed; original files restored. Nginx requires manual inspection. Backup: " + str(backup)) from None
        raise RuntimeError("Update failed; original files and running configuration restored. Backup: " + str(backup)) from None
    return backup


def main():
    if os.name != "posix" or os.geteuid() != 0:
        raise RuntimeError("Run on Ubuntu using sudo python3 update-jsonp.py.")
    destination = Path("/etc/nginx/foodmap")
    directory = destination.lstat()
    if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != 0:
        raise RuntimeError("Expected root-owned Nginx proxy directory.")
    for name in FILES:
        if (destination / name).lstat().st_uid != 0:
            raise RuntimeError("Expected root-owned proxy files.")
    if not shutil.which("nginx"):
        raise RuntimeError("Nginx is not installed.")

    def run(arguments):
        # nginx校验失败可能引用私有配置；仅输出固定错误，不打印捕获的正文。
        result = subprocess.run(arguments, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Nginx validation or reload failed; no credentials printed.")

    backup = update(Path(__file__).resolve().parent, destination,
                    lambda: run(["nginx", "-t"]),
                    lambda: run(["systemctl", "reload", "nginx"]))
    if backup:
        print("JSONP proxy update validated and Nginx reloaded. Backup: " + str(backup))
    else:
        print("JSONP proxy files already current; syntax validation passed. No reload needed.")
    print("Credentials, site, backend and database preserved; nosniff remains enabled.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        if isinstance(error, RuntimeError):
            raise SystemExit(str(error)) from None
        raise SystemExit("Proxy update stopped; inspect the server state. No credentials printed.") from None
