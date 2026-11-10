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
| 评分 | 接口总分与四维评分保留0–10，前端展示统一除以2换算为五分制；发布仍提交接口原分制 |
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
| GET | `/api/me/growth` | Bearer | 200 | 本人经验及数字等级 |
| GET | `/api/me/reviews` | Bearer | 200 | 本人点评按店铺分组分页 |
| GET | `/api/me/profile` | Bearer | 200 | 本人资料与脱敏绑定邮箱 |
| PATCH | `/api/me/profile` | Bearer | 200 | 保存或清空本人简介 |
| GET | `/api/me/restaurant-requests` | Bearer | 200 | 本人投稿/纠错分页及状态计数 |
| GET | `/api/me/restaurant-requests/{id}` | Bearer | 200 | 本人申请原内容及审核结果 |
| GET | `/api/me/restaurant-requests/{id}/photos/{position}` | Bearer | 200 JPEG | 本人申请私有附图 |
| PUT | `/api/me/restaurant-requests/{id}` | Bearer | 200 | 修改本人待审申请 |
| POST | `/api/me/restaurant-requests/{id}/withdraw` | Bearer | 200 | 撤回本人待审申请 |
| POST | `/api/me/restaurant-requests/{id}/resubmit` | Bearer | 201 | 驳回后重提为新申请 |
| POST | `/auth/logout` | Bearer | 200 | 撤销当前会话 |
| GET | `/api/admin/me` | 管理员 | 200 | 工作台身份校验 |
| GET | `/api/admin/restaurant-requests` | 管理员 | 200 | 商户申请分页 |
| GET | `/api/admin/restaurant-requests/{id}` | 管理员 | 200 | 申请与当前资料 |
| GET | `/api/admin/restaurant-requests/{id}/photos/{position}` | 管理员 | 200 JPEG | 申请私有图片 |
| POST | `/api/admin/restaurant-requests/{id}/approve` | 管理员 | 200 | 审核并发布资料及附图 |
| POST | `/api/admin/restaurant-requests/{id}/reject` | 管理员 | 200 | 驳回申请 |
| GET | `/api/restaurant-categories` | 公开 | 200 | 分类字典 |
| GET | `/api/restaurants/{id}/windows` | 公开 | 200 | 食堂完整窗口集合 |
| GET | `/api/canteen-windows/{id}` | 公开 | 200 | 独立窗口详情 |
| GET | `/api/admin/restaurants/{id}/windows` | 管理员 | 200 | 窗口管理集合，含删除项 |
| POST | `/api/admin/restaurants/{id}/windows` | 管理员 | 200 | 幂等新建窗口 |
| PUT | `/api/admin/canteen-windows/{id}` | 管理员 | 200 | 编辑或设置停业/营业 |
| DELETE | `/api/admin/canteen-windows/{id}` | 管理员 | 204 | 软删除窗口 |
| POST | `/api/admin/canteen-windows/{id}/restore` | 管理员 | 204 | 恢复原窗口 |
| GET | `/api/canteen-windows/{id}/photos` | 公开 | 200 | 窗口相册，最多5张 |
| GET | `/api/canteen-windows/{id}/photos/{fileId}` | 公开 | 200 JPEG | 窗口图片字节 |
| POST | `/api/admin/canteen-windows/{id}/photos` | 管理员 | 200 | 上传回执入册或幂等重试 |
| DELETE | `/api/admin/canteen-windows/{id}/photos/{fileId}` | 管理员 | 204 | 删除窗口相册照片 |
| GET | `/api/canteen-windows/{id}/reviews` | 公开 | 200 | 窗口点评按作者分页 |
| GET | `/api/canteen-windows/{id}/reviews/authors/{author}/history` | 公开 | 200 | 窗口同作者历史 |
| POST | `/api/canteen-windows/{id}/reviews` | Bearer | 200 | 发布窗口点评或重放 |
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
| POST | `/api/reviews/{reviewId}/reports` | Bearer | 201 / 200重复 | 举报点评 |
| POST | `/api/review-replies/{replyId}/reports` | Bearer | 201 / 200重复 | 举报回复 |
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

