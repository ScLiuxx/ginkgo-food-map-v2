# 点评、回复与点赞

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/api/restaurants/{restaurantId}/reviews` | Bearer | 201 / 200重放 | [发布](#publish) |
| GET | `/api/restaurants/{restaurantId}/reviews` | 公开 | 200 | [按作者分组分页](#review-list) |
| GET | `/api/restaurants/{restaurantId}/reviews/authors/{authorId}/history` | 公开 | 200 | [同作者历史](#review-history) |
| DELETE | `/api/reviews/{reviewId}` | 原作者 | 204 | [本人删除](#delete-review) |
| GET | `/api/me/reviews` | Bearer | 200 | [本人点评按店铺分组](#my-reviews) |
| POST | `/api/reviews/{reviewId}/replies` | Bearer | 201 / 200重放 | [发布与读取](#reply-publish-list) |
| GET | `/api/reviews/{reviewId}/replies` | 公开 | 200 | [发布与读取](#reply-publish-list) |
| DELETE | `/api/review-replies/{replyId}` | 原作者 | 204 | [删除本人回复](#delete-reply) |
| PUT | `/api/reviews/{id}/like` | Bearer | 200 | [设置点赞状态](#like-state) |
| DELETE | `/api/reviews/{id}/like` | Bearer | 200 | [设置点赞状态](#like-state) |
| PUT | `/api/review-replies/{id}/like` | Bearer | 200 | [设置点赞状态](#like-state) |
| DELETE | `/api/review-replies/{id}/like` | Bearer | 200 | [设置点赞状态](#like-state) |
| GET | `/api/content-likes` | 公开，可携Bearer | 200 | [批量读取当前计数和本人状态](#like-counts) |
| GET | `/api/me/received-likes` | Bearer | 200 | [本人当前获赞总数](#received-likes) |
| GET | `/api/me/received-likes/{id}/target` | Bearer | 200 | [获赞通知与精确内容定位](#like-target) |

<a id="reviews"></a>

## 点评

<a id="publish"></a>

### 发布

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/restaurants/{restaurantId}/reviews` | Bearer | 201 / 200重放 |

**请求参数**

`POST /api/restaurants/{restaurantId}/reviews`，需Bearer，作者来自当前身份；停业可评，不存在/已删除餐厅404。

```json
{"clientRequestId":"d6fb79a2-6866-41ae-9c38-13d239668c21","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"recommendation":"RECOMMENDED","body":"这次出餐很快，分量也比较足。","photoIds":[]}
```

UUID必填；四维完整，0–10且步进0.5；recommendation必填且仅接受大小写精确的字符串`RECOMMENDED`（推荐）、`NEUTRAL`（一般）、`NOT_RECOMMENDED`（不推荐），无默认值，布尔值、数字、空白别名及旧字段`recommended`均拒绝。正文去首尾Unicode空白后5–500码点，按纯文本展示。photoIds最多5个唯一、本人有效REVIEW临时文件ID。未知字段及嵌套scores额外字段拒绝。

**响应**

首次201，data为`{"reviewId":"101","restaurantId":"26","replayed":false}`；同账号同UUID、餐厅及规范化内容重放200、原ID、replayed=true。不同内容409 `REVIEW_REQUEST_CONFLICT`，已删除点评的原UUID重放409 `REVIEW_REQUEST_DELETED`，改UUID才是新点评。

**错误码**

点评、图片和计分统计同事务。每次真实发布保留历史；同一作者每店只取最新有效点评计分，不重复增加计分人数。无效输入400 `REVIEW_INVALID_INPUT`或`COMMON_MALFORMED_REQUEST`；并发/锁竞争重试耗尽503 `REVIEW_TEMPORARILY_UNAVAILABLE`，保留原UUID重试。

**副作用与限制**

三种态度均按所填四维评分正常计分，态度仅在单条点评展示。推荐／不推荐保留旧版内容摘要算法，因此旧成功请求以新字段重试仍可命中原回执；一般使用独立摘要版本。同一UUID改变态度返回409 `REVIEW_REQUEST_CONFLICT`。所有点评读取、历史、个人点评及回复定位响应使用同一三态字段，不再返回`recommended`。

