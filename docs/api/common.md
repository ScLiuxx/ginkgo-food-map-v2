# 通用约定与错误

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

<a id="conventions"></a>

## 通用约定

| 项目 | 约定 |
| --- | --- |
| 本地后端 | `http://localhost:8080` |
| 本地前端 | `http://localhost:5173`，代理`/auth`和`/api` |
| 请求格式 | 有请求体的业务接口使用JSON；图片上传使用multipart/form-data |
| 认证 | 业务接口使用`Authorization: Bearer <accessToken>`，不使用Basic或Cookie会话；内部指标端点可选独立Basic采集凭据 |
| 业务ID | 餐厅、申请、点评、回复和文件ID采用无前导零的正整数字符串，范围1–9223372036854775807 |
| 账号ID | `/auth/login`、`/auth/me`及`/api/admin/me`中的账号ID为JSON整数；其他署名/业务资源引用为字符串 |
| 时间 | ISO 8601 UTC字符串 |
| 评分 | 点评`scores`、`overallScore`、餐厅`score`及四维评分为0–10，前端展示时除以2；`reputationScore`、选店`minRating`及榜单`scoreScale=5`均为五分制，不再除以2 |
| 坐标 | GCJ-02；用户查询位置不写入数据库 |

除图片字节、204无内容、SSE事件流、Actuator及代理／静态探针外，业务响应使用统一封装：

```json
{"success":true,"code":"OK","message":"请求成功","data":{},"timestamp":"2026-11-03T06:00:00Z"}
```

失败时`success=false`，`code`为稳定业务错误码，`data`通常为null；字段校验失败时可为`[{"field":"username","message":"请输入用户名"}]`。错误不会返回SQL、密码、令牌或云存储凭据。

受保护请求校验JWT签名、发行方、受众、有效期、Redis会话、数据库当前账号状态、凭证版本及角色。401可清理该请求所用凭证，不能清除之后切换的新会话；403表示无权限；429表示限流；503表示临时故障，保留凭证和可重试内容。主动向公开接口携带无效Bearer仍可能得到401。

认证、管理员私有资料、点评/回复及图片响应禁止缓存。未预期故障返回500 `COMMON_INTERNAL_SERVER_ERROR`，不能当成空结果或操作成功。

<a id="errors"></a>

## 通用错误与能力边界

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | COMMON_VALIDATION_FAILED | 字段类型、必填、范围或白名单校验失败 |
| 400 | COMMON_MALFORMED_REQUEST | JSON语法/读取/严格DTO未知字段错误 |
| 401 | AUTH_UNAUTHENTICATED | 身份缺失、失效或不可使用 |
| 403 | AUTH_FORBIDDEN | 当前角色无权限 |
| 404 | COMMON_NOT_FOUND | 已通过认证边界但请求地址不存在 |
| 405 | COMMON_METHOD_NOT_ALLOWED | 已通过认证边界但HTTP方法不匹配 |
| 409 | COMMON_DATA_CONFLICT | 数据库完整性约束冲突 |
| 500 | COMMON_INTERNAL_SERVER_ERROR | 未预期故障，不能当成成功或空数据 |

其他业务错误码见各接口章节。认证过滤可先于MVC返回401，不能用错误方法探测接口是否存在。限流响应不保证Retry-After、剩余次数或精确解锁时间。

本人待审申请撤回见[申请接口](submissions.md#my-withdraw)，窗口相册删除见[窗口接口](windows.md#album)。餐厅正式相册的图片删除/替换/重排/手动封面、编辑点评及恢复点评尚未开放；各业务的当前限制在对应分册维护。