`username`字段必填，可填写用户名或注册邮箱，原始输入最多254个UTF-16单元，去首尾空白后查询；含`@`时按邮箱查询并转小写，否则按用户名查询。两种方式共用同一用户ID的登录失败限流。密码必填，DTO最多72个UTF-16单元，服务端额外限制UTF-8不超过72字节，原样匹配BCrypt，不trim。

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
  "records":[{"id":"51","requestType":"UPDATE","status":"PENDING","name":"商户名称","restaurantId":"101","previousRequestId":"48","submittedBy":"22","submitterName":"同学甲","createdAt":"2026-11-03T06:00:00Z","photoCount":1}],
  "total":1,"totalPages":1,"page":1,"pageSize":20,
  "counts":{"PENDING":1,"APPROVED":0,"REJECTED":0}
}
```

`counts`为相同关键字条件下三个状态的数量，不受当前状态及分页限制；`total`为所选状态的完整数量。本人撤回的WITHDRAWN申请不进入管理员队列和状态计数，但管理员仍可按ID读取其详情；`previousRequestId`仅驳回后重提的新申请有值，指向原驳回申请。空结果200、空records、total/totalPages为0；越界页保留请求页码并返回空记录。分页、计数和批量署名在同一个只读快照内读取。署名缺失显示“用户不可用”，不返回邮箱、密码、请求摘要或OSS对象键。

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

- 首次通过时，REGULAR必须提供可用的categoryCode；CANTEEN必须使用categoryCode=null或省略。类型与分类不匹配返回400 RESTAURANT_INVALID_INPUT，不自动将食堂归到“其他美食”。旧的已通过食堂按原完整内容重试仍返回首次回执，不重新写入历史分类。
- CREATE创建正式餐厅和有序标签，created_by/updated_by均为实际审核管理员，新ID回填申请restaurant_id，request_type保持CREATE。投稿人保留在submitted_by，不因此获得直接维护权限。
- UPDATE更新原ID及餐厅类型、堂食/外卖三态，保留既有创建者、来源及评分；before_data保存事务内更新前的业务字段及标签（含类型、用餐方式），approved_data保存规范化后的最终业务字段。新店before_data为空。存在任意窗口关联的食堂不能改为普通餐厅，返回409 RESTAURANT_STATE_CONFLICT，包括软删除窗口仍有外键引用的情况。
- 商户、标签、正式相册关联、审核状态和成长奖励在同一个事务提交或回滚。首次通过按原submitted_by计奖：CREATE默认+20，按申请去重；UPDATE默认+10，按用户和店铺去重，后续纠错可正常通过但不重复领奖。重复通过和历史已通过申请不补发经验，管理员直接维护与驳回不计奖；奖励写入失败整次审核返回500 COMMON_INTERNAL_SERVER_ERROR，不保留已通过结果。纠错统一先锁商户、再锁申请；新店先锁申请并创建正式商户；文件校验按文件ID升序加锁。审核人取当前认证，时间由数据库生成，不能回退终态。
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

`GET /api/restaurant-categories`无参数，成功200、data为`[{"code":"FAST_FOOD","name":"小吃快餐","sortOrder":1,"enabled":true}]`，按sortOrder、code排序。包括停用分类以展示历史资料；前台导航和新建选择只使用enabled=true，停用分类不能用于新建或切换分类。

| 启用编码 | 名称 |
| --- | --- |
| FAST_FOOD | 小吃快餐 |
| CHINESE | 中餐地方菜 |
| HOT_POT | 火锅串串 |
| BARBECUE | 烧烤烤肉 |
| DRY_POT_FISH | 香锅烤鱼 |
| INTERNATIONAL | 国际美食 |
| DESSERT | 甜品烘焙 |
| DRINK | 饮品 |
| OTHER | 其他美食 |

普通餐厅只指定一个主营类目，没有二级分类；食堂由restaurantType=CANTEEN表示，categoryCode和categoryName均为null，不归入九类。食堂窗口同样不设分类。旧申请的原始分类编码和已完成审核快照保留；旧待审食堂首次通过时使用空分类，普通餐厅需选用可用分类。

### 6.2 目录

`GET /api/restaurants`

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| `keyword` | 空 | 最长100字符；ES多字跨名称/分类/标签/地址AND分词匹配，单个汉字查四字段单字索引；SQL降级使用原有包含匹配；输入不作为查询语法 |
| `category` | 空 | 大写分类编码；空表示不限 |
| `restaurantType` | 空 | REGULAR普通餐厅 / CANTEEN食堂；空或省略不限，大小写严格匹配 |
| `diningMode` | 空 | dineIn支持堂食 / delivery支持外卖；空或省略不限，只匹配明确true，false及null不命中 |
| `sort` | auto | auto/relevance/score/reviews/price/distance/recommend；auto在多字关键词时请求相关度，空关键词或单个汉字使用综合分；显式排序保持有效；四维排序仍返回400 |
| `hideClosed` | false | true时隐藏停业商户 |
| `maxPrice` | 无 | 大于0，最多8位整数和2位小数；未知价格不符合上限筛选 |
| `latitude` / `longitude` | 无 | 用户GCJ-02原点，范围[-90,90]/[-180,180]，必须成对提供 |
| `maxDistanceMeters` | 无 | 整数1–20040000，以用户原点计算；缺位置商户不符合距离筛选 |
| `west/east/south/north` | 无 | 可选视口，四边同时提供，经度[-180,180]、纬度[-90,90]，west<east且south<north |
| `page` | 1 | 正整数，最多2147483647 |
| `pageSize` | 8 | 1–100 |

条件取交集，在完整可见集合上排序、计数后分页。关键词价格/点评数/距离排序同值按数字ID升序，ES与SQL采用相同规则；综合分和好评率在目录及搜索中均先四舍五入，同值按数字ID升序。评分类与推荐率排序未知值排后，人均升序；好评率按百分数的一位小数比较，不再按计分人数或原始尾数破同分。距离按请求用户原点升序，缺原点时距离为null且按名称/ID稳定排序。视口不支持跨日期变更线，边界包含，提供视口时排除待定位记录。用户坐标不保存。

餐厅类型读取正式restaurant_type，独立于美食品类；食堂本身无美食分类，因此CANTEEN与具体美食品类组合时返回空集合，不自动忽略任一条件。类型及用餐方式过滤同时作用于COUNT、排序分页和地图点位。非法restaurantType/diningMode返回400 `COMMON_VALIDATION_FAILED`，不静默忽略。公开列表和地图共用RestaurantQueryService引擎策略；字典及详情始终读取MySQL。ES查询需同时显式开启ES_ENABLED及ES_QUERY_ENABLED，默认不启用。无关键词、单个汉字（按Unicode码点识别）或地理条件缺原点时，完整查询走SQL。综合分/好评率/价格/点评数/有原点距离排序及默认相关度均可走ES；未知价格和距离排后。

杯型计算与响应字段tier已移除；目录和地图请求若携带view、tiers或tiers数组参数，返回400 `RESTAURANT_INVALID_INPUT`，不静默忽略旧筛选。详情与点评保留四维评分展示。


成功200，data为`{records,total,page,pageSize,totalPages,accessiblePages,search}`。非空关键词统一要求`(page-1)*pageSize+pageSize <= 10000`，超过返回400 `RESTAURANT_INVALID_INPUT`（不会因ES开关或降级而改变）；窗口内越界页为空、200。`total`/`totalPages`是真实总量，`accessiblePages=min(totalPages,floor(10000/pageSize))`；无关键词不限制窗口。单条餐厅结构：

```json
{
  "id":"101","name":"商户名称","categoryCode":"OTHER","categoryName":"其他","restaurantType":"REGULAR",
  "supportsDineIn":true,"supportsDelivery":null,"pricePerPerson":25,"priceKind":"EXACT",
  "address":"商户地址","latitude":30.670582,"longitude":104.107260,"needsLocation":false,
  "businessStatus":"OPERATING","createdAt":"2026-11-03T06:00:00Z","updatedAt":"2026-11-03T06:00:00Z",
  "distanceKm":null,"tags":["家常菜"],"score":null,"recommendRate":null,"reviewCount":0,"participantCount":0,
  "dimensions":{"taste":null,"value":null,"service":null,"ambience":null}
}
```

无人计分的分数/推荐率/四维为null，真实0分保持0。reviewCount计全部有效点评，participantCount计每人最新有效代表。score为代表点评综合分累计值/人数，四舍五入保留一位小数；recommendRate为推荐人数/人数（0–1），四舍五入保留三位小数，对应百分比一位小数（0.923表示92.3%）。历史点评不重复贡献评分，原始累计值不变。地址、价格和位置未知保持null，不用模拟值补齐。列表/详情不返回创建者、删除元数据或数据库版本。

食堂响应使用`"restaurantType":"CANTEEN","categoryCode":null,"categoryName":null`。不传category时目录及地图包含食堂；指定美食品类时不包含食堂。餐厅类型通过现有前端独立筛选选择，不把CANTEEN作为美食分类参数。

目录、详情及管理回执的restaurant新增coverPhoto：无正式相册首图为null，否则为`{id,position:0,cover:true,url}`，例如`{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}`。首页/地图卡片直接使用此正式封面；目录在分页完成后按当前页商户ID集合一次批量补齐元数据，不逐卡片读取相册/点评，不在列表查询中访问OSS。查询要求商户未删除、来源申请已通过且目标一致；仅有待审照片或点评照片不会产生封面。

### 6.2.1 正式搜索与简单降级契约（2026-11-05）

- 查询与建索引分别使用ik_smart和ik_max_word。所有查询词必须在名称、分类名称、标签及地址的并集中满足；短语命中加权。完整规范化店名以独立排序层保证优先，其余按相关度及数字ID排序。显式业务排序不夹带相关度或页内重排。
- 单个汉字按Unicode码点和Han脚本判断，包含补充平面汉字；直接term查询name.chars/address.chars/categoryName.chars/tags.chars，任一字段包含即满足。auto保持原有score默认排序，显式relevance及业务排序照常执行。列表与地图共用此路径，不再因单字预定走SQL。查询前核对四字段可检索并固定本次物理索引，旧结构未就绪时整次SQL降级，避免未映射字段返回假空集合。
- 单字ES匹配采用原字语义，不继承MySQL排序规则的字符等价扩展，例如〇不会命中ASCII数字0。真实库中〇的ES字面命中为1家，旧SQL为3748家；SQL降级仍可能受数据库排序规则影响，因此基础匹配提示保留。
- score/recommend不再强制走SQL；ES在现有double字段上计算整数排序键：Math.round(ratingScore*10)、Math.round(recommendRate*1000)，同键按数字ID升序，未知末尾、真实零保留。SQL公开读取分别ROUND到一位/三位并使用相同排序；实际排序按此精度比较，不只是页面格式化，无需改mapping或重新导入。
- SQL路径保持原LIKE包含匹配；请求相关度但实际走SQL时，实际排序为score。首版不做无结果放宽、拼音、简称、错字或全局同义扩展。
- 所有硬筛选在ES计数及分页之前执行，MySQL只回查本页正式资料及同快照投影版本，不以LIKE再次过滤ES分词命中。不拿ES前100条交给SQL筛选后当作全量。
- ES不可达、客户端不存在、索引不存在、超时、部分分片失败、非精确总数或不完整结果，均整次执行原SQL；ES本身返回空集合不触发降级。查询前或回查快照存在当前版本PENDING/FAILED任务时保守走SQL；回查有删除、缺失、版本或硬条件变化时丢弃整页ES结果和total，完整重查SQL。
- ES查询接入Resilience4j进程内熔断，列表/地图共享：统计最近20次调用，至少10次后失败率达到50%打开，等待10秒后由首个符合ES入口条件的真实请求触发半开，只允许1次探测，其余直接走SQL；探测成功关闭、失败重新等待10秒。只包ES字段检查/分析/搜索，不把SQL回查或降级成功算ES成功，也不将同步积压或资料变化计为ES故障。空结果仍是成功。半开及熔断状态随进程重启清空，不跨实例共享。
- 不新增后台探活、查询自动重试或全量恢复工具；独立慢调用比例熔断本轮不启用。原ES执行超时800ms及客户端连接2s/响应3s保持不变；这不是整个多阶段ES流程或接口总时限，熔断不会取消已在执行的请求，SQL降级另需时间。
- `search`示例：`{"engine":"ES","degraded":false,"reason":"SEARCH","effectiveSort":"relevance","resultWindow":10000}`。降级示例：`{"engine":"MYSQL","degraded":true,"reason":"ES_UNAVAILABLE","effectiveSort":"score","resultWindow":10000}`。reason枚举：CATALOG、NO_LOCATION、DISABLED、SEARCH、ES_UNAVAILABLE、ES_CIRCUIT_OPEN、SYNC_PENDING、DATA_CHANGED；前三项是预定SQL路径，不标记degraded。ES_CIRCUIT_OPEN表示熔断打开或半开探测名额已用尽，响应仍200、engine=MYSQL、degraded=true；触发熔断的失败请求本身仍为ES_UNAVAILABLE。不再返回SINGLE_HAN。无关键词时resultWindow为null。
- 页面基于accessiblePages导航，保留真实total，并在需要时提示基础关键词匹配。切换关键词不会覆盖用户已选业务排序，auto按空关键词、单汉字、多字选择默认意图。
- 地理runtime字段从_source原始GCJ-02坐标计算，视口含边界，距离沿用SQL的6371000米半径和haversine公式；不使用geo_point量化坐标或不同地球半径改变既有规则。该方式有读取原文与计算成本，尚未做性能收益评测。
- 正常ES搜索为最终一致性；任务已成功到refresh可见之间仍有窗口。回查只能检查本页命中，不能发现所有未命中的资料变化；直接SQL绕过Outbox仍需补同步。独立翻页与列表/地图请求不承诺跨请求快照固定。

### 6.3 详情与地图点位

`GET /api/restaurants/{id}`成功200，data为上述单条餐厅；不存在/已删除404 `RESTAURANT_NOT_FOUND`，停业可读。详情没有用户查询原点，distanceKm为null。

`GET /api/restaurants/map-points`必须提供四个视口边界，其他筛选及排序与目录相同。page/pageSize不扩大点位数量，固定最多100条。data为`{points,matchedCount,returnedCount,limit:100,truncated,search}`，points包含id/name/latitude/longitude/score/pricePerPerson/businessStatus；matchedCount为完整符合条件数量，truncated表示未全部返回。恰好100条且无更多结果时为false。

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

不可指定作者、商户ID、新建/纠错类型、审核人/状态、评分及未知字段；未知字段400 `COMMON_MALFORMED_REQUEST`。服务端决定CREATE/UPDATE类型。成功201、禁止缓存，data为`{"id":"51","status":"PENDING"}`，只是申请受理，不发布商户或覆盖公开资料。

同账号同UUID与原始规范化内容重试返回原回执，图片顺序也参与一致性判断。不同内容/目标/申请类型复用编号409 `RESTAURANT_STATE_CONFLICT`；新建通过后回填商户ID不被当作原目标变化。原始资料摘要不随管理员修订而更新。回执中的PENDING代表首次受理，不用于查询最新审核状态。

已有带分类的旧食堂申请仍可按原请求内容重试，不改写原快照或摘要；新请求不得为食堂填写美食分类。

字段无效400 `RESTAURANT_INVALID_INPUT`或通用校验错误；分类不可用400 `RESTAURANT_CATEGORY_UNAVAILABLE`；纠错目标不存在/已删除404 `RESTAURANT_NOT_FOUND`；图片不可绑定409 `FILE_NOT_BINDABLE`。申请、图片绑定及顺序关联同事务保存，任一失败整体回滚。

## 8. 直接维护餐厅

这些接口均需有效Bearer及数据库当前管理员角色。普通用户即使是历史创建者也返回403 AUTH_FORBIDDEN，只能使用第7节申请接口；创建者和操作者来自服务端认证上下文，不接受客户端身份。

| 接口 | 规则与响应 |
| --- | --- |
| `GET /api/restaurants/{id}/management` | 仅管理员可读，包含已删除记录；成功data为`{restaurant,deleted}` |
| `POST /api/restaurants` | 基础字段加restaurantType、supportsDineIn、supportsDelivery；不含申请UUID或照片字段；201及Location，data为管理资料 |
| `PUT /api/restaurants/{id}` | 与直接新建相同字段，完整替换，成功200及管理资料；省略可选项按默认值处理，不能当PATCH |
| `DELETE /api/restaurants/{id}` | 无请求体/版本；软删除，成功200、deleted=true，不删除标签与统计，公开入口隐藏 |
| `POST /api/restaurants/{id}/restore` | 仅管理员，无请求体/版本；200及deleted=false，保持原创建者、经营状态和标签 |

管理资料中的restaurant字段与公开餐厅结构相同，响应禁止缓存。编辑不要求版本号，最后成功保存的整份资料及标签生效；事务锁住同一商户主行，不能部分保存或混合两次标签。新分类必须启用，当前停用分类可保持原值。删除不会把停业改为营业，恢复不会创建新归属。

直接维护接收restaurantType（REGULAR/CANTEEN）及supportsDineIn、supportsDelivery（true支持、false不支持、null未知）。管理页面始终显式提交类型及两个三态字段；提供类型时这三个字段完整替换，省略用餐方式等价于null，可将已有支持/不支持清为未知。食堂categoryCode必须为null，普通餐厅必须有有效分类；切换类型及分类在同一事务中保存。有关联窗口（包括软删除窗口）的食堂不能改为普通餐厅，409 `RESTAURANT_STATE_CONFLICT`，原资料、标签和搜索版本均保留。成功保存推进一次搜索投影版本，任何失败整体回滚。

兼容旧客户端：restaurantType省略或null且两个用餐方式均为null/省略时，新建为普通餐厅/用餐方式未知，更新保持已有这三个字段；省略类型却提交非null用餐方式返回400。非法类型400；未知、身份、申请UUID或图片字段仍不接受。不改变用户投稿和审核的独立DTO及历史幂等摘要。

管理员直接新建食堂的示例（PUT同样支持）：

```json
{"name":"校内餐厅","restaurantType":"CANTEEN","categoryCode":null,"supportsDineIn":true,"supportsDelivery":false,"priceKind":"UNKNOWN","pricePerPerson":null,"address":"校内地址","latitude":30.7544,"longitude":103.9312,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[]}
```

不存在404 `RESTAURANT_NOT_FOUND`；普通账号403 `AUTH_FORBIDDEN`；服务内部身份防线可返回403 `RESTAURANT_FORBIDDEN`。已删除编辑、重复删除、重复恢复409 `RESTAURANT_STATE_CONFLICT`。未知、内部或版本字段400 `COMMON_MALFORMED_REQUEST`。直接新建无请求幂等键，超时须先核对结果。

## 9. 图片上传、绑定与读取

V23消费更新：Outbox现支持可选单实例定时消费，默认关闭；成功写入ES后确认任务，失败延迟重试至上限后保留FAILED。接口成功仍仅代表MySQL及任务提交，不承诺已可在ES搜索到；无新增HTTP接口或失败重放入口，网站查询继续使用MySQL。

搜索同步副作用（V22）：商户新建、编辑、删除、恢复及审核通过在原业务事务内记录Outbox，点评统计更新同样记录；任务插入失败时原业务一起回滚。没有新增HTTP接口，成功只表示MySQL资料和任务已保存，不表示ES已同步。待审申请、驳回、回复及幂等重试不重复生成任务；V23消费者按上述独立开关启用。字段定义见[同步任务表](architecture/mysql-schema.md#restaurant-search-outbox)。

### 9.1 临时图片

`POST /api/files/images?purpose=REVIEW|RESTAURANT|WINDOW`，Bearer、multipart/form-data、单文件字段`file`；省略purpose默认REVIEW，WINDOW用途额外要求管理员，普通账号403。成功201、禁止缓存，data：

```json
{"fileId":"501","byteSize":85600,"width":1200,"height":800,"expiresAt":"2026-11-04T06:00:00Z"}
```

只接受真实JPEG，单文件不超过300KiB、最长边1200px，multipart总大小不超过320KiB。服务端独立解码重编码，不信任文件名或Content-Type；PNG/WebP需由浏览器转换。前端原图限制20MiB/4000万像素。对象私有，不接收客户端Bucket、对象键或上传者，不返回OSS地址。

图片默认24小时临时有效。REVIEW点评、RESTAURANT商家申请和WINDOW窗口相册不可交叉绑定；相同Bucket下分别使用`review-images/{UUID}.jpg`、`restaurant-images/{UUID}.jpg`和`windows-images/{UUID}.jpg`，临时绑定或审核通过不移动对象。响应丢失后重传不保证同一fileId，未绑定文件由到期清理兜底。配置与权限要求见[图片存储目录](operations/deployment.md#图片存储目录)。

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

成长副作用：接入后新提交成功的点评，在同一事务内尝试创建每用户每店唯一的经验奖励，默认5经验，可通过GROWTH_FIRST_REVIEW_EXPERIENCE配置；重复请求、同店再评或删评重发不重复领奖，普通本人删评不扣经验。已存在发布请求的重放分支不补发历史经验。计奖失败与点评、图片绑定、评分统计及搜索任务一起回滚，响应结构保持不变；最新经验与等级通过第14节查询。金额溢出或账目异常返回500，不提交部分结果。

### 10.2 按作者分组分页

`GET /api/restaurants/{restaurantId}/reviews`公开。page默认1、正整数；pageSize默认8、1–50，按作者组计数。sort默认DEFAULT，仅接受DEFAULT或TIME（大小写精确），非法或空值返回400通用校验错误。200、禁止缓存，data结构：

```json
{"records":[{"author":{"id":"22","username":"同学甲"},"currentReview":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"recommended":true,"body":"这次出餐很快，分量也比较足。","createdAt":"2026-11-03T06:00:00Z","scoringStatus":"current","photos":[{"id":"501","position":0,"url":"/api/reviews/101/photos/0"}]},"historyCount":0,"nextHistoryCursor":null}],"totalGroups":1,"totalReviews":1,"page":1,"pageSize":8,"totalPages":1}
```

每组只返回作者最新有效点评，历史不内嵌；照片有序最多5张。默认排序DEFAULT在完整作者组中按该条最新点评的当前有效点赞数降序、作者当前等级降序，再按createdAt及数字ID降序，之后才分页；历史点评点赞不并入主卡片。TIME仅按createdAt及数字ID降序。没有成长账户按零经验对应等级处理，同等级不继续比较原始经验。每次请求为独立只读快照，点赞、等级或最新点评变化后作者可能跨页移动，跨请求不保证固定排名；切换排序回到第一页。totalGroups与totalReviews区分人数和条数，空/越界页返回真实总数与空records。父店不可见404，停业可读。ID/分页非法400 `REVIEW_INVALID_QUERY`或通用校验错误。

### 10.3 同作者历史

`GET /api/restaurants/{restaurantId}/reviews/authors/{authorId}/history`公开；pageSize默认5、1–20；cursor可省略或原样传nextHistoryCursor/nextCursor，最长200字符。

200、禁止缓存，data为`{author,currentReviewId,records,hasMore,nextCursor}`。records结构与currentReview相同，但scoringStatus为history；按createdAt/id倒序、排除当前代表及游标锚点。无历史返回空数组；作者无记录时author/currentReviewId为null。

游标绑定餐厅、作者、时间和真实锚点，不是授权凭据；不能跨目标复用或自行修改。非法游标400 `REVIEW_INVALID_CURSOR`，无效ID400 `REVIEW_INVALID_QUERY`。每段是独立快照，currentReviewId变化时应刷新主列表；父店隐藏404。

### 10.4 本人删除

`DELETE /api/reviews/{reviewId}`需Bearer，仅原作者可删，管理员不绕过归属。无请求体；首次与本人重复删除均204无内容、禁止缓存。

删除历史只减少条数；删除最新由上一条接替，删除本人最后一条移除其计分贡献。回复/图片关联删除，媒体登记异步清理意图，不保证204时OSS已物理删除。原发布UUID持续保留删除回执，不允许重放重建点评。

非本人403 `REVIEW_FORBIDDEN`；点评不存在且无本人回执404 `REVIEW_NOT_FOUND`；首次删除时父店隐藏404 `RESTAURANT_NOT_FOUND`；非法ID400 `REVIEW_INVALID_QUERY`；竞争耗尽503 `REVIEW_TEMPORARILY_UNAVAILABLE`。不提供恢复点评接口。

### 10.5 本人点评按店铺分组

`GET /api/me/reviews`需Bearer，仅从当前认证身份确定作者；不允许选择其他用户，额外userId/authorUserId/offset查询字段不参与绑定。page默认1、正整数且不超过Integer.MAX_VALUE；pageSize默认8、范围1–50，分页单位为店铺组。

成功200，`Cache-Control: no-store`，data示例：

```json
{"author":{"id":"22","username":"同学甲"},"records":[{"restaurant":{"id":"26","name":"校园面馆","businessStatus":"OPERATING"},"currentReview":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"recommended":true,"body":"这次出餐很快，分量也比较足。","createdAt":"2026-11-03T06:00:00Z","scoringStatus":"current","photos":[]},"historyCount":0,"nextHistoryCursor":null}],"totalGroups":1,"totalReviews":1,"page":1,"pageSize":8,"totalPages":1}
```

每家店只返回该作者的最新有效点评，按createdAt、id倒序确定代表并排序，归组在LIMIT之前完成。totalGroups为当前可见店铺数，totalReviews包括这些店铺中的本人历史点评；空/越界页保留真实计数。restaurant只返回ID、名称和营业状态，不包含管理或删除元数据。当前仅覆盖已有restaurant_id目标（普通餐厅及历史食堂点评），独立window_id尚未接入；停业可见，不存在/已删除店铺整组排除且不计总数，恢复后原点评重新进入查询。

currentReview、照片相对URL及nextHistoryCursor与10.2/10.3一致；历史仍通过现有同店同作者接口按需读取，删除仍使用10.4。接口不改变计分或经验，不创建账户、回执或媒体记录，也不开放公开个人主页的跨店点评列表。

一次响应内作者、目标可见性、计数、页记录和照片来自同一只读RR快照；跨请求不是固定快照，新评/删除可能改变页位置，页面刷新或删除成功后回第一页。查询故障返回500，不降级成空列表；只有实际没有可见记录才返回空records及零计数。分页绑定/校验失败400，认证失效401 `AUTH_UNAUTHENTICATED`，认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`，已认证但方法不匹配405 `COMMON_METHOD_NOT_ALLOWED`。

