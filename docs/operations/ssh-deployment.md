# GitHub通过SSH发布到腾讯云

本页为2026-11-20起的现行发布配置。用户选择GitHub托管机器完成检查、打包和SSH上传，服务器不再安装或注册自托管Runner。沿用`production`环境、develop检查通过后自动发布、首次就绪变量、服务账号与现有健康检查／回滚脚本；专用密钥真实SSH登录及既有权限已验证，用户反馈5项生产环境Secrets已填写，实际读取和发布仍待Actions验收。

## 发布链路

PR和develop推送运行前后端及部署检查。只有本仓库develop推送、检查全部通过且仓库变量`AUTO_DEPLOY_ENABLED=true`才读取生产部署Secrets。托管机器确认当前提交未被新的develop提交替代，下载同一轮已检查产物、生成版本包及SHA-256，通过SSH创建独立待发布目录，将包按4MiB分片、最多8路SCP并行上传；全部分片完成后上传校验文件及两个脚本。远端拼接并校验原包，通过后删除分片并执行`deploy.sh`。任意上传失败不激活；远端仍使用发布锁、归档校验、健康检查及程序失败回滚，Flyway迁移不自动回滚。

服务器无需连接GitHub、编译项目或运行应用测试，SSH使用现有`foodmap-deploy`账号及专用Ed25519密钥。源码不包含真实密钥；GitHub临时私钥文件0600、主机公钥强校验，步骤成功或失败均清理临时文件，不使用`StrictHostKeyChecking=no`或在线扫描结果作为首次信任依据。

## 当前服务器与密钥准备

目标为`43.139.87.34:22`，Ubuntu22.04.5，后端和Nginx配置已准备，入口返回503等待首次发布。用户确认腾讯云安全组TCP22允许全部IPv4来源；未修改安全组、宿主机防火墙或全局SSH设置，真实GitHub来源连通性仍须首次Actions验证。普通GitHub托管机器出口IP会变化，后续收紧来源时需另选固定出口或维护相应策略。

已在本机仓库外`D:\eat\.codex-secrets\foodmap-ssh-deploy-20261120\foodmap-deploy-ed25519`生成无口令部署私钥，目录ACL仅当前用户、SYSTEM和Administrators。公钥传输副本为`D:\eat\.codex-previews\ssh-deploy-20261120\foodmap-deploy.pub`。仅上传公钥到服务器，私钥留待复制到GitHub生产环境Secret；不发送私钥到聊天。未创建、解锁或设置部署账号密码。

电脑PowerShell执行：

```powershell
scp D:\eat\.codex-previews\ssh-deploy-20261120\foodmap-deploy.pub ubuntu@43.139.87.34:~/
```

上传成功后在服务器SSH终端整段执行。保留原authorized_keys并避免重复添加；`restrict`禁用该密钥的端口转发、代理转发和PTY，允许所需命令及SFTP。账号现有home为`/opt/foodmap-runner`，目录名继续保留为SSH家目录，不意味着运行Runner：

```bash
sudo bash <<'BASH'
set -euo pipefail
key_file=/home/ubuntu/foodmap-deploy.pub
ssh-keygen -lf "$key_file"
test "$(getent passwd foodmap-deploy | cut -d: -f6)" = /opt/foodmap-runner
test ! -L /opt/foodmap-runner/.ssh
install -d -o foodmap-deploy -g foodmap-deploy -m 700 /opt/foodmap-runner/.ssh
authorized=/opt/foodmap-runner/.ssh/authorized_keys
test ! -L "$authorized"
touch "$authorized"
if ! grep -qF "$(cat "$key_file")" "$authorized"; then
    printf '\nrestrict %s\n' "$(cat "$key_file")" >> "$authorized"
fi
chown foodmap-deploy:foodmap-deploy "$authorized"
chmod 600 "$authorized"
/usr/sbin/sshd -t
/usr/sbin/sshd -T -C user=foodmap-deploy,host=github-actions,addr=1.1.1.1 | grep -E '^(port|pubkeyauthentication|passwordauthentication|kbdinteractiveauthentication|authenticationmethods|authorizedkeysfile|allowusers|denyusers) '
awk '{print "43.139.87.34 " $1 " " $2}' /etc/ssh/ssh_host_ed25519_key.pub
BASH
```

输出仅为公钥指纹、SSH有效配置及服务器公钥，可以回传。若AllowUsers／DenyUsers等限制该账号或要求附加认证，先核对再修正；不直接改全局SSH、不取消管理员现有登录方式。服务器公钥应由此次已有可信SSH连接取得，不能让CI首次自行`ssh-keyscan`后盲目信任。

用户已回传公钥认证启用、有效授权文件路径及服务器Ed25519公钥；密码认证为yes表示服务器允许密码方式，不代表部署账号已设置密码。已将该可信公钥保存到本机私有目录的`known_hosts`，使用新部署私钥以严格主机校验实际登录成功；只读检查确认用户名正确、仅预期两项有限sudo、发布目录可写且环境配置与JWT私钥不可读，没有启动／重启服务或操作业务数据。该结果仅验证当前电脑来源，GitHub托管机器来源与SCP实际上传仍待后续验证。

## GitHub配置

仓库Settings → Environments → 创建／打开`production`；部署分支仅允许develop，按已确认自动发布模式不设逐次人工审批。添加下列Environment secrets，全部使用本次腾讯云值，避免沿用旧服务器环境Secret：

