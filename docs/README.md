# 文档导航

从这里选择文档。根层是经常查阅的当前说明；设计、计划、运维和历史各有固定目录。

地图基础导航已按当前约定收尾：只把餐厅目的地交给高德，不开发站内起点选择；线上及真机验收暂跳过。搜索已区分本站餐厅与高德地点，学生提交收录仍仅占位，详见[功能说明](features.md)。

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
- 页面：[找店界面方案](plans/discovery-ui.md)。
- 地图：[高德地图基础闭环](plans/map-foundation.md)，包含导航、点位接口、页面联动及分步验收。
- 迁移：[旧系统迁移计划](plans/legacy-migration.md)。
- 运维：[自动部署与排障](operations/deployment.md)。
- 高德：[环境配置与真实底图验收](operations/amap-setup.md)。

## 历史追溯

- [开发记录01](history/development-01.md)：初始工程、账号与基础认证阶段。
- [开发记录02](history/development-02.md)：部署、餐厅、评价结构和前端阶段。
- [原站评价核对与旧规划](history/reviews-development-2026-10-29.md)：已归档，仅用于核对来源与历史决定。
- [需求依据与餐厅阶段过程](history/requirements-history-2026-10-29.md)：原站差异和已完成阶段记录。

## 维护约定

- 产品规则在需求正文定义一次，其他文档链接引用；规则变化直接修订正文，不叠加“最新说明优先”的补丁。
- 已实现功能写入features，已实现接口写入api；计划中的协议保留在plans，不能描述成已经可用。
- architecture说明设计和实际结构，区分已执行版本与拟议变更；plans说明未来实施步骤。
- 当前日志始终使用development.md；过长时轮转到history/development-NN.md，并更新本页索引。
- 原站考察和已替代方案放入history，开头说明归档日期与现行文档入口。
- 不为每次讨论新建一个目录或文件；确需新增时按职责归类，并在本页补入口。移动文件同时修复相对链接。