## 11. 单层回复

### 11.1 发布与读取

`POST /api/reviews/{reviewId}/replies`需Bearer：

```json
{"clientRequestId":"35a4d3f7-476d-4201-b698-d15880a14696","body":"谢谢分享，下次试试。"}
```

UUID必填，正文去首尾Unicode空白后1–200码点；作者与时间由服务端确定，无子回复字段。首次201，data为`{replyId,reviewId,replayed:false}`，同账号/UUID/点评/规范化正文重放200及replayed=true，不同内容或点评409 `REPLY_REQUEST_CONFLICT`。仅写回复，不影响评分统计或作者代表。

`GET /api/reviews/{reviewId}/replies`公开；pageSize默认10、1–50；cursor可省略或原样使用nextCursor，最长200字符。200，data为`{records:[{id,author:{id,username},body,createdAt}],hasMore,nextCursor}`，按createdAt/id升序排除游标锚点。空结果为[]/false/null。作者缺失显示“用户不可用”，不返回邮箱或请求摘要。

两者禁止缓存，支持最新和历史点评，父店或点评不存在/已删除不可访问。非法字段400 `REPLY_INVALID_INPUT`或通用错误；游标错误400 `REPLY_INVALID_CURSOR`；点评不存在404 `REPLY_REVIEW_NOT_FOUND`；父店隐藏404 `RESTAURANT_NOT_FOUND`；竞争/超时耗尽503 `REPLY_TEMPORARILY_UNAVAILABLE`。游标不是授权令牌，跨段不保证固定快照。

