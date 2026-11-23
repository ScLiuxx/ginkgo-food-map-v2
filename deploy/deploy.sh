#!/usr/bin/env bash
# 服务器发布入口：只激活已经通过 CI 的产物，不拉取源码、不构建、不修改数据库基础设施。
set -Eeuo pipefail
umask 022

release_id="${1:?Usage: deploy.sh COMMIT-RUN_ID-ATTEMPT}"
[[ "$release_id" =~ ^[a-f0-9]{40}-[0-9]+-[0-9]+$ ]] || { echo 'Invalid release ID' >&2; exit 1; }
root="${FOODMAP_DEPLOY_ROOT:-/opt/foodmap}"
[[ "$root" = /* && "$root" != / && ! -L "$root" ]] || { echo 'Invalid deployment root' >&2; exit 1; }
root="$(realpath -e "$root")"
incoming="$root/incoming/$release_id"
release="$root/releases/$release_id"
commit="${release_id:0:40}"
[[ -d "$incoming" && ! -L "$incoming" && -d "$root/releases" && ! -L "$root/releases" ]]
[[ ! -e "$release" && ! -L "$release" ]] || { echo 'Release already exists; rerun with a new attempt' >&2; exit 1; }

# 即使多个发布来源同时运行，也只能有一个进程切换当前版本。
exec 9>"$root/deploy.lock"
flock -w 600 9
cd "$incoming"
sha256sum --check --status SHA256SUMS

# 校验归档路径和条目类型，拒绝目录穿越、软链接、设备文件及意外配置文件。
python3 - "$incoming/release.tar.gz" "$commit" <<'PY'
import pathlib, sys, tarfile
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    members = archive.getmembers()
    for member in members:
        path = pathlib.PurePosixPath(member.name)
        allowed = member.name in ('backend.jar', 'commit.txt', 'frontend') or member.name.startswith('frontend/')
        if not allowed or path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
            raise SystemExit('Unexpected release archive member')
    if archive.extractfile('commit.txt').read().decode().strip() != sys.argv[2]:
        raise SystemExit('Commit does not match release ID')
    archive.getmember('backend.jar')
    archive.getmember('frontend/index.html')
PY

mkdir "$release"
tar --extract --gzip --file release.tar.gz --directory "$release" --no-same-owner --no-same-permissions
chmod -R a+rX "$release"
previous=''
if [[ -L "$root/current" ]]; then
  previous="$(readlink -f "$root/current")"
  [[ "$previous" == "$root/releases/"* && -f "$previous/backend.jar" ]] || { echo 'Invalid previous release' >&2; exit 1; }
elif [[ -e "$root/current" ]]; then
  echo 'Current release must be a symlink' >&2
  exit 1
fi

activate() {
  ln -s "$1" "$root/.current-$release_id"
  mv -Tf "$root/.current-$release_id" "$root/current"
}

healthy() {
  # 小规格服务器冷启动及Flyway升级可能超过原先约33秒的重试窗口；
  # 最多检查40次，间隔3秒，给应用约两分钟启动，同时保留超时失败与回滚。
  python3 "$incoming/healthcheck.py" --commit "$1" --attempts 40 --delay 3 --timeout 15
}

# 切换之后失败时尝试恢复上一版程序；Flyway 已执行的数据库迁移不会自动撤销。
rollback() {
  local failure=$?
  trap - ERR
  echo 'Deployment failed; attempting application rollback' >&2
  if [[ -n "$previous" ]]; then
    activate "$previous"
    if sudo -n systemctl restart foodmap-backend.service && healthy "$(cat "$previous/commit.txt")"; then
      echo 'Previous application release restored' >&2
    else
      echo 'Rollback needs operator attention' >&2
    fi
  else
    # 首次发布没有上一版，停止失败服务并移除本次刚建立的链接，保留产物供排查。
    sudo -n systemctl stop foodmap-backend.service || true
    if [[ -L "$root/current" && "$(readlink -f "$root/current")" == "$release" ]]; then
      unlink "$root/current"
    fi
    echo 'No previous release exists' >&2
  fi
  exit "$failure"
}

trap rollback ERR
activate "$release"
sudo -n systemctl restart foodmap-backend.service
healthy "$commit"
trap - ERR
printf 'Deployed commit %s\n' "$commit"
