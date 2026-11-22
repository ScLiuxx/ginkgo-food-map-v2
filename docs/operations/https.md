# 域名与 HTTPS

域名为`nidianfoodmap.cn`和`www.nidianfoodmap.cn`，两条A记录均指向`43.139.87.34`。腾讯云安全组需要放行TCP443；原80及SSH规则保留。

## 当前状态

2026-11-21已检查Nginx证书包：两个域名均在证书SAN中，私钥匹配，证书链包含3张证书，有效期至2027-02-18 10:59:59 UTC（北京时间18:59:59）。证书和私钥不进入仓库或应用发布包。

用户已执行安装脚本，Nginx配置校验及reload成功，备份为`/etc/nginx/.foodmap-before-https-6y_cjm_c`。随后只读核对正式站点指纹匹配、Nginx与后端active、暂存私钥已删除；两个域名在服务器正式443端口均通过系统信任链和主机名校验，版本入口200。此前临时端口的5项验证也已通过，临时Nginx已退出。

首次公网检查发现443超时，截图中的安全组仅放行全部IPv6，已指导补充`0.0.0.0/0`的TCP443入站规则；修正规则后的公网访问尚未独立复验，不能将回环验证当作公网验收。

## 安装

[安装脚本](../../deploy/nginx/enable-https.py)与经过审阅的`https-site.conf`、`manifest.json`、`fullchain.pem`、`privkey.pem`放在服务器同一受限目录。本轮目录为`/opt/foodmap/https-setup-20261121`，权限0700，私钥0600。manifest固定现有／候选站点及证书包的SHA256；不接受未知的人工修改或覆盖已有证书目录。

站点指纹必须按服务器文件的原始字节计算，不能先以文本读取并转换CRLF／LF。本轮首次执行因准备端换行转换导致指纹不符而在写入前停止；已逐字确认内容一致后修正暂存manifest，未放宽安装器校验。

以ubuntu在服务器直接执行一条命令：

```bash
sudo python3 /opt/foodmap/https-setup-20261121/enable-https.py
```

脚本检查证书域名、有效期、信任链、公私钥匹配及原站点，再保存root专属`/etc/nginx/.foodmap-before-https-*`备份。证书安装到`/etc/nginx/foodmap/tls/nidianfoodmap.cn/`，私钥保持root的0600；站点写入后先`nginx -t`再reload，并以本机回环及真实域名SNI验证HTTPS版本入口。失败恢复原站点并重新校验／重载；诊断只保存到备份目录，不打印私钥或高德凭据。成功后删除暂存私钥。重复执行已安装的相同配置只校验，不重复切换。

部署账号无修改root配置或reload Nginx的权限，须由ubuntu通过sudo安装；不增加部署账号权限。应用CI/CD继续只发布应用，无需重新构建前后端。

## 访问与恢复

- 两个域名均支持HTTPS，HTTP请求308跳到对应域名的HTTPS，保留路径及查询参数。
- IP与127.0.0.1的HTTP入口保留，现有部署健康检查不跟随域名跳转。IP不在证书SAN内，HTTPS使用域名访问。
- 沿用原有前端目录、API及高德代理，只reload Nginx，不重启后端、修改数据库、JWT或云存储配置；未启用HSTS。
- IP、域名及HTTPS是不同浏览器来源，原IP入口的登录状态不会自动迁移，用户可能需要在新入口重新登录。高德控制台若限制来源域名，应加入上述两个域名。
- 安装后检查公网HTTPS证书、首页／版本与HTTP跳转；回环验证成功不等于安全组443已放行。若需手动回退，由管理员从脚本打印的备份目录恢复`site.conf`到`/etc/nginx/sites-available/foodmap`，执行`sudo nginx -t`成功后再`sudo systemctl reload nginx`。

## 续期

当前使用手动下载的证书，尚无自动续期。到期前申请／续签并下载新的Nginx证书包，重新核对域名、有效期、公私钥及信任链，备份后更新证书并校验／reload。首次安装器会拒绝覆盖现有证书目录，不能直接拿新证书重复运行；续期需另行准备替换操作。不能把本次安装当作已配置自动续期。