发布请求的作者和UUID独立保存在回复回执中；删除后的原请求返回409 `REPLY_REQUEST_DELETED`，不能在原点评或其他点评重放重建。正常发布新回复仍使用新UUID。删除分页锚点后继续使用旧cursor会返回400 `REPLY_INVALID_CURSOR`，需从头读取。

### 11.2 删除本人回复

`DELETE /api/review-replies/{replyId}`需有效Bearer，路径为无前导零、范围1～Long.MAX_VALUE的回复ID，无业务查询参数及请求体。仅原回复作者可操作，点评作者和管理员均不能代删。首次成功及原作者重复删除均返回204，无响应体，`Cache-Control: no-store`；不提供恢复接口。

回复正文硬删除，回执仅保留作者、UUID、回复/点评ID和删除时间。原回复通知及点赞通知保留、首次已读时间不变，点击按17.5/18.4返回内容失效，不再读取原正文；对应点赞从回复作者当前获赞总数中扣除。其余回复、点评、商户计分及成长经验不变。删除整条点评也标记关联回复回执，禁止旧回复请求复活。

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

前端二次确认后调用；失败保留重试入口，204之后列表刷新失败仅重试读取。成功后丢弃旧分页游标，从头刷新。切换账号、点评或卸载会取消请求并丢弃旧身份回执；取消传输不能证明服务端尚未删除，可重新读取核对。

举报及管理员证据副本尚未接入，本次不提供举报处理或证据留存能力。

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

## 14. 本人成长摘要

`GET /api/me/growth`需Bearer，无业务请求参数，仅从当前认证身份确定用户，不能通过额外userId参数查询别人。无发奖、撤销、任意用户成长查询或经验修改HTTP接口。

成功200，`Cache-Control: no-store`，统一响应的data例如：

```json
{"userId":"11","experience":5,"level":1,"currentLevelThreshold":0,"nextLevelThreshold":100}
```

| 字段 | 含义 |
| --- | --- |
| userId | 当前账号ID，字符串。 |
| experience | 当前净经验，非负整数；等级升级不消耗经验。 |
| level | 从1开始的数字等级。 |
| currentLevelThreshold | 当前等级的累计经验门槛。 |
| nextLevelThreshold | 下一等级的累计经验门槛，最高级为null。 |

未获奖账号返回零经验、初始等级，GET不创建账户。初始门槛为0/100/400/1000，可通过GROWTH_LEVEL_THRESHOLDS配置，以英文逗号分隔；门槛必须从0开始、严格递增，奖励分值必须为正，否则启动配置校验失败。配置随部署生效，旧奖励保留实际发放经验，不因调参重发或重算；已撤销奖励的恢复未开放。

缺少、过期、会话失效或账号不可用的凭证返回401 `AUTH_UNAUTHENTICATED`；认证依赖暂不可用沿用503 `AUTH_SERVICE_UNAVAILABLE`；已认证但方法不匹配返回405 `COMMON_METHOD_NOT_ALLOWED`；未预期查询故障返回500 `COMMON_INTERNAL_SERVER_ERROR`，不降级成零经验。只有实际无账户记录才按零经验解释。

当前只接入普通餐厅及既有可发布餐厅目标的首次点评奖励；前端个人中心、成长详情及本人主页已使用本接口，退出或换号清理旧资料，错误不降级为零经验。审核通过奖励已接入，新店默认+20、同人同店纠错仅首次+10；点评获赞奖励已接入（规则见18节），点评默认/时间排序及内部批量等级查询已接入，作者数字等级展示及登录可见个人主页已接入。内部幂等撤销能力已提供给未来风控协作，当前没有公开处置入口。其他社区页面的待开放入口未增加后端HTTP接口。

## 15. 本人资料与简介

`GET /api/me/profile`、`PATCH /api/me/profile`均需Bearer，用户归属只取当前认证身份。GET无业务参数；额外userId查询参数不改变查询/修改对象。没有任意用户资料查询、改名、头像上传、换绑邮箱或列表隐私修改接口。

成功均为200、`Cache-Control: no-store`，统一响应的data例如：

```json
{"userId":"11","username":"校园食客","maskedEmail":"s***@example.edu.cn","bio":"喜欢探索校园美食"}
```

| 字段 | 含义 |
| --- | --- |
| userId | 当前用户ID，字符串。 |
| username | 当前用户名，只读。 |
| maskedEmail | 脱敏绑定邮箱，不返回完整邮箱；本地部分超过两个Unicode码点时保留首个码点，否则完全隐藏；保留域名，例如student@example.edu.cn为s***@example.edu.cn，ab@example.edu.cn为***@example.edu.cn，异常格式为***。 |
| bio | 已保存的纯文本简介；空字符串表示尚未填写或已清空。 |

PATCH只接受必填字符串bio，空字符串可清空；未知JSON字段（含userId、username、email、roles）返回400 `COMMON_MALFORMED_REQUEST`，数字、布尔值、数组或对象不能自动转为字符串；缺少或null返回400 `COMMON_VALIDATION_FAILED`。请求示例：

```json
{"bio":"喜欢探索校园美食"}
```

服务端将CRLF/CR统一为LF、移除首尾Unicode空白及FEFF，之后最多100个Unicode码点，表情按实际码点计数；允许内部换行和制表符。其他控制字符、未配对代理项不接受。常见联系方式与网址按下述基础识别规则检查，保存后直接生效，无人工审核流程。

检查副本做NFKC、去空白/Unicode格式字符并转小写；手机号检查还移除数字间常见单个分隔标点。识别大陆手机号及可选86前缀、带微信/wechat/wx/vx标记的账号及wxid_、带QQ/QQ群/企鹅标记的数字、带手机/电话/tel/联系方式标记的数字，以及scheme://、www.、常见联系方式协议、裸域名及裸IPv4。正常提及“微信支付”“QQ糖”及菜品编号允许；这是基础识别，可能误拦或漏过混淆及境外号码，不自动处罚账号。展示原文保留正常emoji连接符，使用纯文本。

仅修改bio及既有row_version、updated_at，不改变邮箱、用户名、密码、角色、账号状态或credential_version，原有效登录凭证可继续使用。规范化后与现值相同则不写入、不递增版本或修改更新时间。更新使用用户行锁；多标签页并发修改按最后提交的完整简介生效，不提供客户端版本/ETag冲突协议。超时或取消不能证明服务器未保存，同值重试安全；客户端必须等待成功回执或重新查询确认。

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | USER_BIO_TOO_LONG | 规范化后超过100码点。 |
| 400 | USER_BIO_INVALID | 含不支持的控制字符或无效Unicode。 |
| 400 | USER_BIO_CONTACT_NOT_ALLOWED | 命中常见微信、QQ、手机号或网址形式。 |
| 401 | AUTH_UNAUTHENTICATED | 身份缺失、过期、会话失效或账号不可用。 |
| 401 | USER_PROFILE_UNAVAILABLE | 认证后再次读取/加锁时本人记录已不可用。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖暂不可用，不能当成退出或空资料。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期查询/保存故障；事务失败回滚，不能伪装成功。 |

已通过认证但方法不匹配返回405 `COMMON_METHOD_NOT_ALLOWED`。资料读取失败不降级为未填写；返回字段为显式白名单，不暴露密码摘要、完整邮箱、权限或凭证版本。

## 16. 本人投稿与纠错记录

本节接口均需Bearer，仅操作当前认证身份提交的申请，返回`Cache-Control: no-store`。不复用管理员查询或审核权限，也不接受userId/submittedBy选择其他申请人；额外身份、offset或排序参数不会改变服务端范围。本人只能修改或撤回待审申请，驳回后可基于原申请创建新申请；管理员队列、审核结果和公开商户资料不受这些操作直接影响。

### 16.1 本人列表

`GET /api/me/restaurant-requests`参数：status默认ALL，可选ALL/PENDING/APPROVED/REJECTED/WITHDRAWN；page默认1、范围1–1000000；pageSize默认20、范围1–50。前端每页10条。按createdAt、id依次降序，先按本人和所选状态筛选再分页；同一店铺的不同申请独立展示，不按店铺合并。

成功200，统一封装的data示例：

```json
{"userId":"11","records":[{"id":"101","restaurantId":null,"previousRequestId":null,"requestType":"CREATE","status":"PENDING","name":"新店名","createdAt":"2026-11-07T05:00:00Z","reviewedAt":null,"withdrawnAt":null,"photoCount":0}],"counts":{"PENDING":1,"APPROVED":0,"REJECTED":0,"WITHDRAWN":0,"ALL":1},"total":1,"totalPages":1,"page":1,"pageSize":20}
```

userId为本人字符串ID；requestType为CREATE新店或UPDATE纠错，不因新店通过后回填restaurantId而改变。previousRequestId仅在驳回后重提的新申请上有值，指向原驳回申请；withdrawnAt仅在本人撤回后有值。name为原提交店名，photoCount为原申请关联图数；待审reviewedAt为null。counts始终为本人全部申请的状态计数，不受当前status筛选影响；total为所选状态总数。真正无记录返回空records、零计数及totalPages=0；超出末页也可返回空records，但保留实际总数。列表和计数共用一次数据库快照，跨次查询时新增申请或审核状态变化可能改变分页。

### 16.2 本人详情

`GET /api/me/restaurant-requests/{id}`成功200，data为：

```json
{"userId":"11","application":{"id":"101","restaurantId":null,"previousRequestId":null,"requestType":"CREATE","status":"PENDING","name":"新店名","createdAt":"2026-11-07T05:00:00Z","reviewedAt":null,"withdrawnAt":null,"photoCount":1},"proposed":{"name":"新店名","categoryCode":"OTHER","categoryName":"其他美食","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","supportsDineIn":null,"supportsDelivery":null,"tags":[]},"approved":null,"rejectReason":null,"targetAvailable":false,"photoPositions":[0]}
```

application字段同列表；proposed为原始提交业务快照，approved只在通过后返回实际生效业务快照（可包含管理员修订），否则null。两份快照均只包含示例中的业务字段，不含原始请求UUID、photoIds及旧内部字段；历史缺失字段可为null。rejectReason仅驳回有值；审核时间位于application.reviewedAt。不会返回审核人、纠错前before、管理端current资料、密码、邮箱、请求摘要或存储键。

