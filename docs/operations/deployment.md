# 自动检查与发布

最后核对日期：2026-11-19（腾讯云配置进行中，旧服务器实测记录保留日期边界）。

## 当前状态

项目迁入独立仓库 [kanno111/foodmap](https://github.com/kanno111/foodmap)，默认分支为 `develop`。新历史从原 develop 的 V2 重构基线开始，随后保存登录、注册及 CI/CD 修改，不包含基线之前的旧系统提交。

本轮目标为全新腾讯云服务器`43.139.87.34`，2核8GB、80GB，实际系统为Ubuntu22.04.5 LTS／x86_64。用户选择develop推送检查通过后自动发布；仓库配置已在功能分支调整，服务器仍在初始化教学阶段，尚未安装发布Runner或启用自动发布，详见[本轮计划](../plans/cicd-improvements.md)。流水线位于[ci-cd.yml](../../.github/workflows/ci-cd.yml)，实际执行结果见[Actions](https://github.com/kanno111/foodmap/actions)。

新配置使用`production`环境、腾讯云专属Runner标签`foodmap-tencent-production`，并要求仓库变量`AUTO_DEPLOY_ENABLED`为字符串`true`。变量在服务器准备完成后一次开启，此后无需逐次确认；未设置时只运行检查，不会向旧服务器或尚未配置的新服务器发布。

用户通过SSH回传基础安装结果：Java21、Nginx1.18、Docker29.10及Compose5.8已安装，Nginx／Docker均active。数据库、Redis、ES及发布Runner尚未配置；这些状态依据用户终端输出，尚未完成首次发布验收。

以下旧服务器`8.137.98.235`的上线结果为2026-10-27历史实测，不表示当前可用或已迁入腾讯云：

此前首轮检查全部通过，但 GitHub 托管 Runner 无法连接仅允许本机来源的 SSH，发布步骤以255退出。现改为服务器主动连接 GitHub、下载同一次检查的产物并在本地发布，自动发布不再依赖入站 SSH；原有 SSH 来源限制继续保留。

访问地址为 `http://8.137.98.235`。首次服务器主动发布已于2026-10-27成功，运行记录为 [Actions 40528088040](https://github.com/kanno111/foodmap/actions/runs/40528088040)，发布提交为 `a8df8ff62f578220d626682147fd7cf1b4f80069`。首页、登录页、注册页和公开 API 均返回200，匿名 `/auth/me` 返回预期401，后端健康状态为 UP。当前仅配置 HTTP，尚未配置域名和 HTTPS。

## 改名后的部署前置条件

2026-11-17项目内名称统一为`foodmap`，本次仅修改本地源码和部署模板，没有连接或修改服务器。下方“当前状态”和“服务器部署结构”保留此前实测名称；后续命令使用新命名，不代表服务器已完成迁移。

再次向`develop`推送前，应同步服务器上的`foodmap`应用账号、`foodmap-deploy` Runner账号及标签、`/opt/foodmap`发布目录、`/etc/foodmap/backend.env`、`foodmap-backend.service`、Nginx配置和sudo授权。运行配置仍可连接原数据库、Redis和OSS，不能因为改名新建空卷替换原数据。JWT默认签发者和受众已改为`foodmap`／`foodmap-api`，浏览器存储键和缓存前缀也已更新，升级后需重新登录；现有缓存等待过期后重新生成。

GitHub仓库已更名为`kanno111/foodmap`，本地`origin`、文档和本轮工作流仓库身份条件已同步。监控的任务名、数据源UID和告警名称已更新模板，部署时须同步Prometheus／Grafana配置；不重建已有指标数据卷。IK词典新文件为`foodmap-food.dic`，启用新配置前需同步该文件。部署模板迁移尚未在服务器验收。

## 触发条件与执行流程

使用统计默认关闭。发布本功能前先备份目标数据库、核对V49迁移，再显式配置`ACTIVITY_STATISTICS_ENABLED=true`；启用后会保存唯一采集起点，不回补历史。首次启用、暂停恢复、Redis权限与持久化检查以及当前单后端实例限制集中见[采集运行约定](../architecture/activity-statistics.md#采集运行约定)。2026-11-19已在本地备份并升级V49，通过被忽略的local配置显式启用；尚未修改服务器配置或发布本功能。

向`develop`提交PR及远程`develop`的push均触发检查，普通功能分支push和仅在本地提交不触发。PR更新时取消旧一轮PR检查；develop运行及部署不中断。只有本仓库develop推送且发布就绪变量开启，检查成功后才执行服务器发布；PR不会使用自托管发布Runner。

1. 后端使用Java21执行Maven `verify`，显式开启既有认证／商户／点评／Redis测试及新增统计结构、统计后端集成测试。CI使用临时MySQL8.4和Redis8；统计MySQL映射13306、Redis使用独立16379服务，随机测试库与业务连接隔离，不访问开发库、生产数据库或真实OSS。测试报告即使失败也保留7天；其他可选专项开关不因`verify`自动启用。
2. 前端使用 Node.js 24 和 `npm ci`，执行 ESLint、Vitest、TypeScript 类型检查及 Vite 生产构建。
3. 检查部署脚本语法，运行发布、失败回滚及 HTTP 健康检查测试。
4. 三项检查全部通过后，取本次运行生成的 JAR 和前端产物，打包并生成 SHA-256 校验文件，不在服务器重新构建源码。产物保留7天。
5. 发布开始前核对远程 `develop` 最新提交；若当前提交已经被新推送取代，跳过本次发布。发布任务串行执行，不中断正在切换版本的任务。
6. 服务器上的 `foodmap-deploy` Runner 下载本次运行的产物，将发布包和脚本复制到本地待发布目录。发布脚本再次验证校验和、压缩包路径和提交号，然后原子切换当前版本链接并重启后端。
7. 同时验证后端健康、前端提交号、登录页面、公开 API 及匿名认证响应。失败时尝试恢复上一版程序，并将本次 Actions 标记为失败。

在 GitHub 仓库的 **Actions → CI and automatic deployment** 查看每个步骤及失败日志。检查失败时不会进入发布，线上已有版本继续运行。流水线核对的是发布开始时的最新提交；发布期间若又有推送，后续通过检查的版本会继续更新。

## 服务器部署结构（改名前实测记录）

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

新图片支持腾讯云COS；启用COS后新上传优先使用COS，不因失败回退到OSS。同一私有桶下统一使用四个同级目录，无需手工创建：

```text
Bucket/
├── review-images/{UUID}.jpg       餐厅和窗口的点评照片
├── restaurant-images/{UUID}.jpg   新店投稿、纠错附图和正式商家相册
├── windows-images/{UUID}.jpg      管理员窗口相册，首张作为封面
└── avatars/{UUID}.jpg             用户头像
```

`app.file.oss.prefix`配置为`review-images/`，`app.file.oss.restaurant-prefix`配置为`restaurant-images/`，`app.file.oss.window-prefix`配置为`windows-images/`，`app.file.oss.avatar-prefix`配置为`avatars/`；本地配置与默认配置保持一致，不再使用`review-images/restaurants/`。文件名由服务端随机生成，用户、店铺和点评归属保存在数据库中，不继续分目录。图片绑定或审核通过时沿用原对象，不移动文件。

四个目录及`app.file.oss.temporary-ttl`、`app.file.oss.cleanup-delay-ms`仍使用原有配置键，供COS和OSS共用。名称中保留`oss`用于兼容已有环境，并不表示COS需要OSS凭证。

腾讯云后端环境配置（凭证只填服务器上的`/etc/foodmap/backend.env`或本机被忽略的配置文件）：

```dotenv
COS_ENABLED=true
COS_REGION=ap-guangzhou
COS_BUCKET=nidianfoodmap-1535748846
COS_SECRET_ID=replace-with-cam-secret-id
COS_SECRET_KEY=replace-with-cam-secret-key
COS_SESSION_TOKEN=
OSS_ENABLED=false
```

若本机`application-local.yml`显式配置了`app.file.oss.enabled`，也要将其改为`false`，避免覆盖环境变量。COS可用长期CAM密钥；手动填临时凭证时需同时填写会话令牌并自行在到期前更新，当前不自动获取／刷新CVM角色凭证。

CAM策略仅授予上述四个前缀对象的`name/cos:PutObject`、`name/cos:GetObject`、`name/cos:DeleteObject`，资源范围限定目标桶；无需ListBucket、桶管理或PutObjectACL。代码不设置对象ACL，继承私有桶；桶和对象必须保持私有，且版本控制保持未开启，否则删除标记不能保证图片物理回收。旧OSS兼容模式下RAM需相应的对象Put/Get/Delete权限。WINDOW上传用途仍仅限管理员。

页面继续由后端校验可见性后读取图片，不需要浏览器直连COS、CORS、公开读或新增前端密钥。首次部署后手动验证四种图片用途、私密／待审图片隔离、取消、替换和删除；自动化测试不得写入真实COS。

本轮仅切换新图片，不复制历史对象、不改旧媒体记录。读取和删除严格按已登记桶名匹配已启用的提供者；关闭OSS后旧图可能不可读，旧记录保留且不参与回收。将来重新开启OSS会恢复其读取和清理，需先核对意图。新版本测试和构建通过后再改运行配置、重启；回退到不支持COS的旧JAR将无法读取新COS图片，不能把记录的桶名批量改成另一家存储来解决。

修改前缀只影响新上传文件，既有文件按数据库记录的Bucket与存储键读取。清理旧图片时须先备份，核对数据库关联与实际对象，删除图片关联并刷新相关店铺的搜索封面，不能只在OSS控制台删除文件。2026-11-08本地旧图清理已同步完成，证据见[开发记录](../history/development-04.md#2026-11-08图片存储目录统一与旧图清理)；不代表服务器配置或线上数据已同步修改。

## 发布 Runner 与权限

Runner 以独立的 `foodmap-deploy` 用户运行，并通过 systemd 开机启动。服务器主动通过 HTTPS 连接 GitHub 和产物下载服务，不需要为 GitHub 开放22端口，也不需要固定 GitHub Runner 的出口 IP。

本轮在仓库 **Settings → Actions → Runners** 确认`foodmap-tencent-production`为Online/Idle，且标签包含`foodmap-tencent-production`。服务器检查方式：

```bash
cd /opt/foodmap-runner
sudo ./svc.sh status
# 需要维护时，由管理员停止或重新启动；不要在发布过程中重启 Runner。
sudo ./svc.sh stop
sudo ./svc.sh start
```

Runner 只用于本仓库可信 `develop` 推送的发布任务，不用于外部 Pull Request 或不可信代码的构建。修改工作流、推送 develop 以及管理 Runner 的权限等同于发布权限，应只授予可信维护者。Runner 用户不能读取 root 专属的应用环境配置，也没有通用 sudo、Docker 管理权限。

首次注册使用仓库管理 API 签发的短期注册令牌，只用于本次配置，不提交到代码或保存到工作流 Secrets。Runner 注册后由自身凭据连接 GitHub；每次发布读取 GitHub API 使用只读、限本任务有效的 `GITHUB_TOKEN`，不保存个人访问令牌。

重装服务器时，在上述Runners页面移除失效记录并按 **New self-hosted runner** 的说明下载安装包，核对SHA-256；以`foodmap-deploy`用户注册，名称及自定义标签使用`foodmap-tencent-production`，工作目录使用`_work`。随后由管理员在安装目录执行`./svc.sh install foodmap-deploy`和`./svc.sh start`。

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
systemctl status foodmap-backend.service --no-pager
journalctl -u foodmap-backend.service -n 100 --no-pager
nginx -t
docker ps --format '{{.Names}} {{.Status}} {{.Ports}}'
readlink -f /opt/foodmap/current
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
- 服务器 Runner 在线空闲，服务以 `foodmap-deploy` 运行并启用开机启动，不能读取应用环境配置及 JWT 私钥。
- 正式后端服务运行，健康状态 UP；站点首页、登录页、注册页、公开 API 和版本接口均通过检查，前端及服务器版本均匹配本次发布提交。

本次验收未实际发送邮件、创建账号或进行浏览器完整交互测试。发布结果以新仓库 Actions 与站点版本标识为准。仅记录本次验收的文档提交使用 `[skip ci]`，线上继续运行已检查的上述提交；后续正常 develop 推送仍会自动检查和发布。
