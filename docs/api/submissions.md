# 店铺投稿、本人申请与审核

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

业务阅读顺序：[新店／纠错提交](#create-and-change) → [新店附带点评](#attached-review) → [本人申请](#my-requests) → [管理员审核](#admin-review)。

**当前范围：附带点评已支持提交、本人详情／私有图片读取、待审修改／撤回、驳回后重提及审核通过后自动发布；管理员只审核店铺资料。管理员“店铺已收录”入口与本人处理结果已接入前端；联合提交表单及本人修改、撤回、重提页面仍待接入。**

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/api/restaurants/{id}/change-requests` | Bearer | 201 | [用户商户申请](#create-and-change) |
| POST | `/api/restaurant-submissions` | Bearer | 201 | [用户商户申请](#create-and-change) |
| GET | `/api/me/restaurant-requests` | Bearer | 200 | [本人列表](#my-list) |
| GET | `/api/me/restaurant-requests/{id}` | Bearer | 200 | [本人详情](#my-detail) |
| PUT | `/api/me/restaurant-requests/{id}` | Bearer | 200 | [修改待审申请](#my-edit) |
| POST | `/api/me/restaurant-requests/{id}/withdraw` | Bearer | 200 | [撤回待审申请](#my-withdraw) |
| POST | `/api/me/restaurant-requests/{id}/resubmit` | Bearer | 201 | [驳回后重提](#my-resubmit) |
| GET | `/api/me/restaurant-requests/{id}/photos/{position}` | Bearer | 200 JPEG | [本人申请图片与错误](#my-photos-errors) |
| GET | `/api/me/restaurant-requests/{id}/review/photos/{position}` | Bearer，仅作者 | 200 JPEG | [本人附带点评图片](#my-review-photos) |
| GET | `/api/admin/restaurant-requests` | 管理员 | 200 | [申请分页](#admin-list) |
| GET | `/api/admin/restaurant-requests/{id}` | 管理员 | 200 | [申请详情](#admin-detail) |
| GET | `/api/admin/restaurant-requests/{id}/photos/{position}` | 管理员 | 200 JPEG | [申请私有图片](#admin-photos) |
| POST | `/api/admin/restaurant-requests/{id}/approve` | 管理员 | 200 | [审核通过并发布商户资料及图片](#approve) |
| POST | `/api/admin/restaurant-requests/{id}/link-existing` | 管理员 | 200 | [店铺已收录](#link-existing) |
| POST | `/api/admin/restaurant-requests/{id}/reject` | 管理员 | 200 | [驳回申请](#reject) |

<a id="create-and-change"></a>

## 用户商户申请

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/restaurants/{id}/change-requests` | Bearer | 201 |
| POST | `/api/restaurant-submissions` | Bearer | 201 |

**请求参数**

`POST /api/restaurant-submissions`提交新商户；`POST /api/restaurants/{id}/change-requests`对现有未删除商户提交纠错。两者需Bearer，普通用户可用；纠错不要求是商户创建者。

```json
{"clientRequestId":"2bd0946c-291c-49af-8670-319ca4f64f5d","name":"商户名称","categoryCode":"HOT_POT","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"supportsDineIn":true,"supportsDelivery":null,"address":"商户地址","latitude":30.670582,"longitude":104.107260,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[],"photoIds":[]}
```

| 字段 | 规则 |
| --- | --- |
| `clientRequestId` | 必填UUID，同次失败重试沿用 |
| `name` | 去首尾Unicode空白后1–40码点，同名分店允许 |
| `categoryCode` | REGULAR时必填有效分类，必须存在且可用；CANTEEN时必须为null或省略，不接受空字符串或非空分类 |
| `restaurantType` | REGULAR/CANTEEN，默认REGULAR |
| `priceKind` / `pricePerPerson` | 默认UNKNOWN/null；EXACT或ESTIMATE时为1–5000的整数元，UNKNOWN必须null |
| `address` | 最多80码点，空白转null |
| `latitude` / `longitude` | 必填GCJ-02，纬度3–54、经度73–136，最多6位有效小数 |
| `locationConfirmed` | 必须true |
| `supportsDineIn` / `supportsDelivery` | true/false/null，null表示未知 |
| `businessStatus` | OPERATING/CLOSED，默认OPERATING |
| `tags` | 默认空，最多8项，不能null；规范化去重后总长最多80码点 |
| `photoIds` | 默认空，最多5个唯一正Long字符串，按顺序展示，必须本人有效的RESTAURANT用途临时文件 |
| `review` | 新店提交可选，省略或null为仅提交店铺；仅CREATE普通餐厅可附带。完整字段见[新店附带点评](#attached-review)；[修改](#my-edit)与[重提](#my-resubmit)也接受该字段，但省略或null表示保留／沿用已有点评。纠错提交、管理员审核不接受此字段 |

**响应**

不可指定作者、商户ID、新建/纠错类型、审核人/状态、顶层评分及未知字段；未知字段400 `COMMON_MALFORMED_REQUEST`。服务端决定CREATE/UPDATE类型。仅提交店铺时成功201、禁止缓存，data为`{"id":"51","status":"PENDING"}`，只是申请受理，不发布商户或覆盖公开资料；附带点评时额外返回[新店附带点评](#attached-review)所述回执。

**错误码**

字段无效400 `RESTAURANT_INVALID_INPUT`或通用校验错误；分类不可用400 `RESTAURANT_CATEGORY_UNAVAILABLE`；纠错目标不存在/已删除404 `RESTAURANT_NOT_FOUND`；图片不可绑定409 `FILE_NOT_BINDABLE`。申请、图片绑定及顺序关联同事务保存，任一失败整体回滚。

**副作用与限制**

同账号同UUID与原始规范化内容重试返回原回执，图片顺序也参与一致性判断。不同内容/目标/申请类型复用编号409 `RESTAURANT_STATE_CONFLICT`；新建通过后回填商户ID不被当作原目标变化。原始资料摘要不随管理员修订而更新。回执中的PENDING代表首次受理，不用于查询最新审核状态。

已有带分类的旧食堂申请仍可按原请求内容重试，不改写原快照或摘要；新请求不得为食堂填写美食分类。

<a id="attached-review"></a>

### 新店附带点评

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/restaurant-submissions` | Bearer | 201 |

**请求参数**

`POST /api/restaurant-submissions`，有效Bearer、普通用户可用、`Content-Type: application/json`。在上述店铺字段外，可提供可选的`review`对象。只允许普通餐厅REGULAR附带点评；食堂整体不接受点评，返回400 `RESTAURANT_INVALID_INPUT`，应在窗口存在后评价具体窗口。普通餐厅沿用停业可评规则，businessStatus=CLOSED不排除此能力。

```json
{
  "clientRequestId":"2bd0946c-291c-49af-8670-319ca4f64f5d",
  "name":"校园面馆",
  "categoryCode":"FAST_FOOD",
  "restaurantType":"REGULAR",
  "priceKind":"UNKNOWN",
  "latitude":30.7544,
  "longitude":103.9312,
  "locationConfirmed":true,
  "businessStatus":"OPERATING",
  "photoIds":["501"],
  "review":{
    "scores":{"taste":8,"value":7.5,"service":7,"ambience":8},
    "recommendation":"NEUTRAL",
    "body":"这次出餐很快，分量也比较足。",
    "photoIds":["601","602"]
  }
}
```

| review字段 | 规则 |
| --- | --- |
| `scores` | 必填完整taste/value/service/ambience；十分制0–10，步进0.5，与正式点评相同，不预先舍入非法精度 |
| `recommendation` | 必填精确字符串RECOMMENDED／NEUTRAL／NOT_RECOMMENDED，无默认值；布尔值、数字、大小写或空白别名非法 |
| `body` | 必填；去Unicode首尾空白后非空、最多500码点，纯文本 |
| `photoIds` | 默认空，不能null；最多5个唯一正Long字符串，按原顺序保存，必须为本人未过期TEMPORARY状态的REVIEW用途文件 |

**响应**

首次及同内容重放均201、`Cache-Control: no-store`，统一封装的data为：

```json
{"id":"51","status":"PENDING","reviewSubmission":{"id":"71","status":"WAITING"}}
```

id为新店申请ID，reviewSubmission.id为私有待发布点评ID，不是正式点评ID。省略review或传null时仍返回原`{id,status}`，不生成待发布点评。PENDING表示原提交已受理，不表示查询到了最新审核状态。首次附带点评为WAITING；在审核发布后用原完整请求重试，reviewSubmission.status可为PUBLISHED，不重新绑定文件或恢复待发布内容；最新申请状态及正式点评ID应读取[本人详情](#my-detail)。

**错误码**

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `REVIEW_INVALID_INPUT` | 点评缺少评分/态度/正文、非法半分或图片数量、重复/非法图片ID |
| 400 | `COMMON_MALFORMED_REQUEST` | 非法JSON、错误态度类型/编码、未知或越权字段 |
| 400 | `RESTAURANT_INVALID_INPUT`等 | 店铺字段或分类规则错误；食堂整体附带点评 |
| 401 | `AUTH_UNAUTHENTICATED` | 未登录、会话或账号无效 |
| 409 | `RESTAURANT_STATE_CONFLICT` | 相同UUID对应不同店铺或点评内容，或改变是否附带点评 |
| 409 | `REVIEW_REQUEST_CONFLICT` | 已存在待发布点评回执与本次作者、原申请或首次内容不一致 |
| 409 | `FILE_NOT_BINDABLE` | 点评或店铺图片不存在、已过期、已绑定、用途或归属不符 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 未预期写入失败，整体回滚，不返回部分成功 |

**副作用与限制**

外层photoIds为店铺申请图，使用RESTAURANT用途；review.photoIds为点评图，使用REVIEW用途。两组各最多5张，不能交叉绑定或复用已绑定文件。嵌套对象不接受clientRequestId、作者、店铺/窗口ID、状态、正式发布UUID、照片URL或其他未知字段；scores也拒绝额外维度。点评提交请求UUID沿用外层clientRequestId，作者由认证身份确定，独立正式发布UUID由服务端首次生成并在重试时保留。

店铺申请、店铺图片绑定、待发布点评及其图片绑定在同一数据库事务中保存；任一校验、绑定或关联写入失败均整体回滚。提交期间不创建正式店铺或正式点评，不计分、不计奖、不发审核通知，也不生成搜索同步任务；不访问云存储或复制图片。点评正文独立保存，不写入申请的proposed资料快照。

同账号、同外层UUID及同规范化联合内容重放返回原申请和点评回执，不重新绑定BOUND文件。联合摘要覆盖店铺资料及规范化点评评分、态度、正文和有序图片ID；改变其中任一内容、图片顺序，或在附带／不附带点评之间切换，均不能复用原UUID。只提交店铺继续沿用旧摘要算法，旧成功请求可通过新接口确认回执。请求超时后应保留原UUID重试，而非自动生成新UUID。

已接通联合提交、[本人详情](#my-detail)、[附带点评私有图片](#my-review-photos)、[待审修改](#my-edit)、[撤回](#my-withdraw)、[驳回后重提](#my-resubmit)及[审核通过自动发布](#approve-attached-review)。驳回保留WAITING内容，撤回转为WITHDRAWN，重提将同一点评迁移至新申请；首次提交在点评迁移后重试仍确认原点评回执，不能在原申请下重建副本。本人操作先核对归属，他人申请为404。管理员只审核店铺资料，不提供待发布点评内容或图片的管理入口；管理员通过请求及纠错提交仍拒绝额外review字段。前端入口尚未接通。

<a id="my-requests"></a>

## 本人投稿与纠错记录

本节接口均需Bearer，仅操作当前认证身份提交的申请，返回`Cache-Control: no-store`。不复用管理员查询或审核权限，也不接受userId/submittedBy选择其他申请人；额外身份、offset或排序参数不会改变服务端范围。本人只能修改或撤回待审申请，驳回后可基于原申请创建新申请；管理员队列、审核结果和公开商户资料不受这些操作直接影响。

列表仍只返回店铺申请摘要，详情额外返回本人的附带点评。修改和重提接受可选review，省略或null保留已有内容；撤回同时终止待发布点评。具体状态、图片和幂等规则见各写入接口。

<a id="my-list"></a>

### 本人列表

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/restaurant-requests` | Bearer | 200 |

**请求参数**

`GET /api/me/restaurant-requests`参数：status默认ALL，可选ALL/PENDING/APPROVED/REJECTED/WITHDRAWN；page默认1、范围1–1000000；pageSize默认20、范围1–50。前端显式传入pageSize，默认10，用户可选10／20／50；更改条数时page回到1，筛选、翻页和失败重试沿用所选条数，仅在当前页面会话内保留。按createdAt、id依次降序，先按本人和所选状态筛选再分页；同一店铺的不同申请独立展示，不按店铺合并。

**响应**

成功200，统一封装的data示例：

```json
{"userId":"11","records":[{"id":"101","restaurantId":null,"previousRequestId":null,"requestType":"CREATE","status":"PENDING","name":"新店名","createdAt":"2026-11-07T05:00:00Z","reviewedAt":null,"withdrawnAt":null,"photoCount":0}],"counts":{"PENDING":1,"APPROVED":0,"REJECTED":0,"WITHDRAWN":0,"ALL":1},"total":1,"totalPages":1,"page":1,"pageSize":20}
```

userId为本人字符串ID；requestType为CREATE新店或UPDATE纠错，不因新店通过后回填restaurantId而改变。previousRequestId仅在驳回后重提的新申请上有值，指向原驳回申请；withdrawnAt仅在本人撤回后有值。name为原提交店名，photoCount为原申请关联图数；待审reviewedAt为null。counts始终为本人全部申请的状态计数，不受当前status筛选影响；total为所选状态总数。真正无记录返回空records、零计数及totalPages=0；超出末页也可返回空records，但保留实际总数。列表和计数共用一次数据库快照，跨次查询时新增申请或审核状态变化可能改变分页。

列表及详情的application另含`approvalMode`：CREATED创建新店、UPDATED更新资料、LINKED_EXISTING为[店铺已收录](#link-existing)；未通过或兼容历史记录为空。已收录时restaurantId为已有店铺ID，name仍保留原投稿名称。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="my-detail"></a>

### 本人详情

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/restaurant-requests/{id}` | Bearer | 200 |

**响应**

`GET /api/me/restaurant-requests/{id}`成功200，data为：

```json
{"userId":"11","application":{"id":"101","restaurantId":null,"previousRequestId":null,"requestType":"CREATE","status":"PENDING","name":"新店名","createdAt":"2026-11-07T05:00:00Z","reviewedAt":null,"withdrawnAt":null,"photoCount":1},"proposed":{"name":"新店名","categoryCode":"OTHER","categoryName":"其他美食","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","supportsDineIn":null,"supportsDelivery":null,"tags":[]},"approved":null,"rejectReason":null,"targetAvailable":false,"photoPositions":[0],"resubmittedRequestId":null,"reviewSubmission":null}
```

application字段同列表；proposed为原始提交业务快照，approved只在通过后返回实际生效业务快照（可包含管理员修订），否则null。两份快照均只包含示例中的业务字段，不含原始请求UUID、photoIds及旧内部字段；历史缺失字段可为null。rejectReason仅驳回有值；审核时间位于application.reviewedAt。不会返回审核人、纠错前before、管理端current资料、密码、邮箱、请求摘要或存储键。

`resubmittedRequestId`为基于本申请重提生成的后续申请字符串ID，没有则null；与新申请的application.previousRequestId对应。重提将同一条待发布点评迁移到新申请，原申请reviewSubmission变为null，原点评图片入口404；原店铺快照、驳回原因和店铺附图保留，可凭resubmittedRequestId查看后续内容，不另存一份旧点评正文快照。

详情另含`reviewSubmission`：没有本人附带点评时为null，附带时示例为：

```json
{
  "id":"71",
  "status":"WAITING",
  "scores":{"taste":8,"value":7.5,"service":7,"ambience":8},
  "recommendation":"NEUTRAL",
  "body":"这次出餐很快，分量也比较足。",
  "photoPositions":[0,1],
  "publishedReviewId":null
}
```

| reviewSubmission字段 | 含义 |
| --- | --- |
| `id` | 待发布点评字符串ID，不是正式点评ID |
| `status` | WAITING／WITHDRAWN／PUBLISHED；提交生成WAITING，驳回保留WAITING，撤回转为WITHDRAWN，审核通过后变为PUBLISHED |
| `scores` / `recommendation` / `body` | WAITING或WITHDRAWN返回本人原内容；四维十分制、三态态度、纯文本规则同提交。PUBLISHED均为null，不回查正式点评正文 |
| `photoPositions` | WAITING或WITHDRAWN的有序位置0–4，无图为[]；PUBLISHED固定[]，不恢复残留私有图片关联 |
| `publishedReviewId` | 仅PUBLISHED为正式点评字符串ID，其余null；保留回执不代表正式点评仍存在或可见 |

申请与点评在同一只读快照内读取。点评额外按当前认证作者过滤，不接受authorUserId等身份参数；不返回文件ID、原始／发布UUID或摘要。店铺和点评的photoPositions分别对应各自图片入口。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

photoPositions是原申请内0–4的有序位置，不是文件ID；真正无图返回[]。targetAvailable表示关联店铺在本次读取时未删除；停业仍为true，新店尚未通过或关联目标失效为false。目标被删除不删除或隐藏本人申请快照；不可用时前端不提供可用店铺跳转。不存在与其他人的申请均404 `RESTAURANT_REQUEST_NOT_FOUND`，管理员通过本接口也不能读取别人的申请。

<a id="my-edit"></a>

### 修改待审申请

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| PUT | `/api/me/restaurant-requests/{id}` | Bearer | 200 |

**请求参数**

`PUT /api/me/restaurant-requests/{id}`请求体为完整业务内容，整体替换原提交：

```json
{"name":"修改后的店名","categoryCode":"OTHER","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","supportsDineIn":null,"supportsDelivery":null,"tags":[],"photoIds":["1001","1002"]}
```

可选`review`字段规则见[新店附带点评](#attached-review)：省略或null保留已有点评；提供对象时须为完整scores、recommendation、body及可选photoIds，整体更新点评。仅CREATE普通餐厅允许，可为原本未附带点评的待审新店首次添加；UPDATE纠错不允许附带点评。已有点评时不能将餐厅改为CANTEEN，不能用null删除整条点评；review.photoIds空数组仅表示移除点评附图。

**响应**

成功200，data为`{"id":"101","status":"PENDING"}`。相同完整内容重复提交结果一致；附图替换只释放新列表不再保留的旧图，仍保留的图片继续使用原绑定，新图与申请内容在同一事务内更新，任一步失败全部回滚。

店铺内容、店铺图片、点评内容及点评图片共用一个事务。点评图片可保留本记录原有的BOUND图片并调整顺序；新增图片必须为本人有效的REVIEW用途TEMPORARY文件。被移除的原点评图转为PENDING_DELETE，交由已有清理任务删除；原关联移除，私有读取不再可用，不在本请求内访问云存储。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

字段校验与首次投稿一致；`photoIds`为本人的0–5张RESTAURANT用途图片，按顺序整体替换，空数组表示不再附图。只允许PENDING申请；修改后仍为PENDING并重新等待审核，不产生新申请、不保留修改前的待审快照。关联目标已删除的UPDATE申请返回404；已通过、已驳回或已撤回返回409 `RESTAURANT_STATE_CONFLICT`。修改会更新原申请的业务快照与请求摘要，原clientRequestId不变；再次用旧内容重试首次投稿会因摘要不一致返回409。

<a id="my-withdraw"></a>

### 撤回待审申请

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/me/restaurant-requests/{id}/withdraw` | Bearer | 200 |

**响应**

`POST /api/me/restaurant-requests/{id}/withdraw`无请求体，成功200，data为`{"id":"101","status":"WITHDRAWN"}`。仅PENDING可撤回；撤回写入withdrawnAt，不写审核人、通过快照或驳回原因，申请从管理员待审队列移除，原店铺附图仍仅本人和管理员可读。存在附带点评时，同事务将WAITING改为WITHDRAWN并记录撤回时间，保留内容和BOUND点评图供本人读取，管理员仍不能查看点评。不发布、不计分、不计奖，也不生成审核通知。

重复撤回同一申请返回相同成功结果，不改首次撤回时间；已通过或已驳回返回409 `RESTAURANT_STATE_CONFLICT`。任一步失败整体回滚，不能只撤回店铺或点评。撤回不可撤销，也不能调用驳回重提入口恢复；需重新投稿应走正常提交，使用新UUID及新上传的图片。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="my-resubmit"></a>

### 驳回后重提

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/me/restaurant-requests/{id}/resubmit` | Bearer | 201 |

**请求参数**

`POST /api/me/restaurant-requests/{id}/resubmit`请求体为首次投稿的完整店铺字段，必须携带新的`clientRequestId`；失败重试沿用本次新编号：

```json
{"clientRequestId":"2f8c0d62-7f4e-4f0e-9aa1-52f6b48b4e21","name":"重提修订店名","categoryCode":"OTHER","restaurantType":"REGULAR","priceKind":"UNKNOWN","address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[],"photoIds":[]}
```

可选review：省略或null沿用原附带点评；提供完整对象则先更新再迁移，字段规则同[待审修改](#my-edit)。原本无点评的CREATE普通餐厅可在本次首次添加；UPDATE纠错不能提供review。已有点评时不能改为CANTEEN。

**响应**

成功201，data为`{"id":"106","status":"PENDING"}`，不另返回reviewSubmission；点评及当前审核状态由[本人详情](#my-detail)查询。重试响应中的PENDING仅为首次受理回执，子申请之后已通过、已驳回或已撤回也不改变此回执。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

仅本人REJECTED申请可重提；新申请继承原申请的CREATE/UPDATE类型和UPDATE目标，写入previousRequestId指向原驳回申请，状态为PENDING。原申请保持REJECTED、原店铺快照及原rejectReason，不重新进入待审，也不改审核字段。UPDATE目标已删除返回404；PENDING、APPROVED或WITHDRAWN返回409。

每条被驳回申请最多生成一条后续申请：相同clientRequestId及相同规范化内容重试返回该申请ID，不迁回或再次覆盖点评；即使子申请已审核或又被重提也只确认原回执。不同UUID再次基于同一原申请重提、相同UUID改变内容／目标，或子申请已编辑导致摘要不同，返回409 `RESTAURANT_STATE_CONFLICT`。若子申请再次被驳回，应基于子申请重提，不能从祖先申请另建分支。重试须保持原先是否提供review的形式，不能将省略改为显式对象；省略和null等价。

点评采用迁移同一记录，不复制内容；保留点评ID、原作者、最初申请ID、首次点评UUID／摘要和独立发布UUID。点评仍为WAITING，原本人详情通过resubmittedRequestId指向新申请；仅新申请返回当前点评与图片。新申请、两类图片绑定／替换、点评更新及迁移同事务，任一步失败原申请与点评完整保留，不发布或发奖。

原店铺附图仍绑定在原申请上，重提店铺图需重新上传；继承的点评图继续使用原BOUND文件，无需重传。显式替换点评图时沿用修改规则：可保留当前图、新图必须为本人有效REVIEW临时文件、移除图进入清理流程。

<a id="my-photos-errors"></a>

### 本人申请图片与错误

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/restaurant-requests/{id}/photos/{position}` | Bearer | 200 JPEG |

**响应**

`GET /api/me/restaurant-requests/{id}/photos/{position}`按申请归属、关联位置及文件上传者/RESTAURANT用途/BOUND状态校验。待审、驳回、已撤回及已通过的本人申请均可读原附图，不因读取而公开图片。成功200为原始JPEG字节，另含`X-Content-Type-Options: nosniff`；前端携带固定Bearer取得Blob，不使用匿名img或云存储签名地址。云存储读取不占数据库事务。

**错误码**

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 状态、分页或修改/重提字段不合法。 |
| 400 | RESTAURANT_INVALID_ID | 申请ID不是无前导零、未溢出的正整数字符串。 |
| 400 | RESTAURANT_INVALID_INPUT | 图片位置不是0–4的单个数字；修改内容或图片ID重复/越界。 |
| 400 | REVIEW_INVALID_INPUT | 修改或重提的点评缺少完整评分／态度／正文，非法半分、正文或点评图片ID。 |
| 400 | COMMON_MALFORMED_REQUEST | 非法JSON、未知字段，或review内越权字段与非法态度类型。 |
| 401 | AUTH_UNAUTHENTICATED | 缺少/失效凭证、账号不可用或会话失效。 |
| 404 | RESTAURANT_REQUEST_NOT_FOUND | 申请不存在或不属于本人；修改、撤回、重提和图片入口同样先校验归属。 |
| 404 | RESTAURANT_NOT_FOUND | 修改或重提的UPDATE目标店铺已删除。 |
| 404 | FILE_NOT_FOUND | 本人申请无对应图片位置，或文件归属、用途、绑定状态不符。 |
| 409 | RESTAURANT_STATE_CONFLICT | 修改/撤回/重提的状态不允许，重提UUID对应内容冲突，或原申请已有不同编号的后续申请。 |
| 409 | REVIEW_SUBMISSION_STATE_CONFLICT | 申请类型、餐厅类型、点评作者或WAITING状态不符合操作条件；纠错重提显式提供review为400 RESTAURANT_INVALID_INPUT。 |
| 409 | FILE_NOT_BINDABLE | 新增店铺／点评图过期、已绑定或用途／上传者不符，或保留的点评图已不再是合法BOUND文件。 |
| 503 | FILE_STORAGE_UNAVAILABLE | 图片存储暂不可用，可只重试单图。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖暂不可用，保留凭证供重试。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期查询或写入错误，不降级为空列表、空详情或成功。 |

**副作用与限制**

已通过认证但方法不匹配返回405 `COMMON_METHOD_NOT_ALLOWED`。GET不改变审核状态、已读状态、经验、商户资料或图片绑定状态；PUT、撤回和重提只影响本人申请及其私有附图，不直接发布或修改公开商户资料。

<a id="my-review-photos"></a>

### 本人附带点评图片

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/restaurant-requests/{id}/review/photos/{position}` | Bearer，仅申请提交人且为点评作者；管理员无例外 | 200 JPEG |

**请求参数**

id为无前导零、未溢出的正Long字符串申请ID，position严格为0–4的单个数字。无请求体，不接受指定作者或文件ID。

**响应**

返回原始`image/jpeg`字节，不使用JSON封装；包含`Cache-Control: no-store`及`X-Content-Type-Options: nosniff`。前端需携带Bearer获取Blob，不返回云存储对象键或签名URL。读取不改变申请、点评、图片、评分、奖励或通知，云存储读取不占数据库事务。

**错误码**

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `RESTAURANT_INVALID_ID` | 非法申请ID |
| 400 | `RESTAURANT_INVALID_INPUT` | 本人申请的图片位置非法 |
| 401 | `AUTH_UNAUTHENTICATED` | 未登录或凭证失效 |
| 404 | `RESTAURANT_REQUEST_NOT_FOUND` | 申请不存在或不属于本人，先于位置及点评查询校验；管理员读取他人申请也相同 |
| 404 | `FILE_NOT_FOUND` | 无本人附带点评或位置、点评已发布、文件缺失，或上传者／REVIEW用途／BOUND状态不符 |
| 503 | `FILE_STORAGE_UNAVAILABLE` | 存储不可用，可重试单图 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 查询异常，不降级为无点评或成功 |

认证依赖及通用错误见[通用错误](common.md#errors)。

**副作用与限制**

仅WAITING、WITHDRAWN内容允许读取；已发布记录即使残留图片关联也返回404。重提后原申请的点评图入口404，改用新申请ID读取；被修改移除的点评图也不再可读。文件必须仍为当前作者上传的REVIEW用途BOUND文件，不按临时上传有效期判断BOUND图片。管理员审核详情、列表及店铺图片接口均不增加待发布点评查看能力。

<a id="admin-review"></a>

## 管理员商户审核

以下接口均需有效Bearer及数据库当前`ROLE_ADMIN`，每次请求重新校验权限，成功响应均为`Cache-Control:no-store`。GET接口只读；创建／更新通过时发布正式资料及店铺相册，“店铺已收录”只关联目标并发布附带点评；原店铺投稿图片关联保留。

本人及管理员的申请列表／详情中，申请对象新增`approvalMode`；审核回执同样返回该字段。取值`CREATED`（创建新店）、`UPDATED`（更新资料）、`LINKED_EXISTING`（店铺已收录）。未通过申请为null；迁移将历史已通过记录按原申请类型填为CREATED或UPDATED，兼容历史导入记录的null。requestType仍为原CREATE或UPDATE，不随处理方式改变。

附带待发布点评的新店申请已开放[联合提交](#attached-review)、本人读取、[审核通过自动发布](#approve-attached-review)及[驳回](#reject)。管理员只审核店铺资料，不提供待发布点评内容或图片读取。

<a id="admin-list"></a>

### 申请分页

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/admin/restaurant-requests` | 管理员 | 200 |

**请求参数**

`GET /api/admin/restaurant-requests`

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| `status` | `PENDING` | PENDING / APPROVED / REJECTED，精确匹配 |
| `keyword` | 空 | 最长80字符，去首尾空白；商户申请名称包含匹配或完整申请ID匹配，`%`、`_`按字面量匹配 |
| `page` | 1 | 1–1000000 |
| `pageSize` | 20 | 1–50 |

**响应**

按`createdAt,id`升序，最早提交优先。成功200，`data`示例：

```json
{
  "records":[{"id":"51","requestType":"UPDATE","status":"PENDING","name":"商户名称","restaurantId":"101","previousRequestId":"48","submittedBy":"22","submitterName":"同学甲","createdAt":"2026-11-03T06:00:00Z","photoCount":1}],
  "total":1,"totalPages":1,"page":1,"pageSize":20,
  "counts":{"PENDING":1,"APPROVED":0,"REJECTED":0}
}
```

`counts`为相同关键字条件下三个状态的数量，不受当前状态及分页限制；`total`为所选状态的完整数量。本人撤回的WITHDRAWN申请不进入管理员队列和状态计数，但管理员仍可按ID读取其详情；`previousRequestId`仅驳回后重提的新申请有值，指向原驳回申请。空结果200、空records、total/totalPages为0；越界页保留请求页码并返回空记录。分页、计数和批量署名在同一个只读快照内读取。署名缺失显示“用户不可用”，不返回邮箱、密码、请求摘要或云存储对象键。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="admin-detail"></a>

### 申请详情

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/admin/restaurant-requests/{id}` | 管理员 | 200 |

**请求参数**

`GET /api/admin/restaurant-requests/{id}`，无请求体/查询参数。成功200，`data`：

**响应**

| 字段 | 含义 |
| --- | --- |
| `application` | 与列表单条记录相同 |
| `proposed` | 用户原始业务资料白名单，字段见[用户商户申请](#create-and-change)，附categoryName；不含clientRequestId或文件存储信息 |
| `current` | 当前正式商户业务资料与有序标签；没有正式目标时null，不包含评分统计或内部归属信息 |
| `targetDeleted` | 目标商户是否已软删除；管理员可核对其资料，不使其公开 |
| `photos` | 有序图片引用`[{"position":0,"url":"/api/admin/restaurant-requests/51/photos/0"}]`，不读取云存储 |
| `publishedPhotos` | 本次申请实际发布的正式相册图片`[{id,position,cover,url}]`；待审、驳回、历史未发布为空数组；原photos仍保留私有核对 |
| `reviewedBy` / `reviewedAt` | 审核人字符串ID/时间，未处理为null |
| `rejectReason` | 驳回原因，未驳回为null |
| `approved` / `before` | 实际通过资料与纠错前快照白名单，没有时null |

**错误码**

同一次请求的申请、当前商户和标签处于同一只读快照。资料中的标签缺省可为null，前端按未知/空态展示，不补造已核实信息。ID非法400 `RESTAURANT_INVALID_ID`；申请不存在404 `RESTAURANT_REQUEST_NOT_FOUND`；参数校验失败400 `COMMON_VALIDATION_FAILED`。

<a id="admin-photos"></a>

### 申请私有图片

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/admin/restaurant-requests/{id}/photos/{position}` | 管理员 | 200 JPEG |

**响应**

`GET /api/admin/restaurant-requests/{id}/photos/{position}`，position严格为0–4。成功200、原始`image/jpeg`字节、`Cache-Control:no-store`及`X-Content-Type-Options:nosniff`，不使用JSON封装。

**错误码**

错误：非法位置400 `RESTAURANT_INVALID_INPUT`；缺失申请404 `RESTAURANT_REQUEST_NOT_FOUND`；无对应位置、文件已清理、用途/归属/绑定状态不符404 `FILE_NOT_FOUND`；存储不可用503 `FILE_STORAGE_UNAVAILABLE`。不得用该接口按任意文件ID读取图片，也不返回云存储签名地址。

**副作用与限制**

先核对申请及有序图片关联，再验证文件为申请提交人的RESTAURANT用途BOUND文件，最后读取私有云存储。浏览器必须携带Bearer取得Blob后展示，不能直接依赖无认证img请求。

<a id="approve"></a>

### 审核通过并发布商户资料及图片

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/admin/restaurant-requests/{id}/approve` | 管理员 | 200 |

**请求参数**

`POST /api/admin/restaurant-requests/{id}/approve`。请求为完整可维护业务资料，不是PATCH：

```json
{"name":"银杏面馆","categoryCode":"OTHER","restaurantType":"REGULAR","supportsDineIn":true,"supportsDelivery":null,"priceKind":"EXACT","pricePerPerson":20,"address":"商户地址","latitude":30.7544,"longitude":103.9312,"locationConfirmed":true,"businessStatus":"OPERATING","tags":["面食"]}
```

基础字段校验与[直接维护餐厅](restaurants.md#management)直接维护一致；审核另接收restaurantType（REGULAR普通餐厅/CANTEEN食堂，省略默认REGULAR，显式null非法）、supportsDineIn和supportsDelivery（true支持、false不支持、null未知，省略为null）。管理端始终提交明确类型及三态用餐方式，不能只展示这些字段而在发布时丢弃。只接收上述业务字段，禁止clientRequestId、photoIds、身份、来源、评分、审核状态等字段。管理员修订内容独立于原始投稿；原proposed_data、request_hash和提交人保持不变。

**响应**

成功200，data示例：

```json
{"id":"51","status":"APPROVED","approvalMode":"CREATED","restaurantId":"101","reviewedBy":"33","reviewedAt":"2026-11-03T07:00:00Z","rejectReason":null,"replayed":false,"publishedPhotos":[{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}]}
```

**错误码**

错误：401身份无效；403 AUTH_FORBIDDEN；400字段校验、RESTAURANT_INVALID_INPUT、RESTAURANT_CATEGORY_UNAVAILABLE或未知字段COMMON_MALFORMED_REQUEST；非法ID400 RESTAURANT_INVALID_ID；申请不存在404 RESTAURANT_REQUEST_NOT_FOUND；终态冲突、内容不同或目标已删除409 RESTAURANT_STATE_CONFLICT；未预期写入失败500 COMMON_INTERNAL_SERVER_ERROR，事务整体回滚。响应丢失可用相同内容重试确认结果。

**副作用与限制**

- 首次通过时，REGULAR必须提供可用的categoryCode；CANTEEN必须使用categoryCode=null或省略。类型与分类不匹配返回400 RESTAURANT_INVALID_INPUT，不自动将食堂归到“其他美食”。旧的已通过食堂按原完整内容重试仍返回首次回执，不重新写入历史分类。
- CREATE创建正式餐厅和有序标签，created_by/updated_by均为实际审核管理员，新ID回填申请restaurant_id，request_type保持CREATE。投稿人保留在submitted_by，不因此获得直接维护权限。
- UPDATE更新原ID及餐厅类型、堂食/外卖三态，保留既有创建者、来源及评分；before_data保存事务内更新前的业务字段及标签（含类型、用餐方式），approved_data保存规范化后的最终业务字段。新店before_data为空。存在任意窗口关联的食堂不能改为普通餐厅，返回409 RESTAURANT_STATE_CONFLICT，包括软删除窗口仍有外键引用的情况。
- 商户、标签、正式相册关联、审核状态和成长奖励在同一个事务提交或回滚。首次通过按原submitted_by计奖：CREATE默认+20，按申请去重；UPDATE默认+10，按用户和店铺去重，后续纠错可正常通过但不重复领奖。重复通过和历史已通过申请不补发经验，管理员直接维护与驳回不计奖；奖励写入失败整次审核返回500 COMMON_INTERNAL_SERVER_ERROR，不保留已通过结果。纠错统一先锁商户、再锁申请；新店先锁申请并创建正式商户；文件校验按文件ID升序加锁。审核人取当前认证，时间由数据库生成，不能回退终态。
- 相同规范化完整内容再次通过，返回首次回执且replayed=true，不重复建店、更新商户或覆盖审核人/时间；分类后来被停用不妨碍已成功结果重放。已通过后提交不同内容、已驳回后通过均409。直接维护接口的后续修改不会被通过重试覆盖。
- 不检测页面打开后商户资料是否变化，成功审核的完整业务资料覆盖当时正式值；已删除目标仍禁止通过，不隐式恢复。
- 首次通过将原申请附图全部发布到正式相册，不接受另外提交photoIds或逐张选择。新店按投稿顺序写入，纠错追加到现有相册末尾；没有附图保留原相册。position=0为默认封面。原申请关联、原BOUND文件和私有云存储对象保留，不复制、重传、重新绑定或更改文件状态。
- 文件必须存在、用途RESTAURANT、状态BOUND且归属原提交人，失败409 FILE_NOT_BINDABLE并回滚整次审核；写入相册或审核失败也整体回滚。相同申请重试不重复发布，回执publishedPhotos返回首次正式关联。历史已通过但未发布到正式相册的申请，重试仍不补发旧图片，返回空publishedPhotos。

<a id="approve-attached-review"></a>

#### 附带点评自动发布

复用本节`POST /api/admin/restaurant-requests/{id}/approve`，权限、请求及成功响应不变；无需另发点评请求。管理员仍只提交完整店铺资料，不接受review、作者、评分或点评图片字段，也不返回待发布点评正文或图片。无附带点评时沿用原流程。

有附带点评时，首次通过必须为CREATE新店申请、最终类型REGULAR、附带点评WAITING且作者与原提交人一致。管理员不能将其改成食堂整体后发布点评。系统保留原评分、态度、正文及图片顺序，用原作者和首次预留的独立发布UUID创建正式点评；沿用正式点评的评分与首次点评奖励规则，停业普通餐厅仍可评。审核贡献奖励与点评奖励都归原提交人，通知仍为原审核结果通知，不额外生成一条点评发布通知。

点评图片必须仍属于原作者、用途REVIEW、状态BOUND；只转移有序关联，不再次绑定临时文件、不复制或访问云存储。发布完成后，待发布记录转为PUBLISHED并保存正式点评ID及发布时间，清空四项评分、态度、正文和待发布图片关联；私有点评图片入口随即404，公开读取改走[正式点评](reviews.md#reviews)及[正式点评图片](files.md#review-photos)。本人详情保留发布回执，即使正式点评后来被删除也不恢复内容。

店铺、两类正式图片关联、正式点评、评分、待发布清理、搜索同步任务、两类奖励及审核通知共用同一事务。任何一步失败都整体回滚，不能只通过店铺或只发布点评；失败后可用相同完整通过请求重试。相同内容的串行或并发重试返回首次审核回执、replayed=true，不重复发布、计分、发奖或通知。已通过且附带点评为PUBLISHED时只确认回执，不回查或补建正式点评；已通过却仍为WAITING等不一致记录返回冲突，不补发历史内容。

除本节原错误外，下列错误同样导致整次审核回滚：

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 409 | `REVIEW_SUBMISSION_STATE_CONFLICT` | 申请类型、最终餐厅类型、点评作者或状态不符，或待发布内容摘要不一致 |
| 409 | `FILE_NOT_BINDABLE` | 点评图片缺失、上传者／用途／绑定状态不符；BOUND文件不按临时有效期拒绝 |
| 409 | `REVIEW_REQUEST_CONFLICT` | 预留正式发布UUID已对应另一条正式点评，不能认领已有点评 |
| 409 | `REVIEW_REQUEST_DELETED` | 预留正式发布UUID已有删除回执，不能恢复已删点评 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 点评、关联、清理、评分、奖励、搜索任务或通知写入异常；保留原待审核申请及待发布内容 |

<a id="link-existing"></a>

### 店铺已收录

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/admin/restaurant-requests/{id}/link-existing` | Bearer，ROLE_ADMIN | 200 |

**请求参数**

id为待审新店申请ID，请求体只包含目标正式店铺ID；两者均为无前导零的1～Long.MAX_VALUE十进制字符串。不接受review、资料覆盖、图片、作者、处理状态或其他字段。

```json
{"restaurantId":"101"}
```

仅支持PENDING且requestType=CREATE的申请；目标必须存在、未删除，且餐厅类型与原投稿类型相同。普通餐厅停业仍可关联和发布点评。管理员通过`GET /api/restaurants`按keyword和restaurantType搜索已有店铺，核对地址与位置后选择；搜索记录可能延迟，提交时重新校验目标。

**响应**

成功200、no-store，data示例：

```json
{"id":"52","status":"APPROVED","approvalMode":"LINKED_EXISTING","restaurantId":"101","reviewedBy":"33","reviewedAt":"2026-11-14T06:00:00Z","rejectReason":null,"replayed":false,"publishedPhotos":[]}
```

本人详情的application返回上述处理方式和目标ID，approved是处理时已有店铺的业务资料快照，before=null，proposed仍为用户原投稿。若有附带点评，reviewSubmission.status=PUBLISHED并含publishedReviewId；无附带点评为null。管理员详情不增加私有点评字段。

**错误码**

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` / `COMMON_MALFORMED_REQUEST` | 缺失／非法目标字段、未知字段或请求结构无效 |
| 400 | `RESTAURANT_INVALID_ID` | 申请／目标ID格式或Long范围不符 |
| 401 / 403 | `AUTH_UNAUTHENTICATED` / `AUTH_FORBIDDEN` | 未登录或非管理员 |
| 404 | `RESTAURANT_REQUEST_NOT_FOUND` | 申请不存在 |
| 404 | `RESTAURANT_NOT_FOUND` | 首次处理时目标不存在或已删除 |
| 409 | `RESTAURANT_STATE_CONFLICT` | 非新店申请、目标类型不符、申请已驳回／撤回／通过创建，或已关联另一店铺 |
| 409 | `REVIEW_SUBMISSION_STATE_CONFLICT` / `FILE_NOT_BINDABLE` / `REVIEW_REQUEST_CONFLICT` / `REVIEW_REQUEST_DELETED` | 附带点评或图片不满足[自动发布约束](#approve-attached-review) |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 写入异常，整次处理回滚 |

**副作用与限制**

- 申请结束为APPROVED，requestType保持CREATE，restaurantId关联已有店铺。保留原提交人、原投稿快照、摘要和私有店铺图片；不覆盖已有店铺资料、标签、创建者或相册，不将本次店铺投稿图公开。
- 有附带点评时按原作者发布正文、四维评分、推荐态度和点评图片，计入目标评分；沿用普通点评历史及同店首次点评奖励规则，已有首评资格不重复发奖。没有创建新店，不发新店投稿奖励，也不发纠错奖励。
- 申请、正式点评、评分、搜索同步任务、点评奖励、清理待发布内容及原申请人通知共用一个事务。任何失败都完整回滚，按相同申请及目标重试整次操作。无附带点评时仅完成申请与通知。
- 相同申请和目标重试返回首次回执、replayed=true，不重复发布或通知；目标或正式点评后来被删除，也不恢复内容。改选另一目标、改用approve或reject不能覆盖处理结果，返回409；并发不同操作只允许一种结果。同一目标的多份申请可分别关联，所有原作者点评归到同一家正式店铺。
- 此入口依赖管理员判断同店，不自动查重，也不合并已经公开的两家店铺。

<a id="reject"></a>

### 驳回申请

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/admin/restaurant-requests/{id}/reject` | 管理员 | 200 |

**请求参数**

`POST /api/admin/restaurant-requests/{id}/reject`，请求：

```json
{"reason":"信息不实"}
```

reason必填，DTO最多200个UTF-16单元，去Unicode首尾空白后不能为空，服务端另校验最多200个码点；不接受其他字段。前端提供“图片模糊”“无效地址”“信息不实”快捷原因，接口也接受符合规则的其他文字原因。

**响应**

成功200，回执字段同[审核通过并发布商户资料及图片](#approve)，status=REJECTED、rejectReason为规范化原因、publishedPhotos为空数组；新店restaurantId仍为空，纠错保留原ID。只将PENDING改为REJECTED并保存审核人/时间，正式商户、标签、原始申请及图片关联保持不变。相同原因重试返回replayed=true和首次回执；不同原因再次驳回或已通过后驳回均409 RESTAURANT_STATE_CONFLICT。目标已删除仍允许驳回。拒绝后图片仍为私有BOUND，不触发清理或正式公开。

**错误码**

错误：401/403权限错误；400校验、未知字段或非法ID；404申请不存在；409终态冲突；500未预期故障。身份与公共错误码同[审核通过并发布商户资料及图片](#approve)。

**副作用与限制**

附带点评时核对原作者、CREATE及WAITING状态；不符返回409 `REVIEW_SUBMISSION_STATE_CONFLICT`。驳回只改变店铺申请，点评保留WAITING、正文、评分、态度及BOUND私有图片，待本人修改后重提，不公开、不计分或发奖。驳回结果通知仍按原流程发送，通知或写入失败整次驳回回滚，不留下半次审核。

同原因重试先确认原驳回回执，不再次要求点评仍关联旧申请；点评后来已迁移至子申请或发布，也不会影响旧驳回重试，更不会迁回点评或重复通知。管理员仍只能查看店铺资料与店铺图，待发布点评内容仅本人可读。
