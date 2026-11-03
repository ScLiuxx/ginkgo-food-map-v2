# Ginkgo 美食地图 V2 API 文档

本文描述当前代码提供的接口、参数、响应及副作用。部署环境的域名、有效期和限流值可通过配置覆盖。开发过程见[开发记录](development.md)。

## 1. 通用约定

| 项目 | 约定 |
| --- | --- |
| 本地后端 | `http://localhost:8080` |
| 本地前端 | `http://localhost:5173`，代理`/auth`和`/api` |
| 请求格式 | 有请求体的业务接口使用JSON；图片上传使用multipart/form-data |
| 认证 | `Authorization: Bearer <accessToken>`；不使用Basic或Cookie会话 |
| 业务ID | 餐厅、申请、点评、回复和文件ID采用无前导零的正整数字符串，范围1–9223372036854775807 |
| 账号ID | `/auth/login`、`/auth/me`及`/api/admin/me`中的账号ID为JSON整数；其他署名/业务资源引用为字符串 |
| 时间 | ISO 8601 UTC字符串 |
| 坐标 | GCJ-02；用户查询位置不写入数据库 |

除图片字节、204无内容及Actuator接口外，响应使用统一封装：

```json
{"success":true,"code":"OK","message":"请求成功","data":{},"timestamp":"2026-11-03T06:00:00Z"}
```

失败时`success=false`，`code`为稳定业务错误码，`data`通常为null；字段校验失败时可为`[{"field":"username","message":"请输入用户名"}]`。错误不会返回SQL、密码、令牌或云存储凭据。

受保护请求校验JWT签名、发行方、受众、有效期、Redis会话、数据库当前账号状态、凭证版本及角色。401可清理该请求所用凭证，不能清除之后切换的新会话；403表示无权限；429表示限流；503表示临时故障，保留凭证和可重试内容。主动向公开接口携带无效Bearer仍可能得到401。

认证、管理员私有资料、点评/回复及图片响应禁止缓存。未预期故障返回500 `COMMON_INTERNAL_SERVER_ERROR`，不能当成空结果或操作成功。

## 2. 接口总览

| 方法 | 路径 | 认证 | 成功状态 | 用途 |
| --- | --- | --- | --- | --- |
| POST | `/auth/email/code/register` | 公开 | 200 | 注册验证码 |
| POST | `/auth/email/code/password-reset` | 公开 | 200 | 找回密码验证码 |
| POST | `/auth/register` | 公开 | 201 | 注册普通账号 |
| POST | `/auth/register-and-login` | 公开 | 200 | 注册并建立会话 |
| POST | `/auth/password-reset` | 公开 | 200 | 设置新密码 |
| POST | `/auth/login` | 公开 | 200 | 统一登录 |
| GET | `/auth/me` | Bearer | 200 | 当前身份 |
| POST | `/auth/logout` | Bearer | 200 | 撤销当前会话 |
| GET | `/api/admin/me` | 管理员 | 200 | 工作台身份校验 |
| GET | `/api/admin/restaurant-requests` | 管理员 | 200 | 商户申请分页 |
| GET | `/api/admin/restaurant-requests/{id}` | 管理员 | 200 | 申请与当前资料 |
| GET | `/api/admin/restaurant-requests/{id}/photos/{position}` | 管理员 | 200 JPEG | 申请私有图片 |
| POST | `/api/admin/restaurant-requests/{id}/approve` | 管理员 | 200 | 审核并发布资料及附图 |
| POST | `/api/admin/restaurant-requests/{id}/reject` | 管理员 | 200 | 驳回申请 |
| GET | `/api/restaurant-categories` | 公开 | 200 | 分类字典 |
| GET | `/api/restaurants` | 公开 | 200 | 餐厅目录 |
| GET | `/api/restaurants/map-points` | 公开 | 200 | 有界地图点位 |
| GET | `/api/restaurants/{id}` | 公开 | 200 | 餐厅详情 |
| GET | `/api/restaurants/{id}/photos` | 公开 | 200 | 正式商户相册游标分页 |
| GET | `/api/restaurants/{id}/photos/{photoId}` | 公开 | 200 JPEG | 正式商户图片字节 |
| POST | `/api/restaurant-submissions` | Bearer | 201 | 新商户申请 |
| POST | `/api/restaurants/{id}/change-requests` | Bearer | 201 | 资料纠错申请 |
| GET | `/api/restaurants/{id}/management` | 管理员 | 200 | 管理资料 |
| POST | `/api/restaurants` | 管理员 | 201 | 直接新建餐厅 |
| PUT | `/api/restaurants/{id}` | 管理员 | 200 | 完整编辑 |
| DELETE | `/api/restaurants/{id}` | 管理员 | 200 | 软删除 |
| POST | `/api/restaurants/{id}/restore` | 管理员 | 200 | 恢复餐厅 |
| POST | `/api/files/images` | Bearer | 201 | 上传临时图片 |
| DELETE | `/api/files/images/{fileId}` | 上传者 | 204 / 202 | 取消未绑定图片 |
| POST | `/api/restaurants/{restaurantId}/reviews` | Bearer | 201 / 200重放 | 发布点评 |
| GET | `/api/restaurants/{restaurantId}/reviews` | 公开 | 200 | 按作者分组分页 |
| GET | `/api/restaurants/{restaurantId}/reviews/authors/{authorId}/history` | 公开 | 200 | 同作者历史点评 |
| GET | `/api/reviews/{reviewId}/photos/{position}` | 公开 | 200 JPEG | 点评正式图片 |
| DELETE | `/api/reviews/{reviewId}` | 原作者 | 204 | 删除点评 |
| POST | `/api/reviews/{reviewId}/replies` | Bearer | 201 / 200重放 | 单层回复 |
| GET | `/api/reviews/{reviewId}/replies` | 公开 | 200 | 回复游标分页 |
| GET | `/api/public/status` | 公开 | 200 | 应用状态 |
| GET | `/actuator/health` | 公开 | 200 / 503 | 依赖健康状态 |

