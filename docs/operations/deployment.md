# 自动检查与发布

最后核对日期：2026-10-27。

## 当前状态

项目迁入独立仓库 [kanno111/ginkgo-food-map-v2](https://github.com/kanno111/ginkgo-food-map-v2)，默认分支为 `develop`。新历史从原 develop 的 V2 重构基线开始，随后保存登录、注册及 CI/CD 修改，不包含基线之前的旧系统提交。

服务器 `8.137.98.235` 使用自托管 GitHub Actions Runner 执行发布，前后端构建和测试仍在 GitHub 托管机器运行。流水线位于 [ci-cd.yml](../../.github/workflows/ci-cd.yml)，实际执行结果见[新仓库 Actions](https://github.com/kanno111/ginkgo-food-map-v2/actions)。

此前首轮检查全部通过，但 GitHub 托管 Runner 无法连接仅允许本机来源的 SSH，发布步骤以255退出。现改为服务器主动连接 GitHub、下载同一次检查的产物并在本地发布，自动发布不再依赖入站 SSH；原有 SSH 来源限制继续保留。

访问地址为 `http://8.137.98.235`。首次服务器主动发布已于2026-10-27成功，运行记录为 [Actions 40528088040](https://github.com/kanno111/ginkgo-food-map-v2/actions/runs/40528088040)，发布提交为 `a8df8ff62f578220d626682147fd7cf1b4f80069`。首页、登录页、注册页和公开 API 均返回200，匿名 `/auth/me` 返回预期401，后端健康状态为 UP。当前仅配置 HTTP，尚未配置域名和 HTTPS。

## 触发条件与执行流程

仅远程 `develop` 分支的 `push` 触发本工作流，包括合并其他分支后产生的推送。其他分支推送、Pull Request 和仅在本地提交不会触发。

1. 后端使用 Java 21 执行 Maven `verify`，启用真实 MySQL、Redis 集成测试。CI 使用临时 MySQL 8.4 和 Redis 8 服务，不访问生产数据库；测试报告即使失败也保留7天。
2. 前端使用 Node.js 24 和 `npm ci`，执行 ESLint、Vitest、TypeScript 类型检查及 Vite 生产构建。
3. 检查部署脚本语法，运行发布、失败回滚及 HTTP 健康检查测试。
4. 三项检查全部通过后，取本次运行生成的 JAR 和前端产物，打包并生成 SHA-256 校验文件，不在服务器重新构建源码。产物保留7天。
5. 发布开始前核对远程 `develop` 最新提交；若当前提交已经被新推送取代，跳过本次发布。发布任务串行执行，不中断正在切换版本的任务。
6. 服务器上的 `ginkgo-deploy` Runner 下载本次运行的产物，将发布包和脚本复制到本地待发布目录。发布脚本再次验证校验和、压缩包路径和提交号，然后原子切换当前版本链接并重启后端。
7. 同时验证后端健康、前端提交号、登录页面、公开 API 及匿名认证响应。失败时尝试恢复上一版程序，并将本次 Actions 标记为失败。

在 GitHub 仓库的 **Actions → CI and automatic deployment** 查看每个步骤及失败日志。检查失败时不会进入发布，线上已有版本继续运行。流水线核对的是发布开始时的最新提交；发布期间若又有推送，后续通过检查的版本会继续更新。

## 服务器部署结构

| 组件 | 配置与位置 |
| --- | --- |
| 系统 | Ubuntu 24.04，OpenJDK 21 |
| 访问入口 | 宿主机 Nginx，80端口，页面和 `/auth/`、`/api/` 同源 |
| 后端 | `ginkgo-backend.service`，用户 `ginkgo`，监听 `127.0.0.1:8080` |
| 发布账号 | `ginkgo-deploy`，运行自托管 Runner；sudo 仅允许重启、停止该后端服务 |
| 发布 Runner | `/opt/ginkgo-runner`，名称 `ginkgo-production`，标签 `self-hosted`、`Linux`、`X64`、`ginkgo-deploy` |
| 待发布目录 | `/opt/ginkgo/incoming/<提交号>-<运行编号>-<重试编号>/` |
| 版本目录 | `/opt/ginkgo/releases/<版本编号>/`，包含 JAR、前端及提交号 |
| 当前版本 | `/opt/ginkgo/current`，链接到一个版本目录 |
| 后端配置 | `/etc/ginkgo/backend.env`，仅管理员可读写，由 systemd 注入 |
| JWT 密钥 | `/etc/ginkgo/jwt-private.pem`、`jwt-public.pem`，应用组可读 |
| Nginx 配置 | `/etc/nginx/sites-available/ginkgo`，已链接到 `sites-enabled` |
| MySQL | 保留原容器并改名为 `ginkgo-mysql`，`127.0.0.1:3307`，现有版本8.0 |
| 新项目数据库 | `ginkgo_food_map`，独立 `ginkgo_app` 账号，仅授权该库 |
| Redis | 保留原容器并改名为 `ginkgo-redis`，`127.0.0.1:6380`，现有版本7.4，需密码；应用使用 DB 4 |
| 基础设施网络 | Docker 网络 `ginkgo-infrastructure` |

服务器运行配置中的数据库、Redis、SMTP、验证码 HMAC 及 JWT 密钥不进入 Git、构建产物或部署日志。RSA 和 HMAC 密钥由服务器独立生成。Runner 与后端 systemd 服务均已设置开机启动，首次发布后后端服务已运行。

## Prometheus指标采集端点

后端提供`GET /actuator/prometheus`，与业务服务共用端口（默认8080），返回Prometheus文本，不使用业务JSON封装；无请求参数、无业务数据写入。已接入Prometheus Registry，Actuator只暴露health、info、prometheus，不暴露env、metrics等其他管理端点。

管理员读取携带`Authorization: Bearer <管理员accessToken>`，沿用现有JWT、Redis会话及数据库当前角色校验。匿名或无效Bearer返回401 `AUTH_UNAUTHENTICATED`，普通用户返回403 `AUTH_FORBIDDEN`；认证服务暂不可用时可返回503 `AUTH_SERVICE_UNAVAILABLE`。其他请求方法被拒绝。可选机器Basic采集使用独立凭据，仅能读取指标，无业务权限，配置及认证响应见[Prometheus接入](prometheus.md#独立采集认证)。

本机可在后端重启后验证（令牌通过当前PowerShell会话中的`$env:METRICS_ADMIN_TOKEN`提供，不写入仓库）：

```powershell
Invoke-WebRequest -Uri http://127.0.0.1:8080/actuator/prometheus -Headers @{ Authorization = "Bearer $env:METRICS_ADMIN_TOKEN" }
```

返回内容应包含`jvm_memory_used_bytes`等默认指标；部分指标在对应活动发生后才出现。列表检索专项指标和既有共享熔断指标一起导出，名称及计算口径见[ES列表检索指标](elasticsearch-metrics.md)。

采集端点供内部运维使用，不新增公网Nginx转发。自动采集采用独立凭据，避免管理员JWT过期或注销影响监控；Prometheus本地部署见[运行说明](prometheus.md)，Grafana独立看板访问及启动见[看板说明](grafana.md)。

## 图片存储目录

同一私有OSS Bucket下统一使用三个同级目录：

```text
Bucket/
├── review-images/{UUID}.jpg       餐厅和窗口的点评照片
├── restaurant-images/{UUID}.jpg   新店投稿、纠错附图和正式商家相册
└── windows-images/{UUID}.jpg      管理员窗口相册，首张作为封面
```

`app.file.oss.prefix`配置为`review-images/`，`app.file.oss.restaurant-prefix`配置为`restaurant-images/`，`app.file.oss.window-prefix`配置为`windows-images/`；本地配置与默认配置保持一致，不再使用`review-images/restaurants/`。文件名由服务端随机生成，用户、店铺和点评归属保存在数据库中，不继续分目录。图片绑定或审核通过时沿用原对象，不移动文件。

RAM凭证需允许`review-images/*`、`restaurant-images/*`和`windows-images/*`对象的PutObject、GetObject和DeleteObject；仅创建OSS目录不会自动获得权限。WINDOW上传用途由后端额外限制为管理员。Bucket及对象保持私有，页面通过后端校验可见性后读取。

修改前缀只影响新上传文件，既有文件按数据库记录的Bucket与存储键读取。清理旧图片时须先备份，核对数据库关联与实际对象，删除图片关联并刷新相关店铺的搜索封面，不能只在OSS控制台删除文件。2026-11-08本地旧图清理已同步完成，证据见[开发记录](../development.md#2026-11-08图片存储目录统一与旧图清理)；不代表服务器配置或线上数据已同步修改。

## 发布 Runner 与权限

Runner 以独立的 `ginkgo-deploy` 用户运行，并通过 systemd 开机启动。服务器主动通过 HTTPS 连接 GitHub 和产物下载服务，不需要为 GitHub 开放22端口，也不需要固定 GitHub Runner 的出口 IP。

在仓库 **Settings → Actions → Runners** 确认 `ginkgo-production` 为 Online/Idle。服务器检查方式：

```bash
cd /opt/ginkgo-runner
sudo ./svc.sh status
# 需要维护时，由管理员停止或重新启动；不要在发布过程中重启 Runner。
sudo ./svc.sh stop
sudo ./svc.sh start
```

Runner 只用于本仓库可信 `develop` 推送的发布任务，不用于外部 Pull Request 或不可信代码的构建。修改工作流、推送 develop 以及管理 Runner 的权限等同于发布权限，应只授予可信维护者。Runner 用户不能读取 root 专属的应用环境配置，也没有通用 sudo、Docker 管理权限。

首次注册使用仓库管理 API 签发的短期注册令牌，只用于本次配置，不提交到代码或保存到工作流 Secrets。Runner 注册后由自身凭据连接 GitHub；每次发布读取 GitHub API 使用只读、限本任务有效的 `GITHUB_TOKEN`，不保存个人访问令牌。

重装服务器时，在上述 Runners 页面移除失效记录并按 **New self-hosted runner** 的说明下载安装包，核对 SHA-256；以 `ginkgo-deploy` 用户注册，名称使用 `ginkgo-production`、自定义标签使用 `ginkgo-deploy`，工作目录使用 `_work`。随后由管理员在安装目录执行 `./svc.sh install ginkgo-deploy` 和 `./svc.sh start`。

旧 SSH 发布使用的 `DEPLOY_HOST`、`DEPLOY_PORT`、`DEPLOY_USER`、`DEPLOY_SSH_KEY` 和 `DEPLOY_KNOWN_HOSTS` Secrets 已不被工作流读取。本次保留这些历史配置及本机 SSH 密钥；管理员 SSH 继续只从原有允许来源访问。

## 发布后的检查与排查

在本机验证页面和实际前端提交号：

```powershell
Invoke-WebRequest http://8.137.98.235/login
Invoke-RestMethod http://8.137.98.235/api/public/status
Invoke-RestMethod http://8.137.98.235/version.txt
```

`/version.txt` 返回当前前端产物的40位 Git 提交号。匿名访问 `/auth/me` 返回401属于预期结果。

在服务器以管理员身份检查：

```bash
systemctl status ginkgo-backend.service --no-pager
journalctl -u ginkgo-backend.service -n 100 --no-pager
nginx -t
docker ps --format '{{.Names}} {{.Status}} {{.Ports}}'
readlink -f /opt/ginkgo/current
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1/version.txt
```

Actuator 只在服务器直连后端检查，没有通过站点 Nginx 暴露。分享日志前仍需检查是否含敏感信息，不要输出环境配置文件或私钥。

优先修复代码后重新推送 `develop`；同一提交可在 GitHub 重跑失败的任务，运行重试编号会生成新的版本目录。仅重跑发布任务时，原检查产物必须仍在保留期内。

## 回滚与运行边界

发布期间后端需要重启，会有短暂不可用，不保证零停机。健康检查验证程序及代理链路，不代替浏览器验收，也不实际发送注册邮件。

新版本失败时，发布脚本尝试切回上一版并重启；首次发布没有上一版，会停止失败的服务并移除当前链接。回滚本身失败会明确记录需要人工处理。Flyway 已执行的数据库迁移不会自动撤销，因此迁移需要兼容上一版代码；涉及数据变更时应单独安排备份和恢复方案。

版本目录和上传包暂不自动删除，便于排查和恢复，需定期检查磁盘容量，人工清理前先确认不涉及 `current` 指向的版本及需要保留的回滚版本。配置文件、数据库和 Redis 数据不随应用发布覆盖。

## 旧应用清理与基础设施保留

本次清理针对服务器上原 `/opt/healthy` 应用，已移除其源码、构建文件、配置、日志、备份、旧 Nginx 站点、`healthy-auto-deploy.service` 及脚本，删除原业务容器和对应镜像、Nacos、RabbitMQ 及其专属数据卷、旧网络。

MySQL、Redis 容器及持久化数据保留，没有重建实例。原数据库 `healthy`、`healthy_doctor`、`healthy_identity` 仍保留；这次没有删除旧业务数据库。持久化卷继续使用原名 `healthy-appointment_mysql_data` 和 `healthy-appointment_redis_data`，名称中的旧项目前缀不表示旧 Java 应用仍在运行。

管理员恢复快照位于 `/opt/ginkgo-infrastructure/compose.yaml`，权限限制为 root。现存容器仍保留原 Compose 标签；该文件用于容器缺失后的恢复，不应在当前容器存在时直接执行 `compose up`，避免名称冲突。恢复时必须继续挂载现有数据卷，不要执行删除卷的命令。

## 已完成验证

- 本机 Maven `verify` 成功，64个后端测试全部通过，包含真实 MySQL、Redis 测试。
- 前端 `npm run check` 成功，19个测试、ESLint、类型检查和生产构建通过。
- actionlint 1.7.7 校验流水线通过，Bash 语法检查通过。
- 11个部署及健康检查测试在 Ubuntu 服务器临时目录通过；测试模拟 systemctl，不切换正式服务。
- Nginx 配置和 systemd 单元静态校验通过，独立部署账号 SSH 登录及限定 sudo 权限已验证。
- 真实 Actions 的前端、后端、部署检查及服务器 Runner 发布四项任务全部通过；后端检查启用了真实 MySQL、Redis 集成测试。
- 服务器 Runner 在线空闲，服务以 `ginkgo-deploy` 运行并启用开机启动，不能读取应用环境配置及 JWT 私钥。
- 正式后端服务运行，健康状态 UP；站点首页、登录页、注册页、公开 API 和版本接口均通过检查，前端及服务器版本均匹配本次发布提交。

本次验收未实际发送邮件、创建账号或进行浏览器完整交互测试。发布结果以新仓库 Actions 与站点版本标识为准。仅记录本次验收的文档提交使用 `[skip ci]`，线上继续运行已检查的上述提交；后续正常 develop 推送仍会自动检查和发布。
