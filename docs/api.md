# Ginkgo 美食地图 V2 接口文档

## 账号规则与注册自动登录（2026-11-01）

本节为最新账号契约，覆盖下文旧版本规则。用户名2–24个字符（中文、英文字母、数字、下划线），去首尾空白且区分大小写；V11将username列改为utf8mb4_0900_as_cs，唯一索引保留。邮箱规则不变。新注册与重置密码均为4–32位ASCII可打印字符（字母、数字或英文符号，不允许空格/中文），不强制字符组合。旧密码登录继续原样校验，最长72字节，不追溯新注册限制。

| 路径 | 请求/成功响应 | 说明 |
| --- | --- | --- |
| POST /auth/register-and-login | 请求同/register；200，data同/login的accessToken/tokenType/expiresAt/user | 新前端使用；用户事务提交后复用登录限流、JWT和Redis会话，Cache-Control:no-store |
| POST /auth/register | 请求和201空响应不变 | 保留旧客户端，不自动建立会话；采用新字段校验规则 |
| POST /auth/password-reset | 请求不变；200，data为{"username":"实际用户名"}，不含密码或令牌 | 专用验证码验证、改密提交及凭证版本递增完成后才返回用户名；旧会话失效；前端跳转/login仅预填用户名，密码留空 |

注册已提交而后续登录受限或会话服务不可用时返回503 AUTH_REGISTER_LOGIN_FAILED，提示“账号已创建，但自动登录失败，请前往登录”，不得据此重复注册或误以为账号回滚。验证码/账号校验失败仍使用原有错误码，绝不签发会话。新返回的用户名仅用于本标签页内存预填，不放入URL、localStorage或sessionStorage。刷新后清除预填状态。

## 地图统一搜索与高德开发代理（2026-10-31）

本站业务接口没有新增写操作。关键词搜索复用公开GET /api/restaurants，省略west/south/east/north以搜索完整本站数据，保留已有分类、排序、价格、距校区筛选及分页；地图移动不改变搜索分页。无关键词时仍使用文末视口契约。搜索时点位由当前页本站记录及本页高德地点组成，不代表全站全量；只在无关键词范围浏览时使用/api/restaurants/map-points。

以下为本地Vite同源安全代理供JS SDK使用，属于开发基础设施，生产静态构建不会自动提供，返回高德原始JSON/JSONP格式，不是本站ApiResponse。

| 方法与路径 | 权限与参数 | 响应及边界 |
| --- | --- | --- |
| GET /_AMapService/v3/place/text | 不依赖本站登录；由AMap.PlaceSearch发起。keywords为1–100字符非空词，page为1–100默认1；代理固定餐饮types=050000、offset=10、extensions=base、city=成都市、citylimit=false，服务端补配置Key与安全密钥 | 固定转发restapi.amap.com同路径，基础字段经SDK读取name/id/address/location等；不借用第三方评分。200也可能包含高德业务错误，由SDK适配转为搜索错误，不当作空结果 |

代理拒绝非GET（405 MAP_METHOD_NOT_ALLOWED）、非白名单路径（404 MAP_PATH_NOT_ALLOWED）、空/过长关键词和非法页码（400 MAP_SEARCH_INVALID_QUERY）；缺配置503 MAP_PROXY_NOT_CONFIGURED，上游故障502 MAP_UPSTREAM_UNAVAILABLE，重定向502 MAP_UPSTREAM_REDIRECT。固定JSON错误仅含code。其他已允许底图/坐标转换路径仍保留，周边/地点详情等未开放。代理不记录完整URL，不向浏览器返回安全密钥。

本站收录核对复用公开GET /api/restaurants的100米矩形候选查询（keyword/category为空、所有筛选关闭、pageSize=100），每页最多10个高德地点、3路并发；候选结果完整时，仅同名且80米内唯一记录确认匹配。候选截断/故障显示待核实，不解释为未收录；同名远距离、名称不同或重名歧义不自动关联。前端保留结果序号和取消保护，第三方搜索失败不阻断本站查询。

未新增学生提交、申请保存或审核接口；“提交收录”“手动添加餐厅”均为disabled占位，外部搜索不写数据库或持久化浏览器存储，不生成虚构本站ID/详情。现有管理员维护接口不受此占位影响。

最后核对日期：2026-10-30。

## 图片上传、取消、点评绑定与正式访问

文件接口、写评表单和点评有序绑定已实现。服务需配置并开启私有、未启用版本控制的OSS Bucket；默认关闭时上传返回503，不影响文本点评。写点评期间继续本地预览，不新增临时预览接口。真实OSS上传/读取/删除及隔离MySQL/JWT接口已验收；开发业务库已升级V7，前后端已重启，页面联调入口可用。

| 方法与路径 | 权限 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| POST `/api/files/images` | Bearer登录 | multipart/form-data，单张`file` | 201，统一响应的data见下例 |
| DELETE `/api/files/images/{fileId}` | Bearer登录、本人未绑定文件 | 正数文件ID字符串，无请求体 | 已删除/已不存在204无响应体；意图已提交但删除尚未确认202，统一响应`data:null` |
| GET `/api/reviews/{reviewId}/photos/{position}` | 公开，但校验点评、父店与图片关联 | 正数点评ID、位置0至4 | 200，image/jpeg二进制，不缓存；不存在或不可见404 |

上传data示例（仍由success/code/message/data/timestamp包裹）：

```json
{
  "fileId": "123",
  "byteSize": 85600,
  "width": 1200,
  "height": 800,
  "expiresAt": "2026-10-30T13:00:00Z"
}
```

只接受浏览器压缩后的真实JPEG，单张1–307200字节、宽高1–1200px；服务端解码并重编码，重新校验大小并计算SHA256，不相信文件名和Content-Type。原PNG/WebP由浏览器转换JPEG后上传。multipart请求总大小上限320KiB。不接收自定义Bucket、Object Key或上传者；响应不提供OSS地址。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | FILE_INVALID_IMAGE | 缺少file、伪图片、非JPEG、损坏、尺寸或业务大小超限 |
| 401 | 现有AUTH错误码 | 未登录或会话无效 |
| 404 | FILE_NOT_FOUND | 取消时ID非法或文件属于他人；正式图片不存在或关联无效 |
| 409 | FILE_ALREADY_BOUND | 本人的图片已用于点评，不能从临时取消接口删除 |
| 409 | FILE_NOT_BINDABLE | 发布时图片不属于本人、过期、尚未完成上传或已被其他请求绑定 |
| 413 | FILE_TOO_LARGE | multipart解析阶段超过单文件/请求大小上限 |
| 503 | FILE_STORAGE_UNAVAILABLE | 未配置OSS、云存储失败或上传完成状态无法确认 |

上传前先提交UPLOADING记录，OSS成功后才激活TEMPORARY并返回ID，默认24小时有效。失败/响应丢失可能留下临时文件，由到期任务回收；上传重试不保证返回同一文件ID。上传失败进入PENDING_DELETE并立即尝试删除，一小时保护期内保留凭据，到期再次删除，覆盖普通晚到写入；超长进程冻结后的对象核对仍为后续边界。

取消在短事务内验证归属、提交待删除状态，随后立即在事务外调用OSS。成功后条件删除登记，失败保留重试。默认每60秒最多处理50条到期记录，BOUND不进入清理；云调用期间不持有数据库锁。204表示完成，202表示意图已接收但删除尚未确认。接口成功响应带Cache-Control:no-store。

点评POST新增可选`photoIds`数组，缺省/空数组保持原文本协议，最多5个不重复的本人有效文件ID，按数组顺序保存。文件ID升序加锁，展示位置按请求顺序；点评、关联、文件BOUND和统计同事务提交，失败整体回滚。附图摘要为photo-review-v2，空图保持text-review-v1字节兼容；重放先匹配原请求和有序关联，不重新要求图片处于临时态。

正式照片GET先核验点评、父店及关联再读私有OSS；隐藏内容404、存储故障503，不缓存，不公开任意文件ID访问接口。已传输给浏览器的字节无法撤回。点评主列表及前端分页已接入，见第15节；历史见第16节，独立照片墙仍待实现。

本文面向前后端联调和接口调用者，仅描述当前已实现的接口。参数规则和频率限制中的数值为仓库默认配置，部署环境可以覆盖允许域名、有效期和限流策略。

密码重置提交接口、对应前端页面及登录凭证持久保存已合并发布，用户已确认注册、登录和找回密码基础验收通过。后续美食业务需求见[需求与重构说明](requirements.md)，其中的计划能力不是当前可调用接口。

## 1. 地址与通用约定

| 项目 | 约定 |
| --- | --- |
| 后端直连地址 | `http://localhost:8080`，端口可通过 `SERVER_PORT` 覆盖 |
| 前端开发地址 | `http://localhost:5173`，Vite 代理 `/auth` 和 `/api` 请求 |
| 生产访问 | `http://8.137.98.235`，Nginx 原样代理 `/auth/` 和 `/api/`；首次成功发布前返回503 |
| 路径前缀 | 认证使用 `/auth`，公开状态使用 `/api/public`，餐厅读取使用 `/api/restaurants` 和 `/api/restaurant-categories` |
| 请求体 | 有请求体的业务 POST 使用 `Content-Type: application/json` |
| 响应体 | 业务响应为 JSON；健康检查有独立格式，见第8节 |
| 日期时间 | ISO 8601 UTC 字符串，例如 `2026-10-27T12:00:00Z` |
| 认证方式 | 请求头 `Authorization: Bearer <accessToken>`；不支持 HTTP Basic 或 Cookie 会话认证 |

GET 当前用户、POST 退出均无请求体，也没有查询参数。以下示例中的邮箱、密码、验证码、用户ID和令牌均为演示数据，不代表已有账号或有效凭证。

### 1.1 统一响应

业务接口的成功和失败响应均使用以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | boolean | 请求是否成功 |
| `code` | string | 稳定机器错误码；成功固定为 `OK` |
| `message` | string | 可供用户阅读的提示；不要依赖提示文本编写分支 |
| `data` | object / array / null | 业务数据或字段错误详情；无数据时为 `null` |
| `timestamp` | string | 服务端生成的响应时间 |