“管理员”指数据库当前角色包含`ROLE_ADMIN`。普通用户仅可投稿、纠错申请，不能直接维护正式商户，即使是历史创建者也不例外。公开读取不会创建账号、申请、点评或图片。

## 3. 注册、验证码与密码重置

### 3.1 邮箱验证码

`POST /auth/email/code/register`、`POST /auth/email/code/password-reset`均无需认证，请求：

```json
{"email":"student@std.uestc.edu.cn"}
```

邮箱必须为有效格式，服务端规范化为小写并检查允许域名，默认仅`std.uestc.edu.cn`。成功200、`data:null`；验证码通过邮件发送，不在响应中返回。

默认验证码6位数字、5分钟有效。同邮箱60秒冷却、每小时5次、每24小时10次；注册和找回密码共享发送配额，但验证码用途隔离。错误尝试最多5次；成功消费后失效。同用途重新申请替换原验证码。发送失败会撤销待激活验证码及冷却，不退发送配额。

注册验证码：已注册邮箱409 `USER_EMAIL_ALREADY_REGISTERED`。找回密码验证码：未注册但格式和域名有效的邮箱也返回相同成功响应，不发送邮件，不能据此确认账号存在。

其他错误：400 `COMMON_VALIDATION_FAILED`、`USER_CAMPUS_EMAIL_REQUIRED`；429 `AUTH_CODE_RESEND_COOLDOWN`、`AUTH_CODE_EMAIL_RATE_LIMITED`；503 `AUTH_CODE_MAIL_DELIVERY_FAILED`、`AUTH_CODE_DELIVERY_STATE_EXPIRED`、`AUTH_CODE_SERVICE_UNAVAILABLE`。邮件受理成功不保证收件箱投递完成。

### 3.2 注册

`POST /auth/register`、`POST /auth/register-and-login`请求相同：

| 字段 | 规则 |
| --- | --- |
| `email` | 必填有效校园邮箱，与验证码绑定邮箱一致 |
| `verificationCode` | 必填，严格6位ASCII数字，保留前导零 |
| `username` | 去首尾空白后2–24字符，中文/英文字母/数字/下划线，区分大小写及重音，唯一 |
| `password` | 4–32位ASCII字母、数字或英文符号，不含空格、中文或控制字符，不强制组合，不执行trim |

```json
{"email":"student@std.uestc.edu.cn","verificationCode":"012345","username":"ginkgo_user","password":"ExamplePassword2026"}
```

`/auth/register`成功201、`data:null`，不建立会话。`/auth/register-and-login`成功200，`data`与登录响应相同，`Cache-Control:no-store`。

两者只分配`ROLE_USER`，没有公开管理员注册或提权入口。请求额外附带`role`、`roles`、`isAdmin`会被忽略，不用于授权。确认密码仅由前端比较，不属于接口字段。

邮箱和用户名必须唯一。验证码先消费，再在事务内写入账号及角色；写入失败不会恢复验证码。注册自动登录在账号事务提交后建立会话，后续登录失败返回503 `AUTH_REGISTER_LOGIN_FAILED`，应改用登录，不重复注册。

错误：400 `COMMON_VALIDATION_FAILED`、`USER_CAMPUS_EMAIL_REQUIRED`、`USER_USERNAME_INVALID`、`USER_PASSWORD_WEAK`、`AUTH_CODE_INVALID_OR_EXPIRED`；409 `USER_EMAIL_ALREADY_REGISTERED`、`USER_USERNAME_ALREADY_REGISTERED`、`USER_REGISTRATION_CONFLICT`；429 `AUTH_CODE_ATTEMPTS_EXCEEDED`；503 `AUTH_CODE_SERVICE_UNAVAILABLE`。

### 3.3 设置新密码

`POST /auth/password-reset`无需认证：

```json
{"email":"student@std.uestc.edu.cn","verificationCode":"012345","newPassword":"NewExamplePassword2026"}
```

`newPassword`使用注册密码规则；验证码必须为密码重置用途。成功200、`Cache-Control:no-store`，`data:{"username":"实际用户名"}`，不返回密码或令牌，不自动登录。

仅已验证邮箱且状态ACTIVE的账号可改密，其他情况统一`AUTH_CODE_INVALID_OR_EXPIRED`。密码更新和凭证版本递增同事务完成，使所有旧会话在后续认证时失效，不解除停用或登录失败锁定。已消费验证码不因后续故障恢复。

错误包括验证码错误、429错误次数超限、409 `USER_PASSWORD_RESET_CONFLICT`、503 `AUTH_CODE_SERVICE_UNAVAILABLE`或`USER_PASSWORD_RESET_UNAVAILABLE`。输入无效时返回400；临时故障不能视为密码已更新。

## 4. 登录与会话

### 4.1 统一登录

`POST /auth/login`无需认证：

```json
{"username":"ginkgo_user","password":"ExamplePassword2026"}
```

用户名必填，原始输入最多64个UTF-16单元，去首尾空白后查询；不接受邮箱代替用户名。密码必填，DTO最多72个UTF-16单元，服务端额外限制UTF-8不超过72字节，原样匹配BCrypt，不trim。

成功200、`Cache-Control:no-store`，`data`：

