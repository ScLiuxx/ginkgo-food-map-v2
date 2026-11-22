# 文档导航

从这里选择文档。根层是经常查阅的当前说明；设计、计划、运维和历史各有固定目录。

地图导航将商户目的地交给高德；用户可提交新店或纠错申请，管理员可读取真实申请与私有图片。各功能当前完成情况见[功能说明](features.md)。

```text
docs/
├── README.md             文档入口与维护约定
├── features.md           当前可用功能与使用方法
├── api.md                API统一入口与分册导航
├── api/                  按业务维护的已实现接口契约
├── development.md        当前阶段开发记录
├── architecture/         结构、模块边界及已执行数据库设计
├── plans/                能力范围、后续实施及迁移方案
├── operations/           部署、运行与排障
└── history/              历史记录、正式验证报告与归档资料
```

## 按问题查找

- 腾讯云自动发布：[CI/CD流程与示意图](../.github/workflows/README.md)、[运行与验收边界](operations/deployment.md)、[GitHub通过SSH配置](operations/ssh-deployment.md)；服务器无需安装自托管Runner，首次基础发布已验证，真实业务链路继续验收。

ES 监控的实际故障验证见[故障演练报告](history/elasticsearch-metrics-fault-drill-2026-11-10.md)：保留首次超时失败依据，已修复并验证暂停无响应时的及时降级、熔断与恢复。

| 想了解什么 | 阅读入口 |
| --- | --- |
| 现在能用哪些功能 | [功能说明](features.md) |
| 产品使用规则与接口边界 | [功能说明](features.md)、[接口契约](api.md) |
| 哪些接口已实现、如何调用 | [API统一入口](api.md)，各业务分册包含完整接口索引及详细协议 |
| 地图浏览、本站/高德统一搜索及提交占位如何工作 | [地图实施计划](plans/map-foundation.md)、[功能说明](features.md) |
| 最近改了什么、验证了什么 | [当前开发记录](development.md) |

## 设计与计划