无返回数据的成功示例：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": null,
  "timestamp": "2026-10-27T12:00:00Z"
}
```

普通失败示例（HTTP 401）：

```json
{
  "success": false,
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "用户名或密码错误，或账号暂不可用",
  "data": null,
  "timestamp": "2026-10-27T12:00:00Z"
}
```

DTO 参数校验失败返回 HTTP 400，`data` 为字段错误数组，按字段名排序；同一字段可能有多个错误。不会回传用户提交的敏感值。

```json
{
  "success": false,
  "code": "COMMON_VALIDATION_FAILED",
  "message": "请求参数校验失败",
  "data": [
    { "field": "password", "message": "请输入密码" },
    { "field": "username", "message": "请输入用户名" }
  ],
  "timestamp": "2026-10-27T12:00:00Z"
}
```

### 1.2 认证与错误处理

- 只有总览明确标记为公开的 HTTP 方法和路径允许匿名访问；不要把整个 `/auth` 当作公开路径。
- 登录、注册、密码重置和验证码请求应省略 `Authorization`。即使接口公开，主动携带无效、过期或已撤销的 Bearer 令牌也会触发认证失败。
- 受保护请求同时检查 JWT 签名、发行方、受众、有效期、Redis 会话、最新账号状态及凭证版本。角色使用数据库当前值。
- 受保护请求返回401时，可以清除该请求使用的本地凭证并引导重新登录；旧请求的401不能清除之后新登录得到的凭证。登录接口本身的401表示登录失败。
- 403表示权限不足；429表示限流；503表示服务暂不可用。503不应被当成“密码错误”或“退出成功”，可保留凭证供重试。
- 安全过滤器生成的401包含 `WWW-Authenticate: Bearer`。登录成功、当前用户成功响应和过滤器错误响应设置 `Cache-Control: no-store`。
- 请求方法不匹配时，如果匿名请求先被安全过滤器拦截，可能返回401；通过认证后到达 MVC 才可能得到405。不要依赖错误方法判断接口是否存在。

### 1.3 浏览器客户端的登录保存

- 前端默认将JWT保存到同源 `localStorage`（键名 `ginkgo.access-token`），无“记住我”选项，不保存密码；关闭页面或浏览器后可恢复，仍须调用 `/auth/me` 通过服务端验证后才显示为已登录。
- 同一浏览器同源标签页共享凭证，登录、切换账号或清除凭证后通过 `storage` 事件同步；旧请求的401不得清除其他标签页刚写入的新凭证。不同来源（包括HTTP/HTTPS或端口不同）不共享存储。
- 升级时迁移当前标签页的旧 `sessionStorage` 凭证并清除旧副本；持久存储中已有凭证时优先使用持久凭证。存储访问被浏览器拒绝时退化为本页面内存会话，不保证关闭后恢复。
- JWT与Redis会话的有效期不变，默认7天；没有刷新令牌或滑动续期。过期、撤销或凭证版本不匹配时，由受保护请求的401清除对应本地凭证；503保留以便重试。
- 找回密码页路径为 `/password-reset`，依次调用3.2及4.2接口，均不自动携带Bearer凭证。成功后仅清除提交开始时的旧本地凭证，提示使用新密码登录；失败保留原本的登录状态。其他设备的旧凭证在下次服务端认证时被拒绝。
- 当前仍为Bearer请求头认证，没有改为Cookie认证。`localStorage`可被同源JavaScript读取；HTTPS及HttpOnly Cookie属于后续认证与部署强化工作。

## 2. 接口总览

| 方法 | 路径 | 认证 | 成功状态 | 用途 |
| --- | --- | --- | --- | --- |
| POST | `/auth/email/code/register` | 公开 | 200 | 申请注册验证码 |
| POST | `/auth/email/code/password-reset` | 公开 | 200 | 申请密码重置验证码 |
| POST | `/auth/register` | 公开 | 201 | 校园邮箱注册 |
| POST | `/auth/password-reset` | 公开 | 200 | 使用邮箱验证码设置新密码 |
| POST | `/auth/login` | 公开 | 200 | 用户名密码登录 |
| GET | `/auth/me` | Bearer | 200 | 查询当前用户 |
| POST | `/auth/logout` | Bearer | 200 | 退出当前会话 |
| GET | `/api/public/status` | 公开 | 200 | 应用标识与架构信息 |
| GET | `/api/restaurant-categories` | 公开 | 200 | 餐厅分类字典（含停用状态） |
| GET | `/api/restaurants` | 公开 | 200 | 全局搜索筛选、排序和服务端分页 |
| GET | `/api/restaurants/{id}` | 公开 | 200 | 未删除餐厅详情 |
| GET | `/api/restaurants/{id}/management` | Bearer，创建者或管理员 | 200 | 管理资料；删除记录仅管理员可读 |
| POST | `/api/restaurants` | Bearer | 201 | 新建餐厅，当前账号作为创建者 |
| PUT | `/api/restaurants/{id}` | Bearer，创建者或管理员 | 200 | 完整替换可编辑资料与标签 |
| DELETE | `/api/restaurants/{id}` | Bearer，创建者或管理员 | 200 | 软删除，保留历史标签，无需版本 |
| POST | `/api/restaurants/{id}/restore` | Bearer，管理员 | 200 | 恢复已删除餐厅，无需版本 |
| POST | `/api/restaurants/{restaurantId}/reviews` | Bearer | 201 / 200重放 | 发布纯文本点评并更新每人最新代表统计 |
| GET | `/api/restaurants/{restaurantId}/reviews` | 公开 | 200 | 按作者分页读取最新点评、历史条数和有序照片引用 |
| GET | `/actuator/health` | 公开 | 200 / 503 | 基础设施健康检查 |

## 3. 邮箱验证码

### 3.1 申请注册验证码

`POST /auth/email/code/register`，无需认证。

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `email` | string | 是 | 非空、有效邮箱格式；去除首尾空白并转小写后，域名必须精确匹配允许列表 |

```json
{
  "email": "student@std.uestc.edu.cn"
}
```

默认仅允许 `std.uestc.edu.cn`，可通过 `AUTH_ALLOWED_EMAIL_DOMAINS` 配置多个域名。基础邮箱格式在 DTO 层先校验，调用方应直接提交无多余空白的合法邮箱。相似域名或其他子域名不会自动被允许。

成功返回 HTTP 200、统一成功响应、`data: null`。验证码不在接口响应中返回，由邮件发送；接口成功不保证收件箱已完成投递。

默认策略：

- 6位数字验证码，5分钟有效；有效期从 Redis 预留时开始计时。
- 同一邮箱60秒冷却、小时窗口最多5次、24小时窗口最多10次；注册和密码重置共享邮箱发送配额与冷却。
- 注册与密码重置验证码按用途分别保存，不能混用；重新申请同用途验证码会替换之前的验证码。
- 同一验证码最多允许5次错误尝试，达到上限即失效；成功消费后立即失效。
- SMTP 发送失败会取消本次待激活验证码及其冷却，但不退还已计入的发送次数；请求已失败时仍可能消耗发送配额。

主要失败：

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` | 邮箱缺失或格式不合法 |
| 400 | `USER_CAMPUS_EMAIL_REQUIRED` | 不属于允许的校园邮箱域名 |
| 409 | `USER_EMAIL_ALREADY_REGISTERED` | 邮箱已经注册 |
| 429 | `AUTH_CODE_RESEND_COOLDOWN` | 尚在重发冷却期 |
| 429 | `AUTH_CODE_EMAIL_RATE_LIMITED` | 超出邮箱发送配额 |
| 503 | `AUTH_CODE_MAIL_DELIVERY_FAILED` | 邮件发送失败 |
| 503 | `AUTH_CODE_DELIVERY_STATE_EXPIRED` | 邮件发送后无法激活本次验证码 |
| 503 | `AUTH_CODE_SERVICE_UNAVAILABLE` | Redis 验证码服务暂不可用 |

### 3.2 申请密码重置验证码

`POST /auth/email/code/password-reset`，无需认证。请求字段和格式与3.1相同。

成功返回 HTTP 200、统一成功响应、`data: null`。

- 已注册的校园邮箱会进入验证码发送流程。
- 未注册但格式、域名合法的邮箱直接返回相同的成功响应，不发送邮件。
- 成功响应不表示账号一定存在，也不表示密码已经修改。
- 实际发送时仍可能返回验证码限流或邮件/Redis故障错误；当前实现不保证各种条件下的响应或耗时完全一致。
- 基础参数校验及校园域名限制仍然生效；`USER_EMAIL_ALREADY_REGISTERED` 不用于此接口。

收到密码重置验证码后，可调用4.2的接口提交新密码；注册用途验证码不能用于该流程。

## 4. 注册与密码重置

### 4.1 注册账号

`POST /auth/register`，无需认证。

| 参数 | 类型 | 必填 | 默认规则 |
| --- | --- | --- | --- |
| `email` | string | 是 | 非空、有效校园邮箱；与申请验证码时一致 |
| `verificationCode` | string | 是 | 严格6位ASCII数字，保留可能出现的前导零 |
| `username` | string | 是 | 去除首尾空白后2至24个字符，只允许中文、英文字母、数字和下划线，区分大小写 |
| `password` | string | 是 | 4–32位ASCII字母、数字或英文符号，不含空格、中文或控制字符，不强制字符组合，不执行 trim |

```json
{
  "email": "student@std.uestc.edu.cn",
  "verificationCode": "012345",
  "username": "ginkgo_user",
  "password": "ExamplePassword2026"
}
```

后端不接收确认密码字段，确认密码由前端表单自行比较。

成功返回 HTTP 201、统一成功响应、`data: null`：

- 账号邮箱标记为已验证，账号状态为 `ACTIVE`，分配 `ROLE_USER`。
- 不自动登录、不返回令牌；下一步调用 `/auth/login`。
- 邮箱和用户名必须唯一。V11后用户名比较区分大小写、重音；邮箱沿用原有比较规则。
- 验证码先消费，再写入账号和角色。账号与角色写入在同一数据库事务中；消费验证码后的数据库写入失败，验证码不会恢复，需要重新申请。

