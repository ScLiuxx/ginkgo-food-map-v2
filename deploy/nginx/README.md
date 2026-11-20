# 腾讯云Nginx与高德生产代理

前置为Nginx已安装且运行，项目systemd单元及有限sudo权限已验证；首次发布的产物尚未上传。此步骤让80端口承载静态前端、后端同源接口和高德安全代理，不启动后端。

## 安装、传输与启用

服务器实际为Ubuntu22.04，官方Ubuntu仓库未发布`libnginx-mod-http-js`；先前直接安装该包的命令不适用于此环境。用户回传`dpkg-query`确认包不存在，已启用模块也没有njs，当前仅保留默认站点。模块缺失发生在站点修改前，因此没有`validation.log`。

Nginx官方仓库提供Jammy对应的`nginx`与`nginx-module-njs`，两者有明确的版本依赖，应作为同一发行组合安装。先查看服务器当前候选，之后再安排官方源、原配置备份及安装事务预览，不能仅从另一Ubuntu发行版取一个模块：

```bash
apt-cache policy nginx nginx-core nginx-module-njs libnginx-mod-http-js
```

用户已回传APT候选：当前仅有腾讯云镜像的Ubuntu Jammy源，`nginx`／`nginx-core`已安装1.18.0，两个njs包均无候选。按[Nginx Ubuntu安装说明](https://nginx.org/en/linux_packages.html#Ubuntu)，先下载并查看官方签名公钥；以下均在服务器SSH终端执行，任一步失败停止：

```bash
sudo apt-get install -y curl gnupg ca-certificates
curl -fL --connect-timeout 10 --max-time 60 https://nginx.org/keys/nginx_signing.key -o ~/nginx-signing.key
gpg --show-keys --with-fingerprint ~/nginx-signing.key
```

输出应包含官方公布的完整指纹`573BFD6B3D8FBC641079A6ABABF5BD827BD9BF62`（可能按四字符分组，同时显示其他官方签名公钥）。核对一致后才安装公钥并登记仅用于Nginx的稳定源：

```bash
sudo install -m 644 ~/nginx-signing.key /usr/share/keyrings/foodmap-nginx-signing.asc
echo 'deb [signed-by=/usr/share/keyrings/foodmap-nginx-signing.asc] https://nginx.org/packages/ubuntu jammy nginx' | sudo tee /etc/apt/sources.list.d/foodmap-nginx.list
sudo apt-get update
apt-cache policy nginx nginx-module-njs
sudo apt-get -s install nginx nginx-module-njs
```

最后一条`-s`只预览安装事务，不安装Nginx。用户已回传预览：升级至官方`nginx 1.30.7-1~jammy`、新增配套`nginx-module-njs 1.30.7+1.0.2-1~jammy`，移除Ubuntu的`nginx-common`、`nginx-core`与6个旧模块，不变更数据库或搜索容器。此结果符合预期，实际安装及加载仍待执行。

本机已按包索引SHA-256核对这两个官方deb并只读检查内容：njs安装后仅提示手动`load_module`，不会自动加载；官方主配置仅引入`conf.d`，不会自动读取Ubuntu的`modules-enabled`／`sites-enabled`。因此以下步骤先备份整个原配置，下载完成才停止默认站点、移走旧模块配置；安装后显式恢复原主配置及站点目录，停用新增的官方默认站点，只加载HTTP njs。仅适用于当前已核对的全新服务器默认站点，不能直接用于已有业务站点的升级。

完整复制到服务器SSH终端；脚本在首次错误时停止，备份路径会先输出。失败不自动回滚软件包，保留输出及备份以供诊断，不继续运行后面的项目初始化：

```bash
sudo bash <<'BASH'
set -euo pipefail
grep -qF 'include /etc/nginx/modules-enabled/*.conf;' /etc/nginx/nginx.conf
grep -qF 'include /etc/nginx/sites-enabled/*;' /etc/nginx/nginx.conf
backup_dir=$(mktemp -d /root/foodmap-nginx-upgrade.XXXXXX)
cp -a /etc/nginx "$backup_dir/nginx"
printf 'Backup: %s\n' "$backup_dir"
packages=(nginx=1.30.7-1~jammy nginx-module-njs=1.30.7+1.0.2-1~jammy)
apt-get --download-only -y install "${packages[@]}"
systemctl stop nginx
mv /etc/nginx/modules-enabled "$backup_dir/disabled-modules"
install -d -m 755 /etc/nginx/modules-enabled
DEBIAN_FRONTEND=noninteractive apt-get -y -o Dpkg::Options::=--force-confold install "${packages[@]}"
install -m 644 "$backup_dir/nginx/nginx.conf" /etc/nginx/nginx.conf
cp -a "$backup_dir/nginx/sites-available" "$backup_dir/nginx/sites-enabled" /etc/nginx/
if [ -f /etc/nginx/conf.d/default.conf ]; then
    mv /etc/nginx/conf.d/default.conf "$backup_dir/official-default.conf"
fi
install -d -m 755 /etc/nginx/modules-enabled
printf '%s\n' 'load_module /usr/lib/nginx/modules/ngx_http_js_module.so;' > /etc/nginx/modules-enabled/50-foodmap-http-js.conf
chmod 644 /etc/nginx/modules-enabled/50-foodmap-http-js.conf
nginx -t
systemctl daemon-reload
systemctl restart nginx
systemctl enable nginx
nginx -v
dpkg-query -W nginx nginx-module-njs
systemctl is-active nginx
ls -l /etc/nginx/modules-enabled/
BASH
```

成功应显示1.30.7及匹配的njs包、`nginx -t`通过、服务`active`和唯一的`50-foodmap-http-js.conf`。用户已回传1.30.7及配套njs包版本、配置校验成功和服务active；模块目录输出仅回传`total 4`，不将其扩大为完整文件清单已回传。下一步由项目初始化再次检查njs登记并通过完整配置校验验证模块能力；项目站点和真实高德请求尚未验收。

Ubuntu包供应依据[Launchpad发布索引](https://api.launchpad.net/1.0/ubuntu/+archive/primary)，官方组合依据[Jammy包索引](https://nginx.org/packages/ubuntu/dists/jammy/nginx/binary-amd64/Packages)。下列站点安装步骤须在兼容模块实际安装并启用之后执行。

本轮已在仓库外准备新版公开传输目录`D:\eat\.codex-previews\nginx-server-20261119\foodmap-nginx-setup-v2\`，保留`nginx.conf`与`nginx/`相对目录；不含凭据。使用新目录名避免覆盖旧传输目录时产生嵌套，旧包仍保留。高德参数单独保存在受限ACL目录的`D:\eat\.codex-secrets\foodmap-production-20261119\amap-input.json`，服务器0600副本尚未删除，此步沿用现有副本，不重新上传。

Windows PowerShell传输：

```powershell
scp -r D:\eat\.codex-previews\nginx-server-20261119\foodmap-nginx-setup-v2 ubuntu@43.139.87.34:~/
```

传输成功后在服务器SSH终端执行整段，首次错误停止；不输出私有输入内容：

```bash
sudo bash <<'BASH'
set -euo pipefail
chmod 600 /home/ubuntu/foodmap-setup/amap-input.json
python3 /home/ubuntu/foodmap-nginx-setup-v2/nginx/prepare.py --settings /home/ubuntu/foodmap-setup/amap-input.json
nginx -t
systemctl reload nginx
systemctl is-active nginx
curl --max-time 10 -i http://127.0.0.1/
BASH
```

首次发布前预期返回`503`及`Application deployment pending`。这表示网站入口已切换，应用产物尚未发布；不能据此认定前后端或高德链路已验收。成功后删除服务器临时参数副本：

```bash
rm -- ~/foodmap-setup/amap-input.json
```

用户已回传初始化和完整配置检查成功、服务active，原默认站点备份为`/etc/nginx/.foodmap-before-ipx87vu4`。紧接reload的首个请求仍返回旧欢迎页，稍后独立请求已返回预期503及等待发布正文，确认新站点生效；不能将reload命令成功与立即切换混为一谈。临时参数删除步骤已给出，尚未回传执行结果。

## 变更与失败恢复边界

- 初始化只用于全新服务器，拒绝既有项目配置、同名符号链接或除Ubuntu默认站点之外的已启用站点。原默认站点文件不删除，停用其启用链接前保存原内容和链接目标到root专属`/etc/nginx/.foodmap-before-*/`。
- 项目配置安装到`/etc/nginx/sites-available/foodmap`，启用链接为`sites-enabled/foodmap`；代理脚本和公用配置位于`/etc/nginx/foodmap/`，凭据配置仅root以0600读取。真实JS Key和安全密钥只在私有文件中提供，不进入源码。
- 在安装前后均执行`nginx -t`，后者失败时撤回本次启用链接、恢复默认站点链接，并将未启用的新配置保存在上述私有备份目录。诊断仅保存到root专属`validation.log`，不自动打印可能含凭据的错误正文。脚本不reload；既有Nginx进程继续使用旧配置，由管理员校验成功后显式reload。
- `nginx -t`会解析两个固定高德上游的DNS，不发送高德API请求。不要用`nginx -T`输出完整配置，它会展开包含安全密钥的文件。失败时先检查root专属备份，不把完整私有配置或带参数的转发URL发到聊天。

## 高德代理规则与构建

请求协议集中见[代理接口](../../docs/api/system.md#amap-proxy)，与开发代理具有相同业务范围：地图初始化／样式、精确坐标转换和餐饮关键词搜索，固定两个高德上游、服务器Key及安全密钥。生产实现由njs解析参数，命名location处理实际转发；不用任意URL代理或额外应用服务。

- 上游TLS验证使用Ubuntu CA；不向高德转发本站Cookie、Authorization、请求体或其他客户端请求头。
- 禁止跟随上游重定向，连接超时3秒、发送／读取等待各8秒，不自动重试。代理路径不记录访问和错误日志，避免上游URL中的安全密钥和转换坐标泄露；本轮未新增独立监控或限流。
- [流水线](../../.github/workflows/ci-cd.yml)通过仓库变量`VITE_AMAP_KEY`向前端构建注入浏览器JS Key，代理路径固定`/_AMapService`；自动发布已启用时，develop构建会先校验JS Key格式。该仓库变量仍待后续在GitHub配置，必须与服务器代理的JS Key一致，本机已单独准备仅含JS Key的私有`amap-build-key.txt`供后续复制；安全密钥禁止放入VITE变量、GitHub前端构建或浏览器。
- 高德控制台生产域名／配额和真实底图、样式、坐标转换、地点搜索仍须上线核对，按[环境说明](../../docs/operations/amap-setup.md)验收。当前仅HTTP/IP入口，浏览器本人定位仍需后续HTTPS。

## 本轮验证

当前修复后通过统一入口完成17项部署检查，7项Linux语义用例在Windows跳过；包括5项假密钥高德规则检查和3项模块前置／错误脱敏检查，不访问高德。此前曾因可提交内存1823MiB不足而在启动前退出，用户腾出内存后继续；本次修复检查的余量为3405MiB，未修改门槛或停止开发服务。Python／JS／YAML静态检查及文档检查通过。用户现已回传服务器配套Nginx／njs安装、完整项目配置检查、reload及503等待页成功；真实地图、地点搜索等请求仍待首次应用发布后验收，Node中的规则检查不能代替真实高德链路验收。

针对模块缺失，初始化脚本现会在读取私有参数或修改站点前输出明确的固定错误，配置值和子进程正文仍不回显。已补充模块缺失无写入、不同发行的模块登记名称及私有错误脱敏检查；完整项目配置已由服务器初始化检查通过。
