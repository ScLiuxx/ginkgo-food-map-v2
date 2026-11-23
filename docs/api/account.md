# 账号、会话与本人资料

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/auth/email/code/register` | 公开 | 200 | [邮箱验证码](#email-codes) |
| POST | `/auth/email/code/password-reset` | 公开 | 200 | [邮箱验证码](#email-codes) |
| POST | `/auth/register-and-login` | 公开 | 200 | [注册](#register) |
| POST | `/auth/register` | 公开 | 201 | [注册](#register) |
| POST | `/auth/password-reset` | 公开 | 200 | [设置新密码](#password-reset) |
| POST | `/auth/login` | 公开 | 200 | [统一登录](#login) |
| GET | `/auth/me` | Bearer | 200 | [当前身份与退出](#identity-logout) |
| POST | `/auth/logout` | Bearer | 200 | [当前身份与退出](#identity-logout) |
| GET | `/api/admin/me` | 管理员 | 200 | [工作台身份校验](#admin-session) |
| GET | `/api/me/profile` | Bearer | 200 | [本人资料与简介](#profile) |
| PATCH / PUT | `/api/me/profile` | Bearer | 200 | [本人资料与简介](#profile) |
| PATCH | `/api/me/profile/username` | Bearer | 200 | [修改用户名](#username) |
| PUT | `/api/me/avatar` | Bearer | 204 | [用户头像](#avatar) |
| GET | `/api/users/{userId}/avatar` | 公开 | 200 JPEG / 204 | [用户头像](#avatar) |
| GET | `/api/me/growth` | Bearer | 200 | [本人成长摘要](#growth) |

<a id="avatar"></a>

## 用户头像

**接口与权限**

`PUT /api/me/avatar`只修改认证账号本人；`GET /api/users/{userId}/avatar`允许游客读取当前头像，用于公开点评署名，不开放个人主页或其他资料。

**请求参数**

先以`purpose=AVATAR`调用[临时图片上传](files.md#temporary)，再保存：

```json
{"fileId":"501"}
```

fileId为正Long十进制字符串，只接受本人上传、未过期且可绑定的AVATAR图片，不接受其他用途。身份只从Bearer获取，不能指定其他用户。userId同样为正Long十进制字符串。

**响应**

保存成功204，无正文，禁止缓存。读取有图返回200 `image/jpeg`、`Cache-Control: no-store`和`X-Content-Type-Options: nosniff`；账号存在但没有头像返回204，由前端显示用户名首字。读取不返回文件ID、Bucket、对象键或外部链接。

**错误码**

未登录保存401；请求缺少fileId或ID非法400（格式错误沿用通用参数错误；超出Long范围为`USER_PUBLIC_PROFILE_INVALID`）；文件归属、用途、状态或期限不符409 `FILE_NOT_BINDABLE`；保存账号不可用401 `USER_PROFILE_UNAVAILABLE`。读取不存在、已删除或未验证账号404 `USER_PUBLIC_PROFILE_NOT_FOUND`；文件不可读404 `FILE_NOT_FOUND`，存储故障503 `FILE_STORAGE_UNAVAILABLE`。账号锁定、停用仍保留公开历史署名，不能凭此修改本人资料。

**副作用与限制**

新图绑定、用户关联更新及旧图登记回收在同一事务提交，失败全部回滚；旧图由文件后台任务删除，取消临时上传不能删除已绑定头像。同一当前fileId重试幂等，不重复写入或释放当前图；旧头像在替换完成后不能再次用原ID恢复。并发保存按实际提交顺序生效，最后提交的图片为当前头像。

不改变凭证版本、不强制退出、不覆盖简介。头像作为内容署名可公开展示，已传到浏览器的图片字节不能撤回。当前不提供删除头像、自由拖动裁剪、头像历史或审批接口；前端居中裁剪为不超过512px的方形JPEG，预览与上传一致。

<a id="registration"></a>

## 注册、验证码与密码重置

<a id="email-codes"></a>

### 邮箱验证码

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/auth/email/code/register` | 公开 | 200 |
| POST | `/auth/email/code/password-reset` | 公开 | 200 |

**请求参数**

`POST /auth/email/code/register`、`POST /auth/email/code/password-reset`均无需认证，请求：

```json
{"email":"student@std.uestc.edu.cn"}
```

**响应**

邮箱必须为有效格式，服务端规范化为小写并检查允许域名，默认仅`std.uestc.edu.cn`。成功200、`data:null`；验证码通过邮件发送，不在响应中返回。