主要失败：

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` | 必填、邮箱格式或6位验证码格式不符 |
| 400 | `USER_CAMPUS_EMAIL_REQUIRED` | 非允许校园邮箱 |
| 400 | `USER_USERNAME_INVALID` | 用户名字符或长度不符 |
| 400 | `USER_PASSWORD_WEAK` | 密码不符合规则 |
| 400 | `AUTH_CODE_INVALID_OR_EXPIRED` | 验证码错误、未激活、过期、已消费或用途不匹配 |
| 409 | `USER_EMAIL_ALREADY_REGISTERED` | 邮箱重复 |
| 409 | `USER_USERNAME_ALREADY_REGISTERED` | 用户名重复 |
| 409 | `USER_REGISTRATION_CONFLICT` | 并发注册触发唯一约束冲突 |
| 429 | `AUTH_CODE_ATTEMPTS_EXCEEDED` | 当前验证码错误尝试达到上限 |
| 503 | `AUTH_CODE_SERVICE_UNAVAILABLE` | 验证码校验服务暂不可用 |

### 4.2 使用邮箱验证码设置新密码

`POST /auth/password-reset`，无需登录或角色授权，须提供绑定邮箱的密码重置验证码。请求应省略 `Authorization`，重置不会自动登录。

| 参数 | 类型 | 必填 | 默认规则 |
| --- | --- | --- | --- |
| `email` | string | 是 | 非空、有效校园邮箱；规范化后与申请验证码的绑定邮箱一致 |
| `verificationCode` | string | 是 | 严格6位ASCII数字，保留前导零；仅接受密码重置用途 |
| `newPassword` | string | 是 | 与注册密码规则一致：4–32位ASCII字母、数字或英文符号，不含空格/中文，不强制字符组合，不执行 trim |

```json
{
  "email": "student@std.uestc.edu.cn",
  "verificationCode": "012345",
  "newPassword": "NewExamplePassword2026"
}
```

成功返回 HTTP 200，设置 `Cache-Control: no-store`，响应示例：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": { "username": "ExampleUser" },
  "timestamp": "2026-10-28T02:00:00Z"
}
```

重要副作用与失败处理：

- 先检查邮箱和密码规则，再原子消费验证码；错误、过期、已消费或注册用途的验证码不能改密。成功消费后不可重放，并发使用同一验证码只能成功一次。
- 只有邮箱已验证且状态为 `ACTIVE` 的已有账号能够重置。账号不存在、停用、锁定、删除或邮箱未验证时，统一返回 `AUTH_CODE_INVALID_OR_EXPIRED`，不恢复账号或公开具体状态。
- 规范化后的邮箱必须精确等于账号绑定邮箱，不能利用MySQL重音不敏感比较规则，用另一个相似邮箱的验证码修改该账号。
- 密码哈希、凭证版本和行版本在同一条SQL中更新。数据库提交后，所有重置前签发的登录凭证都会在后续认证时被拒绝；已通过认证的在途请求不会被追溯取消。
- Redis中旧会话记录保留至原有效期自然清理，但不能继续通过最新凭证版本校验。无需逐个删除会话，也不增加数据库迁移。
- 不修改邮箱、角色或账号状态，不自动清除登录失败锁定。重置后需用新密码重新登录；仍处于临时登录锁定期时须等待锁定结束。
- 输入策略校验失败不消费验证码。验证码消费后的数据库故障或并发冲突不恢复验证码，须重新申请，并遵循已有发送冷却与配额。
- 数据库更新核对读取时的邮箱、行版本、凭证版本及可用状态，拒绝覆盖计算密码哈希期间发生的账号变化。

主要失败响应均采用1.1的统一格式，`data: null`，DTO校验错误除外：

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` | 必填、邮箱格式或6位验证码格式不符，`data`包含字段错误 |
| 400 | `USER_CAMPUS_EMAIL_REQUIRED` | 非允许校园邮箱 |
| 400 | `USER_PASSWORD_WEAK` | 新密码不符合注册时的密码规则 |
| 400 | `AUTH_CODE_INVALID_OR_EXPIRED` | 验证码错误、过期、已消费、用途不符，或账号不可用 |
| 429 | `AUTH_CODE_ATTEMPTS_EXCEEDED` | 验证码错误尝试达到上限 |
| 409 | `USER_PASSWORD_RESET_CONFLICT` | 消费验证码后账号发生并发变化，需重新申请验证码 |
| 503 | `AUTH_CODE_SERVICE_UNAVAILABLE` | Redis验证码服务暂不可用，密码未修改 |
| 503 | `USER_PASSWORD_RESET_UNAVAILABLE` | 验证码已消费后的账号读取或更新故障，需重新申请验证码 |
| 401 | `AUTH_UNAUTHENTICATED` | 主动携带的Bearer令牌无效或已撤销，应省略认证头 |

事务提交阶段等未被专门映射的基础设施错误仍可能返回500通用错误；失败不等于重置成功，也不保证验证码仍可用。

## 5. 用户名密码登录

`POST /auth/login`，无需认证。

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `username` | string | 是 | 非空，DTO原始输入最多64个Java UTF-16代码单元，Service去除首尾空白后查询；实际注册用户名规则见第4节 |
| `password` | string | 是 | 非空，DTO最多72个Java UTF-16代码单元；Service另行限制UTF-8最多72字节，原样校验，不执行 trim |

```json
{
  "username": "ginkgo_user",
  "password": "ExamplePassword2026"
}
```

登录使用用户名，不接受邮箱替代用户名。登录不会重新要求密码符合注册时的全部强度规则，而是与已保存的 BCrypt 哈希匹配。

成功返回 HTTP 200：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": {
    "accessToken": "<accessToken>",
    "tokenType": "Bearer",
    "expiresAt": "2026-11-03T12:00:00Z",
    "user": {
      "id": 42,
      "username": "ginkgo_user",
      "roles": ["ROLE_USER"]
    }
  },
  "timestamp": "2026-10-27T12:00:00Z"
}
```

| 响应字段 | 类型 | 说明 |
| --- | --- | --- |
| `data.accessToken` | string | RS256签名的 JWT，后续作为 Bearer 发送 |
| `data.tokenType` | string | 固定 `Bearer` |
| `data.expiresAt` | string | 本次登录截止时间；默认有效期7天，可配置 |
| `data.user.id` | integer（64位） | 用户ID；JavaScript 调用方需注意超过安全整数范围时的精度问题，当前协议以JSON数字返回 |
| `data.user.username` | string | 数据库保存的用户名 |
| `data.user.roles` | string[] | 当前角色编码，例如 `ROLE_USER`、`ROLE_ADMIN`；客户端展示不替代服务端授权 |

每次成功登录创建独立会话，允许多个会话同时存在。默认15分钟内累计失败5次锁定15分钟；同一账号的大小写或重音别名共享限制。成功登录清除失败计数。当前没有刷新令牌，过期后重新登录，也不会因访问接口而自动延长有效期。

主要失败：

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` | 必填或DTO长度校验失败 |
| 401 | `AUTH_INVALID_CREDENTIALS` | 账号不存在、密码不匹配、邮箱未验证、账号不可用，或密码超过UTF-8字节限制；不区分具体原因 |
| 429 | `AUTH_LOGIN_LOCKED` | 达到错误阈值，或账号仍被临时锁定 |
| 503 | `AUTH_SERVICE_UNAVAILABLE` | 数据库、Redis限流或会话写入暂不可用 |

## 6. 查询当前用户

`GET /auth/me`，必须提供 Bearer 令牌，无请求体。

```http
GET /auth/me HTTP/1.1
Authorization: Bearer <accessToken>
Accept: application/json
```

成功返回 HTTP 200：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": {
    "id": 42,
    "username": "ginkgo_user",
    "roles": ["ROLE_USER"]
  },
  "timestamp": "2026-10-27T12:00:00Z"
}
```

数据字段与登录响应中的 `user` 相同。页面刷新后应通过本接口确认身份，不能仅凭本地存在令牌就认为已经登录。

主要失败：401 `AUTH_UNAUTHENTICATED`（缺少、无效或过期令牌，会话失效或账号状态/版本不符）；503 `AUTH_SERVICE_UNAVAILABLE`（认证依赖故障）。如果账号在安全过滤器校验通过后、Controller查询前发生状态或版本变化，也可能返回401 `AUTH_INVALID_CREDENTIALS`。

## 7. 退出当前会话

`POST /auth/logout`，必须提供 Bearer 令牌，无请求体。

```http
POST /auth/logout HTTP/1.1
Authorization: Bearer <accessToken>
Accept: application/json
```

成功返回 HTTP 200、统一成功响应、`data: null`。服务端删除当前 JWT 对应的 Redis 会话，同一令牌在后续请求中立即失效；其他独立登录会话不受影响。

- 返回200后，客户端清除对应本地凭证和用户展示状态。
- 返回401 `AUTH_UNAUTHENTICATED` 表示凭证已不可使用，可清理对应本地状态。
- 返回503 `AUTH_SERVICE_UNAVAILABLE` 不表示服务端已完成退出，应提示失败并保留重试能力。
- Redis删除操作本身幂等，但HTTP接口先经过认证：已成功退出后再次携带同一令牌调用，会返回401，而非第二个200。
- 复制同一个令牌的标签页属于同一个会话；在其中一个标签页退出后，其他持有该令牌的页面也无法继续访问受保护接口。

## 8. 状态与健康检查

### 8.1 应用公开状态

`GET /api/public/status`，无需认证。