成长副作用：接入后新提交成功的点评，在同一事务内尝试创建每用户每店唯一的经验奖励，默认5经验，可通过GROWTH_FIRST_REVIEW_EXPERIENCE配置；重复请求、同店再评或删评重发不重复领奖，普通本人删评不扣经验。已存在发布请求的重放分支不补发历史经验。计奖失败与点评、图片绑定、评分统计及搜索任务一起回滚，响应结构保持不变；最新经验与等级通过[本人成长摘要](account.md#growth)查询。金额溢出或账目异常返回500，不提交部分结果。

<a id="review-list"></a>

### 按作者分组分页

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{restaurantId}/reviews` | 公开 | 200 |

**请求参数**

`GET /api/restaurants/{restaurantId}/reviews`公开。page默认1、正整数；pageSize默认8、1–50，按作者组计数。sort默认DEFAULT，仅接受DEFAULT或TIME（大小写精确），非法或空值返回400通用校验错误。200、禁止缓存，data结构：

**响应**

```json
{"records":[{"author":{"id":"22","username":"同学甲"},"currentReview":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"recommendation":"RECOMMENDED","body":"这次出餐很快，分量也比较足。","createdAt":"2026-11-03T06:00:00Z","scoringStatus":"current","photos":[{"id":"501","position":0,"url":"/api/reviews/101/photos/0"}]},"historyCount":0,"nextHistoryCursor":null}],"totalGroups":1,"totalReviews":1,"page":1,"pageSize":8,"totalPages":1}
```

每组只返回作者最新有效点评，历史不内嵌；照片有序最多5张。默认排序DEFAULT在完整作者组中按该条最新点评的当前有效点赞数降序、作者当前等级降序，再按createdAt及数字ID降序，之后才分页；历史点评点赞不并入主卡片。TIME仅按createdAt及数字ID降序。没有成长账户按零经验对应等级处理，同等级不继续比较原始经验。每次请求为独立只读快照，点赞、等级或最新点评变化后作者可能跨页移动，跨请求不保证固定排名；切换排序回到第一页。totalGroups与totalReviews区分人数和条数，空/越界页返回真实总数与空records。父店不可见404，停业可读。ID/分页非法400 `REVIEW_INVALID_QUERY`或通用校验错误。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="review-history"></a>

### 同作者历史

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{restaurantId}/reviews/authors/{authorId}/history` | 公开 | 200 |

**请求参数**

`GET /api/restaurants/{restaurantId}/reviews/authors/{authorId}/history`公开；pageSize默认5、1–20；cursor可省略或原样传nextHistoryCursor/nextCursor，最长200字符。

**响应**

200、禁止缓存，data为`{author,currentReviewId,records,hasMore,nextCursor}`。records结构与currentReview相同，但scoringStatus为history；按createdAt/id倒序、排除当前代表及游标锚点。无历史返回空数组；作者无记录时author/currentReviewId为null。

**错误码**

游标绑定餐厅、作者、时间和真实锚点，不是授权凭据；不能跨目标复用或自行修改。非法游标400 `REVIEW_INVALID_CURSOR`，无效ID400 `REVIEW_INVALID_QUERY`。每段是独立快照，currentReviewId变化时应刷新主列表；父店隐藏404。

<a id="delete-review"></a>

### 本人删除

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| DELETE | `/api/reviews/{reviewId}` | 原作者 | 204 |

**响应**

`DELETE /api/reviews/{reviewId}`需Bearer，仅原作者可删，管理员不绕过归属。无请求体；首次与本人重复删除均204无内容、禁止缓存。

**错误码**

非本人403 `REVIEW_FORBIDDEN`；点评不存在且无本人回执404 `REVIEW_NOT_FOUND`；首次删除时父店隐藏404 `RESTAURANT_NOT_FOUND`；非法ID400 `REVIEW_INVALID_QUERY`；竞争耗尽503 `REVIEW_TEMPORARILY_UNAVAILABLE`。不提供恢复点评接口。

**副作用与限制**

删除历史只减少条数；删除最新由上一条接替，删除本人最后一条移除其计分贡献。回复/图片关联删除，媒体登记异步清理意图，不保证204时OSS已物理删除。原发布UUID持续保留删除回执，不允许重放重建点评。

<a id="my-reviews"></a>