**错误码**

注册验证码：已注册邮箱409 `USER_EMAIL_ALREADY_REGISTERED`。找回密码验证码：未注册但格式和域名有效的邮箱也返回相同成功响应，不发送邮件，不能据此确认账号存在。

其他错误：400 `COMMON_VALIDATION_FAILED`、`USER_CAMPUS_EMAIL_REQUIRED`；429 `AUTH_CODE_RESEND_COOLDOWN`、`AUTH_CODE_EMAIL_RATE_LIMITED`；503 `AUTH_CODE_MAIL_DELIVERY_FAILED`、`AUTH_CODE_DELIVERY_STATE_EXPIRED`、`AUTH_CODE_SERVICE_UNAVAILABLE`。邮件受理成功不保证收件箱投递完成。

**副作用与限制**

默认验证码6位数字、5分钟有效。同邮箱60秒冷却、每小时5次、每24小时10次；注册和找回密码共享发送配额，但验证码用途隔离。错误尝试最多5次；成功消费后失效。同用途重新申请替换原验证码。发送失败会撤销待激活验证码及冷却，不退发送配额。

<a id="register"></a>

### 注册

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/auth/register-and-login` | 公开 | 200 |
| POST | `/auth/register` | 公开 | 201 |

**请求参数**

`POST /auth/register`、`POST /auth/register-and-login`请求相同：

| 字段 | 规则 |
| --- | --- |
| `email` | 必填有效校园邮箱，与验证码绑定邮箱一致 |
| `verificationCode` | 必填，严格6位ASCII数字，保留前导零 |
| `username` | 去首尾空白后1–24字符，中文/英文字母/数字/下划线，区分大小写及重音，唯一 |
| `password` | 4–32位ASCII字母、数字或英文符号，不含空格、中文或控制字符，不强制组合，不执行trim |

```json
{"email":"student@std.uestc.edu.cn","verificationCode":"012345","username":"foodmap_user","password":"ExamplePassword2026"}
```

**响应**

`/auth/register`成功201、`data:null`，不建立会话。`/auth/register-and-login`成功200，`data`与登录响应相同，`Cache-Control:no-store`。

**错误码**

错误：400 `COMMON_VALIDATION_FAILED`、`USER_CAMPUS_EMAIL_REQUIRED`、`USER_USERNAME_INVALID`、`USER_PASSWORD_WEAK`、`AUTH_CODE_INVALID_OR_EXPIRED`；409 `USER_EMAIL_ALREADY_REGISTERED`、`USER_USERNAME_ALREADY_REGISTERED`、`USER_REGISTRATION_CONFLICT`；429 `AUTH_CODE_ATTEMPTS_EXCEEDED`；503 `AUTH_CODE_SERVICE_UNAVAILABLE`。

**副作用与限制**

两者只分配`ROLE_USER`，没有公开管理员注册或提权入口。请求额外附带`role`、`roles`、`isAdmin`会被忽略，不用于授权。确认密码仅由前端比较，不属于接口字段。

邮箱和用户名必须唯一。验证码先消费，再在事务内写入账号及角色；写入失败不会恢复验证码。注册自动登录在账号事务提交后建立会话，后续登录失败返回503 `AUTH_REGISTER_LOGIN_FAILED`，应改用登录，不重复注册。

<a id="password-reset"></a>

### 设置新密码

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/auth/password-reset` | 公开 | 200 |

**请求参数**

`POST /auth/password-reset`无需认证：

```json
{"email":"student@std.uestc.edu.cn","verificationCode":"012345","newPassword":"NewExamplePassword2026"}
```

**响应**

`newPassword`使用注册密码规则；验证码必须为密码重置用途。成功200、`Cache-Control:no-store`，`data:{"username":"实际用户名"}`，不返回密码或令牌，不自动登录。

**错误码**

错误包括验证码错误、429错误次数超限、409 `USER_PASSWORD_RESET_CONFLICT`、503 `AUTH_CODE_SERVICE_UNAVAILABLE`或`USER_PASSWORD_RESET_UNAVAILABLE`。输入无效时返回400；临时故障不能视为密码已更新。

**副作用与限制**

仅已验证邮箱且状态ACTIVE的账号可改密，其他情况统一`AUTH_CODE_INVALID_OR_EXPIRED`。密码更新和凭证版本递增同事务完成，使所有旧会话在后续认证时失效，不解除停用或登录失败锁定。已消费验证码不因后续故障恢复。

<a id="sessions"></a>

## 登录与会话