photoPositions是原申请内0–4的有序位置，不是文件ID；真正无图返回[]。targetAvailable表示关联店铺在本次读取时未删除；停业仍为true，新店尚未通过或关联目标失效为false。目标被删除不删除或隐藏本人申请快照；不可用时前端不提供可用店铺跳转。不存在与其他人的申请均404 `RESTAURANT_REQUEST_NOT_FOUND`，管理员通过本接口也不能读取别人的申请。

### 16.3 修改待审申请

`PUT /api/me/restaurant-requests/{id}`请求体为完整业务内容，整体替换原提交：

```json
{"name":"修改后的店名","categoryCode":"OTHER","restaurantType":"REGULAR","priceKind":"UNKNOWN","pricePerPerson":null,"address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","supportsDineIn":null,"supportsDelivery":null,"tags":[],"photoIds":["1001","1002"]}
```

字段校验与首次投稿一致；`photoIds`为本人的0–5张RESTAURANT用途图片，按顺序整体替换，空数组表示不再附图。只允许PENDING申请；修改后仍为PENDING并重新等待审核，不产生新申请、不保留修改前的待审快照。关联目标已删除的UPDATE申请返回404；已通过、已驳回或已撤回返回409 `RESTAURANT_STATE_CONFLICT`。修改会更新原申请的业务快照与请求摘要，原clientRequestId不变；再次用旧内容重试首次投稿会因摘要不一致返回409。

成功200，data为`{"id":"101","status":"PENDING"}`。相同完整内容重复提交结果一致；附图替换只释放新列表不再保留的旧图，仍保留的图片继续使用原绑定，新图与申请内容在同一事务内更新，任一步失败全部回滚。

### 16.4 撤回待审申请

`POST /api/me/restaurant-requests/{id}/withdraw`无请求体，成功200，data为`{"id":"101","status":"WITHDRAWN"}`。仅PENDING可撤回；撤回写入withdrawnAt，不写审核人、通过快照或驳回原因，申请从管理员待审队列移除，原附图仍仅本人和管理员可读。重复撤回同一申请返回相同成功结果；已通过或已驳回返回409 `RESTAURANT_STATE_CONFLICT`。撤回不可撤销，需重新投稿时走正常提交或驳回重提。

### 16.5 驳回后重提

`POST /api/me/restaurant-requests/{id}/resubmit`请求体与首次投稿相同，必须携带新的`clientRequestId`：

```json
{"clientRequestId":"2f8c0d62-7f4e-4f0e-9aa1-52f6b48b4e21","name":"重提修订店名","categoryCode":"OTHER","restaurantType":"REGULAR","priceKind":"UNKNOWN","address":"学校附近","latitude":30.75,"longitude":103.93,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[],"photoIds":[]}
```

仅本人REJECTED申请可重提；新申请继承原申请的CREATE/UPDATE类型和UPDATE目标，写入previousRequestId指向原驳回申请，状态为PENDING。原申请保持REJECTED及原rejectReason，不重新进入待审，也不改审核字段。UPDATE目标已删除返回404；PENDING、APPROVED或WITHDRAWN返回409。相同clientRequestId和相同内容重试返回已创建的新申请ID；相同UUID改变内容或目标返回409 `RESTAURANT_STATE_CONFLICT`。原申请附图仍绑定在原申请上，重提需重新上传并提交新图片ID。

成功201，data为`{"id":"106","status":"PENDING"}`。

### 16.6 本人申请图片与错误

`GET /api/me/restaurant-requests/{id}/photos/{position}`按申请归属、关联位置及文件上传者/RESTAURANT用途/BOUND状态校验。待审、驳回、已撤回及已通过的本人申请均可读原附图，不因读取而公开图片。成功200为原始JPEG字节，另含`X-Content-Type-Options: nosniff`；前端携带固定Bearer取得Blob，不使用匿名img或OSS签名地址。OSS读取不占数据库事务。

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

已通过认证但方法不匹配返回405 `COMMON_METHOD_NOT_ALLOWED`。GET不改变审核状态、已读状态、经验、商户资料或图片绑定状态；PUT、撤回和重提只影响本人申请及其私有附图，不直接发布或修改公开商户资料。

## 17. 本人系统通知

通知列表、已读及删除接口均需有效Bearer，仅以当前身份作为收件人，返回`Cache-Control: no-store`；管理员不扩大收件箱权限。支持ALL（全部）、SYSTEM（审核结果及全体公告）、COMMENTS（点评回复）、LIKES（获赞）、FOLLOWERS（新增关注），普通用户不接受客户端发信或指定收件人；管理员全体公告入口见17.8。

### 17.1 查询收件箱与未读数

`GET /api/me/notifications?category=SYSTEM&page=1&pageSize=10`。category默认ALL，可选ALL/SYSTEM/COMMENTS/LIKES/FOLLOWERS；page默认1、范围1～1,000,000；pageSize默认20、范围1～50。额外userId、recipientUserId、offset、排序参数不能改变本人范围。按时间、来源内ID、消息类型倒序分页；列表、所选分类计数、各类未读数、最新系统摘要来自同一只读快照，浏览不标记已读。

成功200，统一响应data示例：

```json
{"userId":"22","category":"COMMENTS","records":[{"id":"302","category":"COMMENTS","sourceId":"901","reviewId":"101","actorUserId":"33","actorUsername":"校园食友","requestId":null,"requestType":null,"decision":null,"restaurantName":null,"rejectReason":null,"createdAt":"2026-11-07T09:00:00Z","readAt":null}],"total":1,"unreadCount":1,"unreadCounts":{"SYSTEM":1,"COMMENTS":1,"LIKES":0,"FOLLOWERS":0},"latestSystem":{"id":"301","category":"SYSTEM","sourceId":"51","requestId":"51","reviewId":null,"actorUserId":null,"actorUsername":null,"requestType":"UPDATE","decision":"REJECTED","restaurantName":"银杏小馆","rejectReason":"请补充地址","createdAt":"2026-11-07T08:00:00Z","readAt":null},"page":1,"pageSize":10,"totalPages":1}
```

所有非空ID为字符串。total、unreadCount属于所选category；unreadCounts始终为本人SYSTEM/COMMENTS/LIKES/FOLLOWERS各自未读数，ALL为四者之和。latestSystem为本人最新系统消息，不受当前分类及页码限制，无系统消息为null；首页据此显示系统摘要，避免被最新评论替换。readAt为首次阅读时间，未读为null。无记录或越界页返回records=[]，保留实际计数；真正无消息时total、unreadCount、totalPages为0。故障不降级为空数据，跨次翻页不保证固定快照。

SYSTEM中审核结果的sourceId=requestId，requestType为CREATE/UPDATE、decision为APPROVED/REJECTED，通过时rejectReason为null；reviewId/actorUserId/actorUsername为空。只保留审核时摘要，点击后固定进入`/account/contributions?requestId=51`，由本人申请详情重新检查归属及可访问性。

COMMENTS的sourceId是回复ID、reviewId是原点评ID、actorUserId是回复者ID，actorUsername批量读取当前公开用户名，缺失时显示“用户不可用”；审核字段为空。通知不保存点评或回复正文，不接受任意跳转URL。点击成功保存单条已读后，由17.5固定目标接口重新读取原文；失效内容保留通知并提示“内容已失效或不可访问”。

### 17.2 单条已读

`POST /api/me/notifications/{id}/read`，无请求体。普通通知id为1～Long.MAX_VALUE、无前导零的十进制字符串；公告使用`announcement:{公告ID}`（例如`announcement:12`），公告ID同样为正Long十进制。仅改变本人这一条消息的readAt，首次时间由数据库生成，重复操作不重写。成功204、无响应体；不存在与他人消息统一404，标记失败不模拟已读。

### 17.3 全部已有消息已读

`POST /api/me/notifications/read-all?category=ALL`，无请求体；category默认ALL，可选ALL/SYSTEM/COMMENTS/LIKES/FOLLOWERS。SYSTEM处理审核消息及有效全体公告，COMMENTS只标回复消息，LIKES只标获赞消息，FOLLOWERS只标关注消息，ALL覆盖四类，其他分类400。成功204、无响应体，空收件箱也可调用，不删除消息或改变源业务。

服务端按操作开始时数据库快照可见的对应分类未读消息分批更新，较大新ID和较小但迟提交的消息均保留未读。任一批失败整体回滚，重复操作保留首次阅读时间。

### 17.4 自动发信与错误

管理员审核首次进入终态时发一条SYSTEM通知给原submitted_by；通过取最终店名，驳回取原提交店名及规范化原因。通知与审核、商户/相册/搜索任务及通过经验同事务，失败整体回滚。重复终态回执不重发，历史终态不补发。

首次发布回复时发一条COMMENTS通知给原点评作者，自回复跳过；历史点评上的新回复同样通知。通知与回复同事务，实际写入后失败也整体回滚；原请求UUID重放和完整事务重试不重复发送。V28建空收件箱，V29仅扩展同一张notification_messages，保留旧审核消息及首次已读时间，没有新增表，也不补发历史回复。

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 分类、分页或消息ID格式/范围不合法。 |
| 401 | AUTH_UNAUTHENTICATED | 缺少、失效凭证或账号/会话不可用。 |
| 404 | NOTIFICATION_NOT_FOUND | 消息不存在或不属于当前身份。 |
| 405 | COMMON_METHOD_NOT_ALLOWED | 已认证请求使用不支持的方法。 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖暂不可用。 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期查询/写入失败，不能伪装为空数据或成功已读。 |

消息删除见17.6；关注通知见21.4。全体公告后端见17.8～17.9，实时刷新见17.7；管理端公告页及用户系统消息已接入。

### 17.5 本人收到的回复精确定位

`GET /api/me/review-replies/{id}/target`需Bearer。id为具体回复ID，十进制正整数、无前导零、不超过Long.MAX_VALUE。仅原点评作者可读，管理员不绕过归属，额外userId/reviewId参数不能扩大范围。成功200、`Cache-Control: no-store`。

统一响应data示例：

```json
{"userId":"22","restaurantId":"26","reviewId":"101","replyId":"901","reviewAuthor":{"id":"22","username":"点评作者"},"review":{"id":"101","scores":{"taste":8,"value":7.5,"service":7,"ambience":8},"overallScore":7.63,"recommended":true,"body":"原点评正文","createdAt":"2026-11-01T06:00:00Z","scoringStatus":"history","photos":[]},"replyAuthor":{"id":"33","username":"校园食友"},"replyBody":"谢谢分享","replyCreatedAt":"2026-11-07T09:00:00Z"}
```

review沿用10.2的currentReview结构，scoringStatus按读取时实际最新代表判断。直接按ID定位历史点评及任意位置回复，不逐页扫描；回复、点评、父店可见性和作者信息共用RR只读快照，停业店仍可读。接口不依赖通知是否已读，也不更改已读状态；弹窗仅展示当前所需原点评和高亮目标回复。

