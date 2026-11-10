# 文档导航

从这里选择文档。根层是经常查阅的当前说明；设计、计划、运维和历史各有固定目录。

地图导航将商户目的地交给高德；用户可提交新店或纠错申请，管理员可读取真实申请与私有图片。各功能当前完成情况见[功能说明](features.md)。

```text
docs/
├── README.md             文档入口与维护约定
├── features.md           当前可用功能与使用方法
├── api.md                已实现接口契约
├── development.md        当前阶段开发记录
├── architecture/         结构、模块边界及已执行数据库设计
├── plans/                能力范围、后续实施及迁移方案
├── operations/           部署、运行与排障
└── history/              旧日志、原站依据与已替代规划
```

## 按问题查找

ES 监控的实际故障验证见[故障演练报告](history/elasticsearch-metrics-fault-drill-2026-11-10.md)：保留首次超时失败依据，已修复并验证暂停无响应时的及时降级、熔断与恢复。

| 想了解什么 | 阅读入口 |
| --- | --- |
| 现在能用哪些功能 | [功能说明](features.md) |
| 产品使用规则与接口边界 | [功能说明](features.md)、[接口契约](api.md) |
| 哪些接口已实现、如何调用 | [接口文档](api.md) |
| 地图浏览、本站/高德统一搜索及提交占位如何工作 | [地图实施计划](plans/map-foundation.md)、[功能说明](features.md) |
| 最近改了什么、验证了什么 | [当前开发记录](development.md) |

## 设计与计划