成功返回 HTTP 200：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": {
    "application": "ginkgo-food-map-v2",
    "architecture": "modular-monolith"
  },
  "timestamp": "2026-10-27T12:00:00Z"
}
```

该接口只确认应用能够处理请求，不检查数据库、Redis或SMTP健康状态。

### 8.2 后端健康检查

`GET /actuator/health`，无需认证，使用 Spring Boot Actuator 响应，**不使用 `ApiResponse` 包装**。

健康时返回 HTTP 200：

```json
{ "status": "UP" }
```

健康状态为 `DOWN` 或 `OUT_OF_SERVICE` 时通常返回 HTTP 503；当前配置隐藏组件细节。它与“HTTP应用仍能响应”是两个不同概念，状态取决于实际启用的健康组件及其连接情况。

当前邮件组件的健康检查连接 SMTP 并校验 TLS/账号认证，不发送验证码、不消耗验证码配额，也不证明收件箱投递成功。SMTP 网络故障可使本接口变慢或返回503，公开状态/餐厅查询仍可能正常；配置中的连接、读取、写入超时不是整个健康请求的统一总时限，本机名称解析等耗时需单独排查。不要将邮件健康检查通过当成真实邮件验收通过。

当前 Vite 和生产 Nginx未代理 `/actuator/`，应直连后端或使用部署环境单独配置的探针地址。生产 Nginx另有 `GET /nginx-health`，返回 `200`、`text/plain` 的 `ok` 加换行，仅检查Nginx本身；它不是后端接口，也不检查后端或数据库。Actuator还暴露 `/actuator/info`，但它不在匿名白名单中，需通过Bearer认证，使用Actuator原生格式，默认可能为空对象。

### 8.3 部署版本标识

服务器站点的 `GET /version.txt` 无需认证，无参数，成功返回 HTTP 200、纯文本40位 Git 提交号及换行，并设置 `Cache-Control: no-store`。由发布流水线生成并交给 Nginx 托管，不使用业务 JSON 包装，没有业务副作用。首次发布前站点返回503；文件缺失且站点已有版本时返回404。此资源不由后端或本地 Vite 提供，不能代替后端健康检查。

宿主机部署使用 [deploy/nginx.conf](../deploy/nginx.conf)，没有单独的 `/nginx-health` 探针；上节的该探针仅适用于前端 Docker 镜像。完整部署与排查说明见[自动部署文档](operations/deployment.md)。

## 9. 错误码索引

各接口的常见错误见对应章节；以下索引汇总当前枚举及安全处理器已定义的错误码，不表示每个接口都会返回全部错误。未预期的业务基础设施异常仍可能被兜底映射为500，请始终兼容通用错误。

| HTTP | 错误码 | 说明 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` | DTO参数校验失败，`data`含字段错误 |
| 400 | `COMMON_MALFORMED_REQUEST` | 请求JSON缺失、语法错误或无法读取 |
| 405 | `COMMON_METHOD_NOT_ALLOWED` | 通过认证边界后，请求方法与Controller不匹配 |
| 409 | `COMMON_DATA_CONFLICT` | 数据库约束等状态冲突 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 未被专门映射的内部错误 |
| 400 | `USER_CAMPUS_EMAIL_REQUIRED` | 校园邮箱域名不允许 |
| 409 | `USER_EMAIL_ALREADY_REGISTERED` | 邮箱已经注册 |
| 409 | `USER_USERNAME_ALREADY_REGISTERED` | 用户名已经使用 |
| 400 | `USER_USERNAME_INVALID` | 用户名不符合规则 |
| 400 | `USER_PASSWORD_WEAK` | 注册或重置的新密码不符合规则 |
| 409 | `USER_REGISTRATION_CONFLICT` | 并发注册唯一约束冲突 |
| 409 | `USER_PASSWORD_RESET_CONFLICT` | 改密时账号状态或版本发生并发变化 |
| 503 | `USER_PASSWORD_RESET_UNAVAILABLE` | 验证码已消费后的账号读取或密码更新故障 |
| 429 | `AUTH_CODE_RESEND_COOLDOWN` | 邮箱重发冷却中 |
| 429 | `AUTH_CODE_EMAIL_RATE_LIMITED` | 邮箱发送次数超限 |
| 400 | `AUTH_CODE_INVALID_OR_EXPIRED` | 验证码错误或已失效 |
| 429 | `AUTH_CODE_ATTEMPTS_EXCEEDED` | 验证码错误尝试超限 |
| 503 | `AUTH_CODE_DELIVERY_STATE_EXPIRED` | 验证码投递状态已失效 |
| 503 | `AUTH_CODE_MAIL_DELIVERY_FAILED` | 邮件发送失败 |
| 503 | `AUTH_CODE_SERVICE_UNAVAILABLE` | 验证码Redis服务暂不可用 |
| 401 | `AUTH_INVALID_CREDENTIALS` | 登录失败或身份状态已变化 |
| 429 | `AUTH_LOGIN_LOCKED` | 密码登录被临时锁定 |
| 503 | `AUTH_SERVICE_UNAVAILABLE` | 登录、身份验证或会话服务暂不可用 |
| 401 | `AUTH_UNAUTHENTICATED` | 安全过滤器拒绝缺失或失效身份 |
| 403 | `AUTH_FORBIDDEN` | 没有执行操作的权限 |
| 400 | `RESTAURANT_INVALID_ID` | 餐厅ID不是Java Long范围内的规范正整数 |
| 404 | `RESTAURANT_NOT_FOUND` | 餐厅不存在或已删除，统一提示 |
| 400 | `RESTAURANT_INVALID_INPUT` | 餐厅文字长度、价格组合、坐标精度或标签总长不符合规则 |
| 400 | `RESTAURANT_CATEGORY_UNAVAILABLE` | 分类不存在或新分配的分类已停用 |
| 403 | `RESTAURANT_FORBIDDEN` | 非创建者尝试管理他人餐厅或历史未知归属餐厅 |
| 409 | `RESTAURANT_STATE_CONFLICT` | 对已删除餐厅编辑/再删，或对未删除餐厅恢复 |

当前限流错误不提供剩余次数、精确解锁时间或 `Retry-After`，客户端不要假设这些字段或标头存在。

## 10. PowerShell联调示例

以下步骤使用已经注册的账号。命令会实际登录、查询身份和撤销该次会话，不涉及发邮件或创建账号。

```powershell
$apiBase = 'http://localhost:8080'
$credential = Get-Credential -Message '请输入已注册的用户名和密码' -ErrorAction Stop
$loginBody = @{
    username = $credential.UserName
    password = $credential.GetNetworkCredential().Password
} | ConvertTo-Json

$loginResult = Invoke-RestMethod -Method Post -Uri "$apiBase/auth/login" `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) -ErrorAction Stop
$loginBody = $null
$credential = $null
$authHeaders = @{ Authorization = "Bearer $($loginResult.data.accessToken)" }

$currentUser = Invoke-RestMethod -Method Get -Uri "$apiBase/auth/me" -Headers $authHeaders -ErrorAction Stop
$currentUser.data

$logoutResult = Invoke-RestMethod -Method Post -Uri "$apiBase/auth/logout" -Headers $authHeaders -ErrorAction Stop
$logoutResult.success
$authHeaders = $null
$loginResult = $null
```

示例只输出公开用户信息和退出结果，不打印令牌或密码。调用失败时依据实际HTTP状态和响应错误码处理，不要把失败请求当成成功继续执行。

## 11. 餐厅公开只读接口

前端使用范围（2026-10-30）：页面已移除杯型评级、杯型筛选及依赖杯型的推荐/踩雷入口，公开目录发送keyword/category/sort/hideClosed/page/pageSize，并按需发送maxPrice、maxDistanceMeters。下文tiers、view和tier仍为后端兼容契约，本轮未删除HTTP参数或响应字段；新前端不读取tier、不在本地判档。participantCount、reviewCount及真实均分已用于卡片与详情，发布确认后独立刷新统计。

本节描述本仓库已实现的接口，线上是否可调用需核对实际部署版本。前端已接入本节接口。V3仅初始化13类字典，没有自动导入餐厅或评价；空库返回空目录。本地25家联调模拟店由开发工具显式写入，见第13节。

三个公开读取接口均无需登录、无角色要求、无业务写入副作用，不创建会话或发送邮件。调用方应省略 `Authorization`；主动携带无效或过期Bearer仍返回401。只放行这三个GET入口，已实现的写入和管理接口见第12节，仍必须认证及服务端授权。

### 11.1 分类字典

`GET /api/restaurant-categories`，无查询参数，成功200。`data`为按 `sortOrder`、`code` 排序的数组，下例仅展示一项：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": [{ "code": "HOT_POT", "name": "火锅", "sortOrder": 1, "enabled": true }],
  "timestamp": "2026-10-28T06:00:00Z"
}
```

迁移初始化13类。接口包含停用分类，供已有餐厅展示和筛选；`enabled=false`不能被理解为允许后续新建使用该分类。分类管理接口尚未实现。

### 11.2 餐厅列表

`GET /api/restaurants`，成功200，查询参数如下。未提供字段使用默认值，非法类型、范围和编码返回400 `COMMON_VALIDATION_FAILED`，`data`含字段错误；未知查询字段不参与筛选。

| 参数 | 类型 | 默认值 | 规则 |
| --- | --- | --- | --- |
| `keyword` | string | 空字符串 | 最多100个Java字符串字符；去首尾空白；搜索名称、地址、分类展示名和标签。百分号、下划线、感叹号、反斜杠按字面量搜索，非通配符 |
| `category` | string | 空字符串 | 精确分类编码，1–32位大写字母/数字/下划线且以字母开头；空值不限分类。合法但不存在的编码返回空集合 |
| `tiers` | string array | 空数组 | `bucket`、`super`、`large`、`medium`、`small`、`none`；最多6项，组合为并集。重复参数或逗号分隔均可，例如 `tiers=none&tiers=small` |
| `view` | string | `all` | `all`、`recommended`、`pitfall`，与其他筛选取交集 |
| `sort` | string | `score` | `score`、`taste`、`value`、`service`、`ambience`、`reviews`、`price`、`distance` |
| `hideClosed` | boolean | `false` | `true`时只返回正常经营记录；停业与删除是独立状态 |
| `maxPrice` | decimal | 不限 | 人均消费上限（元），包含边界；大于0，最多8位整数、2位小数。已填报金额与估值均参与，UNKNOWN/NULL排除；省略或空值表示不限 |
| `maxDistanceMeters` | integer | 不限 | 到清水河校区参考点的直线距离上限（米），包含边界；1–20040000。缺坐标、待定位或非规范坐标排除；省略或空值表示不限，不使用浏览器定位 |
| `page` | integer | `1` | 1–2147483647；偏移量按long计算，避免溢出 |
| `pageSize` | integer | `8` | 1–100；8沿用现有页面，100为本步查询上限，产品参数仍待审核，不将需求中的20条建议视为已批准 |

筛选、计数、排序、分页均在数据库的完整未删除集合上执行。标签搜索使用EXISTS，不因同店多个命中标签重复计数；只对当前页批量读取有序标签。COUNT、餐厅和标签查询处于同一REPEATABLE_READ只读事务；读取开始后的并发修改在下次请求体现。

高级条件与分类、搜索、经营状态及所有排序取交集，例如`GET /api/restaurants?category=HOT_POT&maxPrice=50&maxDistanceMeters=2000&hideClosed=true&sort=score&page=1&pageSize=8`。条件省略时不排除价格或位置未知的餐厅。非法金额、负数、零、非整数距离和超范围参数返回400 `COMMON_VALIDATION_FAILED`，不静默忽略有效参数名下的非法值；无匹配仍返回200、total=0、totalPages=0及空records，无业务写入副作用。

距离筛选与既有距离排序共用SQL中的Haversine表达式，采用6371000米地球半径，未舍入值参与上限比较。GCJ-02参考坐标为原站`ginkgo-food-map/index.html`的CAMPUS定义（纬度30.7544，经度103.9312），项目目前只有清水河校区固定参考点，无多校区切换和实时位置授权。该定义可追溯至原站，未重新测绘，也不代表校门、校区边界或步行距离。新增距离条件使用米；为保持已有客户端兼容，响应`distanceKm`及既有距离显示继续使用公里，同一米制计算结果除以1000得到，不改变原响应字段单位。

