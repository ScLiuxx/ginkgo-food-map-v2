# 消息、公告与实时提醒

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| GET | `/api/me/notifications` | Bearer | 200 | [查询收件箱与未读数](#list) |
| POST | `/api/me/notifications/{id}/read` | Bearer | 204 | [单条已读](#read) |
| POST | `/api/me/notifications/read-all` | Bearer | 204 | [全部已有消息已读](#read-all) |
| GET | `/api/me/review-replies/{id}/target` | Bearer | 200 | [本人收到的回复精确定位](#reply-target) |
| DELETE | `/api/me/notifications/{id}` | Bearer | 204 | [删除本人单条消息](#delete) |
| GET | `/api/me/notifications/unread` | Bearer | 200 | [实时未读红点](#unread-events) |
| GET | `/api/me/notifications/events` | Bearer | 200 SSE | [实时未读红点](#unread-events) |
| POST | `/api/admin/announcements` | 管理员 | 200 | [管理员全体公告](#admin-announcements) |
| GET | `/api/admin/announcements` | 管理员 | 200 | [管理员全体公告](#admin-announcements) |
| GET | `/api/admin/announcements/{id}` | 管理员 | 200 | [管理员全体公告](#admin-announcements) |
| POST | `/api/admin/announcements/{id}/withdraw` | 管理员 | 200 | [管理员全体公告](#admin-announcements) |
| GET | `/api/me/announcements/{id}` | Bearer | 200 | [用户公告详情及系统消息合并](#announcements) |

<a id="inbox"></a>

## 本人系统通知

通知列表、已读及删除接口均需有效Bearer，仅以当前身份作为收件人，返回`Cache-Control: no-store`；管理员不扩大收件箱权限。支持ALL（全部）、SYSTEM（审核结果及全体公告）、COMMENTS（点评回复）、LIKES（获赞）、FOLLOWERS（新增关注），普通用户不接受客户端发信或指定收件人；管理员全体公告入口见[管理员全体公告](#admin-announcements)。

<a id="list"></a>

### 查询收件箱与未读数

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/notifications` | Bearer | 200 |

**请求参数**

`GET /api/me/notifications?category=SYSTEM&page=1&pageSize=10`。category默认ALL，可选ALL/SYSTEM/COMMENTS/LIKES/FOLLOWERS；page默认1、范围1～1,000,000；pageSize默认20、范围1～50。额外userId、recipientUserId、offset、排序参数不能改变本人范围。按时间、来源内ID、消息类型倒序分页；列表、所选分类计数、各类未读数、最新系统摘要来自同一只读快照，浏览不标记已读。

**响应**

成功200，统一响应data示例：

```json
{"userId":"22","category":"COMMENTS","records":[{"id":"302","category":"COMMENTS","sourceId":"901","reviewId":"101","actorUserId":"33","actorUsername":"校园食友","requestId":null,"requestType":null,"decision":null,"restaurantName":null,"rejectReason":null,"createdAt":"2026-11-07T09:00:00Z","readAt":null}],"total":1,"unreadCount":1,"unreadCounts":{"SYSTEM":1,"COMMENTS":1,"LIKES":0,"FOLLOWERS":0},"latestSystem":{"id":"301","category":"SYSTEM","sourceId":"51","requestId":"51","reviewId":null,"actorUserId":null,"actorUsername":null,"requestType":"UPDATE","decision":"REJECTED","restaurantName":"银杏小馆","rejectReason":"请补充地址","createdAt":"2026-11-07T08:00:00Z","readAt":null},"page":1,"pageSize":10,"totalPages":1}
```

所有非空ID为字符串。total、unreadCount属于所选category；unreadCounts始终为本人SYSTEM/COMMENTS/LIKES/FOLLOWERS各自未读数，ALL为四者之和。latestSystem为本人最新系统消息，不受当前分类及页码限制，无系统消息为null；首页据此显示系统摘要，避免被最新评论替换。readAt为首次阅读时间，未读为null。无记录或越界页返回records=[]，保留实际计数；真正无消息时total、unreadCount、totalPages为0。故障不降级为空数据，跨次翻页不保证固定快照。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

SYSTEM中审核结果的sourceId=requestId，requestType为CREATE/UPDATE、decision为APPROVED/REJECTED，通过时rejectReason为null；reviewId/actorUserId/actorUsername为空。只保留审核时摘要，点击后固定进入`/account/contributions?requestId=51`，由本人申请详情重新检查归属及可访问性。

COMMENTS的sourceId是回复ID、reviewId是原点评ID、actorUserId是回复者ID，actorUsername批量读取当前公开用户名，缺失时显示“用户不可用”；审核字段为空。通知不保存点评或回复正文，不接受任意跳转URL。点击成功保存单条已读后，由[本人收到的回复精确定位](#reply-target)固定目标接口重新读取原文；失效内容保留通知并提示“内容已失效或不可访问”。

<a id="read"></a>

### 单条已读

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/me/notifications/{id}/read` | Bearer | 204 |

**响应**

`POST /api/me/notifications/{id}/read`，无请求体。普通通知id为1～Long.MAX_VALUE、无前导零的十进制字符串；公告使用`announcement:{公告ID}`（例如`announcement:12`），公告ID同样为正Long十进制。仅改变本人这一条消息的readAt，首次时间由数据库生成，重复操作不重写。成功204、无响应体；不存在与他人消息统一404，标记失败不模拟已读。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="read-all"></a>

### 全部已有消息已读

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/me/notifications/read-all` | Bearer | 204 |

**响应**

`POST /api/me/notifications/read-all?category=ALL`，无请求体；category默认ALL，可选ALL/SYSTEM/COMMENTS/LIKES/FOLLOWERS。SYSTEM处理审核消息及有效全体公告，COMMENTS只标回复消息，LIKES只标获赞消息，FOLLOWERS只标关注消息，ALL覆盖四类，其他分类400。成功204、无响应体，空收件箱也可调用，不删除消息或改变源业务。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

服务端按操作开始时数据库快照可见的对应分类未读消息分批更新，较大新ID和较小但迟提交的消息均保留未读。任一批失败整体回滚，重复操作保留首次阅读时间。

<a id="delivery-errors"></a>

### 自动发信与错误

管理员审核首次进入终态时发一条SYSTEM通知给原submitted_by；通过取最终店名，驳回取原提交店名及规范化原因。通知与审核、商户/相册/搜索任务及通过经验同事务，失败整体回滚。重复终态回执不重发，历史终态不补发。

首次发布回复时发一条COMMENTS通知给原点评作者，自回复跳过；历史点评上的新回复同样通知。通知与回复同事务，实际写入后失败也整体回滚；原请求UUID重放和完整事务重试不重复发送。不补发历史回复通知，既有审核消息及首次已读时间保留。

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 分类、分页或消息ID格式/范围不合法。 |
| 401 | AUTH_UNAUTHENTICATED | 缺少、失效凭证或账号/会话不可用。 |
| 404 | NOTIFICATION_NOT_FOUND | 消息不存在或不属于当前身份。 |
| 405 | COMMON_METHOD_NOT_ALLOWED | 已认证请求使用不支持的方法。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖暂不可用。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期查询/写入失败，不能伪装为空数据或成功已读。 |

消息删除见[删除本人单条消息](#delete)；关注通知见[新增关注消息](community.md#follow-notices)。全体公告后端见[管理员全体公告](#admin-announcements)、[用户公告详情及系统消息合并](#announcements)，实时刷新见[实时未读红点](#unread-events)；管理端公告页及用户系统消息已接入。

<a id="reply-target"></a>

### 本人收到的回复精确定位

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/review-replies/{id}/target` | Bearer | 200 |

**请求参数**

`GET /api/me/review-replies/{id}/target`需Bearer。id为具体回复ID，十进制正整数、无前导零、不超过Long.MAX_VALUE。仅原点评作者可读，管理员不绕过归属，额外userId/reviewId参数不能扩大范围。成功200、`Cache-Control: no-store`。

**响应**

统一响应data示例：

```json
{"userId":"22","restaurantId":"26","reviewId":"101","replyId":"901","reviewAuthor":{"id":"22","username":"点评作者"},"review":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"recommendation":"RECOMMENDED","body":"原点评正文","createdAt":"2026-11-01T06:00:00Z","scoringStatus":"history","photos":[]},"replyAuthor":{"id":"33","username":"校园食友"},"replyBody":"谢谢分享","replyCreatedAt":"2026-11-07T09:00:00Z"}
```

**错误码**

非法ID400 `REPLY_INVALID_INPUT`；回复/点评不存在或点评不属于本人404 `REPLY_REVIEW_NOT_FOUND`；店铺不存在或已删除404 `RESTAURANT_NOT_FOUND`。前端将404统一显示为“内容已失效或不可访问”。缺少/失效凭证401 `AUTH_UNAUTHENTICATED`，认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`，未预期读取失败500；已认证但方法不符405。源内容删除不删除通知，也不从消息恢复正文，再次读取重新检查可见性。

**副作用与限制**

review沿用[按作者分组分页](reviews.md#review-list)的currentReview结构，scoringStatus按读取时实际最新代表判断。直接按ID定位历史点评及任意位置回复，不逐页扫描；回复、点评、父店可见性和作者信息共用RR只读快照，停业店仍可读。接口不依赖通知是否已读，也不更改已读状态；弹窗仅展示当前所需原点评和高亮目标回复。

<a id="delete"></a>

### 删除本人单条消息

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| DELETE | `/api/me/notifications/{id}` | Bearer | 204 |

**响应**

`DELETE /api/me/notifications/{id}`，有效Bearer，无业务请求参数及请求体。普通通知id为1～Long.MAX_VALUE、无前导零的十进制字符串；公告传`announcement:{公告ID}`，详见[用户公告详情及系统消息合并](#announcements)。只能删除本人收件箱的任意单条已读或未读消息，管理员无代删权限。

**错误码**

首次成功及原收件人重复删除均204，无响应体，`Cache-Control: no-store`。非法ID400 `COMMON_VALIDATION_FAILED`；无有效身份401 `AUTH_UNAUTHENTICATED`；未知或他人消息（包含他人的已删除消息）统一404 `NOTIFICATION_NOT_FOUND`；认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`；未预期数据库失败500 `COMMON_INTERNAL_SERVER_ERROR`并整体回滚，不伪装成功。其他错误沿用[自动发信与错误](#delivery-errors)。

**副作用与限制**

删除后该消息不再出现在本人列表、总数、未读数及系统最新摘要中；最后一条系统消息删除后latestSystem为null。删除不等于已读，原readAt保持不变；对已删除消息单条已读返回404，全部已读也跳过它。查询响应契约不增加deletedAt，跨次查询仍不保证固定分页快照。

内部保留来源唯一键及原通知记录防重放，不提供恢复或重新投递接口；同一审核、回复、点赞或关注来源重试不会让消息重新出现。删除不操作原申请、点评、回复、点赞、关注或经验记录，原业务内容仍按自身权限可访问。

前端各分类列表提供二次确认；确认后只发一次DELETE。失败保留原通知和重试入口，删除成功后回第一页读取最新列表、未读数及摘要；读取失败仅重试GET。退出、换号、分类切换或卸载会取消在途请求并拒绝旧身份回执。传输取消或超时不保证服务器未提交，可刷新核对或安全重试。

<a id="unread-events"></a>

### 实时未读红点

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/notifications/unread` | Bearer | 200 |
| GET | `/api/me/notifications/events` | Bearer | 200 SSE |

**请求参数**

`GET /api/me/notifications/unread`：必须登录，仅查询当前用户。无参数；200响应示例：

**响应**

```json
{"success":true,"data":{"userId":"11","unreadCount":2}}
```

`GET /api/me/notifications/events`：必须通过Authorization Bearer请求头认证，不接受URL令牌或收件人参数。200为`text/event-stream`，禁止缓存和代理缓冲。事件`notifications-changed`表示事务已提交，请重新查询未读数；`sync`为首次握手/15秒心跳补齐提示。两者data均为`{}`，不含正文。回滚不发送；同一事务同一收件人合并。来源幂等重试可能产生无害刷新，不会重建通知。

**错误码**

连接最长5分钟，客户端退避重连并重新查询；每用户最多5连接、单实例最多1000连接，超额拒绝。每次发送前复核JWT到期、会话及当前账号，失败关闭。未登录/失效凭证401；基础设施认证异常503。连接期间异常表现为断流。仅当前实例广播，无事件存档或Last-Event-ID补发，数据库未读查询用于恢复最终状态。进入消息列表不自动已读。

<a id="admin-announcements"></a>

### 管理员全体公告

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/admin/announcements` | 管理员 | 200 |
| GET | `/api/admin/announcements` | 管理员 | 200 |
| GET | `/api/admin/announcements/{id}` | 管理员 | 200 |
| POST | `/api/admin/announcements/{id}/withdraw` | 管理员 | 200 |

**请求参数**

| 方法 | 路径 | 参数及结果 |
| --- | --- | --- |
| POST | `/api/admin/announcements` | JSON包含clientRequestId（UUID）、title、content；发布或同内容重试均200，返回Detail。 |
| GET | `/api/admin/announcements` | status默认ALL，可选ALL/PUBLISHED/WITHDRAWN；page默认1、范围1～1000000；pageSize默认20、范围1～50。200返回records、total、page、pageSize、totalPages。 |
| GET | `/api/admin/announcements/{id}` | 正Long十进制ID。200返回Detail，允许核对撤回历史。 |
| POST | `/api/admin/announcements/{id}/withdraw` | 无请求体。首次与重复撤回均200返回Detail，重复时保留首次撤回人及时间。 |

发布请求示例：

```json
{"clientRequestId":"12345678-1234-4234-8234-123456789abc","title":"校园服务通知","content":"本周服务安排如下。"}
```

标题/正文先统一CRLF/CR换行为LF并去除两端空白；按Unicode码点限制标题1～120、正文1～10000，禁止空白内容。发布身份及时间由服务端确定。按发布人+UUID去重，摘要覆盖规范化标题与正文：相同内容返回首次公告且replayed=true，不重复插入或广播；不同内容409。公告已撤回后的相同发布请求仍返回原撤回记录，不重新发布。不同管理员的同一UUID互不冲突。replayed只表示本次发布/撤回是否重放，GET返回false。

**响应**

Detail为统一响应的data：

```json
{"id":"12","title":"校园服务通知","content":"本周服务安排如下。","status":"PUBLISHED","publishedBy":"22","publishedAt":"2026-11-08T05:00:00Z","withdrawnBy":null,"withdrawnAt":null,"replayed":false}
```

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

以下接口均要求有效Bearer及当前数据库ROLE_ADMIN；返回`Cache-Control: no-store`。不提供定向接收、编辑、草稿、定时发布或到期接口，正文按纯文本保存，客户端不得将其作为HTML执行。

管理员列表按publishedAt/id倒序，计数与记录同一快照，越界返回空records及真实总数。撤回与个人已读/删除共用公告主行锁；任意管理员可撤回，首次撤回人/时间不可被重试覆盖。公告及用户状态保留，用户侧不显示撤回占位。发布和撤回事务提交后向本实例所有在线连接发送刷新提示；失败回滚不广播，不创建逐用户通知。

前端管理入口为`/admin/announcements`：单列表单填写标题和正文，确认后发布，成功进入公告记录。记录支持状态筛选、分页、详情及撤回确认。未知发布结果保留原UUID和正文再重试，写入成功但记录读取失败只重试GET；退出、换号或卸载清理私有草稿与迟到回执，不保存到浏览器存储。

<a id="announcements"></a>

### 用户公告详情及系统消息合并

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/announcements/{id}` | Bearer | 200 |

**响应**

`GET /api/me/announcements/{id}`：有效Bearer，id为正Long无前导零十进制。200返回[管理员全体公告](#admin-announcements)的Detail结构，仅允许仍发布且本人未删除的公告；GET不标记已读。撤回、本人删除或不存在统一404。管理员访问此用户接口同样受本人删除状态限制；管理员历史核对使用[管理员全体公告](#admin-announcements)管理接口。

[查询收件箱与未读数](#list)列表中的公告示例：

```json
{"id":"announcement:12","notificationType":"ANNOUNCEMENT","category":"SYSTEM","sourceId":"12","announcementId":"12","announcementTitle":"校园服务通知","requestId":null,"requestType":null,"decision":null,"restaurantName":null,"rejectReason":null,"reviewId":null,"actorUserId":null,"actorUsername":null,"createdAt":"2026-11-08T05:00:00Z","readAt":null}
```

普通通知ID保持原十进制字符串，新增announcementId/announcementTitle为null。公告标题不冒用店名或审核字段；列表不携带正文，详情按announcementId读取。前端需按notificationType分支及带前缀id作为列表键，不能将公告交给申请/回复定位接口。前端已识别ANNOUNCEMENT类型及带类型ID，用户在系统消息中打开纯文本公告详情。

**错误码**

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 字段、长度、ID、状态或分页非法。 |
| 400 | COMMON_MALFORMED_REQUEST | JSON或UUID解析失败。 |
| 401 | AUTH_UNAUTHENTICATED | 缺失或无效凭证。 |
| 403 | AUTH_FORBIDDEN | 普通用户访问公告管理或管理员角色已撤销。 |
| 404 | NOTIFICATION_NOT_FOUND | 公告不存在，或用户侧已删除/撤回。 |
| 409 | COMMON_DATA_CONFLICT | 同一发布人UUID被不同内容复用。 |
| 405 | COMMON_METHOD_NOT_ALLOWED | 已认证请求使用未提供的编辑等方法。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证基础设施暂时不可用。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 其他读写失败，事务回滚，不伪装成成功。 |

**副作用与限制**

有效全体公告对所有当前及后来注册用户可见，未生成个人状态时初始未读；系统/全部列表、分类总数、未读数、latestSystem及轻量未读查询统一包含公告。已撤回或本人删除后全部排除。列表只查询标题，不读取公告正文；未读查询只做来源计数。

公告已读和删除复用[单条已读](#read)、[删除本人单条消息](#delete)，将id传为`announcement:12`。首次及重复已读204，保留首次readAt；已删除或已撤回则404。首次及本人重复删除204，可直接删除未读公告而不生成readAt；已删除公告即使后来撤回仍可重试204，未删过的撤回公告404。删除回执长期保留，无恢复接口。其他用户的个人状态不受影响。

[全部已有消息已读](#read-all)的SYSTEM/ALL全部已读在同一RR快照中按500条分页选择初始可见未读公告；锁定后复核当前撤回和删除状态，不能恢复并发删除，也不能标记快照之后才提交的公告。整笔失败全部回滚。COMMENTS/LIKES/FOLLOWERS不处理公告。个人状态操作提交后只刷新当前用户的多标签页红点。

<a id="report-results"></a>

### 举报处理结果通知

举报被驳回或确认违规后，每位原举报者收到一条SYSTEM消息：`notificationType=REPORT_RESULT`，`sourceId`为该举报编号，`reviewId`为所属点评（回复举报填写父点评），`decision`为`DISMISSED`（未认定违规）或`REMOVED`（已删除内容），`category=SYSTEM`，其余审核字段为null。同一内容的多份举报分别通知各自举报者，不合并、不暴露其他举报者、处理人或内部备注。

举报结果计入SYSTEM分类总数、未读数和`latestSystem`；逐条已读、单条删除和分类全部已读与其他系统消息一致。重复驳回或重复删除不重复发送；通知与举报结案同事务提交，通知写入失败时结案回滚。点击通知只展示处理结果，不跳转到已删除内容。