- 食堂窗口：[资料、独立点评与评分汇总](api.md#22-食堂窗口资料点评与汇总)，管理员维护及用户浏览已接通，窗口资料由管理员统一录入，用户窗口投稿审核暂不规划。

- 业务访谈：[社区业务规则讨论记录](plans/community-rules.md)，逐项确认“我的”、消息、互动、风控及相关未完成业务，区分待讨论、暂定、已确认与暂缓，不代表已经实现。
- 社区页面：[前端接入状态](plans/community-frontend.md)，覆盖个人中心、个人主页、消息、设置及内容互动入口；区分真实可用与等待后端的能力。
- 点赞数据：[点赞关系表](architecture/mysql-schema.md#content-likes)，字段与唯一关系约束；接口见[API 18](api.md#18-点评与回复点赞)。
- 全体公告：[管理员发布、查询及撤回](api.md#178-管理员全体公告)、[用户详情及系统消息合并](api.md#179-用户公告详情及系统消息合并)；管理端`/admin/announcements`和用户系统消息已接入。
- 通知数据：[通知表结构](architecture/mysql-schema.md#notification-inbox)，包含业务通知、全体公告及公告个人状态的字段、枚举、索引及约束。
- 本人点评：[API 10.5](api.md#105-本人点评按店铺分组)、[点评表结构](architecture/mysql-schema.md#review-schema)；私有跨店分页、历史与删除已接通。
- 基础关注：[关系、名单与首次通知](api.md#21-基础关注名单隐私与关注提醒)，名单默认私密，人数向登录用户展示。
- 店铺收藏：[收藏与隐私接口](api.md#20-店铺收藏与隐私)，详情收藏、本人/公开列表及默认私密的设置。
- 公开主页：[登录可见个人主页](api.md#19-登录可见个人主页)，用户名、简介、数字等级、当前获赞及按店铺分组的公开点评。
- 本人资料：[API 15](api.md#15-本人资料与简介)、[用户账号与资料表结构](architecture/mysql-schema.md#user-profile)。
- 本人投稿：[API 16](api.md#16-本人投稿与纠错记录)、[申请表结构](architecture/mysql-schema.md#restaurant_change_requests商户资料待审核申请)。
- 成长等级：[V24经验账户与奖励表结构](architecture/mysql-schema.md#growth-schema)、[事务与后续接入设计](plans/community-growth-v1.md)；首次点评计奖、内部撤销、本人等级查询及本人页面展示已实现，其他奖励与排序待接入，接口见[API](api.md#14-本人成长摘要)，验证与运行状态见开发记录。
- 架构：[模块化单体](architecture/modular-monolith.md)、[前端分层](architecture/frontend.md)。
- 数据结构：[MySQL 表结构](architecture/mysql-schema.md)，集中查看全部 30 张应用表，涵盖应用元数据、用户/角色/安全审计、商户、点评、媒体、点赞、成长与通知；[Elasticsearch 索引](architecture/elasticsearch-schema.md)。
- 分类：[分类表及编码](architecture/mysql-schema.md#restaurant_categories餐厅分类)；数据整理记录见[开发记录](development.md)。
- 审核存储：[商户申请表V17](architecture/mysql-schema.md#restaurant_change_requests商户资料待审核申请)，采用request_type和单个restaurant_id，记录审核结果与资料快照；通过/驳回及正式相册发布已实现，新增[正式相册V18](architecture/mysql-schema.md#restaurant_photos正式商户相册)，餐厅/食堂详情相册及管理端发布记录已接入，首页/地图卡片封面也已接入。
- 页面：[找店界面与前端架构](architecture/frontend.md)。
- 管理端：`/admin/reviews`已接入真实申请查询、就地编辑、通过/驳回及连续审核，见[功能说明](features.md)及[API文档](api.md#5-管理员商户审核)。
- 统一登录：`/login`按服务端角色分流前台/工作台，`/admin/login`仅兼容跳转；管理身份由后端`/api/admin/me`校验。身份与查询边界见[前端架构](architecture/frontend.md)。
- 搜索：[实施进度与待办](plans/elasticsearch-search.md)、[索引结构](architecture/elasticsearch-schema.md)、[同步任务表](architecture/mysql-schema.md#restaurant-search-outbox)；现行查询契约见[API](api.md#621-正式搜索与简单降级契约2026-11-05)。
- 词库：[IK配置与维护](../deploy/elasticsearch/README.md)，包含持久化、词表加载与重建边界。
- ES监控：[建设计划](plans/elasticsearch-metrics.md)、[检索指标](operations/elasticsearch-metrics.md)、[Outbox同步指标](operations/elasticsearch-sync-metrics.md)，已接通受保护端点、Prometheus采集及Grafana检索/同步看板；[八条本地告警与排查](operations/search-alerts.md)、[首次告警演练](history/elasticsearch-alert-drill-2026-11-10.md)、[同步指标验证](history/elasticsearch-sync-metrics-2026-11-10.md)、[提交后唤醒与合并验证](history/elasticsearch-sync-wakeup-2026-11-10.md)及[固定窗口与Bulk验证](history/elasticsearch-bulk-2026-11-10.md)。
- 搜索投影：[内部组装服务](../backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantSearchProjectionService.java)，字段定义见[ES结构](architecture/elasticsearch-schema.md)。
- 地图：[高德地图基础闭环](plans/map-foundation.md)，包含导航、点位接口、页面联动及分步验收。
- 运维：[自动部署与排障](operations/deployment.md)。
- 指标采集：[Prometheus本地运行与独立认证](operations/prometheus.md)、[Grafana检索看板与启动](operations/grafana.md)。
- 高德：[环境配置与真实底图验收](operations/amap-setup.md)。

## 历史追溯

- [食堂窗口第1至5步验证（2026-11-08）](history/canteen-windows-2026-11-08.md)：身份、评分、并发/回滚、迁移、前端与本地运行证据及限制。

- [v003＋查询熔断最终评测（2026-11-05）](history/elasticsearch-final-benchmark-2026-11-05.md)：当前构建的准确度、串行接口耗时、独立一致性核验及适用限制；当前版本测量优先查阅此报告。

搜索当前进度统一见[实施计划](plans/elasticsearch-search.md)，不在本导航重复维护阶段状态。

- [开发记录01](history/development-01.md)：初始工程、账号与基础认证阶段。
- [开发记录02](history/development-02.md)：部署、餐厅、评价结构和前端阶段。
- [开发记录03](history/development-03.md)：地图、商户投稿与图片、统一管理员登录及V17审核存储结构；后续开发记录见当前入口development.md。

## 维护约定

- 产品规则在需求正文定义一次，其他文档链接引用；规则变化直接修订正文，不叠加“最新说明优先”的补丁。
- 已实现功能写入features，已实现接口写入api；计划中的协议保留在plans，不能描述成已经可用。
- architecture中的数据库表及索引文档只描述当前结构、字段类型/含义、默认值、枚举、主键、索引、外键及字段约束；不记录修改过程、业务流程、并发边界、接口或测试。修改与验证写入development，接口写入api；plans说明未来实施步骤。
- 当前日志始终使用development.md；过长时轮转到history/development-NN.md，并更新本页索引。
- 原站考察和已替代方案放入history，开头说明归档日期与现行文档入口。
- 不为每次讨论新建一个目录或文件；确需新增时按职责归类，并在本页补入口。移动文件同时修复相对链接。

文档信息分工与去重执行[AGENTS.md](../AGENTS.md#文档信息分工与去重)。