### 本人点评按店铺分组

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/reviews` | Bearer | 200 |

**请求参数**

`GET /api/me/reviews`需Bearer，仅从当前认证身份确定作者；不允许选择其他用户，额外userId/authorUserId/offset查询字段不参与绑定。page默认1、正整数且不超过Integer.MAX_VALUE；pageSize默认8、范围1–50，分页单位为店铺组。

**响应**

成功200，`Cache-Control: no-store`，data示例：

```json
{"author":{"id":"22","username":"同学甲"},"records":[{"restaurant":{"id":"26","name":"校园面馆","businessStatus":"OPERATING"},"currentReview":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"recommendation":"RECOMMENDED","body":"这次出餐很快，分量也比较足。","createdAt":"2026-11-03T06:00:00Z","scoringStatus":"current","photos":[]},"historyCount":0,"nextHistoryCursor":null}],"totalGroups":1,"totalReviews":1,"page":1,"pageSize":8,"totalPages":1}
```

每家店只返回该作者的最新有效点评，按createdAt、id倒序确定代表并排序，归组在LIMIT之前完成。totalGroups为当前可见店铺数，totalReviews包括这些店铺中的本人历史点评；空/越界页保留真实计数。restaurant只返回ID、名称和营业状态，不包含管理或删除元数据。普通餐厅、历史食堂整体点评及独立窗口点评均已接入；窗口按WINDOW类型和window_id独立归组，不与同号餐厅合并，目标投影见[窗口点评](windows.md#reviews)；停业可见，不存在/已删除店铺整组排除且不计总数，恢复后原点评重新进入查询。

**错误码**

一次响应内作者、目标可见性、计数、页记录和照片来自同一只读RR快照；跨请求不是固定快照，新评/删除可能改变页位置，页面刷新或删除成功后回第一页。查询故障返回500，不降级成空列表；只有实际没有可见记录才返回空records及零计数。分页绑定/校验失败400，认证失效401 `AUTH_UNAUTHENTICATED`，认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`，已认证但方法不匹配405 `COMMON_METHOD_NOT_ALLOWED`。

**副作用与限制**