| Secret | 内容 |
| --- | --- |
| `DEPLOY_HOST` | `43.139.87.34` |
| `DEPLOY_PORT` | `22` |
| `DEPLOY_USER` | `foodmap-deploy` |
| `DEPLOY_SSH_KEY` | 本次部署私钥完整内容，含BEGIN／END行 |
| `DEPLOY_KNOWN_HOSTS` | 从上述服务器终端取得的`43.139.87.34 ssh-ed25519 公钥内容`整行 |

本轮已将用户回传的整行可信主机公钥保存到下列文件，可在私钥Secret保存后，再复制并粘贴到`DEPLOY_KNOWN_HOSTS`：

```powershell
Get-Content -Raw D:\eat\.codex-secrets\foodmap-ssh-deploy-20261120\known_hosts | Set-Clipboard
```

在电脑PowerShell将私钥复制到剪贴板后直接粘贴到GitHub的Secret输入框，不在终端打印：

```powershell
Get-Content -Raw D:\eat\.codex-secrets\foodmap-ssh-deploy-20261120\foodmap-deploy-ed25519 | Set-Clipboard
```

若改非22端口，known_hosts行首必须相应改为`[43.139.87.34]:端口`。工作流未设置端口时默认22，用户名固定要求`foodmap-deploy`，不允许用root或ubuntu执行自动部署。现有两个后端systemctl有限sudo授权继续复用，不给通用sudo或Docker组。

用户已反馈`production`环境5项Secrets填写完成，并确认私钥保留完整BEGIN／END行；这是用户确认，不代表代理读取或验证了GitHubSecret值。

下一步在仓库Settings → Secrets and variables → Actions → Variables设置下列Repository variables，不能放在production的Environment secrets／variables：前端构建作业未绑定production环境，只有仓库级变量才能供其读取。

| Repository variable | 当前设置 |
| --- | --- |
| `VITE_AMAP_KEY` | 浏览器JS Key，使用下方命令复制 |
| `AUTO_DEPLOY_ENABLED` | 准备阶段先设为字符串`false`，完成核对并安排发布后改为`true` |

```powershell
(Get-Content -Raw D:\eat\.codex-secrets\foodmap-production-20261119\amap-build-key.txt).Trim() | Set-Clipboard
```

本机已只读核对文件去空白后为32位十六进制，且等于此前用于服务器初始化的高德输入中的JS Key，不打印值。不得把高德安全密钥放入前端变量；真实GitHub变量值、构建结果及服务器链路仍待验收。用户最初误将环境命名为DEPLOY_HOST，随后截图确认已创建production并填入5项Secret；又确认production的Deployment branches and tags已添加develop规则，以及仓库两项变量均已配置、开关false。以上依赖用户界面反馈，未由代理读取GitHub值。

## 验证与完成边界

电脑使用专用私钥与可信known_hosts的非交互登录、`sudo -n -l`及目录权限检查已通过，未启动尚无产物的后端。用户已确认5项GitHubSecrets、两项仓库变量及production的develop分支限制均配置完成，发布开关false。关闭本机代理后只读预检通过：SSH、SFTP列表、发布工具和可写目录正常，后端单元loaded／inactive且运行账号foodmap，Nginx active且返回503等待页，MySQL／Redis／ES回环端口可达，ES集群green，磁盘剩余约65GiB。未上传产物或访问业务凭据。

PR #4的最新检查通过后，用户进一步明确授权开启发布并合并；开关已设为true，合并提交为`43db856`，合并后的三项检查也通过。首次发布因DEPLOY_HOST格式不符在连接前退出；已用GitHub环境公钥加密重写5项已确认的部署参数，从本机已验证文件读取私钥／主机公钥并规范换行，不打印值，再仅重跑失败发布任务。随后实测单连接约30–43KiB/s、约84MiB包上传缓慢；8路HTTPS范围读取采样的总速率约206KiB/s，此值只是采样，不代表完整SCP发布速度。停止尚未激活的慢速任务，增加有界分片并行SCP修复，启动结果待最新Actions及服务器检查。没有导入本地数据库、Redis或演示数据，未放宽服务器防护规则。

服务器如仅下载／解压过Runner，文件可暂时保留，不启动`run.sh`或执行注册。当前没有收到Runner已注册／安装服务的证据，不自动删目录；若实际已有服务，先确认是否承担旧任务再停用。首次发布成功后由管理员启用`foodmap-backend.service`开机启动。

## 本机代理导致SSH连接提前关闭

GitHub环境设置完成后预检曾出现SSH／SFTP返回255：TCP22建立后、密钥认证前被关闭，本机读不到SSH服务banner。服务器SSH服务／本机握手正常，UFW未开启，INPUT默认ACCEPT；云镜防护链只含其他来源的拒绝规则且命中计数为0。用户重试时服务器抓包未记录新握手，不能据此删除云镜规则或重启SSH。

实际核对Windows路由发现目标43.139.87.34经Clash TUN虚拟网卡，路由为32.0.0.0/3、下一跳198.18.0.2。用户关闭代理后手动SSH恢复，代理随后实际完成只读SSH及SFTP列表预检，确认本轮异常由本机代理链路导致；服务器防火墙、SSH配置及部署密钥均未修改。

需要长期保留Clash时，可在规则模式的自定义规则前部添加目标直连规则，然后复测；这里只提供配置示例，没有修改本机代理：

```text
IP-CIDR,43.139.87.34/32,DIRECT,no-resolve
```

本机代理不参与GitHub托管机器的SSH连接，后者仍需真实Actions验收。诊断证据和关闭代理后的预检结果`preflight-after-proxy-disabled.json`位于仓库外`D:\eat\.codex-previews\ssh-deploy-20261120\`；发布开关保持false。