- 图片存储：[切换至COS](plans/oss-to-cos.md)，只接入新图片，不迁移历史图片；定向测试和打包通过，本机已启用COS并重启，用户确认真实上传成功，删除待手动验收；已恢复CI/CD部署教学。
- CI/CD：[腾讯云首次部署与流水线完善](plans/cicd-improvements.md)，已确认全新Ubuntu22.04服务器及develop检查通过自动发布；当前为配置阶段，运行约定见[自动检查与发布](operations/deployment.md)。
- 用户活跃度：[管理端统计计划](plans/admin-activity-statistics.md)集中维护业务口径、上线步骤及验收范围；[MySQL日汇总结构](architecture/mysql-schema.md#activity-statistics)、[Redis集合及存储协作](architecture/activity-statistics.md)和[接口契约](api/statistics.md)已实现；本地已备份升级V49并启用实际采集，前端读取正式接口。项目默认关闭，服务器尚未发布本功能。
- 新店附带点评：[业务规则与实施计划](plans/restaurant-review-submission.md)，使用方式见[功能说明](features.md#补充店铺与纠错)，联合提交、本人操作及[店铺已收录](api/submissions.md#link-existing)协议见[投稿接口](api/submissions.md)。
- 帮我选一家：[业务规则与实施计划](plans/restaurant-picker.md)。独立六项条件（含最低评分）、等概率抽取、本轮不重复、回看及详情返回已接通；[查询接口](api/discovery.md#picker)不写入抽取记录、不调整表结构。
- 餐厅榜单：[近期榜单计划](plans/hot-restaurants.md)记录规则及分阶段实施；[快照表](architecture/mysql-schema.md#restaurant-ranking-snapshots)、[读取接口与两级缓存](api/discovery.md#rankings)、内部计算已完成；实测见[MySQL基线](history/restaurant-ranking-read-2026-11-11.md)、[Redis对照](history/restaurant-ranking-redis-2026-11-11.md)、[Caffeine三模式对照及故障验证](history/restaurant-ranking-caffeine-2026-11-11.md)。自动发布、小时调度及防重叠已完成；首页入口及独立榜单页已按确认示意图接入，本地真实接口的空榜及模拟点评生成的20项榜单联调通过，上线验收待进行。
- 食堂直接点评：[窗口体系移除计划](plans/remove-canteen-windows.md)，移除窗口及关联数据，食堂沿用餐厅点评与评分协议。

- 业务访谈：[社区业务规则讨论记录](plans/community-rules.md)，逐项确认“我的”、消息、互动、风控及相关未完成业务，区分待讨论、暂定、已确认与暂缓，不代表已经实现。
- 社区页面：[前端接入状态](plans/community-frontend.md)，覆盖个人中心、个人主页、消息、设置及内容互动入口；区分真实可用与等待后端的能力。
- 点赞数据：[点赞关系表](architecture/mysql-schema.md#content-likes)，字段与唯一关系约束；接口见[点评与回复点赞](api/reviews.md#likes)。
- 全体公告：[管理员发布、查询及撤回](api/notifications.md#admin-announcements)、[用户详情及系统消息合并](api/notifications.md#announcements)；管理端`/admin/announcements`和用户系统消息已接入。
- 通知数据：[通知表结构](architecture/mysql-schema.md#notification-inbox)，包含业务通知、全体公告及公告个人状态的字段、枚举、索引及约束。
- 本人点评：[本人点评按店铺分组](api/reviews.md#my-reviews)、[点评表结构](architecture/mysql-schema.md#review-schema)；私有跨店分页、历史与删除已接通。
- 基础关注：[关系、名单与首次通知](api/community.md#follows)，名单默认私密，人数向登录用户展示。
- 店铺收藏：[收藏与隐私接口](api/community.md#favorites)，详情收藏、本人/公开列表及默认私密的设置。
- 公开主页：[登录可见个人主页](api/community.md#public-profile)，用户名、简介、数字等级、当前获赞及按店铺分组的公开点评。
- 本人资料：[本人资料与简介](api/account.md#profile)、[用户账号与资料表结构](architecture/mysql-schema.md#user-profile)。
- 本人投稿：[本人投稿与纠错记录](api/submissions.md#my-requests)、[申请表结构](architecture/mysql-schema.md#restaurant_change_requests商户资料待审核申请)。
- 成长等级：[经验账户与奖励表结构](architecture/mysql-schema.md#growth-schema)、[奖励规则与事务设计](plans/community-growth-v1.md)；当前能力见[功能说明](features.md#账号与个人中心)，读取协议见[本人成长摘要](api/account.md#growth)。
- 架构：[模块化单体](architecture/modular-monolith.md)说明后端模块、数据归属及事务协作；[前端架构](architecture/frontend.md)说明分层、会话、来源导航和关键组件复用。
- 接口内部实现：[搜索引擎、保护及异步同步](architecture/restaurant-search.md)、[榜单快照、缓存及生成](architecture/restaurant-ranking.md)；请求响应契约分别见[餐厅接口](api/restaurants.md)和[选店／榜单接口](api/discovery.md)。
- 数据结构：[MySQL 表结构](architecture/mysql-schema.md)，集中查看当前应用表，涵盖应用元数据、用户/角色/安全审计、商户及榜单快照、点评、媒体、点赞、成长与通知；[Elasticsearch 索引](architecture/elasticsearch-schema.md)。
- 分类：[分类表及编码](architecture/mysql-schema.md#restaurant_categories餐厅分类)；数据整理记录见[开发记录](development.md)。
- 审核存储：[商户申请表V17](architecture/mysql-schema.md#restaurant_change_requests商户资料待审核申请)，采用request_type和单个restaurant_id，记录审核结果与资料快照；通过/驳回及正式相册发布已实现，新增[正式相册V18](architecture/mysql-schema.md#restaurant_photos正式商户相册)，餐厅/食堂详情相册及管理端发布记录已接入，首页/地图卡片封面也已接入。
- 管理端：`/admin/reviews`已接入真实申请查询、就地编辑、通过/驳回及连续审核，见[功能说明](features.md)及[API文档](api/submissions.md#admin-review)。
- 统一登录：`/login`按服务端角色分流前台/工作台，`/admin/login`仅兼容跳转；管理身份由后端`/api/admin/me`校验。身份与查询边界见[前端架构](architecture/frontend.md)。
- 搜索：[实施进度与待办](plans/elasticsearch-search.md)、[索引结构](architecture/elasticsearch-schema.md)、[同步任务表](architecture/mysql-schema.md#restaurant-search-outbox)；现行查询契约见[搜索契约](api/restaurants.md#search)。
- 词库：[IK配置与维护](../deploy/elasticsearch/README.md)，包含持久化、词表加载与重建边界。
- ES监控：[建设计划](plans/elasticsearch-metrics.md)、[检索指标](operations/elasticsearch-metrics.md)、[Outbox同步指标](operations/elasticsearch-sync-metrics.md)，已接通受保护端点、Prometheus采集及Grafana检索/同步看板；[八条本地告警与排查](operations/search-alerts.md)、[首次告警演练](history/elasticsearch-alert-drill-2026-11-10.md)、[同步指标验证](history/elasticsearch-sync-metrics-2026-11-10.md)、[提交后唤醒与合并验证](history/elasticsearch-sync-wakeup-2026-11-10.md)及[固定窗口与Bulk验证](history/elasticsearch-bulk-2026-11-10.md)。
- 搜索投影：[内部组装服务](../backend/src/main/java/com/foodmap/restaurant/service/RestaurantSearchProjectionService.java)，字段定义见[ES结构](architecture/elasticsearch-schema.md)。
- 地图：[高德地图基础闭环](plans/map-foundation.md)，包含导航、点位接口、页面联动及分步验收。
- 运维：[自动部署与排障](operations/deployment.md)。
- 本地验证：[测试与构建入口、资源限制及故障分类](operations/local-checks.md)。
- 指标采集：[Prometheus本地运行与独立认证](operations/prometheus.md)、[Grafana检索看板与启动](operations/grafana.md)。
- 高德：[环境配置与真实底图验收](operations/amap-setup.md)。

## 历史追溯

- [帮我选一家交付验证（2026-11-13）](history/restaurant-picker-2026-11-13.md)：完整候选抽样、真实MySQL集成、详情往返、异常场景、手机布局及本机串行耗时。

- [食堂窗口第1至5步验证（2026-11-08）](history/canteen-windows-2026-11-08.md)：身份、评分、并发/回滚、迁移、前端与本地运行证据及限制。

- [v003＋查询熔断最终评测（2026-11-05）](history/elasticsearch-final-benchmark-2026-11-05.md)：当前构建的准确度、串行接口耗时、独立一致性核验及适用限制；当前版本测量优先查阅此报告。

搜索当前进度统一见[实施计划](plans/elasticsearch-search.md)，不在本导航重复维护阶段状态。

- [开发记录01](history/development-01.md)：初始工程、账号与基础认证阶段。
- [开发记录02](history/development-02.md)：部署、餐厅、评价结构和前端阶段。
- [开发记录03](history/development-03.md)：地图、商户投稿与图片、统一管理员登录及V17审核存储结构。
- [开发记录04](history/development-04.md)：2026-11-03至2026-11-18的审核发布、搜索与榜单、社区功能及前后台体验打磨；后续记录见[当前开发记录](development.md)。

## 维护约定

[开发规则](../AGENTS.md)规定协作、验证和交付要求；文档专用细则集中维护在本节。

| 文档 | 职责 |
| --- | --- |
| `features.md` | 面向使用者的当前功能、用法与未完成能力 |
| `api.md`、`api/` | 已实现接口的统一入口、分册索引与现行契约 |
| `architecture/` | 设计、模块边界、实际结构及内部实现 |
| `plans/` | 业务规则、当前实施状态、后续待办及计划中的协议 |
| `operations/` | 部署、运行、调度和排障 |
| `development.md` | 阶段变化、关键决策及最终验证；编写与归档要求见[开发规则](../AGENTS.md#文档信息分工与去重) |
| `history/` | 历史记录、正式实验与验证报告、原站考察及已替代方案 |

### 来源与更新

- 产品规则以本导航指定的业务规则文档为准，同一规则或事实只保留一个主要来源，其他文档简述后链接引用，不另建重复需求副本。当前说明直接修订正文，不叠加“最新说明优先”的补丁；已实现、仅设计和待完成必须区分。
- 同阶段开发记录合并整理，不叠加完整过程、逐文件职责清单或每轮测试数量；归档后更新本页索引。发现冗余时优先合并、链接和修正现行状态，不另建同样冗长的汇总。
- 不为每次讨论新建目录或文件；新增文档按职责归类并在本页补入口。移动文件或章节时同步修复相对链接及锚点。

### API 与结构说明

- `api.md`只维护统一入口、业务分册导航和维护约定。接口新增、修改或删除时，同步对应分册索引、HTTP方法、路径、认证及角色、请求参数、响应示例、状态码、错误码和重要副作用；计划中的协议留在`plans/`。
- 通用认证、响应封装、ID及错误规则只在`api/common.md`定义。接口说明按“接口与权限、请求参数、响应、错误码、副作用与限制”排列；共用协议可合并，不适用栏目可省略，引用使用稳定语义锚点。内部锁、缓存格式及调度机制放架构或运维文档。
- 数据库表及索引文档只描述当前结构、字段类型和含义、默认值、枚举、主键、索引、外键及字段约束；不混入修改过程、详细业务流程、并发边界、接口或测试。修改与验证写入`development.md`，接口写入`api/`，未来实施步骤写入`plans/`。

### 历史与证据

- 历史报告保留当时的数据、版本和范围，标明时间边界，不将后续状态倒写成当时已完成。原站考察和已替代方案注明归档日期及现行文档入口。
- 正式报告保留影响结论的数据量、环境、方法、核心指标、语义差异、局限及证据索引；精简时不能只留下提速数字。完整哈希、逐请求样本、临时PID、启动日志及无关排障尝试放原始证据或专门排障记录，正文保留追溯入口。测试数量口径遵循[验证与交付](../AGENTS.md#验证与交付)。
- 按既有约定，检索评测仅保留当前最终报告，旧报告删除并清理引用；查询集、工具及仓库外原始样本独立管理，不因删除报告正文而一并删除。