<a id="login"></a>

### 统一登录

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 公开 | 200 |

**请求参数**

`POST /auth/login`无需认证：

```json
{"username":"foodmap_user","password":"ExamplePassword2026"}
```

`username`字段必填，可填写用户名或注册邮箱，原始输入最多254个UTF-16单元，去首尾空白后查询；含`@`时按邮箱查询并转小写，否则按用户名查询。两种方式共用同一用户ID的登录失败限流。密码必填，DTO最多72个UTF-16单元，服务端额外限制UTF-8不超过72字节，原样匹配BCrypt，不trim。

**响应**

成功200、`Cache-Control:no-store`，`data`：

```json
{"accessToken":"<accessToken>","tokenType":"Bearer","expiresAt":"2026-11-10T06:00:00Z","user":{"id":42,"username":"foodmap_user","roles":["ROLE_USER"]}}
```

**错误码**

每次成功创建独立Redis会话，默认7天有效，无刷新令牌或滑动续期。默认15分钟内累计失败5次锁定15分钟，成功清除失败计数。账号不存在、密码错误、未验证邮箱或不可用统一401 `AUTH_INVALID_CREDENTIALS`；锁定429 `AUTH_LOGIN_LOCKED`；认证依赖故障503 `AUTH_SERVICE_UNAVAILABLE`。

**副作用与限制**

前端统一入口`/login`，普通账号进入前台或安全站内回跳，含`ROLE_ADMIN`的账号进入工作台。管理账号仅显示管理界面，使用普通用户功能需退出并换普通账号；`/admin/login`仅跳转统一登录。前后台共用同源会话，跨标签页同步退出与账号切换。

<a id="identity-logout"></a>

### 当前身份与退出

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/auth/me` | Bearer | 200 |
| POST | `/auth/logout` | Bearer | 200 |

**响应**

`GET /auth/me`需Bearer，无参数/请求体；成功200、禁止缓存，`data`为上述`user`。刷新必须验证身份，不能仅凭本地存在令牌判定已登录。401 `AUTH_UNAUTHENTICATED`表示会话无效，503 `AUTH_SERVICE_UNAVAILABLE`可重试；认证后身份再次变化可返回401 `AUTH_INVALID_CREDENTIALS`。

`POST /auth/logout`需Bearer，无请求体；成功200、`data:null`，撤销当前会话，其他独立会话不受影响。收到成功后清理对应本地凭证；503不表示退出成功，保留重试能力。成功撤销后的同一令牌再次请求返回401。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

浏览器默认用同源localStorage保存访问令牌，不保存密码；存储不可用时使用内存会话。公开登录、验证码、注册和找回密码请求应省略Bearer。

<a id="admin-session"></a>

## 工作台身份校验

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/admin/me` | 管理员 | 200 |

**响应**

`GET /api/admin/me`无参数，成功200，`data`为当前公开身份`{id,username,roles}`。普通账号403 `AUTH_FORBIDDEN`；匿名/失效会话401；认证依赖故障503。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="profile"></a>

