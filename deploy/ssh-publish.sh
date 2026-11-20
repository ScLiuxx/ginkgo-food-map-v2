#!/usr/bin/env bash
# GitHub托管机器上传已检查产物；主机公钥必须通过可信的服务器终端取得。
set -euo pipefail
umask 077

fail() {
    echo "$1" >&2
    exit 1
}

[[ "${DEPLOY_HOST:-}" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || fail 'Invalid DEPLOY_HOST'
[[ "${DEPLOY_USER:-}" == foodmap-deploy ]] || fail 'DEPLOY_USER must be foodmap-deploy'
deploy_port="${DEPLOY_PORT:-22}"
[[ "$deploy_port" =~ ^[0-9]{1,5}$ ]] || fail 'Invalid DEPLOY_PORT'
(( 10#$deploy_port >= 1 && 10#$deploy_port <= 65535 )) || fail 'Invalid DEPLOY_PORT'
deploy_port="$((10#$deploy_port))"
[[ -n "${DEPLOY_SSH_KEY:-}" && -n "${DEPLOY_KNOWN_HOSTS:-}" ]] || fail 'Missing deployment SSH credentials'
release_id="${GITHUB_SHA:-}-${GITHUB_RUN_ID:-}-${GITHUB_RUN_ATTEMPT:-}"
[[ "$release_id" =~ ^[a-f0-9]{40}-[0-9]+-[0-9]+$ ]] || fail 'Invalid release ID'
for artifact in release.tar.gz SHA256SUMS deploy/deploy.sh deploy/healthcheck.py; do
    [[ -f "$artifact" ]] || fail 'Missing release artifact or deployment script'
done
sha256sum --check --status SHA256SUMS

ssh_directory="$(mktemp -d)"
trap 'rm -rf -- "$ssh_directory"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s\n' "$DEPLOY_SSH_KEY" > "$ssh_directory/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" > "$ssh_directory/known_hosts"
chmod 600 "$ssh_directory/key" "$ssh_directory/known_hosts"
unset DEPLOY_SSH_KEY DEPLOY_KNOWN_HOSTS
known_host="$DEPLOY_HOST"
if [[ "$deploy_port" != 22 ]]; then
    known_host="[$DEPLOY_HOST]:$deploy_port"
fi
ssh-keygen -F "$known_host" -f "$ssh_directory/known_hosts" > /dev/null || fail 'Pinned host key does not match deployment address'

# 不读取Runner账号的默认SSH配置或信任库，不接受未知／变更的主机公钥。
options=(-F /dev/null -i "$ssh_directory/key"
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
    -o "UserKnownHostsFile=$ssh_directory/known_hosts" -o GlobalKnownHostsFile=/dev/null
    -o ConnectTimeout=15 -o ConnectionAttempts=1
    -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
target="$DEPLOY_USER@$DEPLOY_HOST"
incoming="/opt/foodmap/incoming/$release_id"
ssh "${options[@]}" -p "$deploy_port" "$target" "umask 077; mkdir '$incoming'" < /dev/null
scp "${options[@]}" -P "$deploy_port" release.tar.gz SHA256SUMS deploy/deploy.sh deploy/healthcheck.py "$target:$incoming/"
# 仅在所有文件上传成功后激活；远端沿用锁、校验、健康检查和回滚。
ssh "${options[@]}" -p "$deploy_port" "$target" "bash '$incoming/deploy.sh' '$release_id'" < /dev/null
