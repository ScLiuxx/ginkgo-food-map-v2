# 自动检查与发布

最后核对日期：2026-11-20（腾讯云改用GitHub托管机器通过SSH发布，旧服务器记录保留日期边界）。

最新部署结果：PR #4已合并、发布开关true；修复后的[Actions 44682553571](https://github.com/kanno111/foodmap/actions/runs/44682553571)四项任务全部成功，线上版本f89f6c2。后端active／running、health为UP，公网首页／登录页／版本／公开接口检查通过，用户设置开机启动后已核对enabled。访问地址<http://43.139.87.34/>，公开店铺总数0、启用分类9，未导入本地业务数据。大包按4MiB、最多8路SCP传输，重组并校验后激活；完整验证边界见[本轮计划](../plans/cicd-improvements.md)。

## 当前状态

使用统计：2026-11-20用户授权开启，已以ubuntu执行单次配置操作，核对MySQL V49统计表、Redis AOF／everysec、noeviction及内存限额，备份服务器`/etc/foodmap/backend.env`后将`ACTIVITY_STATISTICS_ENABLED`设为`true`。本轮应用自动发布重启后才生效，统计从实际首次启用时刻开始，不回补历史。该开关与`AUTO_DEPLOY_ENABLED`、Actuator指标采集独立；初始化模板继续默认关闭，不会覆盖服务器现有配置。修改私有配置需要管理员权限，部署账号仍仅允许重启／停止后端。

