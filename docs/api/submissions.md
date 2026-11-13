# 店铺投稿、本人申请与审核

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

业务阅读顺序：[新店／纠错提交](#create-and-change) → [新店附带点评](#attached-review) → [本人申请](#my-requests) → [管理员审核](#admin-review)。

**阶段限制：附带点评的申请当前只能提交；查询不返回待发布点评，审核、修改、撤回和重提暂返回409 `REVIEW_SUBMISSION_STATE_CONFLICT`。**

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
| GET | `/api/admin/restaurant-requests` | 管理员 | 200 | [申请分页](#admin-list) |
| GET | `/api/admin/restaurant-requests/{id}` | 管理员 | 200 | [申请详情](#admin-detail) |
| GET | `/api/admin/restaurant-requests/{id}/photos/{position}` | 管理员 | 200 JPEG | [申请私有图片](#admin-photos) |
| POST | `/api/admin/restaurant-requests/{id}/approve` | 管理员 | 200 | [审核通过并发布商户资料及图片](#approve) |
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
| `review` | 仅新店提交入口可选；省略或null为仅提交店铺。完整点评字段、事务及防重规则见[新店附带点评](#attached-review)；纠错、修改和重提暂不接受该字段 |

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
| `body` | 必填；去Unicode首尾空白后5–500码点，纯文本 |
| `photoIds` | 默认空，不能null；最多5个唯一正Long字符串，按原顺序保存，必须为本人未过期TEMPORARY状态的REVIEW用途文件 |

**响应**

首次及同内容重放均201、`Cache-Control: no-store`，统一封装的data为：

```json
{"id":"51","status":"PENDING","reviewSubmission":{"id":"71","status":"WAITING"}}
```

id为新店申请ID，reviewSubmission.id为私有待发布点评ID，不是正式点评ID。省略review或传null时仍返回原`{id,status}`，不生成待发布点评。PENDING表示原提交已受理，不表示查询到了最新审核状态。

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

店铺申请、店铺图片绑定、待发布点评及其图片绑定在同一数据库事务中保存；任一校验、绑定或关联写入失败均整体回滚。提交期间不创建正式店铺或正式点评，不计分、不计奖、不发审核通知，也不生成搜索同步任务；不访问OSS或复制图片。点评正文独立保存，不写入申请的proposed资料快照。

同账号、同外层UUID及同规范化联合内容重放返回原申请和点评回执，不重新绑定BOUND文件。联合摘要覆盖店铺资料及规范化点评评分、态度、正文和有序图片ID；改变其中任一内容、图片顺序，或在附带／不附带点评之间切换，均不能复用原UUID。只提交店铺继续沿用旧摘要算法，旧成功请求可通过新接口确认回执。请求超时后应保留原UUID重试，而非自动生成新UUID。

阶段边界：本次只接通联合提交。本人/管理员申请查询仍仅返回店铺资料，没有待发布点评详情或图片读取接口；前端入口和审核自动发布尚未接通。对于已附带点评的申请，旧审核通过、驳回、本人修改、撤回及重提返回409 `REVIEW_SUBMISSION_STATE_CONFLICT`，避免脱离点评联动更改申请；本人操作仍先验证归属，他人申请为404。上述旧写入接口继续拒绝额外review字段。无附带点评的申请不受此保护影响。

<a id="my-requests"></a>

## 本人投稿与纠错记录

本节接口均需Bearer，仅操作当前认证身份提交的申请，返回`Cache-Control: no-store`。不复用管理员查询或审核权限，也不接受userId/submittedBy选择其他申请人；额外身份、offset或排序参数不会改变服务端范围。本人只能修改或撤回待审申请，驳回后可基于原申请创建新申请；管理员队列、审核结果和公开商户资料不受这些操作直接影响。

附带点评的申请暂按[联合提交阶段边界](#attached-review)保护：读取仍只返回店铺资料；本人修改、撤回和重提在核对归属后返回409 `REVIEW_SUBMISSION_STATE_CONFLICT`。这些写入请求暂不接收review字段；未附带点评的原流程不变。

<a id="my-list"></a>

### 本人列表

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/restaurant-requests` | Bearer | 200 |

**请求参数**

`GET /api/me/restaurant-requests`参数：status默认ALL，可选ALL/PENDING/APPROVED/REJECTED/WITHDRAWN；page默认1、范围1–1000000；pageSize默认20、范围1–50。前端每页10条。按createdAt、id依次降序，先按本人和所选状态筛选再分页；同一店铺的不同申请独立展示，不按店铺合并。

**响应**

成功200，统一封装的data示例：

```json
{"userId":"11","records":[{"id":"101","restaurantId":null,"previousRequestId":null,"requestType":"CREATE","status":"PENDING","name":"新店名","createdAt":"2026-11-07T05:00:00Z","reviewedAt":null,"withdrawnAt":null,"photoCount":0}],"counts":{"PENDING":1,"APPROVED":0,"REJECTED":0,"WITHDRAWN":0,"ALL":1},"total":1,"totalPages":1,"page":1,"pageSize":20}
```

userId为本人字符串ID；requestType为CREATE新店或UPDATE纠错，不因新店通过后回填restaurantId而改变。previousRequestId仅在驳回后重提的新申请上有值，指向原驳回申请；withdrawnAt仅在本人撤回后有值。name为原提交店名，photoCount为原申请关联图数；待审reviewedAt为null。counts始终为本人全部申请的状态计数，不受当前status筛选影响；total为所选状态总数。真正无记录返回空records、零计数及totalPages=0；超出末页也可返回空records，但保留实际总数。列表和计数共用一次数据库快照，跨次查询时新增申请或审核状态变化可能改变分页。

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
{"userId":"11","application":{"id":"101","restaurantId":null,"previousRequestId":null,"requestType":"CREATE","status":"PENDING","name":"新店名","createdAt":"2026-11-07T05:00:00Z","reviewedAt":null,"withdrawnAt":null,"photoCount":1},"proposed":{"name":"新店名","categoryCode":"OTHER","categoryName":"其他美食","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","supportsDineIn":null,"supportsDelivery":null,"tags":[]},"approved":null,"rejectReason":null,"targetAvailable":false,"photoPositions":[0]}
```

application字段同列表；proposed为原始提交业务快照，approved只在通过后返回实际生效业务快照（可包含管理员修订），否则null。两份快照均只包含示例中的业务字段，不含原始请求UUID、photoIds及旧内部字段；历史缺失字段可为null。rejectReason仅驳回有值；审核时间位于application.reviewedAt。不会返回审核人、纠错前before、管理端current资料、密码、邮箱、请求摘要或存储键。

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

**响应**

成功200，data为`{"id":"101","status":"PENDING"}`。相同完整内容重复提交结果一致；附图替换只释放新列表不再保留的旧图，仍保留的图片继续使用原绑定，新图与申请内容在同一事务内更新，任一步失败全部回滚。

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

`POST /api/me/restaurant-requests/{id}/withdraw`无请求体，成功200，data为`{"id":"101","status":"WITHDRAWN"}`。仅PENDING可撤回；撤回写入withdrawnAt，不写审核人、通过快照或驳回原因，申请从管理员待审队列移除，原附图仍仅本人和管理员可读。重复撤回同一申请返回相同成功结果；已通过或已驳回返回409 `RESTAURANT_STATE_CONFLICT`。撤回不可撤销，需重新投稿时走正常提交或驳回重提。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="my-resubmit"></a>

### 驳回后重提

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/me/restaurant-requests/{id}/resubmit` | Bearer | 201 |

**请求参数**

`POST /api/me/restaurant-requests/{id}/resubmit`请求体为首次投稿的店铺字段，暂不接受review，必须携带新的`clientRequestId`：

```json
{"clientRequestId":"2f8c0d62-7f4e-4f0e-9aa1-52f6b48b4e21","name":"重提修订店名","categoryCode":"OTHER","restaurantType":"REGULAR","priceKind":"UNKNOWN","address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[],"photoIds":[]}
```

**响应**

成功201，data为`{"id":"106","status":"PENDING"}`。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

仅本人REJECTED申请可重提；新申请继承原申请的CREATE/UPDATE类型和UPDATE目标，写入previousRequestId指向原驳回申请，状态为PENDING。原申请保持REJECTED及原rejectReason，不重新进入待审，也不改审核字段。UPDATE目标已删除返回404；PENDING、APPROVED或WITHDRAWN返回409。相同clientRequestId和相同内容重试返回已创建的新申请ID；相同UUID改变内容或目标返回409 `RESTAURANT_STATE_CONFLICT`。原申请附图仍绑定在原申请上，重提需重新上传并提交新图片ID。

<a id="my-photos-errors"></a>

### 本人申请图片与错误

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/restaurant-requests/{id}/photos/{position}` | Bearer | 200 JPEG |

**响应**

`GET /api/me/restaurant-requests/{id}/photos/{position}`按申请归属、关联位置及文件上传者/RESTAURANT用途/BOUND状态校验。待审、驳回、已撤回及已通过的本人申请均可读原附图，不因读取而公开图片。成功200为原始JPEG字节，另含`X-Content-Type-Options: nosniff`；前端携带固定Bearer取得Blob，不使用匿名img或OSS签名地址。OSS读取不占数据库事务。

**错误码**

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 状态、分页或修改/重提字段不合法。 |
| 400 | RESTAURANT_INVALID_ID | 申请ID不是无前导零、未溢出的正整数字符串。 |
| 400 | RESTAURANT_INVALID_INPUT | 图片位置不是0–4的单个数字；修改内容或图片ID重复/越界。 |
| 401 | AUTH_UNAUTHENTICATED | 缺少/失效凭证、账号不可用或会话失效。 |
| 404 | RESTAURANT_REQUEST_NOT_FOUND | 申请不存在或不属于本人；修改、撤回、重提和图片入口同样先校验归属。 |
| 404 | RESTAURANT_NOT_FOUND | 修改或重提的UPDATE目标店铺已删除。 |
| 404 | FILE_NOT_FOUND | 本人申请无对应图片位置，或文件归属、用途、绑定状态不符。 |
| 409 | RESTAURANT_STATE_CONFLICT | 修改/撤回/重提的状态不允许，或重提UUID对应内容冲突。 |
| 409 | FILE_NOT_BINDABLE | 修改附图时图片已过期、已绑定其他申请或不属于本人的RESTAURANT用途。 |
| 503 | FILE_STORAGE_UNAVAILABLE | 图片存储暂不可用，可只重试单图。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖暂不可用，保留凭证供重试。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期查询或写入错误，不降级为空列表、空详情或成功。 |

**副作用与限制**

已通过认证但方法不匹配返回405 `COMMON_METHOD_NOT_ALLOWED`。GET不改变审核状态、已读状态、经验、商户资料或图片绑定状态；PUT、撤回和重提只影响本人申请及其私有附图，不直接发布或修改公开商户资料。

<a id="admin-review"></a>

## 管理员商户审核

以下接口均需有效Bearer及数据库当前`ROLE_ADMIN`，每次请求重新校验权限，成功响应均为`Cache-Control:no-store`。GET接口只读；审核POST接口改变申请及相应正式资料、正式相册关联，不改变原媒体状态、申请图片关联或OSS对象。

附带待发布点评的新店申请目前仅开放[联合提交](#attached-review)，审核通过及驳回暂返回409 `REVIEW_SUBMISSION_STATE_CONFLICT`，不改变申请、点评、评分或奖励。普通不附带点评的申请继续按本节处理；本阶段尚未开放管理员读取附带点评内容或图片。

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

`counts`为相同关键字条件下三个状态的数量，不受当前状态及分页限制；`total`为所选状态的完整数量。本人撤回的WITHDRAWN申请不进入管理员队列和状态计数，但管理员仍可按ID读取其详情；`previousRequestId`仅驳回后重提的新申请有值，指向原驳回申请。空结果200、空records、total/totalPages为0；越界页保留请求页码并返回空记录。分页、计数和批量署名在同一个只读快照内读取。署名缺失显示“用户不可用”，不返回邮箱、密码、请求摘要或OSS对象键。

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
| `photos` | 有序图片引用`[{"position":0,"url":"/api/admin/restaurant-requests/51/photos/0"}]`，不读取OSS |
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

错误：非法位置400 `RESTAURANT_INVALID_INPUT`；缺失申请404 `RESTAURANT_REQUEST_NOT_FOUND`；无对应位置、文件已清理、用途/归属/绑定状态不符404 `FILE_NOT_FOUND`；存储不可用503 `FILE_STORAGE_UNAVAILABLE`。不得用该接口按任意文件ID读取图片，也不返回OSS签名地址。

**副作用与限制**

先核对申请及有序图片关联，再验证文件为申请提交人的RESTAURANT用途BOUND文件，最后读取私有OSS。浏览器必须携带Bearer取得Blob后展示，不能直接依赖无认证img请求。

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
{"id":"51","status":"APPROVED","restaurantId":"101","reviewedBy":"33","reviewedAt":"2026-11-03T07:00:00Z","rejectReason":null,"replayed":false,"publishedPhotos":[{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}]}
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
- 首次通过将原申请附图全部发布到正式相册，不接受另外提交photoIds或逐张选择。新店按投稿顺序写入，纠错追加到现有相册末尾；没有附图保留原相册。position=0为默认封面。原申请关联、原BOUND文件和私有OSS对象保留，不复制、重传、重新绑定或更改文件状态。
- 文件必须存在、用途RESTAURANT、状态BOUND且归属原提交人，失败409 FILE_NOT_BINDABLE并回滚整次审核；写入相册或审核失败也整体回滚。相同申请重试不重复发布，回执publishedPhotos返回首次正式关联。历史已通过但未发布到正式相册的申请，重试仍不补发旧图片，返回空publishedPhotos。

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
