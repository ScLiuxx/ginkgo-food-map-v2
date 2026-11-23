# 后端私有配置首次准备

前置为[运行账号与目录](../../docs/operations/deployment.md#首次创建运行账号与目录)已建立，以及服务器MySQL／Redis和ES空索引已就绪。用户已选择沿用本地SMTP；COS沿用此前已验证上传的目标桶。JWT和验证码HMAC在服务器重新生成，不复用开发环境密钥。

## 传输与执行

服务器SSH终端先建立私有传输目录：

```bash
install -d -m 700 ~/foodmap-setup
```

本轮已在仓库外`D:\eat\.codex-secrets\foodmap-production-20261119\input.json`整理实际SMTP和COS参数，不输出值或提交Git。在Windows PowerShell通过SCP传输脚本和私有参数：

```powershell
scp D:\eat\foodmap\deploy\application\prepare.py D:\eat\.codex-secrets\foodmap-production-20261119\input.json ubuntu@43.139.87.34:~/foodmap-setup/
```

服务器SSH终端执行：

```bash
chmod 600 ~/foodmap-setup/input.json
sudo python3 ~/foodmap-setup/prepare.py --settings /home/ubuntu/foodmap-setup/input.json
sudo ls -l /etc/foodmap
```

成功后移除传输副本，保留正式配置和本机私有来源：

```bash
rm -- ~/foodmap-setup/input.json
```

不在聊天或日志中显示私有JSON、`backend.env`及密钥正文。目录及文件权限输出可用于核对，不能代替后续应用启动、真实邮件和图片验收。

2026-11-19用户已回传初始化成功：`backend.env`为root:root、0600，两个JWT文件为root:foodmap、0640，服务器临时输入副本已删除。实际应用读取、邮件发送和COS访问尚未验收。

## 安装后端服务与有限发布权限

首次安装前，Windows PowerShell上传仓库模板；不传输私有配置：

```powershell
scp D:\eat\foodmap\deploy\foodmap-backend.service D:\eat\foodmap\deploy\foodmap-deploy.sudoers ubuntu@43.139.87.34:~/foodmap-setup/
```

服务器SSH终端逐条执行，任一步报错则停止；已有同名配置时先核对，不覆盖其他部署：

```bash
sudo systemd-analyze verify ~/foodmap-setup/foodmap-backend.service
sudo visudo -cf ~/foodmap-setup/foodmap-deploy.sudoers
sudo install -o root -g root -m 644 ~/foodmap-setup/foodmap-backend.service /etc/systemd/system/foodmap-backend.service
sudo install -o root -g root -m 440 ~/foodmap-setup/foodmap-deploy.sudoers /etc/sudoers.d/foodmap-deploy
sudo systemctl daemon-reload
systemctl show foodmap-backend.service -p LoadState -p ActiveState -p User -p Group
sudo -u foodmap-deploy sudo -n -l
```

预期服务`LoadState=loaded`、`ActiveState=inactive`，User和Group均为`foodmap`。权限列表只新增以root执行该项目后端`restart`和`stop`两项命令；不实际执行它们验证，不使用`sudo -l`以外的命令启动服务。

安装到`sudoers.d`的文件名为`foodmap-deploy`，不带点号后缀，避免被sudo忽略。此阶段没有发布产物或`current`链接，只加载单元，不启动服务；首次发布成功后再执行`sudo systemctl enable foodmap-backend.service`设置开机启动。Nginx、高德生产代理、前端构建参数、Runner和首次发布仍按[部署计划](../../docs/plans/cicd-improvements.md)继续配置。

2026-11-19用户回传`LoadState=loaded`、`ActiveState=inactive`，User／Group均为`foodmap`；发布账号的sudo列表仅含上述两项命令，服务安装和权限范围核对通过。下一步见[Nginx与生产地图代理](../nginx/README.md)，实际后端启动仍待首次发布。

## 输入与行为边界

- 参数JSON含`mail`（host、整数port、username、password、properties）、`mailFrom`和`cos`（region、bucket、secret-id、secret-key）。从被忽略的本地配置中仅抽取这些字段，不搬运数据库、JWT、开发开关或演示数据。原SMTP属性通过Spring Boot原生`SPRING_APPLICATION_JSON`保留，含TLS及超时；环境文件按systemd规则引用，不能用shell `source`执行。
- 数据库与Redis连接直接读取服务器`/opt/foodmap-infra/backend-database.env`，不使用本地开发数据库密码。生成的环境文件只允许root读取；systemd由root读取后注入后端进程。
- RSA3072私钥使用PKCS#8，公钥使用X.509格式；两者root:foodmap、0640，配置目录root:foodmap、0750。初始化拒绝已有文件或符号链接，不轮换已发布密钥，不变更账号、目录权限或systemd配置。
- 同文件系统内暂存，生成、校验后以不覆盖的硬链接逐个安装，`backend.env`最后发布。若中途异常，已安装的新文件会保留并阻止再次初始化，先检查，不强制删除重跑；此步骤不声称三个文件具有跨文件原子提交。
- 后端仅监听127.0.0.1:8080，启用COS并停用OSS；启用ES连接和同步，首次查询仍使用MySQL，待实际分词和同步核对后再切换ES查询。使用统计首次保持关闭，首次迁移及运行前置核对后再单独启用。
- 不安装或启动systemd服务，不发邮件，不请求COS，不连接数据库／Redis／ES。实际配置读取与连接、MySQL8.4业务兼容、验证码发送、图片读写及完整发布仍待验收。