非法ID400 `REPLY_INVALID_INPUT`；回复/点评不存在或点评不属于本人404 `REPLY_REVIEW_NOT_FOUND`；店铺不存在或已删除404 `RESTAURANT_NOT_FOUND`。前端将404统一显示为“内容已失效或不可访问”。缺少/失效凭证401 `AUTH_UNAUTHENTICATED`，认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`，未预期读取失败500；已认证但方法不符405。源内容删除不删除通知，也不从消息恢复正文，再次读取重新检查可见性。


### 17.6 删除本人单条消息

`DELETE /api/me/notifications/{id}`，有效Bearer，无业务请求参数及请求体。普通通知id为1～Long.MAX_VALUE、无前导零的十进制字符串；公告传`announcement:{公告ID}`，详见17.9。只能删除本人收件箱的任意单条已读或未读消息，管理员无代删权限。

首次成功及原收件人重复删除均204，无响应体，`Cache-Control: no-store`。非法ID400 `COMMON_VALIDATION_FAILED`；无有效身份401 `AUTH_UNAUTHENTICATED`；未知或他人消息（包含他人的已删除消息）统一404 `NOTIFICATION_NOT_FOUND`；认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`；未预期数据库失败500 `COMMON_INTERNAL_SERVER_ERROR`并整体回滚，不伪装成功。其他错误沿用17.4。

删除后该消息不再出现在本人列表、总数、未读数及系统最新摘要中；最后一条系统消息删除后latestSystem为null。删除不等于已读，原readAt保持不变；对已删除消息单条已读返回404，全部已读也跳过它。查询响应契约不增加deletedAt，跨次查询仍不保证固定分页快照。

内部保留来源唯一键及原通知记录防重放，不提供恢复或重新投递接口；同一审核、回复、点赞或关注来源重试不会让消息重新出现。删除不操作原申请、点评、回复、点赞、关注或经验记录，原业务内容仍按自身权限可访问。

前端各分类列表提供二次确认；确认后只发一次DELETE。失败保留原通知和重试入口，删除成功后回第一页读取最新列表、未读数及摘要；读取失败仅重试GET。退出、换号、分类切换或卸载会取消在途请求并拒绝旧身份回执。传输取消或超时不保证服务器未提交，可刷新核对或安全重试。

### 17.7 实时未读红点

`GET /api/me/notifications/unread`：必须登录，仅查询当前用户。无参数；200响应示例：

```json
{"success":true,"data":{"userId":"11","unreadCount":2}}
```

`GET /api/me/notifications/events`：必须通过Authorization Bearer请求头认证，不接受URL令牌或收件人参数。200为`text/event-stream`，禁止缓存和代理缓冲。事件`notifications-changed`表示事务已提交，请重新查询未读数；`sync`为首次握手/15秒心跳补齐提示。两者data均为`{}`，不含正文。回滚不发送；同一事务同一收件人合并。来源幂等重试可能产生无害刷新，不会重建通知。

连接最长5分钟，客户端退避重连并重新查询；每用户最多5连接、单实例最多1000连接，超额拒绝。每次发送前复核JWT到期、会话及当前账号，失败关闭。未登录/失效凭证401；基础设施认证异常503。连接期间异常表现为断流。仅当前实例广播，无事件存档或Last-Event-ID补发，数据库未读查询用于恢复最终状态。进入消息列表不自动已读。


### 17.8 管理员全体公告

以下接口均要求有效Bearer及当前数据库ROLE_ADMIN；返回`Cache-Control: no-store`。不提供定向接收、编辑、草稿、定时发布或到期接口，正文按纯文本保存，客户端不得将其作为HTML执行。

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

Detail为统一响应的data：

```json
{"id":"12","title":"校园服务通知","content":"本周服务安排如下。","status":"PUBLISHED","publishedBy":"22","publishedAt":"2026-11-08T05:00:00Z","withdrawnBy":null,"withdrawnAt":null,"replayed":false}
```

标题/正文先统一CRLF/CR换行为LF并去除两端空白；按Unicode码点限制标题1～120、正文1～10000，禁止空白内容。发布身份及时间由服务端确定。按发布人+UUID去重，摘要覆盖规范化标题与正文：相同内容返回首次公告且replayed=true，不重复插入或广播；不同内容409。公告已撤回后的相同发布请求仍返回原撤回记录，不重新发布。不同管理员的同一UUID互不冲突。replayed只表示本次发布/撤回是否重放，GET返回false。

管理员列表按publishedAt/id倒序，计数与记录同一快照，越界返回空records及真实总数。撤回与个人已读/删除共用公告主行锁；任意管理员可撤回，首次撤回人/时间不可被重试覆盖。公告及用户状态保留，用户侧不显示撤回占位。发布和撤回事务提交后向本实例所有在线连接发送刷新提示；失败回滚不广播，不创建逐用户通知。

前端管理入口为`/admin/announcements`：单列表单填写标题和正文，确认后发布，成功进入公告记录。记录支持状态筛选、分页、详情及撤回确认。未知发布结果保留原UUID和正文再重试，写入成功但记录读取失败只重试GET；退出、换号或卸载清理私有草稿与迟到回执，不保存到浏览器存储。

### 17.9 用户公告详情及系统消息合并

`GET /api/me/announcements/{id}`：有效Bearer，id为正Long无前导零十进制。200返回17.8的Detail结构，仅允许仍发布且本人未删除的公告；GET不标记已读。撤回、本人删除或不存在统一404。管理员访问此用户接口同样受本人删除状态限制；管理员历史核对使用17.8管理接口。

17.1列表中的公告示例：

```json
{"id":"announcement:12","notificationType":"ANNOUNCEMENT","category":"SYSTEM","sourceId":"12","announcementId":"12","announcementTitle":"校园服务通知","requestId":null,"requestType":null,"decision":null,"restaurantName":null,"rejectReason":null,"reviewId":null,"actorUserId":null,"actorUsername":null,"createdAt":"2026-11-08T05:00:00Z","readAt":null}
```

普通通知ID保持原十进制字符串，新增announcementId/announcementTitle为null。公告标题不冒用店名或审核字段；列表不携带正文，详情按announcementId读取。前端需按notificationType分支及带前缀id作为列表键，不能将公告交给申请/回复定位接口。前端已识别ANNOUNCEMENT类型及带类型ID，用户在系统消息中打开纯文本公告详情。

有效全体公告对所有当前及后来注册用户可见，未生成个人状态时初始未读；系统/全部列表、分类总数、未读数、latestSystem及轻量未读查询统一包含公告。已撤回或本人删除后全部排除。列表只查询标题，不读取公告正文；未读查询只做来源计数。

公告已读和删除复用17.2/17.6，将id传为`announcement:12`。首次及重复已读204，保留首次readAt；已删除或已撤回则404。首次及本人重复删除204，可直接删除未读公告而不生成readAt；已删除公告即使后来撤回仍可重试204，未删过的撤回公告404。删除回执长期保留，无恢复接口。其他用户的个人状态不受影响。

17.3的SYSTEM/ALL全部已读在同一RR快照中按500条分页选择初始可见未读公告；锁定后复核当前撤回和删除状态，不能恢复并发删除，也不能标记快照之后才提交的公告。整笔失败全部回滚。COMMENTS/LIKES/FOLLOWERS不处理公告。个人状态操作提交后只刷新当前用户的多标签页红点。

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

## 18. 点评与回复点赞

### 18.1 设置点赞状态

`PUT /api/reviews/{id}/like`、`DELETE /api/reviews/{id}/like`分别设置点评为已赞、未赞；回复使用`PUT /api/review-replies/{id}/like`、`DELETE /api/review-replies/{id}/like`。均需Bearer，无请求体，点赞者仅取当前身份。id为无前导零、不超过Long.MAX_VALUE的正整数字符串。

成功200、`Cache-Control: no-store`，统一响应data：

```json
{"targetId":"101","count":1,"liked":true,"available":true}
```

count为该内容当前点赞数，liked为本人当前状态。重复PUT不增加计数，重复DELETE不减少额外计数；首次DELETE也不创建关系。禁止自赞，管理员同样不能自赞；父店、点评或回复不存在/已删除时不可操作，停业店铺仍可操作。取消保留首次关系；重新点赞不重复通知和发奖。操作不改变商户评分、点评代表或搜索投影版本。

第一次点赞与作者通知、符合条件的点评获赞经验同事务提交；通知或奖励写入失败整体回滚。死锁/锁超时采用最多三次完整事务尝试，耗尽返回503；网络超时不代表未提交，应重读当前状态再继续操作。

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

### 18.2 批量读取当前计数和本人状态

`GET /api/content-likes?type=REVIEW&ids=101,102`公开，可携带Bearer取得本人状态。type仅REVIEW/REPLY；ids为1～50个逗号分隔的合法ID，最多1000字符，重复ID合并。成功200、no-store，data：

```json
{"userId":"22","type":"REVIEW","records":[{"targetId":"101","count":1,"liked":true,"available":true},{"targetId":"102","count":0,"liked":false,"available":false}]}
```

匿名userId为null、liked为false。失效或父店不可见的目标返回available=false、count=0、liked=false，不泄露其实际计数。每批源目标、父店可见性和计数来自同一只读快照；无效Bearer仍401。前端同次挂载的点评和回复按钮按类型/身份合批，不为每张卡单独查询；状态不跨账号缓存。

### 18.3 本人当前获赞总数

`GET /api/me/received-likes`需Bearer，无业务参数，成功200、no-store，data为`{"userId":"11","count":3}`。合计本人仍存在的点评和回复当前有效点赞；取消及内容硬删除后减少，删除点评关联删除的回复同样排除。不是历史累计次数；父店隐藏对个人合计的额外规则未扩展。

### 18.4 获赞通知与精确内容定位

首次点赞给被赞内容作者发送一条LIKES消息。notificationType为REVIEW_LIKED或REPLY_LIKED，sourceId为点赞关系ID，reviewId为所属点评ID，actorUserId/actorUsername为点赞者身份和当前公开用户名，其他审核字段为null。unreadCounts新增LIKES，系统摘要仍独立取最新审核消息；消息按17节规则单条或分类已读。

点击先保存该条已读，再调用`GET /api/me/received-likes/{id}/target`，这里id为点赞关系ID。需Bearer且当前用户必须是被赞内容作者，管理员不扩大权限；取消点赞后仍可读取有效源内容。成功200、no-store，data示例：

```json
{"userId":"11","likeId":"501","targetType":"REPLY","targetId":"901","reviewId":"101","restaurantId":"26","author":{"id":"11","username":"回复作者"},"body":"被赞的回复正文","createdAt":"2026-11-07T09:00:00Z"}
```