项目迁入独立仓库 [kanno111/foodmap](https://github.com/kanno111/foodmap)，默认分支为 `develop`。新历史从原 develop 的 V2 重构基线开始，随后保存登录、注册及 CI/CD 修改，不包含基线之前的旧系统提交。

当前腾讯云服务器为`43.139.87.34`，2核8GB、80GB，Ubuntu22.04.5 LTS／x86_64。GitHub通过SSH完成首次程序发布、版本和基础健康验证；develop后续检查通过自动发布。首次配置过程及真实业务待验收范围见[本轮计划](../plans/cicd-improvements.md)，流水线位于[ci-cd.yml](../../.github/workflows/ci-cd.yml)，实际执行结果见[Actions](https://github.com/kanno111/foodmap/actions)。

现行新配置在GitHub托管机器运行，通过SSH发布到`production`环境，不再使用服务器Runner标签，并要求仓库变量`AUTO_DEPLOY_ENABLED`为字符串`true`。变量在服务器准备完成后一次开启，此后无需逐次确认；未设置时只运行检查，不会向旧服务器或尚未配置的新服务器发布。

用户已明确本次暂不配置Actuator指标采集，也不部署Prometheus、Grafana等监控／告警服务，不生成或启用独立指标采集凭据。下方监控相关内容保留为既有能力说明，属于后续可选阶段。本轮发布只保留服务器本机`/actuator/health`健康检查，以判断启动及依赖状态和触发失败回滚；不新增公网Actuator代理。

用户通过SSH回传基础安装结果：Java21、Nginx1.18、Docker29.10及Compose5.8已安装，Nginx／Docker均active。MySQL8.4、Redis8-alpine容器已启动且均healthy，端口仅绑定127.0.0.1:3306和127.0.0.1:6379；应用配置随后已生成，SSH登录与首次发布仍待完成。数据服务的配置边界及容器内MySQL客户端用法见[初始化说明](../../deploy/infrastructure/README.md)，容器健康结果不代替应用连接、备份恢复及MySQL8.4全链路验收。

ES9.5.4官方镜像已由用户下载成功；[ES／IK部署文件](../../deploy/elasticsearch/server/README.md)及带已校验官方插件包的传输目录已准备。派生镜像固定基础镜像摘要和IK9.5.4，使用1GiB堆、2GiB容器上限和独立数据卷，9200只绑定回环地址；初始化会持久化所需`vm.max_map_count`设置。本机仅完成Python、插件包和Compose静态核对；用户随后回传服务器容器healthy及`analysis-ik 9.5.4`插件列表，确认服务已启动和插件已加载。既有初始化工具的只读检查也已由用户回传通过，v004结构、别名及写索引标记符合定义，当前0条文档；不导入本机开发数据。扩展词典分词及应用实际搜索仍待验收。

腾讯云服务器直连Docker Hub超时时，可按[官方说明](https://cloud.tencent.com/document/product/1207/45596)将Docker daemon的`registry-mirrors`设为`["https://mirror.ccs.tencentyun.com"]`。该地址仅支持腾讯云内网访问，本机不能解析并不等于服务器不可用。修改前备份`/etc/docker/daemon.json`并保留其他键，对候选文件执行`dockerd --validate --config-file`后再替换和重启；用`docker info`及实际镜像拉取验证，不能把Docker安装的APT源当作容器镜像加速源。用户执行后已回传两个数据服务镜像拉取成功。

以下旧服务器`8.137.98.235`的上线结果为2026-10-27历史实测，不表示当前可用或已迁入腾讯云：

此前首轮检查全部通过，但 GitHub 托管 Runner 无法连接仅允许本机来源的 SSH，发布步骤以255退出。现改为服务器主动连接 GitHub、下载同一次检查的产物并在本地发布，自动发布不再依赖入站 SSH；原有 SSH 来源限制继续保留。

访问地址为 `http://8.137.98.235`。首次服务器主动发布已于2026-10-27成功，运行记录为 [Actions 40528088040](https://github.com/kanno111/foodmap/actions/runs/40528088040)，发布提交为 `a8df8ff62f578220d626682147fd7cf1b4f80069`。首页、登录页、注册页和公开 API 均返回200，匿名 `/auth/me` 返回预期401，后端健康状态为 UP。当前仅配置 HTTP，尚未配置域名和 HTTPS。

## 首次创建运行账号与目录

本节用于全新腾讯云服务器，在SSH终端逐条执行；两个账号不授予通用sudo或Docker组权限。若提示同名账号／目录已存在，先核对归属，不改已有权限或覆盖配置。

```bash
sudo adduser --system --group --no-create-home foodmap
sudo adduser --system --group --home /opt/foodmap-runner --no-create-home --shell /bin/bash foodmap-deploy
sudo install -d -o foodmap-deploy -g foodmap-deploy -m 755 /opt/foodmap /opt/foodmap/releases
sudo install -d -o foodmap-deploy -g foodmap-deploy -m 700 /opt/foodmap/incoming /opt/foodmap-runner
sudo install -d -o root -g foodmap -m 750 /etc/foodmap
id foodmap
id foodmap-deploy
```

`foodmap`仅运行后端；`foodmap-deploy`接受专用SSH密钥并管理版本文件。版本目录允许后端及Nginx读取，待发布目录与Runner工作区只允许发布账号访问；配置目录仅root和后端组可进入，后续`backend.env`由root以0600保存。此步不创建业务数据、生成密钥或启动应用。2026-11-19用户已回传两个账号及目录归属正确：`/etc/foodmap`为root:foodmap、0750，`/opt/foodmap`为foodmap-deploy:foodmap-deploy、0755，原Runner目录为同发布账号、0700；改用SSH后该目录继续作为发布账号home，不运行Runner。

后端配置见[私有配置首次准备](../../deploy/application/README.md)：用户确认沿用现有SMTP，从本机被忽略的配置中仅整理邮件和COS参数，通过SSH加密传输；数据库密码在服务器读取，JWT及验证码HMAC独立生成。源码只包含脚本与说明，真实输入保存在仓库外私有目录；初始化拒绝覆盖已存在的配置或密钥，不发送邮件、调用COS或启动应用。用户已回传服务器生成成功，`backend.env`为root:root、0600，JWT文件为root:foodmap、0640，临时输入副本已删除；应用实际读取与服务连通性仍待验收。

服务安装步骤在同一说明中：复用systemd模板，新增[有限sudo配置](../../deploy/foodmap-deploy.sudoers)，先执行服务器端静态校验再安装，仅允许发布账号重启／停止该项目后端。用户已回传单元loaded／inactive、运行账号foodmap，以及sudo仅允许预期两项命令；不提前启动应用。首次发布前不创建虚假的`current`或启用自动发布变量，首次发布成功后再启用后端开机启动。

服务器已按[Nginx及高德生产代理](../../deploy/nginx/README.md)安装njs固定上游代理、TLS验证及私有凭据，初始化保留默认站点备份，用户完成独立配置检查和显式reload。后续请求已返回503及`Application deployment pending`，确认项目入口生效。前端JS Key的GitHub仓库变量、真实高德请求、前端构建与SSH发布仍待设置或验收；本机部署检查边界见上述说明，不将规则测试等同于真实高德请求成功。

首次站点初始化曾因Ubuntu22.04源无njs包而失败；按官方源、签名核对及安装预览纠正后，用户已安装Nginx1.30.7及配套njs并启用HTTP模块，保留Ubuntu站点加载方式。上述503结果为纠正后的项目站点实际结果；早期1.18.0及未加载模块的输出仅表示当时状态。

## 改名后的部署前置条件

2026-11-17项目内名称统一为`foodmap`，本次仅修改本地源码和部署模板，没有连接或修改服务器。下方“当前状态”和“服务器部署结构”保留此前实测名称；后续命令使用新命名，不代表服务器已完成迁移。

再次向`develop`推送前，应同步服务器上的`foodmap`应用账号、`foodmap-deploy`发布账号及SSH授权、`/opt/foodmap`发布目录、`/etc/foodmap/backend.env`、`foodmap-backend.service`、Nginx配置和sudo授权。运行配置仍可连接原数据库、Redis和OSS，不能因为改名新建空卷替换原数据。JWT默认签发者和受众已改为`foodmap`／`foodmap-api`，浏览器存储键和缓存前缀也已更新，升级后需重新登录；现有缓存等待过期后重新生成。

GitHub仓库已更名为`kanno111/foodmap`，本地`origin`、文档和本轮工作流仓库身份条件已同步。监控的任务名、数据源UID和告警名称已更新模板，部署时须同步Prometheus／Grafana配置；不重建已有指标数据卷。IK词典新文件为`foodmap-food.dic`，启用新配置前需同步该文件。部署模板迁移尚未在服务器验收。

## 触发条件与执行流程

使用统计默认关闭。发布本功能前先备份目标数据库、核对V49迁移，再显式配置`ACTIVITY_STATISTICS_ENABLED=true`；启用后会保存唯一采集起点，不回补历史。首次启用、暂停恢复、Redis权限与持久化检查以及当前单后端实例限制集中见[采集运行约定](../architecture/activity-statistics.md#采集运行约定)。2026-11-19已在本地备份并升级V49，通过被忽略的local配置显式启用；尚未修改服务器配置或发布本功能。

向`develop`提交PR及远程`develop`的push均触发检查，普通功能分支push和仅在本地提交不触发。PR更新时取消旧一轮PR检查；develop运行及部署不中断。只有本仓库develop推送且发布就绪变量开启，检查成功后才执行服务器发布；PR不读取部署密钥，也不连接生产服务器。

1. 后端使用Java21执行Maven `verify`，显式开启既有认证／商户／点评／Redis测试及新增统计结构、统计后端集成测试。CI使用临时MySQL8.4和Redis8；统计MySQL映射13306、Redis使用独立16379服务，随机测试库与业务连接隔离，不访问开发库、生产数据库或真实OSS。测试报告即使失败也保留7天；其他可选专项开关不因`verify`自动启用。
2. 前端使用 Node.js 24 和 `npm ci`，执行 ESLint、Vitest、TypeScript 类型检查及 Vite 生产构建。
3. 检查部署脚本语法，运行发布、失败回滚及 HTTP 健康检查测试。
4. 三项检查全部通过后，取本次运行生成的 JAR 和前端产物，打包并生成 SHA-256 校验文件，不在服务器重新构建源码。产物保留7天。
5. 发布开始前核对远程 `develop` 最新提交；若当前提交已经被新推送取代，跳过本次发布。发布任务串行执行，不中断正在切换版本的任务。
6. GitHub托管发布机器通过SSH创建版本专属目录，以SCP上传同一轮产物和脚本；全部上传成功后才调用远端发布入口。发布脚本再次验证校验和、压缩包路径和提交号，然后原子切换当前版本链接并重启后端。
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

## SSH发布配置与权限

2026-11-20用户选择改为GitHub通过SSH发布，服务器不再下载安装或注册自托管Runner。完整的公钥安装、生产环境Secrets、主机公钥固定、网络前提及验收步骤集中见[SSH发布配置](ssh-deployment.md)。`foodmap-deploy`继续管理版本文件，有限sudo仍仅允许重启／停止后端；不使用root部署。

本次沿用同一轮检查产物、发布锁、版本校验、健康检查及程序失败回滚。SSH私钥仅保存在受限本机目录及GitHub生产环境Secret；GitHub临时文件在步骤退出时删除。主机公钥由现有可信服务器终端取得，不关闭校验。用户确认TCP22安全组允许IPv4来源，真实GitHub访问仍待验证。

旧服务器自托管Runner的安装与验收记录属于下方历史结果；未收到新服务器Runner已注册的证据，不自动删除目录或停止未知服务。旧Secrets可能仍指向旧服务器，必须按新说明全部配置为腾讯云生产环境值后再开启发布开关。

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