currentReview、照片相对URL及nextHistoryCursor与[按作者分组分页](#review-list)、[同作者历史](#review-history)一致；历史仍通过现有同店同作者接口按需读取，删除仍使用[本人删除](#delete-review)。接口不改变计分或经验，不创建账户、回执或媒体记录，公开个人主页的跨店列表使用[个人主页点评接口](community.md#public-reviews)。

<a id="replies"></a>

## 单层回复

<a id="reply-publish-list"></a>

### 发布与读取

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/reviews/{reviewId}/replies` | Bearer | 201 / 200重放 |
| GET | `/api/reviews/{reviewId}/replies` | 公开 | 200 |

**请求参数**

`POST /api/reviews/{reviewId}/replies`需Bearer：

```json
{"clientRequestId":"35a4d3f7-476d-4201-b698-d15880a14696","body":"谢谢分享，下次试试。"}
```

**响应**

UUID必填，正文去首尾Unicode空白后1–200码点；作者与时间由服务端确定，无子回复字段。首次201，data为`{replyId,reviewId,replayed:false}`，同账号/UUID/点评/规范化正文重放200及replayed=true，不同内容或点评409 `REPLY_REQUEST_CONFLICT`。仅写回复，不影响评分统计或作者代表。

`GET /api/reviews/{reviewId}/replies`公开；pageSize默认10、1–50；cursor可省略或原样使用nextCursor，最长200字符。200，data为`{records:[{id,author:{id,username},body,createdAt}],hasMore,nextCursor}`，按createdAt/id升序排除游标锚点。空结果为[]/false/null。作者缺失显示“用户不可用”，不返回邮箱或请求摘要。

**错误码**

两者禁止缓存，支持最新和历史点评，父店或点评不存在/已删除不可访问。非法字段400 `REPLY_INVALID_INPUT`或通用错误；游标错误400 `REPLY_INVALID_CURSOR`；点评不存在404 `REPLY_REVIEW_NOT_FOUND`；父店隐藏404 `RESTAURANT_NOT_FOUND`；竞争/超时耗尽503 `REPLY_TEMPORARILY_UNAVAILABLE`。游标不是授权令牌，跨段不保证固定快照。

**副作用与限制**

发布请求的作者和UUID独立保存在回复回执中；删除后的原请求返回409 `REPLY_REQUEST_DELETED`，不能在原点评或其他点评重放重建。正常发布新回复仍使用新UUID。删除分页锚点后继续使用旧cursor会返回400 `REPLY_INVALID_CURSOR`，需从头读取。

<a id="delete-reply"></a>

### 删除本人回复

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| DELETE | `/api/review-replies/{replyId}` | 原作者 | 204 |

**响应**

`DELETE /api/review-replies/{replyId}`需有效Bearer，路径为无前导零、范围1～Long.MAX_VALUE的回复ID，无业务查询参数及请求体。仅原回复作者可操作，点评作者和管理员均不能代删。首次成功及原作者重复删除均返回204，无响应体，`Cache-Control: no-store`；不提供恢复接口。

**错误码**

| 状态码 | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `REPLY_INVALID_INPUT` | 非法回复ID |
| 401 | `AUTH_UNAUTHENTICATED` | 凭证缺失、过期或账号不可用 |
| 403 | `REPLY_FORBIDDEN` | 当前账号不是原回复作者，包括查询他人的已删除回执 |
| 404 | `REPLY_NOT_FOUND` | 回复不存在且无删除回执 |
| 404 | `RESTAURANT_NOT_FOUND` | 首次删除时父店已删除或不可访问；停业仍可删除 |
| 503 | `REPLY_TEMPORARILY_UNAVAILABLE` | 锁竞争或超时，最多三次整笔事务重试耗尽 |
| 503 | `AUTH_SERVICE_UNAVAILABLE` | 认证依赖故障 |
| 500 | 通用内部错误 | 未预期失败，正文与回执一并回滚 |

**副作用与限制**

回复正文硬删除，回执仅保留作者、UUID、回复/点评ID和删除时间。原回复通知及点赞通知保留、首次已读时间不变，点击按[本人收到的回复精确定位](notifications.md#reply-target)、[获赞通知与精确内容定位](#like-target)返回内容失效，不再读取原正文；对应点赞从回复作者当前获赞总数中扣除。其余回复、点评、商户计分及成长经验不变。删除整条点评也标记关联回复回执，禁止旧回复请求复活。

前端二次确认后调用；失败保留重试入口，204之后列表刷新失败仅重试读取。成功后丢弃旧分页游标，从头刷新。切换账号、点评或卸载会取消请求并丢弃旧身份回执；取消传输不能证明服务端尚未删除，可重新读取核对。

举报、提交时正文快照及管理员处置见[内容举报](moderation.md#reports)；本人删除接口不提供恢复或读取证据副本的能力。

<a id="likes"></a>

## 点评与回复点赞

<a id="like-state"></a>

### 设置点赞状态

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| PUT | `/api/reviews/{id}/like` | Bearer | 200 |
| DELETE | `/api/reviews/{id}/like` | Bearer | 200 |
| PUT | `/api/review-replies/{id}/like` | Bearer | 200 |
| DELETE | `/api/review-replies/{id}/like` | Bearer | 200 |

**请求参数**

`PUT /api/reviews/{id}/like`、`DELETE /api/reviews/{id}/like`分别设置点评为已赞、未赞；回复使用`PUT /api/review-replies/{id}/like`、`DELETE /api/review-replies/{id}/like`。均需Bearer，无请求体，点赞者仅取当前身份。id为无前导零、不超过Long.MAX_VALUE的正整数字符串。

**响应**

成功200、`Cache-Control: no-store`，统一响应data：

```json
{"targetId":"101","count":1,"liked":true,"available":true}
```

**错误码**

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | LIKE_INVALID_INPUT | 目标ID、类型或批量ID列表非法。 |
| 401 | AUTH_UNAUTHENTICATED | 缺少或失效身份。 |
| 403 | LIKE_SELF_NOT_ALLOWED | 当前用户是被赞内容作者。 |
| 404 | LIKE_TARGET_NOT_FOUND | 点评或回复失效，或私有获赞目标不属于本人。 |
| 404 | RESTAURANT_NOT_FOUND | 写入时父店不存在或已删除。 |
| 503 | LIKE_TEMPORARILY_UNAVAILABLE | 完整事务重试耗尽。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖故障。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期错误，不模拟点赞成功。 |

**副作用与限制**

count为该内容当前点赞数，liked为本人当前状态。重复PUT不增加计数，重复DELETE不减少额外计数；首次DELETE也不创建关系。禁止自赞，管理员同样不能自赞；父店、点评或回复不存在/已删除时不可操作，停业店铺仍可操作。取消保留首次关系；重新点赞不重复通知和发奖。操作不改变商户评分、点评代表或搜索投影版本。

第一次点赞与作者通知、符合条件的点评获赞经验同事务提交；通知或奖励写入失败整体回滚。死锁/锁超时采用最多三次完整事务尝试，耗尽返回503；网络超时不代表未提交，应重读当前状态再继续操作。

<a id="like-counts"></a>

### 批量读取当前计数和本人状态

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/content-likes` | 公开，可携Bearer | 200 |

**请求参数**

`GET /api/content-likes?type=REVIEW&ids=101,102`公开，可携带Bearer取得本人状态。type仅REVIEW/REPLY；ids为1～50个逗号分隔的合法ID，最多1000字符，重复ID合并。成功200、no-store，data：

**响应**

```json
{"userId":"22","type":"REVIEW","records":[{"targetId":"101","count":1,"liked":true,"available":true},{"targetId":"102","count":0,"liked":false,"available":false}]}
```

匿名userId为null、liked为false。失效或父店不可见的目标返回available=false、count=0、liked=false，不泄露其实际计数。每批源目标、父店可见性和计数来自同一只读快照；无效Bearer仍401。前端同次挂载的点评和回复按钮按类型/身份合批，不为每张卡单独查询；状态不跨账号缓存。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="received-likes"></a>

### 本人当前获赞总数

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/received-likes` | Bearer | 200 |

**响应**

`GET /api/me/received-likes`需Bearer，无业务参数，成功200、no-store，data为`{"userId":"11","count":3}`。合计本人仍存在的点评和回复当前有效点赞；取消及内容硬删除后减少，删除点评关联删除的回复同样排除。不是历史累计次数；父店隐藏对个人合计的额外规则未扩展。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="like-target"></a>

### 获赞通知与精确内容定位

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/received-likes/{id}/target` | Bearer | 200 |

**响应**

点击先保存该条已读，再调用`GET /api/me/received-likes/{id}/target`，这里id为点赞关系ID。需Bearer且当前用户必须是被赞内容作者，管理员不扩大权限；取消点赞后仍可读取有效源内容。成功200、no-store，data示例：

```json
{"userId":"11","likeId":"501","targetType":"REPLY","targetId":"901","reviewId":"101","restaurantId":"26","author":{"id":"11","username":"回复作者"},"body":"被赞的回复正文","createdAt":"2026-11-07T09:00:00Z"}
```

**错误码**

按固定ID直接定位历史点评或任意分页位置回复，源内容和父店可见性每次重新检查。不存在、归属不符或内容失效404 LIKE_TARGET_NOT_FOUND，前端提示“内容已失效或不可访问”。原通知保留，不保存源正文或任意跳转URL。取消点赞保留通知及首次已读时间；重新点赞不重复通知。

**副作用与限制**

首次点赞给被赞内容作者发送一条LIKES消息。notificationType为REVIEW_LIKED或REPLY_LIKED，sourceId为点赞关系ID，reviewId为所属点评ID，actorUserId/actorUsername为点赞者身份和当前公开用户名，其他审核字段为null。unreadCounts新增LIKES，系统摘要独立取最新SYSTEM消息，包含审核结果、有效公告和举报结果；消息按17节规则单条或分类已读。

<a id="like-growth"></a>

### 获赞经验

只有本人点评获赞可能计奖，回复获赞不计经验。暂定每次+1、每作者每日最多+5；同一点赞者每天最多为同一作者贡献一次，同一点赞者对同一条点评最多奖励一次。奖励与日额度在作者账户锁内处理，业务日期取首次操作时刻的Asia/Shanghai自然日，完整事务重试沿用同一时刻。

额度不足时点赞及通知正常成功，只跳过经验。取消点赞或普通删除内容不撤销已发经验，取消后重赞不重新计奖。本轮不开放超限后补发、违规撤销后的额度返还或奖励资格恢复。参数可配置GROWTH_LIKE_EXPERIENCE、GROWTH_DAILY_LIKE_EXPERIENCE_LIMIT，均须为正整数；调参不重算旧奖励。