按固定ID直接定位历史点评或任意分页位置回复，源内容和父店可见性每次重新检查。不存在、归属不符或内容失效404 LIKE_TARGET_NOT_FOUND，前端提示“内容已失效或不可访问”。原通知保留，不保存源正文或任意跳转URL。取消点赞保留通知及首次已读时间；重新点赞不重复通知。

### 18.5 获赞经验

只有本人点评获赞可能计奖，回复获赞不计经验。暂定每次+1、每作者每日最多+5；同一点赞者每天最多为同一作者贡献一次，同一点赞者对同一条点评最多奖励一次。奖励与日额度在作者账户锁内处理，业务日期取首次操作时刻的Asia/Shanghai自然日，完整事务重试沿用同一时刻。

额度不足时点赞及通知正常成功，只跳过经验。取消点赞或普通删除内容不撤销已发经验，取消后重赞不重新计奖。本轮不开放超限后补发、违规撤销后的额度返还或奖励资格恢复。参数可配置GROWTH_LIKE_EXPERIENCE、GROWTH_DAILY_LIKE_EXPERIENCE_LIMIT，均须为正整数；调参不重算旧奖励。

## 19. 登录可见个人主页

两个入口均需有效Bearer、当前可用账号及会话，匿名返回401；成功200并返回Cache-Control: no-store。查看对象来自路径userId，不代表写入身份。主页没有经验、资料或处罚写接口。

### 19.1 公开资料

`GET /api/users/{userId}/profile`，无查询参数。data示例：

```json
{"userId":"22","username":"同学甲","bio":"喜欢面食","level":3,"receivedLikes":7}
```

只返回上述5项，不返回原始/脱敏邮箱、经验余额、奖励明细、角色、学生认证/账号状态、投稿或纠错记录。level按当前经验和部署门槛计算，无成长账户按零经验计算，不创建账户。receivedLikes沿用当前有效点评与回复获赞合计，取消赞或源内容删除后相应减少。资料、等级与获赞在同一只读快照查询。

非法或超出正Long范围的userId返回400 `USER_PUBLIC_PROFILE_INVALID`；用户不存在、已删除或未验证邮箱时返回404 `USER_PUBLIC_PROFILE_NOT_FOUND`。停用账号正常历史内容不因此隐藏；本入口不授予被停用账号正常登录权限。

### 19.2 按店铺分组的公开点评