```json
{"accessToken":"<accessToken>","tokenType":"Bearer","expiresAt":"2026-11-10T06:00:00Z","user":{"id":42,"username":"ginkgo_user","roles":["ROLE_USER"]}}
```

每次成功创建独立Redis会话，默认7天有效，无刷新令牌或滑动续期。默认15分钟内累计失败5次锁定15分钟，成功清除失败计数。账号不存在、密码错误、未验证邮箱或不可用统一401 `AUTH_INVALID_CREDENTIALS`；锁定429 `AUTH_LOGIN_LOCKED`；认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`。

前端统一入口`/login`，普通账号进入前台或安全站内回跳，含`ROLE_ADMIN`的账号进入工作台。管理账号仅显示管理界面，使用普通用户功能需退出并换普通账号；`/admin/login`仅跳转统一登录。前后台共用同源会话，跨标签页同步退出与账号切换。

### 4.2 当前身份与退出

`GET /auth/me`需Bearer，无参数/请求体；成功200、禁止缓存，`data`为上述`user`。刷新必须验证身份，不能仅凭本地存在令牌判定已登录。401 `AUTH_UNAUTHENTICATED`表示会话无效，503 `AUTH_SERVICE_UNAVAILABLE`可重试；认证后身份再次变化可返回401 `AUTH_INVALID_CREDENTIALS`。

`POST /auth/logout`需Bearer，无请求体；成功200、`data:null`，撤销当前会话，其他独立会话不受影响。收到成功后清理对应本地凭证；503不表示退出成功，保留重试能力。成功撤销后的同一令牌再次请求返回401。

浏览器默认用同源localStorage保存访问令牌，不保存密码；存储不可用时使用内存会话。公开登录、验证码、注册和找回密码请求应省略Bearer。

## 5. 管理员商户审核

以下接口均需有效Bearer及数据库当前`ROLE_ADMIN`，每次请求重新校验权限，成功响应均为`Cache-Control:no-store`。GET接口只读；审核POST接口改变申请及相应正式资料、正式相册关联，不改变原媒体状态、申请图片关联或OSS对象。

### 5.1 工作台身份校验

`GET /api/admin/me`无参数，成功200，`data`为当前公开身份`{id,username,roles}`。普通账号403 `AUTH_FORBIDDEN`；匿名/失效会话401；认证依赖故障503。

### 5.2 申请分页

`GET /api/admin/restaurant-requests`

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| `status` | `PENDING` | PENDING / APPROVED / REJECTED，精确匹配 |
| `keyword` | 空 | 最长80字符，去首尾空白；商户申请名称包含匹配或完整申请ID匹配，`%`、`_`按字面量匹配 |
| `page` | 1 | 1–1000000 |
| `pageSize` | 20 | 1–50 |

按`createdAt,id`升序，最早提交优先。成功200，`data`示例：

```json
{
  "records":[{"id":"51","requestType":"UPDATE","status":"PENDING","name":"商户名称","restaurantId":"101","submittedBy":"22","submitterName":"同学甲","createdAt":"2026-11-03T06:00:00Z","photoCount":1}],
  "total":1,"totalPages":1,"page":1,"pageSize":20,
  "counts":{"PENDING":1,"APPROVED":0,"REJECTED":0}
}
```

`counts`为相同关键字条件下三个状态的数量，不受当前状态及分页限制；`total`为所选状态的完整数量。空结果200、空records、total/totalPages为0；越界页保留请求页码并返回空记录。分页、计数和批量署名在同一个只读快照内读取。署名缺失显示“用户不可用”，不返回邮箱、密码、请求摘要或OSS对象键。

### 5.3 申请详情

`GET /api/admin/restaurant-requests/{id}`，无请求体/查询参数。成功200，`data`：

| 字段 | 含义 |
| --- | --- |
| `application` | 与列表单条记录相同 |
| `proposed` | 用户原始业务资料白名单，字段见第7节，附categoryName；不含clientRequestId或文件存储信息 |
| `current` | 当前正式商户业务资料与有序标签；没有正式目标时null，不包含评分统计或内部归属信息 |
| `targetDeleted` | 目标商户是否已软删除；管理员可核对其资料，不使其公开 |
| `photos` | 有序图片引用`[{"position":0,"url":"/api/admin/restaurant-requests/51/photos/0"}]`，不读取OSS |
| `publishedPhotos` | 本次申请实际发布的正式相册图片`[{id,position,cover,url}]`；待审、驳回、历史未发布为空数组；原photos仍保留私有核对 |
| `reviewedBy` / `reviewedAt` | 审核人字符串ID/时间，未处理为null |
| `rejectReason` | 驳回原因，未驳回为null |
| `approved` / `before` | 实际通过资料与纠错前快照白名单，没有时null |

同一次请求的申请、当前商户和标签处于同一只读快照。资料中的标签缺省可为null，前端按未知/空态展示，不补造已核实信息。ID非法400 `RESTAURANT_INVALID_ID`；申请不存在404 `RESTAURANT_REQUEST_NOT_FOUND`；参数校验失败400 `COMMON_VALIDATION_FAILED`。

### 5.4 申请私有图片

`GET /api/admin/restaurant-requests/{id}/photos/{position}`，position严格为0–4。成功200、原始`image/jpeg`字节、`Cache-Control:no-store`及`X-Content-Type-Options:nosniff`，不使用JSON封装。

先核对申请及有序图片关联，再验证文件为申请提交人的RESTAURANT用途BOUND文件，最后读取私有OSS。浏览器必须携带Bearer取得Blob后展示，不能直接依赖无认证img请求。

错误：非法位置400 `RESTAURANT_INVALID_INPUT`；缺失申请404 `RESTAURANT_REQUEST_NOT_FOUND`；无对应位置、文件已清理、用途/归属/绑定状态不符404 `FILE_NOT_FOUND`；存储不可用503 `FILE_STORAGE_UNAVAILABLE`。不得用该接口按任意文件ID读取图片，也不返回OSS签名地址。

### 5.5 审核通过并发布商户资料及图片

`POST /api/admin/restaurant-requests/{id}/approve`。请求为完整可维护业务资料，不是PATCH：

```json
{"name":"银杏面馆","categoryCode":"OTHER","restaurantType":"REGULAR","supportsDineIn":true,"supportsDelivery":null,"priceKind":"EXACT","pricePerPerson":20,"address":"商户地址","latitude":30.7544,"longitude":103.9312,"locationConfirmed":true,"businessStatus":"OPERATING","tags":["面食"]}
```

基础字段校验与第8节直接维护一致；审核另接收restaurantType（REGULAR普通餐厅/CANTEEN食堂，省略默认REGULAR，显式null非法）、supportsDineIn和supportsDelivery（true支持、false不支持、null未知，省略为null）。管理端始终提交明确类型及三态用餐方式，不能只展示这些字段而在发布时丢弃。只接收上述业务字段，禁止clientRequestId、photoIds、身份、来源、评分、审核状态等字段。管理员修订内容独立于原始投稿；原proposed_data、request_hash和提交人保持不变。

成功200，data示例：

```json
{"id":"51","status":"APPROVED","restaurantId":"101","reviewedBy":"33","reviewedAt":"2026-11-03T07:00:00Z","rejectReason":null,"replayed":false,"publishedPhotos":[{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}]}
```

- CREATE创建正式餐厅和有序标签，created_by/updated_by均为实际审核管理员，新ID回填申请restaurant_id，request_type保持CREATE。投稿人保留在submitted_by，不因此获得直接维护权限。
- UPDATE更新原ID及餐厅类型、堂食/外卖三态，保留既有创建者、来源及评分；before_data保存事务内更新前的业务字段及标签（含类型、用餐方式），approved_data保存规范化后的最终业务字段。新店before_data为空。存在任意窗口关联的食堂不能改为普通餐厅，返回409 RESTAURANT_STATE_CONFLICT，包括软删除窗口仍有外键引用的情况。
- 商户、标签、正式相册关联和审核状态在同一个事务提交或回滚。纠错统一先锁商户、再锁申请；新店先锁申请并创建正式商户；文件校验按文件ID升序加锁。审核人取当前认证，时间由数据库生成，不能回退终态。
- 相同规范化完整内容再次通过，返回首次回执且replayed=true，不重复建店、更新商户或覆盖审核人/时间；分类后来被停用不妨碍已成功结果重放。已通过后提交不同内容、已驳回后通过均409。直接维护接口的后续修改不会被通过重试覆盖。
- 不检测页面打开后商户资料是否变化，成功审核的完整业务资料覆盖当时正式值；已删除目标仍禁止通过，不隐式恢复。
- 首次通过将原申请附图全部发布到正式相册，不接受另外提交photoIds或逐张选择。新店按投稿顺序写入，纠错追加到现有相册末尾；没有附图保留原相册。position=0为默认封面。原申请关联、原BOUND文件和私有OSS对象保留，不复制、重传、重新绑定或更改文件状态。
- 文件必须存在、用途RESTAURANT、状态BOUND且归属原提交人，失败409 FILE_NOT_BINDABLE并回滚整次审核；写入相册或审核失败也整体回滚。相同申请重试不重复发布，回执publishedPhotos返回首次正式关联。历史已通过但未发布到正式相册的申请，重试仍不补发旧图片，返回空publishedPhotos。

错误：401身份无效；403 AUTH_FORBIDDEN；400字段校验、RESTAURANT_INVALID_INPUT、RESTAURANT_CATEGORY_UNAVAILABLE或未知字段COMMON_MALFORMED_REQUEST；非法ID400 RESTAURANT_INVALID_ID；申请不存在404 RESTAURANT_REQUEST_NOT_FOUND；终态冲突、内容不同或目标已删除409 RESTAURANT_STATE_CONFLICT；未预期写入失败500 COMMON_INTERNAL_SERVER_ERROR，事务整体回滚。响应丢失可用相同内容重试确认结果。

### 5.6 驳回申请

`POST /api/admin/restaurant-requests/{id}/reject`，请求：

```json
{"reason":"信息不实"}
```

reason必填，DTO最多200个UTF-16单元，去Unicode首尾空白后不能为空，服务端另校验最多200个码点；不接受其他字段。前端提供“图片模糊”“无效地址”“信息不实”快捷原因，接口也接受符合规则的其他文字原因。

成功200，回执字段同5.5，status=REJECTED、rejectReason为规范化原因、publishedPhotos为空数组；新店restaurantId仍为空，纠错保留原ID。只将PENDING改为REJECTED并保存审核人/时间，正式商户、标签、原始申请及图片关联保持不变。相同原因重试返回replayed=true和首次回执；不同原因再次驳回或已通过后驳回均409 RESTAURANT_STATE_CONFLICT。目标已删除仍允许驳回。拒绝后图片仍为私有BOUND，不触发清理或正式公开。

错误：401/403权限错误；400校验、未知字段或非法ID；404申请不存在；409终态冲突；500未预期故障。身份与公共错误码同5.5。

## 6. 餐厅公开读取

### 6.1 分类

`GET /api/restaurant-categories`无参数，成功200、data为`[{"code":"HOT_POT","name":"火锅","sortOrder":1,"enabled":true}]`，按sortOrder、code排序。包括停用分类以展示历史资料，停用分类不能用于新建或切换分类。

### 6.2 目录

`GET /api/restaurants`

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| `keyword` | 空 | 最长100字符，搜索名称/地址/标签；通配符按字面量处理 |
| `category` | 空 | 大写分类编码；空表示不限 |
| `sort` | score | score/taste/value/service/ambience/reviews/price/distance/recommend |
| `hideClosed` | false | true时隐藏停业商户 |
| `maxPrice` | 无 | 大于0，最多8位整数和2位小数；未知价格不符合上限筛选 |
| `latitude` / `longitude` | 无 | 用户GCJ-02原点，范围[-90,90]/[-180,180]，必须成对提供 |
| `maxDistanceMeters` | 无 | 整数1–20040000，以用户原点计算；缺位置商户不符合距离筛选 |
| `west/east/south/north` | 无 | 可选视口，四边同时提供，经度[-180,180]、纬度[-90,90]，west<east且south<north |
| `page` | 1 | 正整数，最多2147483647 |
| `pageSize` | 8 | 1–100 |
| `tiers` | 空 | 可重复查询参数，最多6项，bucket/super/large/medium/small/none |
| `view` | all | all/recommended/pitfall |

条件取交集，在完整可见集合上排序、计数后分页，时间/名称/ID提供稳定次序。评分类与推荐率排序未知值排后，人均升序；距离按请求用户原点升序，缺原点时距离为null且按名称/ID稳定排序。视口不支持跨日期变更线，边界包含，提供视口时排除待定位记录。用户坐标不保存。

兼容字段口径：tier按未展示舍入的均分划分，>=8.6 bucket、>=7.4 super、>=6.2 large、>=5 medium、其余small，无评分none；view=recommended为均分>=6.2，pitfall为均分<5，均排除无人评分。当前用户界面不要求展示tier。

成功200，data为`{records,total,page,pageSize,totalPages}`；空或越界页不返回404。单条餐厅结构：

```json
{
  "id":"101","name":"商户名称","categoryCode":"OTHER","categoryName":"其他","restaurantType":"REGULAR",
  "supportsDineIn":true,"supportsDelivery":null,"pricePerPerson":25,"priceKind":"EXACT",
  "address":"商户地址","latitude":30.670582,"longitude":104.107260,"needsLocation":false,
  "businessStatus":"OPERATING","createdAt":"2026-11-03T06:00:00Z","updatedAt":"2026-11-03T06:00:00Z",
  "distanceKm":null,"tags":["家常菜"],"score":null,"recommendRate":null,"reviewCount":0,"participantCount":0,
  "tier":"none","dimensions":{"taste":null,"value":null,"service":null,"ambience":null}
}
```

无人计分的分数/推荐率/四维为null，真实0分保持0。reviewCount计全部有效点评，participantCount计每人最新有效代表。score为代表点评综合分累计值/人数，recommendRate为推荐人数/人数（0–1），历史点评不重复贡献评分。地址、价格和位置未知保持null，不用模拟值补齐。列表/详情不返回创建者、删除元数据或数据库版本。

目录、详情及管理回执的restaurant新增coverPhoto：无正式相册首图为null，否则为`{id,position:0,cover:true,url}`，例如`{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}`。首页/地图卡片直接使用此正式封面；目录在分页完成后按当前页商户ID集合一次批量补齐元数据，不逐卡片读取相册/点评，不在列表查询中访问OSS。查询要求商户未删除、来源申请已通过且目标一致；仅有待审照片或点评照片不会产生封面。

### 6.3 详情与地图点位

`GET /api/restaurants/{id}`成功200，data为上述单条餐厅；不存在/已删除404 `RESTAURANT_NOT_FOUND`，停业可读。详情没有用户查询原点，distanceKm为null。

`GET /api/restaurants/map-points`必须提供四个视口边界，其他筛选及排序与目录相同。page/pageSize不扩大点位数量，固定最多100条。data为`{points,matchedCount,returnedCount,limit:100,truncated}`，points包含id/name/latitude/longitude/score/pricePerPerson/businessStatus；matchedCount为完整符合条件数量，truncated表示未全部返回。恰好100条且无更多结果时为false。

无视口400 `RESTAURANT_INVALID_INPUT`；缺边、非法范围/参数400 `COMMON_VALIDATION_FAILED`。查询不会访问高德或写入资料。

### 6.4 正式商户相册列表

`GET /api/restaurants/{id}/photos`允许匿名读取，无请求体，成功200且Cache-Control:no-store。默认limit=20，范围1–50；cursor可省略或原样使用上次nextCursor，为当前相册图片关联ID正整数字符串，不是media_file_id或position。

```json
{"records":[{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}],"nextCursor":"801"}
```

按position、id升序，在SQL中最多读取limit+1条判断后续；仅有后续记录时nextCursor为本页最后一张关联ID，否则null。上例表示仍有下一页，不表示相册只有一张。cover仅在position=0为true；后续页第一条不会被误作封面。无图或已读到末尾为records:[]、nextCursor:null，不改变数据或访问OSS。

前端纠错页也使用本接口只读回显已有正式照片；读取和浏览不构成图片编辑，不将正式关联ID混入申请photoIds。photoIds仍仅接受本次新上传的文件ID，新增图审核通过后追加；旧图保持不变，新店投稿不查询已有相册。

仅返回已通过且来源目标与本商户一致的正式关联，不返回原文件ID、来源申请ID、上传者或OSS地址。商户不存在/已删除404 RESTAURANT_NOT_FOUND；非法资源ID或超出long范围400 RESTAURANT_INVALID_ID；cursor格式或limit无效400 COMMON_VALIDATION_FAILED；不存在或属于其他商户的游标404 RESTAURANT_PHOTO_NOT_FOUND。每页独立只读快照，后续新追加记录可在继续翻页时出现。

### 6.5 正式商户图片读取

`GET /api/restaurants/{id}/photos/{photoId}`允许匿名读取。photoId为6.4返回的稳定正式关联ID，禁止传任意文件ID读取。成功200、原始image/jpeg字节、Cache-Control:no-store及X-Content-Type-Options:nosniff，不使用JSON封装，不重定向或返回签名OSS链接。

逐次检查商户未删除、图片属于商户正式相册、来源申请已通过且目标一致，然后通过file服务验证原上传人、RESTAURANT用途和BOUND状态，最后在数据库事务外读取私有OSS。商户软删除后列表/图片停止公开，关联与文件保留；恢复后重新可读。停业商户仍可读取。普通餐厅和食堂共用此协议。

非法ID400 RESTAURANT_INVALID_ID；图片/商户不可访问、图片不属于指定商户或来源不符合条件404 RESTAURANT_PHOTO_NOT_FOUND；关联文件不存在、状态/归属/用途不符404 FILE_NOT_FOUND；存储读取失败503 FILE_STORAGE_UNAVAILABLE，保留相册以便重试。客户端已取得的字节无法撤回。

## 7. 用户商户申请

`POST /api/restaurant-submissions`提交新商户；`POST /api/restaurants/{id}/change-requests`对现有未删除商户提交纠错。两者需Bearer，普通用户可用；纠错不要求是商户创建者。

```json
{"clientRequestId":"2bd0946c-291c-49af-8670-319ca4f64f5d","name":"商户名称","categoryCode":"HOT_POT","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"supportsDineIn":true,"supportsDelivery":null,"address":"商户地址","latitude":30.670582,"longitude":104.107260,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[],"photoIds":[]}
```

| 字段 | 规则 |
| --- | --- |
| `clientRequestId` | 必填UUID，同次失败重试沿用 |
| `name` | 去首尾Unicode空白后1–40码点，同名分店允许 |
| `categoryCode` | 必填有效分类，必须存在且可用 |
| `restaurantType` | REGULAR/CANTEEN，默认REGULAR |
| `priceKind` / `pricePerPerson` | 默认UNKNOWN/null；EXACT或ESTIMATE时为1–5000的整数元，UNKNOWN必须null |
| `address` | 最多80码点，空白转null |
| `latitude` / `longitude` | 必填GCJ-02，纬度3–54、经度73–136，最多6位有效小数 |
| `locationConfirmed` | 必须true |
| `supportsDineIn` / `supportsDelivery` | true/false/null，null表示未知 |
| `businessStatus` | OPERATING/CLOSED，默认OPERATING |
| `tags` | 默认空，最多8项，不能null；规范化去重后总长最多80码点 |
| `photoIds` | 默认空，最多5个唯一正Long字符串，按顺序展示，必须本人有效的RESTAURANT用途临时文件 |

不可指定作者、商户ID、新建/纠错类型、审核人/状态、评分及未知字段；未知字段400 `COMMON_MALFORMED_REQUEST`。服务端决定CREATE/UPDATE类型。成功201、禁止缓存，data为`{"id":"51","status":"PENDING"}`，只是申请受理，不发布商户或覆盖公开资料。

同账号同UUID与原始规范化内容重试返回原回执，图片顺序也参与一致性判断。不同内容/目标/申请类型复用编号409 `RESTAURANT_STATE_CONFLICT`；新建通过后回填商户ID不被当作原目标变化。原始资料摘要不随管理员修订而更新。回执中的PENDING代表首次受理，不用于查询最新审核状态。

字段无效400 `RESTAURANT_INVALID_INPUT`或通用校验错误；分类不可用400 `RESTAURANT_CATEGORY_UNAVAILABLE`；纠错目标不存在/已删除404 `RESTAURANT_NOT_FOUND`；图片不可绑定409 `FILE_NOT_BINDABLE`。申请、图片绑定及顺序关联同事务保存，任一失败整体回滚。

## 8. 直接维护餐厅

这些接口均需有效Bearer及数据库当前管理员角色。普通用户即使是历史创建者也返回403 AUTH_FORBIDDEN，只能使用第7节申请接口；创建者和操作者来自服务端认证上下文，不接受客户端身份。

| 接口 | 规则与响应 |
| --- | --- |
| `GET /api/restaurants/{id}/management` | 仅管理员可读，包含已删除记录；成功data为`{restaurant,deleted}` |
| `POST /api/restaurants` | 请求为第7节name/categoryCode/priceKind/pricePerPerson/address/latitude/longitude/locationConfirmed/businessStatus/tags；不含申请UUID、类型、堂食/外卖或照片字段；201及Location，data为管理资料 |
| `PUT /api/restaurants/{id}` | 与直接新建相同字段，完整替换，成功200及管理资料；省略可选项按默认值处理，不能当PATCH |
| `DELETE /api/restaurants/{id}` | 无请求体/版本；软删除，成功200、deleted=true，不删除标签与统计，公开入口隐藏 |
| `POST /api/restaurants/{id}/restore` | 仅管理员，无请求体/版本；200及deleted=false，保持原创建者、经营状态和标签 |

管理资料中的restaurant字段与公开餐厅结构相同，响应禁止缓存。编辑不要求版本号，最后成功保存的整份资料及标签生效；事务锁住同一商户主行，不能部分保存或混合两次标签。新分类必须启用，当前停用分类可保持原值。删除不会把停业改为营业，恢复不会创建新归属。

直接维护协议仍不接收restaurantType、supportsDineIn、supportsDelivery；新建采用REGULAR/未知，更新保留原有这三个字段。类型和用餐方式的申请发布由5.5审核协议处理，不能将两个请求DTO混用。

不存在404 `RESTAURANT_NOT_FOUND`；普通账号403 `AUTH_FORBIDDEN`；服务内部身份防线可返回403 `RESTAURANT_FORBIDDEN`。已删除编辑、重复删除、重复恢复409 `RESTAURANT_STATE_CONFLICT`。未知、内部或版本字段400 `COMMON_MALFORMED_REQUEST`。直接新建无请求幂等键，超时须先核对结果。

## 9. 图片上传、绑定与读取

### 9.1 临时图片

`POST /api/files/images?purpose=REVIEW|RESTAURANT`，Bearer、multipart/form-data、单文件字段`file`；省略purpose默认REVIEW。成功201、禁止缓存，data：

```json
{"fileId":"501","byteSize":85600,"width":1200,"height":800,"expiresAt":"2026-11-04T06:00:00Z"}
```

只接受真实JPEG，单文件不超过300KiB、最长边1200px，multipart总大小不超过320KiB。服务端独立解码重编码，不信任文件名或Content-Type；PNG/WebP需由浏览器转换。前端原图限制20MiB/4000万像素。对象私有，不接收客户端Bucket、对象键或上传者，不返回OSS地址。

图片默认24小时临时有效。用途区分点评和商户申请，不可交叉绑定；相同Bucket下目录由服务端配置。响应丢失后重传不保证同一fileId，未绑定文件由到期清理兜底。

`DELETE /api/files/images/{fileId}`只允许上传者取消未绑定文件。204无响应体表示完成；202表示删除意图已保存，云删除待后台重试。非法/他人文件404 `FILE_NOT_FOUND`；已绑定409 `FILE_ALREADY_BOUND`。文件清理先保存意图，再在事务外删除OSS，默认每60秒最多处理50条到期记录；BOUND不按临时期限清理。

错误：400 `FILE_INVALID_IMAGE`；413 `FILE_TOO_LARGE`；409 `FILE_NOT_BINDABLE`；503 `FILE_STORAGE_UNAVAILABLE`。上传、取消和绑定失败不会暴露OSS内部异常。

### 9.2 正式点评图与商户申请图

`GET /api/reviews/{reviewId}/photos/{position}`公开，position为0–4；先校验父店、点评、关联和REVIEW用途，再返回私有JPEG，禁止缓存及nosniff。隐藏/不存在404 `FILE_NOT_FOUND`，云不可用503。

原商户申请图片通过第5.4节管理员接口核对；审核正式发布后另通过6.4/6.5的商户相册关联公开，不能用点评图片接口或任意文件ID读取。图片绑定、申请/点评及关联在各自数据库事务提交；正式发布与审核事务原子提交，BOUND本身不等于公开。已传给浏览器的字节无法撤回。

## 10. 点评

### 10.1 发布

`POST /api/restaurants/{restaurantId}/reviews`，需Bearer，作者来自当前身份；停业可评，不存在/已删除餐厅404。

```json
{"clientRequestId":"d6fb79a2-6866-41ae-9c38-13d239668c21","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"recommended":true,"body":"这次出餐很快，分量也比较足。","photoIds":[]}
```

UUID必填；四维完整，0–10且步进0.5；recommended为真实布尔值；正文去首尾Unicode空白后5–500码点，按纯文本展示。photoIds最多5个唯一、本人有效REVIEW临时文件ID。未知字段及嵌套scores额外字段拒绝。

首次201，data为`{"reviewId":"101","restaurantId":"26","replayed":false}`；同账号同UUID、餐厅及规范化内容重放200、原ID、replayed=true。不同内容409 `REVIEW_REQUEST_CONFLICT`，已删除点评的原UUID重放409 `REVIEW_REQUEST_DELETED`，改UUID才是新点评。

点评、图片和计分统计同事务。每次真实发布保留历史；同一作者每店只取最新有效点评计分，不重复增加计分人数。无效输入400 `REVIEW_INVALID_INPUT`或`COMMON_MALFORMED_REQUEST`；并发/锁竞争重试耗尽503 `REVIEW_TEMPORARILY_UNAVAILABLE`，保留原UUID重试。

### 10.2 按作者分组分页

`GET /api/restaurants/{restaurantId}/reviews`公开。page默认1、正整数；pageSize默认8、1–50，按作者组计数。200、禁止缓存，data结构：

```json
{"records":[{"author":{"id":"22","username":"同学甲"},"currentReview":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"tier":"super","recommended":true,"body":"这次出餐很快，分量也比较足。","createdAt":"2026-11-03T06:00:00Z","scoringStatus":"current","photos":[{"id":"501","position":0,"url":"/api/reviews/101/photos/0"}]},"historyCount":0,"nextHistoryCursor":null}],"totalGroups":1,"totalReviews":1,"page":1,"pageSize":8,"totalPages":1}
```

每组只返回作者最新有效点评，历史不内嵌；照片有序最多5张。totalGroups与totalReviews区分人数和条数，空/越界页返回真实总数与空records。父店不可见404，停业可读。ID/分页非法400 `REVIEW_INVALID_QUERY`或通用校验错误。

### 10.3 同作者历史

`GET /api/restaurants/{restaurantId}/reviews/authors/{authorId}/history`公开；pageSize默认5、1–20；cursor可省略或原样传nextHistoryCursor/nextCursor，最长200字符。

200、禁止缓存，data为`{author,currentReviewId,records,hasMore,nextCursor}`。records结构与currentReview相同，但scoringStatus为history；按createdAt/id倒序、排除当前代表及游标锚点。无历史返回空数组；作者无记录时author/currentReviewId为null。

游标绑定餐厅、作者、时间和真实锚点，不是授权凭据；不能跨目标复用或自行修改。非法游标400 `REVIEW_INVALID_CURSOR`，无效ID400 `REVIEW_INVALID_QUERY`。每段是独立快照，currentReviewId变化时应刷新主列表；父店隐藏404。

### 10.4 本人删除

`DELETE /api/reviews/{reviewId}`需Bearer，仅原作者可删，管理员不绕过归属。无请求体；首次与本人重复删除均204无内容、禁止缓存。

删除历史只减少条数；删除最新由上一条接替，删除本人最后一条移除其计分贡献。回复/图片关联删除，媒体登记异步清理意图，不保证204时OSS已物理删除。原发布UUID持续保留删除回执，不允许重放重建点评。

非本人403 `REVIEW_FORBIDDEN`；点评不存在且无本人回执404 `REVIEW_NOT_FOUND`；首次删除时父店隐藏404 `RESTAURANT_NOT_FOUND`；非法ID400 `REVIEW_INVALID_QUERY`；竞争耗尽503 `REVIEW_TEMPORARILY_UNAVAILABLE`。不提供恢复点评接口。

## 11. 单层回复

`POST /api/reviews/{reviewId}/replies`需Bearer：

```json
{"clientRequestId":"35a4d3f7-476d-4201-b698-d15880a14696","body":"谢谢分享，下次试试。"}
```

UUID必填，正文去首尾Unicode空白后1–200码点；作者与时间由服务端确定，无子回复字段。首次201，data为`{replyId,reviewId,replayed:false}`，同账号/UUID/点评/规范化正文重放200及replayed=true，不同内容或点评409 `REPLY_REQUEST_CONFLICT`。仅写回复，不影响评分统计或作者代表。

`GET /api/reviews/{reviewId}/replies`公开；pageSize默认10、1–50；cursor可省略或原样使用nextCursor，最长200字符。200，data为`{records:[{id,author:{id,username},body,createdAt}],hasMore,nextCursor}`，按createdAt/id升序排除游标锚点。空结果为[]/false/null。作者缺失显示“用户不可用”，不返回邮箱或请求摘要。

两者禁止缓存，支持最新和历史点评，父店或点评不存在/已删除不可访问。非法字段400 `REPLY_INVALID_INPUT`或通用错误；游标错误400 `REPLY_INVALID_CURSOR`；点评不存在404 `REPLY_REVIEW_NOT_FOUND`；父店隐藏404 `RESTAURANT_NOT_FOUND`；竞争/超时耗尽503 `REPLY_TEMPORARILY_UNAVAILABLE`。游标不是授权令牌，跨段不保证固定快照。

## 12. 状态、部署与开发代理

`GET /api/public/status`公开，业务封装的data为`{"application":"ginkgo-food-map-v2","architecture":"modular-monolith"}`，仅确认应用可响应，不检查依赖。

`GET /actuator/health`公开，Actuator原生响应`{"status":"UP"}`，不使用业务封装；依赖DOWN通常503。邮件健康检查验证SMTP连接/TLS/认证，但不发送邮件。Vite不代理Actuator，应直连后端。`/actuator/info`需要Bearer，采用Actuator原生格式。

部署站点的`GET /version.txt`由Nginx返回40位提交号纯文本及no-store，不是本地Vite或后端业务接口。前端Docker镜像的`GET /nginx-health`返回纯文本ok，仅检查Nginx，不检查数据库或后端。

开发代理`GET /_AMapService/v3/place/text`用于主动高德门店搜索，keywords必填、去空白后最多100字符，page默认1、范围1–100；代理固定餐饮类型050000、每页10条、城市成都市、citylimit=false，返回高德原生格式，不用ApiResponse。它由Vite代理插件提供，不是Spring接口；凭据由服务端配置，不进入业务响应。门店选择不会自动写入本站餐厅，只有用户提交申请才持久化资料。

## 13. 通用错误与能力边界

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 字段类型、必填、范围或白名单校验失败 |
| 400 | COMMON_MALFORMED_REQUEST | JSON语法/读取/严格DTO未知字段错误 |
| 401 | AUTH_UNAUTHENTICATED | 身份缺失、失效或不可使用 |
| 403 | AUTH_FORBIDDEN | 当前角色无权限 |
| 405 | COMMON_METHOD_NOT_ALLOWED | 已通过认证边界但HTTP方法不匹配 |
| 409 | COMMON_DATA_CONFLICT | 数据库完整性约束冲突 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期故障，不能当成成功或空数据 |

其他业务错误码见各接口章节。认证过滤可先于MVC返回401，不能用错误方法探测接口是否存在。限流响应不保证Retry-After、剩余次数或精确解锁时间。

撤回申请、相册图片删除/替换/重排/手动封面、编辑点评及恢复点评没有可调用接口。只有首次通过审核才创建或更新正式商户并发布其附图，待审/驳回申请不能作为公开商户或相册返回，历史已通过申请不自动补发布图片。前端餐厅/食堂详情已接入6.4/6.5：每页20张，公开列表及JPEG读取省略Bearer，按需加载下一页，最多4个图片请求并发，临时Blob在切店/卸载释放；管理员页面使用publishedPhotos显示实际发布记录。首页/地图卡片使用目录或详情返回的coverPhoto，直接匿名加载对应JPEG，不另发逐商户相册/点评查询。
