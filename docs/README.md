# 文档导航

从这里选择文档。根层是经常查阅的当前说明；设计、计划、运维和历史各有固定目录。

地图导航将商户目的地交给高德；用户可提交新店或纠错申请，管理员可读取真实申请与私有图片。各功能当前完成情况见[功能说明](features.md)。

```text
docs/
├── README.md             文档入口与维护约定
├── requirements.md       当前产品规则与验收条件
├── features.md           当前可用功能与使用方法
├── api.md                已实现接口契约
├── development.md        当前阶段开发记录
├── architecture/         结构、模块边界及已执行数据库设计
├── plans/                能力范围、后续实施及迁移方案
├── operations/           部署、运行与排障
└── history/              旧日志、原站依据与已替代规划
```

## 按问题查找

| 想了解什么 | 阅读入口 |
| --- | --- |
| 现在能用哪些功能 | [功能说明](features.md) |
| 产品应该怎样工作 | [需求与验收](requirements.md) |
| 哪些接口已实现、如何调用 | [接口文档](api.md) |
| 下一步写点评后端如何做 | [后端实施计划](plans/review-backend.md) |
| 整个评价阶段还缺哪些能力 | [评价范围与进度](plans/review-roadmap.md) |
| 地图浏览、本站/高德统一搜索及提交占位如何工作 | [地图实施计划](plans/map-foundation.md)、[功能说明](features.md) |
| 最近改了什么、验证了什么 | [当前开发记录](development.md) |

## 设计与计划

- 架构：[模块化单体](architecture/modular-monolith.md)、[前端分层](architecture/frontend.md)。
- 数据结构：[餐厅/食堂/窗口结构与 V9 增量](architecture/restaurant-schema.md)、[评价结构与 V10 窗口归属](architecture/review-schema.md)；区分迁移脚本就绪与运行库部署状态。
- 审核存储：[商户申请表V17](architecture/restaurant-schema.md#restaurant_change_requests商户资料待审核申请)，采用request_type和单个restaurant_id，记录审核结果与资料快照；通过/驳回及正式相册发布已实现，新增[正式相册V18](architecture/restaurant-schema.md#restaurant_photos正式商户相册)，餐厅/食堂详情相册及管理端发布记录已接入，首页/地图卡片封面也已接入。
- 页面：[找店界面方案](plans/discovery-ui.md)。
- 管理端：[商户审核示意图](plans/admin-review-mockup.html)为设计依据；`/admin/reviews`已接入真实申请查询、就地编辑、通过/驳回及连续审核，见[功能说明](features.md)及[API文档](api.md#5-管理员商户审核)。
- 统一登录：`/login`按服务端角色分流前台/工作台，`/admin/login`仅兼容跳转；管理身份由后端`/api/admin/me`校验。身份与查询边界见[前端架构](architecture/frontend.md)。
- 搜索：[Elasticsearch 餐厅搜索方案](plans/elasticsearch-search.md)，当前仅设计，包含资源约束、相关性、可靠同步与降级验收。
- 地图：[高德地图基础闭环](plans/map-foundation.md)，包含导航、点位接口、页面联动及分步验收。
- 迁移：[旧系统迁移计划](plans/legacy-migration.md)。
- 运维：[自动部署与排障](operations/deployment.md)。
- 高德：[环境配置与真实底图验收](operations/amap-setup.md)。

## 历史追溯

- [开发记录01](history/development-01.md)：初始工程、账号与基础认证阶段。
- [开发记录02](history/development-02.md)：部署、餐厅、评价结构和前端阶段。
- [开发记录03](history/development-03.md)：地图、商户投稿与图片、统一管理员登录及V17审核存储结构；后续开发记录见当前入口development.md。
- [原站评价核对与旧规划](history/reviews-development-2026-10-29.md)：已归档，仅用于核对来源与历史决定。
- [需求依据与餐厅阶段过程](history/requirements-history-2026-10-29.md)：原站差异和已完成阶段记录。

## 维护约定

- 产品规则在需求正文定义一次，其他文档链接引用；规则变化直接修订正文，不叠加“最新说明优先”的补丁。
- 已实现功能写入features，已实现接口写入api；计划中的协议保留在plans，不能描述成已经可用。
- architecture说明设计和实际结构，区分已执行版本与拟议变更；plans说明未来实施步骤。
- 当前日志始终使用development.md；过长时轮转到history/development-NN.md，并更新本页索引。
- 原站考察和已替代方案放入history，开头说明归档日期与现行文档入口。
- 不为每次讨论新建一个目录或文件；确需新增时按职责归类，并在本页补入口。移动文件同时修复相对链接。
