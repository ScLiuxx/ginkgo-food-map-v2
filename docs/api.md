# 泥电美食地图 V2 API 文档

本页是当前已实现接口的统一入口。按业务选择分册，各分册的“接口索引”列出完整方法、路径、权限、成功状态及详细协议。字段和错误码以详细协议为准，部署能力以实际版本为准。

先阅读[通用约定](api/common.md#conventions)及[通用错误](api/common.md#errors)。业务ID、认证、响应封装、时间与评分单位在通用约定中统一维护。

<a id="endpoints"></a>

## 按业务查找接口

| 分册 | 内容 |
| --- | --- |
| [账号、会话与本人资料](api/account.md) | 验证码、注册、登录、改密、身份、改名、简介、头像、成长 |
| [餐厅、地图与管理](api/restaurants.md) | 分类、目录、搜索、是否有点评筛选、地图范围、详情、正式相册、管理员直接维护及点评选图 |
| [帮我选一家与榜单](api/discovery.md) | 候选抽取／复核、周榜／月榜读取 |
| [店铺投稿、本人申请与审核](api/submissions.md) | 新店、附带点评、纠错、本人查询／修改／撤回／重提、管理员审核 |
| [点评、回复与点赞](api/reviews.md) | 发评与正文校验、单条详情、历史、本人点评、删除、回复、点赞与获赞定位 |
| [图片上传与读取](api/files.md) | 临时图片上传／取消、用途、正式点评图片与各类图片入口 |
| [个人主页、收藏与关注](api/community.md) | 登录可见主页、公开点评、店铺收藏、关注与名单隐私 |
| [消息、公告与实时提醒](api/notifications.md) | 收件箱、未读／已读、删除、回复定位、SSE、公告与处理结果 |
| [内容举报与处置](api/moderation.md) | 提交举报、管理员核查、驳回及永久删除 |
| [使用统计](api/statistics.md) | 普通用户主动访问上报、管理员日汇总及七日活跃读取 |
| [状态、指标与代理入口](api/system.md) | 应用状态、Actuator、Nginx探针与高德开发／生产代理 |

## 新店附带点评的当前范围

后端已接通[联合提交](api/submissions.md#attached-review)、[本人详情](api/submissions.md#my-detail)、[私有点评图片](api/submissions.md#my-review-photos)、[待审修改](api/submissions.md#my-edit)、[撤回](api/submissions.md#my-withdraw)、[驳回后重提](api/submissions.md#my-resubmit)、[审核通过自动发布](api/submissions.md#approve-attached-review)及[店铺已收录处理](api/submissions.md#link-existing)。发布前内容仅作者本人可读，管理员只审核店铺资料。“店铺已收录”的管理入口、本人处理结果与通知已接入前端；联合表单及本人修改、撤回、重提页面仍待接入。

## 接口文档维护

- 在对应业务分册更新详细协议和接口索引；跨业务共用字段只维护一处，其他位置链接引用。
- 接口小节按“接口与权限 → 请求参数 → 响应 → 错误码 → 副作用与限制”排列；共用协议可合并说明，不适用的栏目可省略。
- 路径、认证、校验、返回字段、状态码、幂等、事务结果和阶段限制属于API契约；内部锁、缓存格式和调度参数分别维护在架构或运维文档。
- 使用语义锚点，修改标题或调整顺序时保留锚点；移动文件时同步更新仓库内引用。
- 未实现的接口保留在[实施计划](plans/)，功能使用说明见[功能文档](features.md)，验证记录见[开发记录](development.md)。

搜索引擎保护与同步实现见[搜索架构](architecture/restaurant-search.md)；榜单缓存、发布与调度见[榜单架构](architecture/restaurant-ranking.md)。
