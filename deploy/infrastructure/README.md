# 腾讯云MySQL与Redis首次配置

供全新Ubuntu服务器使用。Java和Nginx运行在宿主机，本目录不使用开发Compose，也不安装ES；ES和匹配IK另行配置。

## 传输与启动

服务器需先安装Docker／Compose并下载`mysql:8.4`、`redis:8-alpine`。Windows PowerShell中执行：

```powershell
scp -r D:\eat\foodmap\deploy\infrastructure ubuntu@43.139.87.34:~/
```

回到服务器SSH终端：

```bash
sudo python3 ~/infrastructure/prepare.py
```

看到`Infrastructure configuration ready`后启动：

```bash
sudo docker compose -f /opt/foodmap-infra/compose.yaml up -d --wait --wait-timeout 180
sudo docker compose -f /opt/foodmap-infra/compose.yaml ps
```

这一步创建空的`foodmap`数据库和专用账号，业务表由后续应用首次启动时的Flyway建立。用户已回传服务器MySQL和Redis均为healthy，端口分别绑定127.0.0.1:3306、127.0.0.1:6379；该结果不代替后续应用连接、备份恢复或MySQL8.4完整兼容验收。

## 进入MySQL

客户端随MySQL镜像位于容器中，宿主机直接执行`mysql`可能提示未安装。服务器SSH终端执行下列命令，用已有业务账号进入，不显示密码，也无需安装宿主机8.0客户端：

```bash
sudo docker exec -it foodmap-mysql sh -c '
export MYSQL_PWD="$(cat /run/secrets/mysql_password)"
exec mysql --protocol=TCP -h127.0.0.1 -ufoodmap -Dfoodmap
'
```

出现`mysql>`后可只读核对，再退出：

```sql
SELECT VERSION(), DATABASE(), CURRENT_USER();
SHOW TABLES;
exit;
```

应用尚未首次启动时，`foodmap`业务库表列表为空是预期状态。

## 配置边界

- `prepare.py`仅限sudo执行，自动生成三个不同的随机密码，不输出密码；原子安装配置到`/opt/foodmap-infra`，目录0700、凭证0600。若目录、同名容器或目标数据卷已存在则拒绝初始化，保留原数据；重试启动直接运行Compose，不删除目录重新生成密码。
- MySQL使用官方8.4镜像、256MiB缓冲池和最多50连接，容器内存上限1536MiB；默认时区明确为`+08:00`，应用连接口径为`Asia/Shanghai`。业务账号`foodmap`仅拥有同名数据库权限，不用root运行应用；数据库首次启动初始化与版本升级须分别验收。
- Redis使用8-alpine镜像、密码认证、AOF／everysec、256MiB数据上限和`noeviction`，容器内存上限768MiB，为运行与AOF重写留出空间。不能自动淘汰登录、验证码或日活去重数据；内存满时写入会失败，需监控并按容量处置。AOF不代替独立备份，也不保证断电前最后一秒数据不丢。
- 两个端口分别只绑定`127.0.0.1:3306`、`127.0.0.1:6379`；腾讯云安全组无需对公网开放它们。容器日志各保留最多3份10MiB，数据分别保存于`foodmap-infra_mysql_data`、`foodmap-infra_redis_data`命名卷，与应用发布独立。
- Compose使用`pull_policy: never`，运行已下载的镜像；更新镜像需另行备份和受控拉取。本轮用户回传镜像摘要分别为MySQL `sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902`、Redis `sha256:7958cfbb9d152f87dadfbc89f1b0b15938f0b81ff8982feb327476f4911935c3`，这里未将浮动标签误称为永久固定版本。
- MySQL健康检查使用业务账号实际执行`SELECT 1`，Redis健康检查带密码执行`PING`，都不把密码作为命令行参数。`ps`只输出服务状态，不要回传凭证文件、Redis配置全文或完整容器环境。
- `/opt/foodmap-infra/backend-database.env`保存后续systemd应用所需连接设置；它仅为数据库部分，尚不能直接作为完整应用环境文件。后续由管理员合入`/etc/foodmap/backend.env`，补齐COS、JWT、SMTP及其他配置，Runner不接触这些凭证。

Redis统计故障边界见[统计运行约定](../../docs/architecture/activity-statistics.md)，整体顺序见[CI/CD计划](../../docs/plans/cicd-improvements.md)。不得将真实服务器部署当作自动化测试，自动化测试继续使用临时隔离资源。