`price`按人均升序，未知金额在末尾；`distance`按距清水河参考点（GCJ-02：30.7544、103.9312）的直线公里数升序，待定位在末尾，不代表导航路程。`score`及四维排序按高精度真实均分降序，无人评分在末尾，真实0分先于未知分；`reviews`按点评总条数（含历史）降序。同值沿用名称、数值ID升序，不新增人数优先规则。名称匹配和排序沿用MySQL的 `utf8mb4_0900_ai_ci`，支持常见大小写差异。

杯型读取真实累计分及有效人数判定；`tiers`组合取并集。`view=recommended`沿用大杯及以上（原始综合分≥6.2），`view=pitfall`只含小杯（原始综合分<5.0且有人评分）；两者与`tiers`及其他筛选取交集，不以`recommendRate`判断。无人评分为`none`，不属于推荐或踩雷。杯型直接比较累计值与“人数×阈值”，不依赖已舍入均分。

响应示例为**隔离测试夹具**，不代表生产店铺：

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": {
    "records": [{
      "id": "101",
      "name": "测试餐厅",
      "categoryCode": "OTHER",
      "categoryName": "其他",
      "pricePerPerson": 20,
      "priceKind": "ESTIMATE",
      "address": "测试地址",
      "latitude": 30.754400,
      "longitude": 103.931200,
      "coordinateSystem": "GCJ02",
      "needsLocation": false,
      "businessStatus": "OPERATING",
      "sourceType": "LEGACY_IMPORT",
      "createdAt": "2026-10-28T01:02:03.123456Z",
      "updatedAt": "2026-10-28T01:02:03.123456Z",
      "distanceKm": 0.0,
      "tags": ["测试标签"],
      "score": null,
      "recommendRate": null,
      "reviewCount": 0,
      "participantCount": 0,
      "tier": "none",
      "dimensions": { "taste": null, "value": null, "service": null, "ambience": null }
    }],
    "total": 1,
    "page": 1,
    "pageSize": 8,
    "totalPages": 1
  },
  "timestamp": "2026-10-28T06:00:00Z"
}
```

`id`始终为字符串。`pricePerPerson=null`和 `priceKind=UNKNOWN`表示未知；`EXACT`是填报金额，不表示平台已核实；`ESTIMATE`是估值。地址未知为null；待定位的经纬度及距离均为null。`sourceType`仅为 `USER` 或 `LEGACY_IMPORT`，后者不证明信息已核实或已认领。`OPERATING`表示经营记录，非此刻营业状态。

上述示例为无人评分状态。统计来自restaurant模块自有的`restaurant_review_statistics`，不跨模块关联点评表；列表和详情复用相同投影，与计数、分页及标签保持同一REPEATABLE_READ快照。缺少零统计行的旧餐厅也可读取，GET不初始化或修复数据；首次发评仍在原事务中惰性初始化。统计核对和重建属于运维/后续能力，不在每次公开GET扫描所有点评。

| 字段 | 口径 |
| --- | --- |
| `reviewCount` | 当前保留的全部点评条数，含历史，不是评分分母 |
| `participantCount` | 每账号每店一份最新有效点评的计分人数，作为所有均分和推荐率的分母 |
| `score` | 最新有效代表点评的单条综合分累计值÷人数；单条综合分先按HALF_UP保留两位 |
| `dimensions` | 各维度代表分累计值÷人数，字段为`taste/value/service/ambience` |
| `recommendRate` | 代表点评中推荐人数÷计分人数，0–1比例，非0–100百分数，不由综合分推导 |
| `tier` | 权威杯型，阈值见[评分需求](requirements.md#score-01评分计算与杯型原版迁移p1正确性-p0)，不按展示舍入值判档 |

有评分的隔离样例：小王8分后再评6分、小李9分，返回`reviewCount:3`、`participantCount:2`、`score:7.5`；两位当前代表中一位推荐时`recommendRate:0.5`，`tier:super`。若四维均同单条分，则四维均为7.5。无人评分时分数、四维和推荐率显式为null，两个计数为0、`tier:none`；真实0分正常返回0与`tier:small`。初版用户等权，不加入推荐排序修正；接口保留高精度十进制结果，前端仅格式化为一位评分/整数百分比，使用`tier`判档。

`records`只包含当前页；`total`是相同条件下的全部数量。空结果为 `records: []`、`total: 0`、`totalPages: 0`。越界页返回原请求页码与真实总数、空记录，不跳到最后一页，也不返回404。不内嵌点评或全店图片数组。

### 11.3 餐厅详情

`GET /api/restaurants/{id}`，无查询参数；`id`为1–9223372036854775807的十进制正整数，不允许前导零、负数或原版 `r01`。成功200，`data`直接为11.2中单个餐厅对象，字段、空值、标签顺序和距离口径一致。例如 `GET /api/restaurants/101` 返回 `data.id: "101"`，不包含分页外层。

格式错误返回400 `RESTAURANT_INVALID_ID`；不存在和已软删除均返回404 `RESTAURANT_NOT_FOUND`。停业餐厅仍可读取。列表与详情都不返回创建/修改者ID、删除时间/操作者/原因或行版本。详情尚无评价分页、图片墙或地图SDK。

### 11.4 状态码与调用边界

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 200 | `OK` | 字典、列表或详情读取成功；列表无结果仍为200 |
| 400 | `COMMON_VALIDATION_FAILED` | 列表参数类型、范围或白名单校验失败 |
| 400 | `RESTAURANT_INVALID_ID` | 详情ID格式错误或超出Long范围 |
| 404 | `RESTAURANT_NOT_FOUND` | 详情不存在或已删除 |
| 401 | `AUTH_UNAUTHENTICATED` | 调用方主动携带了无效Bearer |
| 503 | `AUTH_SERVICE_UNAVAILABLE` | 主动携带Bearer后认证依赖暂不可用 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 未预期的读取/数据库故障；不能当成空目录 |

匿名只读不依赖Redis会话查询；带Bearer时仍走原有认证链。不要把整个 `/api/restaurants/**` 或其他HTTP方法当成公开接口。

## 12. 餐厅维护接口

本节接口已在本仓库实现。前端新增、编辑和删除已接入本节接口，只读页面已接入第11节API；管理员回收站页面尚未实现。线上能力以实际部署版本为准。继续使用V3三张表，不修改V1/V2/V3。

**当前保存规则**：用户决定餐厅不需要版本号。编辑、删除、恢复无需提交版本，响应不返回版本；最后成功保存的完整资料及标签生效，允许旧页面覆盖此前保存的内容。权限、当前删除状态、事务和标签一致性仍由服务端保证。用户账号的认证凭证版本与其他模块不受本次调整影响。

### 12.1 权限与管理资料

所有本节接口都必须携带有效Bearer，继续校验RS256 JWT、Redis会话、当前账号状态/凭证版本及数据库当前角色。创建者ID取自认证身份，管理员能力只取数据库角色，不信任JWT自报角色、用户名、请求体或查询参数。普通用户只管理自己创建的未删除餐厅；历史未知归属不允许认领。管理员可纠错、删除和恢复，但不能改写创建者与来源。

`GET /api/restaurants/{id}/management` 无请求体、无查询参数。创建者/管理员成功200，`Cache-Control: no-store`；不存在返回404，非创建者返回403。已删除餐厅仅管理员可读，普通用户（包括创建者）读取返回404。该接口用于读取管理资料和删除状态，不再用于获取版本号。

成功统一响应的 `data` 如下，`restaurant`字段结构与11.2一致，不含账号ID、版本或删除原因；示例仅为结构说明：

```json
{
  "restaurant": {
    "id": "101",
    "name": "测试餐厅",
    "categoryCode": "OTHER",
    "categoryName": "其他",
    "pricePerPerson": null,
    "priceKind": "UNKNOWN",
    "address": null,
    "latitude": 30.754400,
    "longitude": 103.931200,
    "coordinateSystem": "GCJ02",
    "needsLocation": false,
    "businessStatus": "OPERATING",
    "sourceType": "USER",
    "createdAt": "2026-10-28T01:02:03.123456Z",
    "updatedAt": "2026-10-28T01:02:03.123456Z",
    "distanceKm": 0.0,
    "tags": ["学生聚餐"],
    "score": null,
    "recommendRate": null,
    "reviewCount": 0,
    "participantCount": 0,
    "tier": "none",
    "dimensions": { "taste": null, "value": null, "service": null, "ambience": null }
  },
  "deleted": false
}
```

创建、编辑、删除和恢复均返回该管理资料（在标准 `ApiResponse.data` 内），并设置 `Cache-Control: no-store`。`deleted`为当前删除状态，`restaurant.businessStatus`仍独立表示经营状态。删除成功的资料仅返回给完成授权的本次操作者，不使公开读取可见。

管理对象的评分字段也读取同一统计投影，已有评分不会因保存餐厅资料而返回空态。删除餐厅仅隐藏父餐厅，统计保留供授权管理回执及恢复使用；不开放客户端改分，也不将管理读取视为公开入口。

### 12.2 新建餐厅

`POST /api/restaurants`，需要登录。提交JSON，不接受客户端指定ID、创建者、来源、分数、版本或删除信息。

| 字段 | 必填/默认 | 规则 |
| --- | --- | --- |
| `name` | 必填 | 去首尾Unicode空白后1–40个Unicode码点，允许同名分店 |
| `categoryCode` | 必填 | 1–32位规范大写字母/数字/下划线且以字母开头；必须存在且启用 |
| `priceKind` | 默认 `UNKNOWN` | `UNKNOWN`、`EXACT`、`ESTIMATE`，显式null不允许 |
| `pricePerPerson` | 默认null | `UNKNOWN`必须为null；`EXACT/ESTIMATE`必须为1–5000的整数数值，不截断小数金额；EXACT不表示平台核实 |
| `address` | 默认null | 去首尾Unicode空白后最多80个Unicode码点，空白转null |
| `latitude` | 必填 | 数值3–54，最多6位有效小数 |
| `longitude` | 必填 | 数值73–136，最多6位有效小数 |
| `coordinateSystem` | 默认 `GCJ02` | 只允许 `GCJ02`，显式null不允许，不自动转换外部坐标 |
| `locationConfirmed` | 必填 | 必须为true，不允许无位置发布；服务端不自动填默认校园坐标，准确性需提交者确认 |
| `businessStatus` | 默认 `OPERATING` | `OPERATING`或 `CLOSED`，显式null不允许 |
| `tags` | 默认空数组 | 最多提交8项，不能为null；各项去首尾空白后非空，按数据库排序规则去重保留首项和顺序；去重后总Unicode码点数最多80 |

Unicode长度与Java的UTF-16长度不同，例如单个emoji按一个码点计数。标签去重使用MySQL的 `utf8mb4_0900_ai_ci` 规则，常见大小写和重音等价项只保留第一个。保存失败不会留下餐厅或半组标签。

```json
{
  "name": "测试餐厅",
  "categoryCode": "OTHER",
  "priceKind": "ESTIMATE",
  "pricePerPerson": 25,
  "address": "测试地址",
  "latitude": 30.754400,
  "longitude": 103.931200,
  "coordinateSystem": "GCJ02",
  "locationConfirmed": true,
  "businessStatus": "OPERATING",
  "tags": ["学生聚餐", "晚餐"]
}
```

成功201，`Location: /api/restaurants/{新ID}`，`data`为12.1管理资料，`deleted: false`、`sourceType: "USER"`。创建/修改者由当前账号写入。不会生成评价、评分、其他店铺或新登录会话，不发邮件。同名不覆盖已有记录；当前没有请求幂等键，超时应先核对结果，不要自动无限重试POST。

### 12.3 编辑资料、标签及经营状态

`PUT /api/restaurants/{id}`，创建者或管理员。JSON字段与12.2相同，无需 `rowVersion`；成功200，`data`为最新管理资料，不返回版本。若仍在创建/编辑JSON中提交旧 `rowVersion`，按未知字段返回400 `COMMON_MALFORMED_REQUEST`。

这是**完整替换**：必须重新提交名称、分类、坐标、位置确认；省略地址会清空，省略标签会清空，省略人均会回到UNKNOWN，省略经营状态按OPERATING保存。客户端应填写完整请求，不能当作局部PATCH。可通过 `businessStatus` 切换停业/正常经营，停业不等于删除。

采用最后成功保存覆盖：两人打开同一份资料后都可以依次成功保存，后保存的完整资料覆盖前一次，包括旧页面携带的旧值；不会因资料在页面打开后被别人改过而报版本冲突。新分类必须启用，当前已停用分类允许保持原值。修改历史记录仍保留原来源和创建者；补齐位置后清除待定位状态。未知/内部JSON字段拒绝，不允许提交创建者、来源或评分。

事务内先 `SELECT … FOR UPDATE` 锁主行核验当前权限和删除状态，UPDATE按ID、归属和未删除条件执行，不比较版本。同一家店的并发写入串行执行，资料、整组标签、操作者和修改时间同事务保存，任一步失败全部回滚。最终资料和标签必须完整来自同一次保存，不会交叉混合。原V3的 `row_version` 列保留，但应用不读取、比较或递增；本次不修改迁移。

### 12.4 软删除

`DELETE /api/restaurants/{id}`，创建者或管理员，**无查询参数、无请求体、无需版本**。

成功200，`data`为管理资料，`deleted: true`。记录当前操作者、删除时间及固定原因：普通创建者为 `OWNER_REQUEST`，管理员为 `ADMIN_REMOVAL`，原因不能由客户端指定。标签保留，公开列表、搜索、计数及详情均隐藏，公开详情404。

普通用户重复删除已删除记录返回404 `RESTAURANT_NOT_FOUND`；管理员重复删除返回409 `RESTAURANT_STATE_CONFLICT`。不会重复修改删除元数据。删除以执行时的当前授权/状态为准，允许删除页面打开后已被别人修改的餐厅。没有永久删除接口；D02中有他人评价后能否自删仍待评价阶段确认。

### 12.5 管理员恢复

`POST /api/restaurants/{id}/restore`，仅数据库当前角色包含 `ROLE_ADMIN` 的账号，**无查询参数、无请求体、无需版本**。

成功200，`data`为管理资料，`deleted: false`。清空当前删除时间、操作者和原因，记录恢复操作者/修改时间，保留原创建者、来源、经营状态和标签。不因恢复将停业改为正常经营，也不创建账号归属。

记录不存在返回404；记录尚未删除或重复恢复返回409 `RESTAURANT_STATE_CONFLICT`。普通用户即使是创建者也返回403 `AUTH_FORBIDDEN`。回收站分页、审计历史及评价/回复/图片的独立恢复尚未实现。

### 12.6 错误与并发约定

ID沿用11.3的规范正Long字符串。餐厅接口不再定义或返回 `RESTAURANT_INVALID_VERSION`、`RESTAURANT_VERSION_CONFLICT`；409仅表示当前删除状态或数据库约束等实际状态冲突，不能当成旧页面版本失效。

| HTTP | 错误码 | 适用情况 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_FAILED` | 必填字段、编码、位置确认或标签数量校验失败，含字段详情 |
| 400 | `COMMON_MALFORMED_REQUEST` | JSON不可读/类型不匹配，或创建/编辑JSON含未知/内部字段（包括旧rowVersion） |
| 400 | `RESTAURANT_INVALID_INPUT` | Unicode长度、价格组合、坐标范围/精度或标签总长不符 |
| 400 | `RESTAURANT_INVALID_ID` | 资源ID不合法或溢出 |
| 400 | `RESTAURANT_CATEGORY_UNAVAILABLE` | 分类不存在，新建/切换分类已停用 |
| 401 | `AUTH_UNAUTHENTICATED` | 缺失/过期/撤销身份，账号停用或认证凭证版本失效 |
| 403 | `RESTAURANT_FORBIDDEN` | 普通用户管理他人或历史未知归属的未删除店铺 |
| 403 | `AUTH_FORBIDDEN` | 普通用户调用管理员恢复 |
| 404 | `RESTAURANT_NOT_FOUND` | 不存在，或普通用户操作已删除餐厅 |
| 409 | `RESTAURANT_STATE_CONFLICT` | 管理员编辑/再删已删除餐厅，或恢复未删除餐厅 |
| 409 | `COMMON_DATA_CONFLICT` | 未提前拦截的数据库约束冲突，事务回滚 |
| 503 | `AUTH_SERVICE_UNAVAILABLE` | 认证依赖临时不可用，拒绝写入 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 未预期的数据库/标签保存故障，事务回滚 |

并发编辑均可成功，最后成功写入的完整资料及标签生效。并发编辑/删除：编辑先执行则两者可依次成功、最终删除；删除先执行则后续普通用户编辑404，管理员编辑409，PUT不能意外恢复删除记录。恢复后再保存仍允许按当前状态覆盖。所有控制都在服务端执行，不因取消版本号取消权限或事务。

创建仍直接写入；审核、频率限制、同名同址提示和有评价后的删除限制属于后续范围，基础CRUD不代表完整治理完成。

## 13. 尚未实现与维护要求

已实现文本/带图点评发布、图片上传/取消/正式访问和第15节的点评分组读取；历史读取见第16节；尚未提供本人计分上下文、回复、删评、照片墙、地图、刷新令牌或设备管理接口。餐厅只读及维护接口已实现，部署状态以实际发布为准；分类、列表、详情、新增、编辑和删除已接入前端，点评读取页面已接入，管理员回收站页面尚未实现；密码重置及基础认证验收已由用户确认通过。不要将其他计划中的路径作为现有API调用。

前端的 `/restaurants/:id`、`/restaurants/new`、`/restaurants/:id/edit`、`/scoring-guide` 是客户端页面路由。餐厅前端已接入第11节分类、列表和独立详情；所有筛选、排序、计数和分页由服务端执行，tiers按重复查询参数发送。支持加载、防抖搜索、取消旧请求、失败重试、404及真实空态，不回退到预览快照。三个公开GET默认不携带旧Bearer，管理GET和所有写操作仍携带登录凭证。

维护页先读取字典；编辑读取12.1管理快照后填入全部可编辑字段，始终提交12.2定义的完整请求，未知价格及空地址显式为null、空标签为数组，编辑不发送局部PATCH。当前仅手动填写并确认GCJ-02坐标，修改任一坐标后需重新确认；停用分类只允许保留原值。标签输入每行一个、忽略空行，客户端按输入总字符数保守校验80，服务端再按数据库规则去重并核验。详情仅在管理快照读取成功且未删除时显示编辑/删除，403不影响公开阅读，最终写入仍重新鉴权。

维护请求固定使用发起操作时的Bearer，避免排队时切换账号后提交原草稿；身份或路由变化丢弃旧响应。账号变化锁住原表单，重新进入后才能提交。浏览器只对当前操作禁用重复点击，没有增加服务端幂等键；POST超时不自动重发，提示先核对结果。删除经二次确认，无请求体、无版本参数；成功后使目录缓存失效，保留条件/页码并在下次查询时校正有效末页。登录页仅允许回跳本站规范的餐厅页面路径，其他目标返回首页。

本地开发库已执行V3，并按用户要求通过 [联调数据工具](../backend/tools/seed_restaurants.py) 手动写入25家原版候选，统一带“联调模拟”标签。数据来源为LEGACY_IMPORT、归属未知、旧档位价格为ESTIMATE、坐标为null且needsLocation=true、无评价和评分。重复执行跳过已有同名店（含软删除），不覆盖、不恢复或认领账号，不接触生产库；V3自身仍只初始化分类。操作说明见 [README](../README.md#本地启动)。

每次新增、修改或删除接口时同步更新本文，包括HTTP方法、路径、认证/角色要求、参数及默认规则、响应示例、状态码、错误码和重要副作用。接口行为以Controller、DTO、Service、安全配置及当前部署配置共同决定，不能只从Controller注解推断。

主要核对来源：

- [登录Controller](../backend/src/main/java/com/ginkgo/foodmap/security/controller/LoginController.java)、[登录Service](../backend/src/main/java/com/ginkgo/foodmap/security/service/LoginService.java)。
- [注册Controller](../backend/src/main/java/com/ginkgo/foodmap/user/controller/AuthController.java)、[验证码Controller](../backend/src/main/java/com/ginkgo/foodmap/user/controller/AuthEmailCodeController.java)。
- [密码重置Controller](../backend/src/main/java/com/ginkgo/foodmap/user/controller/PasswordResetController.java)、[密码重置Service](../backend/src/main/java/com/ginkgo/foodmap/user/service/PasswordResetService.java)。
- [安全配置](../backend/src/main/java/com/ginkgo/foodmap/security/config/SecurityConfiguration.java)、[统一响应](../backend/src/main/java/com/ginkgo/foodmap/common/api/ApiResponse.java)。
- [默认配置](../backend/src/main/resources/application.yml)、[功能说明](features.md)、[开发记录](history/development-01.md)。

## 14. 最简文本点评发布

`POST /api/restaurants/{restaurantId}/reviews`。必须携带有效Bearer；作者取当前会话，不由请求指定。餐厅ID为1至Long最大值的十进制正整数，不接受前导零。停业允许，已删除或不存在返回404。

```json
{
  "clientRequestId": "d6fb79a2-6866-41ae-9c38-13d239668c21",
  "scores": { "taste": 8, "value": 7.5, "service": 7, "ambience": 8 },
  "recommended": true,
  "body": "这次出餐很快，分量也比较足，下次还会来。"
}
```

| 字段 | 规则 |
| --- | --- |
| clientRequestId | 必填规范UUID，同次重试沿用；大小写规范化。不同账号有独立作用域，同账号跨餐厅不能复用同一次编号 |
| scores | 四维完整、十进制0–10、步进0.5；不将7.1等非法分数舍入后保存 |
| recommended | 必填JSON布尔值，不接受字符串或数字 |
| body | 必填纯文本，首尾Unicode空白规范化后5–500码点；正文不执行HTML |

当前支持最多五个有序photoIds，图片协议见本文开头。不支持photos、authorUserId、createdAt、总分或计分状态等额外字段，提交返回400，不会丢弃照片后宣告成功。嵌套scores的额外字段也拒绝。

首次成功HTTP 201；同账号、同UUID且规范化内容一致，HTTP 200返回原ID及replayed=true。此接口不返回尚未实现的GET地址，也不提供Location。成功响应禁止缓存：`Cache-Control: no-store`。

```json
{
  "success": true,
  "code": "OK",
  "message": "请求成功",
  "data": { "reviewId": "101", "restaurantId": "26", "replayed": false },
  "timestamp": "2026-10-29T12:00:00Z"
}
```

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | REVIEW_INVALID_INPUT | 餐厅ID、UUID、四维、推荐或正文不符合规则 |
| 400 | COMMON_MALFORMED_REQUEST | 无法解析JSON、类型错误或不支持字段 |
| 401 | AUTH_UNAUTHENTICATED | 缺失、过期或无效会话，或账号不可用；由安全过滤链统一返回 |
| 404 | RESTAURANT_NOT_FOUND | 餐厅不存在或已删除，包括在原请求成功后被删店的重试 |
| 409 | REVIEW_REQUEST_CONFLICT | 同账号同UUID已对应不同餐厅、正文、评分或推荐 |
| 503 | REVIEW_TEMPORARILY_UNAVAILABLE | 并发锁、唯一键竞争或超时经最多3次完整事务尝试仍未成功；保留原内容/UUID重试 |
| 503 | AUTH_SERVICE_UNAVAILABLE | 认证依赖临时故障，拒绝写入 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期存储故障，事务回滚；不返回SQL或内部详情 |

关键副作用：每次真实发布保留旧点评并新增一条，review_count增加1；同店首次评价才增加participant_count，再次评价从累计值中减本人旧代表、加新代表。点评、代表统计及必要的统计行初始化同事务提交，失败一起回滚。单条总分由数据库生成并使用HALF_UP两位；新记录时间不早于旧代表，时钟回拨时仍由新ID稳定定序。

同内容不同UUID属于不同用餐记录，不设内容唯一约束或每日业务配额。幂等由数据库author_user_id+client_request_id唯一键兜底；请求摘要含协议版本、餐厅、四维、推荐和规范化正文。无效UUID不会自动换号；内容改动才生成新的提交编号。

当前已实现文本/带图发布、数据库计分、前端上传与回执，以及校验内容状态后的正式照片访问。点评主列表及页面分页见第15节；历史分页见第16节；预测尚未接通；回复与本人删除已接通（第17、18节），餐厅GET返回真实统计。前端失败保留草稿与同次UUID，图片上传失败可重试/移除，发布中禁止取消，成功后关闭不会清理已绑定图片。本地开发库此前已升级V7；B1后端与Vite现已启动供本地联调，后续列表页面改动由Vite热更新。

## 15. 点评按作者分组读取

`GET /api/restaurants/{restaurantId}/reviews?page=1&pageSize=8`。公开，无需Bearer；建议匿名浏览不附带旧令牌，携带无效令牌仍会由安全过滤链拒绝。餐厅ID为1至Long最大值的规范十进制正整数，不接受前导零。停业允许读取，不存在或已删除返回404。

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| page | 1 | 整数1–2147483647，超出最后一页返回空records并保留真实总数 |
| pageSize | 8 | 整数1–50，按作者组计数 |

HTTP 200，`Cache-Control: no-store`。例如某人先评8分、再评6分，另一人评9分，返回2组、3条；主卡仅含6分和9分，不把旧的8分内嵌。示例data如下，外层仍为success/code/message/data/timestamp：

```json
{
  "records": [
    {
      "author": { "id": "22", "username": "同学乙" },
      "currentReview": {
        "id": "103", "scores": { "taste": 9, "value": 9, "service": 9, "ambience": 9 },
        "overallScore": 9.00, "tier": "bucket", "recommended": true,
        "body": "这家餐厅味道不错，下次还会来。", "createdAt": "2026-10-30T01:00:00Z",
        "scoringStatus": "current",
        "photos": [{ "id": "501", "position": 0, "url": "/api/reviews/103/photos/0" }]
      },
      "historyCount": 0, "nextHistoryCursor": null
    },
    {
      "author": { "id": "11", "username": "同学甲" },
      "currentReview": {
        "id": "102", "scores": { "taste": 6, "value": 6, "service": 6, "ambience": 6 },
        "overallScore": 6.00, "tier": "medium", "recommended": false,
        "body": "这次体验一般，希望以后有所改进。", "createdAt": "2026-10-29T02:00:00Z",
        "scoringStatus": "current", "photos": []
      },
      "historyCount": 1, "nextHistoryCursor": "MToxOjExOjE3OTMyMzkyMDA6MDoxMDI"
    }
  ],
  "totalGroups": 2, "totalReviews": 3, "page": 1, "pageSize": 8, "totalPages": 1
}
```

每位作者按(createdAt,id)倒序选出最新点评，主卡再按同样规则排序。totalGroups为独立账号数，totalReviews为该店保留的全部点评条数；查询直接从点评计算，兼容缺少餐厅统计行的旧记录。单条overallScore使用数据库已生成的四维均分，不等于餐厅聚合分；tier为bucket/super/large/medium/small。0分有效，不显示待评级。

照片按position升序，最多五个，url为既有正式照片接口的相对地址；列表不访问OSS，不暴露对象键或凭证。作者只含公开ID/当前用户名，按账号ID归组；账号记录缺失时保留ID，名称显示“用户不可用”。不返回邮箱、账号状态、角色、幂等材料或删除权限。

有历史时nextHistoryCursor定位当前代表，可作为首次历史请求的cursor；没有历史时historyCount为0且游标为空。历史读取见第16节。单次响应在同一个REPEATABLE_READ只读快照内完成父店可见性、计数、主卡、作者和照片查询，无写锁或写入副作用。跨请求有新评时分组会移动，页码分页不保证动态列表绝无重复/遗漏；前端已按每页8组接入，发布完成并关闭回执后回第一页刷新；读取错误支持原页重试，切店会取消旧请求并忽略晚到结果。公开GET不带旧Bearer，图片使用正式相对地址。餐厅汇总统计按用户要求暂缓，不从当前点评页推算全店评分。

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | REVIEW_INVALID_QUERY | 非法餐厅ID、前导零或Long溢出 |
| 400 | COMMON_VALIDATION_FAILED / COMMON_MALFORMED_REQUEST | 页码或每页数量非法，具体取决于约束或类型转换错误 |
| 401 | AUTH_UNAUTHENTICATED | 主动携带了无效或过期Bearer；匿名请求可正常访问 |
| 404 | RESTAURANT_NOT_FOUND | 餐厅不存在或已删除，不泄露其点评 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期读取故障，不返回SQL或内部详情 |

## 16. 作者在单店的历史点评

`GET /api/restaurants/{restaurantId}/reviews/authors/{authorId}/history`。公开，无需Bearer，HTTP 200及`Cache-Control: no-store`；两个路径ID均为无前导零的Long范围正整数。停业可读，餐厅不存在或已删除返回404。只读，无数据库或OSS写入副作用。

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| pageSize | 5 | 整数1–20，额外查询1条判断hasMore |
| cursor | 无 | 可省略，从当前最新点评之前开始；或原样传递列表nextHistoryCursor/上一段nextCursor，最长200字符 |

响应外层仍为success/code/message/data/timestamp，data示例：

```json
{
  "author": { "id": "11", "username": "同学甲" },
  "currentReviewId": "102",
  "records": [{
    "id": "101", "scores": { "taste": 8, "value": 8, "service": 8, "ambience": 8 },
    "overallScore": 8.00, "tier": "super", "recommended": true,
    "body": "上一次用餐的历史记录，保留原有照片。", "createdAt": "2026-10-28T01:00:00Z",
    "scoringStatus": "history",
    "photos": [{ "id": "500", "position": 0, "url": "/api/reviews/101/photos/0" }]
  }],
  "hasMore": false,
  "nextCursor": null
}
```

历史按(createdAt,id)严格倒序，排除游标锚点，默认排除当前代表。照片最多五张、按position排序，复用正式照片接口；不返回邮箱、权限、OSS对象键或请求幂等材料。作者无任何点评时author/currentReviewId为空，records为空、hasMore=false、nextCursor为空。只有当前一条时作者和currentReviewId仍存在，历史为空。

存在后续时hasMore=true，nextCursor为本段最后一条的游标；末段false/null。游标为版本化Base64URL参数，绑定餐厅、作者、时间及ID，并核对真实锚点；不是权限凭证，不允许拼接SQL。不要自行构造、修改或跨作者复用。

父店、最新代表、锚点、历史和公开作者/照片均来自单次REPEATABLE_READ只读快照。跨请求新评可能改变代表：始终比较currentReviewId与屏幕主卡ID，若不一致，应丢弃历史并重新读取主列表。正式页面刷新当前页并折叠历史；接口不承诺整个浏览会话固定快照。游标失效可重试或刷新列表，不能把失败当作没有历史。

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | REVIEW_INVALID_QUERY | 非法餐厅/作者ID |
| 400 | REVIEW_INVALID_CURSOR | 非规范编码、跨餐厅/作者、锚点不存在或时间不匹配 |
| 400 | COMMON_VALIDATION_FAILED / COMMON_MALFORMED_REQUEST | pageSize越界/类型错误或cursor超长 |
| 401 | AUTH_UNAUTHENTICATED | 请求主动附带无效Bearer；正常匿名读取无需登录 |
| 404 | RESTAURANT_NOT_FOUND | 父店不可见 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期读取故障 |

前端点击“历史点评”首次打开面板才请求，每次5条，可加载更多；桌面为侧边面板，手机为底部面板。关闭取消在途历史和回复读取、恢复背景滚动与入口焦点；重开保留已载历史与草稿，失败原游标重试。换店、翻页和刷新卸载旧组件并忽略晚到响应。餐厅聚合/四维统计已接通，本人删除见第18节；最新和历史点评的回复见第17节。

## 17. 点评单层回复

后端及正式前端已接通。复用V4回复表，无新增迁移。以下接口均返回统一ApiResponse外层及`Cache-Control: no-store`。路径reviewId为无前导零的Long范围正整数；最新和历史点评均可回复，停业餐厅可回复，父店删除时不可读写。

前端两处共用ReplyThread：“查看回复”才发GET，每次10条；“回复”独立打开编辑器，列表与历史面板共享当前编辑焦点，只显示一个编辑框。取消或切换保留各点评草稿及失败请求编号。公开GET不附带过期凭证，POST固定发起时凭证；确认回执后清空草稿、关闭本编辑器并只刷新回复区，刷新失败不会误报发送失败。收起回复或关闭历史面板取消GET，切账号/点评或卸载使旧响应失效；取消POST不代表服务端一定未保存。成功刷新从第一页开始，长讨论通过“加载更多回复”继续查看，不改变外层点评分页、历史面板和评分。默认不显示回复总数，未新增计数或预览接口。

### 17.1 发布回复

`POST /api/reviews/{reviewId}/replies`，需要有效Bearer JWT、有效会话和正常账号；普通用户即可发布。作者只取认证上下文，不接受authorUserId、时间、子回复等额外字段。

```json
{"clientRequestId":"35a4d3f7-476d-4201-b698-d15880a14696","body":"谢谢分享，下次试试。"}
```

clientRequestId必填、规范UUID。body必填，去除首尾Unicode空白后1–200个码点，按纯文本使用，不解释HTML。首次保存201，data如下：

```json
{"replyId":"301","reviewId":"101","replayed":false}
```

同账号、同UUID、同点评及规范化正文重放返回200、原replyId及replayed=true；换正文或点评返回409。不同账号可用相同UUID。网络失败保留正文和UUID，同内容重试；修改内容属于新请求。唯一键与回复记录同生命周期。父点评硬删除后，原reviewId不可回复，旧回复请求返回404，不会转移或重建父点评；不承诺回复UUID跨其他点评永久占用。

事务先定位父店，经restaurant公开服务锁父店并验证可见性，再锁点评、检查幂等、插入回复。唯一键或锁竞争最多重试三个完整事务，单次事务超时10秒，耗尽503。只写回复，不初始化或修改评分统计、不改点评时间或归组代表、不调用OSS，不将旧点评回复转移到新点评。

### 17.2 分页读取回复

`GET /api/reviews/{reviewId}/replies`，公开，无需登录。pageSize默认10，整数1–50；cursor可省略，或原样使用上一段nextCursor，最长200字符。data示例：

```json
{
  "records":[{"id":"301","author":{"id":"11","username":"同学甲"},"body":"谢谢分享，下次试试。","createdAt":"2026-10-30T09:00:00.123456Z"}],
  "hasMore":false,
  "nextCursor":null
}
```

按(createdAt,id)升序排除游标锚点，多取一条判断hasMore；有后续时nextCursor定位本段最后一条，否则为null。无回复返回空数组及false/null。只返回公开作者ID和用户名，缺失作者显示“用户不可用”，不返回邮箱、幂等材料或摘要。

每段REPEATABLE_READ快照覆盖父点评、父店、锚点、正文和署名。非空段无游标固定4条查询，有游标5条；作者批量查询一次，无N+1。不保持跨段快照，后续新回复可出现在后段，没有主动推送。游标绑定点评、微秒时间和ID，验证规范编码及真实锚点，不是授权令牌。锚点消失或时间变化应重新加载。父锁内限制新回复时间不早于该点评最新回复，防时钟倒退导致新回复落在旧游标前。

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | REPLY_INVALID_INPUT | 路径ID、UUID或正文非法 |
| 400 | REPLY_INVALID_CURSOR | 游标编码、点评归属、真实锚点或时间校验失败 |
| 400 | COMMON_VALIDATION_FAILED / COMMON_MALFORMED_REQUEST | 分页越界、类型错误、超长游标、非法JSON或未知字段 |
| 401 | AUTH_UNAUTHENTICATED | 未登录写入、失效会话或停用账号；公开GET主动携带失效Bearer也被拒绝 |
| 403 | AUTH_FORBIDDEN | 认证用户被服务端权限规则拒绝 |
| 404 | REPLY_REVIEW_NOT_FOUND | 点评不存在 |
| 404 | RESTAURANT_NOT_FOUND | 父店不存在或已删除，包含旧成功请求重放 |
| 409 | REPLY_REQUEST_CONFLICT | 同账号UUID对应不同正文或点评 |
| 409 | COMMON_DATA_CONFLICT | 其他数据库完整性约束失败 |
| 503 | REPLY_TEMPORARILY_UNAVAILABLE | 唯一键/锁竞争、查询或事务超时重试耗尽 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期故障，不暴露SQL和正文 |


## 18. 本人删除点评

`DELETE /api/reviews/{reviewId}`，需要有效Bearer JWT、有效会话和正常账号。reviewId为Long范围内无前导零正整数，无请求体。仅作者本人可删，管理员身份不绕过归属限制。最新/历史点评均支持，停业可删；首次删除时父餐厅已隐藏则404。

成功204，无响应体，`Cache-Control: no-store`；本人重复删除仍204，不重复扣分。先锁父店再重读归属，与发评、回复及删店统一锁顺序；最多3个完整事务重试，每次10秒。

删历史仅条数减一；删最新由上一条接替、人数不变；删本人最后一条人数减一并移除贡献。回复和照片关联显式硬删除，不转移。媒体经file公开服务在同事务转换为PENDING_DELETE，默认后台每60秒处理至多50条，云失败保留重试，云调用不持数据库锁。204表示点评删除已提交，不保证云对象已物理删除；OSS关闭时需开启正确配置才能执行清理任务。

V8新增review_request_receipts并回填已有发布：作者、UUID、点评/餐厅ID及删除时间，不保存正文照片，无跨模块外键。回执持续保留、不自动过期，未来账号清理另定策略。旧发布UUID重放409 REVIEW_REQUEST_DELETED，换内容或换店亦拒绝；新UUID允许真实再次评价。跨店竞争由回执唯一键和完整事务重试兜底。旧回复请求因原点评不存在返回404，不恢复父点评。

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 204 | 无响应体 | 删除完成，或本人重复删除 |
| 400 | REVIEW_INVALID_QUERY | 非法reviewId |
| 401 | AUTH_UNAUTHENTICATED | 未登录、失效凭证/会话、停用账号 |
| 403 | REVIEW_FORBIDDEN | 非原作者，包括管理员代删 |
| 404 | REVIEW_NOT_FOUND | 未知点评且无删除回执 |
| 404 | RESTAURANT_NOT_FOUND | 首次删除时父餐厅隐藏 |
| 409 | COMMON_DATA_CONFLICT | 数据完整性失败，整体回滚 |
| 503 | REVIEW_TEMPORARILY_UNAVAILABLE | 锁/超时重试耗尽，可重试 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期故障，整体回滚 |

前端比较已认证账号ID与公开作者ID决定入口，不按昵称、管理员角色或预览canDelete授权。服务端独立校验。确认框支持取消/Escape、失败重试；历史面板先关闭、释放滚动锁再确认。成功回第一页，并复用发布后的统计刷新流程。

## 地图视口读取（已实现）

### GET /api/restaurants：可选地图范围

访客可读，无写入副作用。新增west、south、east、north四个GCJ-02十进制边界，四项同时省略保持原目录行为；一旦提供，必须四边齐全，west<east、south<north，经度[-180,180]、纬度[-90,90]，首版不支持跨日期变更线。非数值、缺边、越界、反向、零面积均400 COMMON_VALIDATION_FAILED。

提供视口时排除待定位/缺坐标和软删除餐厅，边界包含；在完整符合区域及业务条件的集合上排序后分页。keyword、category、hideClosed、maxPrice、maxDistanceMeters、全部8种sort和原page/pageSize规则保持不变。距校区筛选仍以清水河参考点计算，不随地图中心移动。记录与total/totalPages同一只读事务快照。

### GET /api/restaurants/map-points

访客可读，仅公开该GET入口，不扩大写入或管理权限。必填west、south、east、north，其余业务筛选与目录相同；page/pageSize不控制点位集合，点位固定最多500条。四边全部缺失400 RESTAURANT_INVALID_INPUT，其他边界校验同上。请求不访问高德、不调用外部搜索、不写数据库，也没有新增审核状态；只查询当前系统已有可见餐厅。

```http
GET /api/restaurants/map-points?west=104.04&east=104.16&south=30.62&north=30.73&sort=score
```

返回ApiResponse.data字段：

| 字段 | 含义 |
| --- | --- |
| points | 公开轻量点位数组，不随列表翻页变化 |
| points[].id/name | 字符串餐厅ID、名称 |
| points[].latitude/longitude | 已确认GCJ-02坐标；不包含待定位记录 |
| points[].score | 当前每账号最新代表的综合均分；无评分为null |
| points[].pricePerPerson | 人均整数元或null，不伪造未知消费 |
| points[].businessStatus | OPERATING/CLOSED，不表示实时开门状态 |
| matchedCount | 该视口符合条件的完整数量，与点位同一事务快照 |
| returnedCount | 实际返回数量，最大500 |
| limit/truncated | 固定500；matchedCount超过returnedCount时为true |

空区域data示例：

```json
{"points":[],"matchedCount":0,"returnedCount":0,"limit":500,"truncated":false}
```

排序与目录共用SQL：默认综合评分降序，无评分靠后，同分名称和ID稳定排序，筛选和排序发生在截断前。超限时前端提示放大地图缩小范围，不把500点称为全部；列表仍可按完整匹配数量分页。两个独立HTTP请求不是跨请求原子快照，并发写入时各自反映各自的事务快照。

前端监听高德moveend/zoomend，停顿300毫秒自动刷新最终视口，取消并拒绝迟到响应，失败保留上次结果且明确提示。缺Key或安全代理时保留全局餐厅目录，不能把降级目录当作视口点位验收。本地真实高德底图及自动视口请求已验证，生产环境及App唤起另行验收。