`GET /api/users/{userId}/reviews?page=1&pageSize=8`，page为正整数，pageSize为1～50；只绑定分页，查询参数不能更换作者、目标或派生offset。响应复用[本人按店分组](#105-本人点评按店铺分组)的data结构，author为被查看用户，并新增数字level；不返回投稿或其他隐私记录。

先排除不可见店铺，再按被查看作者的restaurant_id归组，每家店选最新有效点评作为主卡片；按主卡片createdAt、数字ID倒序后分页。返回真实totalGroups/totalReviews、historyCount和nextHistoryCursor，空/越界页仍保留计数；停业可见；窗口点评按WINDOW类型和window_id独立归组，不与同号餐厅混合。历史展开沿用[同作者历史接口](#103-同作者历史)，删除仍仅允许内容本人，不能凭主页参数取得他人删除权限。目标用户错误同19.1，分页非法返回400通用校验错误。

公开作者投影`{id,username}`现增加可选数字`level`（用户缺失的占位署名无等级）；店铺点评、本人/公开主页点评及历史响应中的真实作者均通过批量成长查询补全等级，不泄露原始经验。

## 20. 店铺收藏与隐私

本节所有入口均需有效Bearer、可用账号及当前会话；匿名401，成功200且Cache-Control: no-store。收藏仅针对restaurants中的普通餐厅及食堂，不提供窗口或点评收藏。写入用户来自认证上下文，不接受客户端指定其他用户；不生成通知、经验或搜索同步任务，不返回店铺收藏人数或收藏者名单。

### 20.1 本人收藏状态与操作

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/restaurants/{id}/favorite` | 返回本人当前收藏状态；目标必须存在且可见 |
| `PUT /api/restaurants/{id}/favorite` | 收藏可见店铺；无请求体；重复请求保留原关系ID、收藏时间和排序位置 |
| `DELETE /api/restaurants/{id}/favorite` | 取消本人收藏；无请求体；允许清理已删除目标，未收藏或重复取消同样成功 |

上述data为`{"restaurantId":"1","favorited":true}`，取消返回false。停业店铺仍可收藏；GET/PUT目标不存在或已软删除返回404 `RESTAURANT_NOT_FOUND`，非法或超正Long范围ID返回400 `RESTAURANT_INVALID_ID`。写入前锁定商户主行，与商户删除/恢复保持顺序；唯一键防止重复关系。取消后重新收藏生成新关系及时间；取消结果不确定时可重发相同DELETE，详情页翻转前需重读状态。

### 20.2 本人与公开收藏列表

- `GET /api/me/favorites?page=1&pageSize=8`：仅本人，包含失效占位。
- `GET /api/users/{id}/favorites?page=1&pageSize=8`：本人可直接查看；其他登录用户仅当目标收藏已公开时可查看，且不返回失效记录。私密返回403 `RESTAURANT_FAVORITES_PRIVATE`，不返回数量或记录；主体不存在、已删除或未验证邮箱返回404 `USER_PUBLIC_PROFILE_NOT_FOUND`。

page为正整数，pageSize为1～50，只绑定这两个字段；非法分页400通用校验错误。按created_at DESC、数字收藏ID DESC排序，先过滤可见范围，再计数和分页。一次请求在RR快照中核验隐私及读取计数/记录；跨请求数据变化可能移动分页，每次请求重新核验隐私。

```json
{"userId":"11","records":[{"restaurantId":"1","name":"校园小馆","restaurantType":"REGULAR","businessStatus":"OPERATING","available":true,"createdAt":"2026-11-07T00:00:00Z"}],"total":1,"page":1,"pageSize":8,"totalPages":1}
```

本人列表失效项仍保留restaurantId、createdAt及available=false，name/restaurantType/businessStatus为null，不继续展示商户历史资料。失效项可取消；未主动取消时店铺恢复后重新可用，已取消的不自动恢复。停业不是失效。空页及越界页保留真实total/totalPages；公开列表total只计入当前可见店铺，不泄露失效项数量。

### 20.3 收藏列表可见性

`GET /api/me/favorites/visibility`读取，`PATCH /api/me/favorites/visibility`保存；PATCH只接受`{"favoritesPublic":true}`或false，必须提供真实JSON布尔值。缺少/null、字符串/数字布尔值及未知字段返回400通用校验错误；不能更换设置所属用户。data为`{"userId":"11","favoritesPublic":false}`。

默认false表示仅本人可见，true表示其他登录用户可查看。真实修改仅更新该字段及row_version，不改变凭证、密码、简介；重复保存同值不推进版本。保存结果不确定时界面先重新读取，不把未确认状态当作成功。公开主页切换到收藏时重新请求列表，退出或切换会话/对象立即清空旧结果；已在其他页面展示的内容不会由后台推送自动撤回。

## 21. 基础关注、名单隐私与关注提醒

本节全部入口要求有效Bearer及当前可用账号，匿名401；成功200并禁止缓存。访问者身份来自认证上下文，查询参数或JSON不能指定actor。首版同步维护关系、计数及首次通知，不提供Feed或异步统计。

### 21.1 人数与关系操作

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/users/{id}/social` | 读取目标用户当前关注数、粉丝数及本人是否关注；人数不受名单私密设置影响 |
| `PUT /api/users/{id}/follow` | 无请求体，关注目标用户；只有首次创建有向关系才发通知 |
| `DELETE /api/users/{id}/follow` | 无请求体，取消本人关注；无关系或重复取消同样成功，不创建取消占位 |

返回data：`{"userId":"22","followingCount":2,"followersCount":3,"following":true}`。仅active=1的关系计数，不使用历史累计次数。自己查看自己的following为false；尝试关注或取消自己返回400 `FOLLOW_SELF_FORBIDDEN`。非法/溢出ID返回400 `FOLLOW_INVALID_ID`；读取主体不存在、已删除或未验证邮箱返回404 `USER_PUBLIC_PROFILE_NOT_FOUND`；新关注还要求目标账号ACTIVE，停用目标可取消已有关系。

关系操作按两个用户数字ID升序加锁，再写关系和通知，避免互相关注的反向取锁。首次插入、关注通知在同一READ_COMMITTED事务提交，通知失败整笔回滚；取消和重新关注保留原关系ID、created_at、原通知及首次已读时间，不重复提醒。重复提交同一目标状态不更新关系时间。取消后重关按原关系ID位置显示；并发不同目标状态按数据库实际事务顺序生效。没有收藏、经验、私信、拉黑或Feed副作用。

### 21.2 关注与粉丝名单

- `GET /api/users/{id}/following?page=1&pageSize=8`
- `GET /api/users/{id}/followers?page=1&pageSize=8`

page为正整数，pageSize为1～50，只绑定分页字段；超出范围400通用校验错误。本人可查看自己的两个名单；其他登录用户分别受following_public/followers_public控制，私密返回403 `FOLLOW_LIST_PRIVATE`，不返回名单数量或记录。每次请求重新校验权限；人数仍可通过21.1查询。

```json
{"userId":"11","kind":"FOLLOWING","records":[{"userId":"22","username":"食友","level":2,"following":true}],"total":1,"totalPages":1,"page":1,"pageSize":8}
```

kind为FOLLOWING或FOLLOWERS。只对有效关系按关系ID倒序分页，先计数再LIMIT；本页署名、等级及访问者对各成员的关注状态批量补全。缺失逻辑账号显示“用户不可用”、level为null；不按用户名归并，不返回邮箱、账号权限或经验。空/越界页保留真实total。一次请求采用RR快照，跨请求关系变化可能移动分页，不承诺固定排名。

### 21.3 两种名单的隐私设置

`GET /api/me/follows/privacy`读取本人设置；`PATCH /api/me/follows/privacy`要求完整显式JSON：`{"followingPublic":true,"followersPublic":false}`。两种名单独立，均默认false；缺少/null、非布尔值或未知字段返回400通用校验错误。data为`{"userId":"11","followingPublic":true,"followersPublic":false}`。

只更新两个开关及row_version，保留收藏设置、简介和凭证；同值保存不推进版本。写入失败不伪造成功，客户端先重读设置。多个客户端提交不同完整设置时以最后提交的设置为准。

### 21.4 新增关注消息

V33仅扩展原notification_messages的CHECK约束，新增USER_FOLLOWED组合，无新增表或历史补发。notification_type为USER_FOLLOWED，source_id为首次关注关系ID，actor_user_id为关注者，review_id及审核字段全部为空。

现有[通知接口](#17-本人系统通知)的category新增FOLLOWERS；首页ALL包含该类，unreadCounts新增FOLLOWERS。单条/分类/全部已读与归属规则沿用原收件箱；进入列表不自动已读。提醒逐条显示“某用户关注了你”，先保存已读再打开关注者个人主页。取关不撤销旧通知或重置已读；重新关注不再生成通知。目标主页不可用时页面提示不可访问，不提供私有账号信息。本人可按17.6删除单条提醒。

## 22. 食堂窗口资料、点评与汇总

### 22.1 公开资料

`GET /api/restaurants/{id}/windows`返回data数组，按floorNumber、数字ID升序，包含营业和停业窗口，排除删除项；必须是未删除的食堂。`GET /api/canteen-windows/{id}`返回单个窗口，窗口或父食堂删除均404 `RESTAURANT_NOT_FOUND`。没有窗口返回空数组，无评分返回null，真实零分返回0。成功200、no-store，公开GET无需Bearer。

单个data示例（接口使用十分制）：

```json
{"id":"12","canteenId":"901","canteenName":"第一食堂","campusCode":null,"name":"面食窗口","floorNumber":2,"description":"现煮面食","priceKind":"EXACT","pricePerPerson":15,"businessStatus":"OPERATING","deleted":false,"replayed":false,"coverUrl":null,"score":8,"taste":8,"value":8,"service":8,"ambience":8,"recommendRate":100,"reviewCount":3,"participantCount":2,"address":"食堂地址","latitude":30.7,"longitude":104.1,"needsLocation":false,"createdAt":"2026-11-08T06:00:00Z","updatedAt":"2026-11-08T06:00:00Z"}
```

地址与坐标来自父食堂，campusCode当前为null；coverUrl为窗口相册首张图片的后端读取路径，无图或窗口已删除时为null。窗口图片见22.5，菜品尚无写入接口。业务ID为独立正Long字符串，窗口与餐厅即使数字相同也不能互换。

### 22.2 管理员维护

所有`/api/admin/**`窗口接口要求当前数据库管理员角色；匿名401，普通用户403，不能由历史创建者代替管理员。管理集合`GET /api/admin/restaurants/{id}/windows`返回同一白名单，额外包含deleted=true的窗口；允许查看已删除食堂的管理集合，写入前必须恢复父食堂。

`POST /api/admin/restaurants/{id}/windows`输入示例：

```json
{"clientRequestId":"ad9166d8-a7b4-4055-8b93-274d16389102","name":"面食窗口","floorNumber":2,"description":"现煮面食","priceKind":"EXACT","pricePerPerson":15,"businessStatus":"OPERATING"}
```

name必填、去两端空白后1～40码点；floorNumber必填整数-10～100；description可空，空白转null，最多500码点。priceKind为UNKNOWN/EXACT/ESTIMATE，缺省UNKNOWN；UNKNOWN要求金额为空，其余要求1～5000整数元。businessStatus为OPERATING/CLOSED，缺省OPERATING。新建必须提供UUID；窗口不设分类，不接收categoryCode，也不返回categoryCode/categoryName；携带旧分类字段返回400 COMMON_MALFORMED_REQUEST。不接受评分、归属、操作者等未知字段。Bean Validation长度同时受Java字符串长度限制。

创建及原请求重放均200，data为窗口详情，重放replayed=true；同一管理员UUID只允许相同规范化资料及所属食堂，冲突409 `COMMON_DATA_CONFLICT`。重试不会恢复已删除窗口或覆盖之后的编辑。`PUT /api/admin/canteen-windows/{id}`完整替换可维护资料，clientRequestId不参与更新；所属食堂不可修改，成功200。

`DELETE /api/admin/canteen-windows/{id}`软删除，`POST /api/admin/canteen-windows/{id}/restore`恢复；均无请求体，首次及同状态重试204。删除停止公开、保留点评；恢复不改变原营业状态。不存在或父食堂不可写404，非法参数400 `COMMON_VALIDATION_FAILED`/`COMMON_MALFORMED_REQUEST`/`RESTAURANT_INVALID_INPUT`；500表示事务故障。维护操作与统计、搜索版本和Outbox同事务提交，失败整体回滚。

### 22.3 点评与互动

窗口点评列表、历史及发布使用总览中的独立窗口路由，参数和主体响应沿用[点评协议](#10-点评)：列表page/pageSize/sort，历史cursor/pageSize，发布clientRequestId/scores/recommended/body/photoIds。发布要求Bearer，窗口营业且父层可见；本轮窗口首次发布与重放均200，data为`{"reviewId":"41","restaurantId":null,"windowId":"12","replayed":false}`。普通餐厅发布状态沿用原201/200。停业阻止新点评，返回409 `RESTAURANT_STATE_CONFLICT`；原成功请求可在停业后重放。直接新增食堂整体点评同样409，旧食堂整体点评仍可读和由本人删除。

发布UUID在作者的全部点评间唯一；同号餐厅和窗口属于不同目标，跨目标复用UUID返回409 `REVIEW_REQUEST_CONFLICT`，已删除请求返回409 `REVIEW_REQUEST_DELETED`。历史游标与对象类型、ID、作者、真实锚点绑定，跨类型复用400 `REVIEW_INVALID_CURSOR`。其他点评/图片错误沿用第10节。

本人和公开主页点评分组中的restaurant投影增加`targetType`（RESTAURANT/WINDOW）、`windowId`和`canteenId`；窗口组id为windowId，普通组保持原ID。同号对象分别计组、历史和评分。回复、照片、点赞及删除继续使用全局reviewId，不新建平行接口；回复和获赞消息源定位增加windowId，窗口目标restaurantId=null。窗口或父食堂删除后原内容不可公开读取。

### 22.4 评分与副作用

窗口内每位用户仅最新有效点评贡献分数，历史计入reviewCount；删当前点评回退上一条，删最后一条移除该用户贡献。父食堂总分及四维采用营业、未删除、有评分窗口均分的等权平均，零分计入、无分跳过，旧食堂整体点评不参与；人数与条数为有效营业窗口统计之和，人数不跨窗口去重，推荐率按推荐贡献人数/贡献总人数。没有计分窗口时分数null。

点评、图片绑定、窗口统计、食堂汇总、首次窗口点评奖励及搜索Outbox同事务提交；写入锁顺序为父食堂→窗口→点评等子资源。窗口维护亦使用父锁，使停业/删除与发评互斥。首次点评经验按每用户每窗口一次，使用WINDOW类型业务键；普通删评不退经验、不重领。详情、目录和地图SQL读同一统计；ES按现有Outbox最终一致性更新，不承诺立即可见。无公开手工改分接口。窗口资料统一由管理员录入和维护，当前不提供普通用户新增窗口、上传窗口资料或投稿审核接口；窗口点评及点评照片仍按本节开放。

### 22.5 管理员窗口相册

窗口相册独立于点评照片，最多5张，按入册时间及文件ID排序，首张为封面。先由管理员调用9.1上传`purpose=WINDOW`，再将临时文件回执入册；不能绑定REVIEW/RESTAURANT用途、其他人上传、已过期或已绑定到其他资源的文件。

| 方法 | 路径 | 认证 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/canteen-windows/{id}/photos` | 公开 | 200，data为相册数组，空相册为`[]` |
| GET | `/api/canteen-windows/{id}/photos/{fileId}` | 公开 | 200 JPEG，no-store、nosniff |
| POST | `/api/admin/canteen-windows/{id}/photos` | 管理员 | 200，data为当前完整相册 |
| DELETE | `/api/admin/canteen-windows/{id}/photos/{fileId}` | 管理员 | 204，无响应体 |

POST只接受`{"fileId":"501"}`，fileId为正Long字符串。相同窗口和文件回执重复入册返回当前相册，不占用额外名额。首次入册及封面、文件绑定在同一事务提交，按父食堂→窗口→媒体行锁定；并发新增仍限制最多5张。成功data示例：

```json
[{"id":"501","url":"/api/canteen-windows/12/photos/501","cover":true}]
```

DELETE只解除本窗口的图片关联，并提交媒体待回收状态；云端文件由后台删除，失败继续重试。同窗口重复删除已移除的图片仍204，不操作其他窗口文件。删除首张后下一张接替封面，最后一张删除后coverUrl为null。窗口软删除保留相册，恢复后重新可读；窗口或父食堂已删除时公开相册及单图均404，管理员写入前也必须恢复。

匿名管理操作401、普通用户403；非法ID或请求字段400；窗口/父食堂不可见或图片不属于窗口404；文件不可绑定409 `FILE_NOT_BINDABLE`；第六张409 `RESTAURANT_STATE_CONFLICT`；私有图片读取故障503 `FILE_STORAGE_UNAVAILABLE`。窗口停业仍允许管理员维护相册；相册不改变点评、评分或成长经验。当前不提供手动封面选择、重排、菜品图片或普通用户窗口资料上传。

## 23. 内容举报

第一版只受理点评和回复。被举报内容在核查前继续公开（RISK-003）；当前仅完成举报提交与正文快照保存，管理员后台处置尚未实现。

### 23.1 提交举报

`POST /api/reviews/{reviewId}/reports`或`POST /api/review-replies/{replyId}/reports`，需Bearer。路径决定目标类型，举报人只取当前认证身份，不能指定其他举报人。请求体示例：

```json
{"reason":"ABUSE","detail":"包含人身攻击"}
```

reason必填，大小写精确，取值为SPAM、ABUSE、PORNOGRAPHY、ILLEGAL、PRIVACY、IRRELEVANT、OTHER。detail选填，去首尾空白后为空视为未填写，最多200码点；未知字段返回400通用请求错误。

首次提交201，data示例：

```json
{"reportId":"1","targetType":"REVIEW","targetId":"101","status":"PENDING","duplicate":false,"createdAt":"2026-11-10T08:00:00Z"}
```

同一用户对同一内容再次提交返回200、duplicate=true和原记录，不新增行，也不覆盖首次原因、说明和正文快照。服务端在提交时读取并保存正文及作者快照，普通接口不回显快照。

目标不存在或父店/窗口不可见统一404 `REPORT_TARGET_NOT_FOUND`；举报自己发布的内容403 `REPORT_SELF_NOT_ALLOWED`；原因非法、编号非法或说明超长400 `REPORT_INVALID_INPUT`；未登录401。举报不改变内容可见性、商户评分、点赞或成长经验。

### 23.2 尚未实现的处置能力

管理员举报列表、详情、驳回和删除内容仍待实现。已确认规则为确认违规后永久删除且不恢复（RISK-004），并向举报者发送结果系统消息（RISK-012）；账号处罚、禁言、停用和申诉不在本轮范围。