## 本人资料与简介

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/profile` | Bearer | 200 |
| PATCH / PUT | `/api/me/profile` | Bearer | 200 |

**请求参数**

`GET /api/me/profile`读取资料，`PATCH`和`PUT /api/me/profile`均可保存简介，三者都需Bearer。用户归属只取当前认证身份。GET无业务参数。额外userId查询参数不改变查询或修改对象。改名和头像分别使用[修改用户名](#username)和[用户头像](#avatar)接口。收藏及关注名单隐私使用[收藏设置](community.md#favorite-visibility)和[关注设置](community.md#follow-privacy)。

保存只接受必填字符串bio，空字符串可清空。未知JSON字段（含userId、username、email、roles）返回400 `COMMON_MALFORMED_REQUEST`，数字、布尔值、数组或对象不能自动转为字符串。缺少或null返回400 `COMMON_VALIDATION_FAILED`。请求示例：

```json
{"bio":"喜欢探索校园美食"}
```

服务端将CRLF/CR统一为LF、移除首尾Unicode空白及FEFF，之后最多100个Unicode码点，表情按实际码点计数；允许内部换行和制表符。其他控制字符、未配对代理项不接受。常见联系方式与网址按下述基础识别规则检查，保存后直接生效，无人工审核流程。

检查副本做NFKC、去空白/Unicode格式字符并转小写；手机号检查还移除数字间常见单个分隔标点。识别大陆手机号及可选86前缀、带微信/wechat/wx/vx标记的账号及wxid_、带QQ/QQ群/企鹅标记的数字、带手机/电话/tel/联系方式标记的数字，以及scheme://、www.、常见联系方式协议、裸域名及裸IPv4。正常提及“微信支付”“QQ糖”及菜品编号允许；这是基础识别，可能误拦或漏过混淆及境外号码，不自动处罚账号。展示原文保留正常emoji连接符，使用纯文本。

**响应**

成功均为200、`Cache-Control: no-store`，统一响应的data例如：

```json
{"userId":"11","username":"校园食客","maskedEmail":"s***@example.edu.cn","bio":"喜欢探索校园美食"}
```

| 字段 | 含义 |
| --- | --- |
| userId | 当前用户ID，字符串。 |
| username | 当前用户名。 |
| maskedEmail | 脱敏绑定邮箱，不返回完整邮箱；本地部分超过两个Unicode码点时保留首个码点，否则完全隐藏；保留域名，例如student@example.edu.cn为s***@example.edu.cn，ab@example.edu.cn为***@example.edu.cn，异常格式为***。 |
| bio | 已保存的纯文本简介；空字符串表示尚未填写或已清空。 |

**错误码**

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

**副作用与限制**

仅修改bio及既有row_version、updated_at，不改变邮箱、用户名、密码、角色、账号状态或credential_version，原有效登录凭证可继续使用。规范化后与现值相同则不写入、不递增版本或修改更新时间。更新使用用户行锁；多标签页并发修改按最后提交的完整简介生效，不提供客户端版本/ETag冲突协议。超时或取消不能证明服务器未保存，同值重试安全；客户端必须等待成功回执或重新查询确认。

<a id="username"></a>

## 修改用户名

`PATCH /api/me/profile/username` 需要有效 Bearer 会话，只更新当前账号。请求仅接受必填字符串 `username`，沿用[注册用户名规则](#register)，去首尾空白后允许 1–24 个汉字、英文字母、数字或下划线，区分大小写。

```json
{"username":"清河食客"}
```

成功返回 200 和 `Cache-Control: no-store`，data 与[本人资料](#profile)相同。同值重试成功且不会重复写入。改名后按新用户名登录，原会话和学号登录继续有效，已有内容仍关联同一用户 ID。

缺失或空白用户名返回 400 `COMMON_VALIDATION_FAILED`，字符或长度不合规则返回 400 `USER_USERNAME_INVALID`，非字符串或额外 JSON 字段返回 400 `COMMON_MALFORMED_REQUEST`。用户名已被其他账号占用返回 409 `USER_USERNAME_ALREADY_REGISTERED`，由数据库唯一约束处理并发抢占。会话或账号不可用时沿用[本人资料](#profile)的 401。

<a id="growth"></a>

## 本人成长摘要

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/growth` | Bearer | 200 |

**请求参数**

`GET /api/me/growth`需Bearer，无业务请求参数，仅从当前认证身份确定用户，不能通过额外userId参数查询别人。无发奖、撤销、任意用户成长查询或经验修改HTTP接口。

**响应**

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

**错误码**

缺少、过期、会话失效或账号不可用的凭证返回401 `AUTH_UNAUTHENTICATED`；认证依赖暂不可用沿用503 `AUTH_SERVICE_UNAVAILABLE`；已认证但方法不匹配返回405 `COMMON_METHOD_NOT_ALLOWED`；未预期查询故障返回500 `COMMON_INTERNAL_SERVER_ERROR`，不降级成零经验。只有实际无账户记录才按零经验解释。

**副作用与限制**

未获奖账号返回零经验、初始等级，GET不创建账户。初始门槛为0/100/400/1000，可通过GROWTH_LEVEL_THRESHOLDS配置，以英文逗号分隔；门槛必须从0开始、严格递增，奖励分值必须为正，否则启动配置校验失败。配置随部署生效，旧奖励保留实际发放经验，不因调参重发或重算；已撤销奖励的恢复未开放。

普通餐厅与食堂均按每用户每店接入首次点评奖励；前端个人中心、成长详情及本人主页已使用本接口，退出或换号清理旧资料，错误不降级为零经验。审核通过奖励已接入，新店默认+20、同人同店纠错仅首次+10；点评获赞奖励已接入（规则见18节），点评默认/时间排序及内部批量等级查询已接入，作者数字等级展示及登录可见个人主页已接入。内部幂等撤销能力已提供给未来风控协作，当前没有公开处置入口。其他社区页面的待开放入口未增加后端HTTP接口。
