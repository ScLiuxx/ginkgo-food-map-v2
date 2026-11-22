# 内容举报与处置

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/api/reviews/{id}/reports` | Bearer | 201 / 200重复 | [提交举报](#submit-report) |
| POST | `/api/review-replies/{id}/reports` | Bearer | 201 / 200重复 | [提交举报](#submit-report) |
| GET | `/api/admin/content-reports` | 管理员 | 200 | [管理员列表与详情](#admin-read) |
| GET | `/api/admin/content-reports/{id}` | 管理员 | 200 | [管理员列表与详情](#admin-read) |
| POST | `/api/admin/content-reports/{id}/dismiss` | 管理员 | 200 | [驳回举报](#dismiss) |
| POST | `/api/admin/content-reports/{id}/remove` | 管理员 | 200 | [删除违规内容](#remove) |

<a id="reports"></a>

## 内容举报

第一版只受理点评和回复。被举报内容在核查前继续公开（RISK-003）；当前已完成举报提交、管理员查询、驳回、永久删除和举报结果通知。

<a id="submit-report"></a>

### 提交举报

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/reviews/{id}/reports` | Bearer | 201 / 200重复 |
| POST | `/api/review-replies/{id}/reports` | Bearer | 201 / 200重复 |

**请求参数**

`POST /api/reviews/{reviewId}/reports`或`POST /api/review-replies/{replyId}/reports`，需Bearer。路径决定目标类型，举报人只取当前认证身份，不能指定其他举报人。请求体示例：

```json
{"reason":"ABUSE","detail":"包含人身攻击"}
```

reason必填，大小写精确，取值为SPAM、ABUSE、PORNOGRAPHY、ILLEGAL、PRIVACY、IRRELEVANT、OTHER。detail选填，去首尾空白后为空视为未填写，最多200码点；未知字段返回400通用请求错误。

**响应**

首次提交201，data示例：

```json
{"reportId":"1","targetType":"REVIEW","targetId":"101","status":"PENDING","duplicate":false,"createdAt":"2026-11-10T08:00:00Z"}
```

**错误码**

目标不存在或父店不可见统一404 `REPORT_TARGET_NOT_FOUND`；举报自己发布的内容403 `REPORT_SELF_NOT_ALLOWED`；原因非法、编号非法或说明超长400 `REPORT_INVALID_INPUT`；未登录401。举报不改变内容可见性、商户评分、点赞或成长经验。

**副作用与限制**

同一用户对同一内容再次提交返回200、duplicate=true和原记录，不新增行，也不覆盖首次原因、说明和正文快照。服务端在提交时读取并保存正文及作者快照，普通接口不回显快照。

<a id="admin-read"></a>

### 管理员列表与详情

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/admin/content-reports` | 管理员 | 200 |
| GET | `/api/admin/content-reports/{id}` | 管理员 | 200 |

**请求参数**

`GET /api/admin/content-reports`需管理员。status默认PENDING，只接受PENDING、RESOLVED、DISMISSED；page默认1，pageSize默认20、范围1–50；按提交时间及ID升序。成功200、禁止缓存，data为：

**响应**

```json
{"records":[{"id":"1","targetType":"REVIEW","targetId":"101","reviewId":"101","reporterUserId":"11","targetAuthorUserId":"22","contentSnapshot":"被举报正文","reason":"ABUSE","detail":"含攻击性内容","status":"PENDING","handledBy":null,"handledAt":null,"decisionNote":null,"createdAt":"2026-11-10T08:00:00Z"}],"total":1,"totalPages":1,"page":1,"pageSize":20}
```

**错误码**

`GET /api/admin/content-reports/{id}`返回同一单条结构。所有编号为字符串；contentSnapshot为提交时保存的正文，仅管理员可读。匿名401、普通用户403、状态或分页非法400、编号不存在404 `REPORT_NOT_FOUND`。

<a id="dismiss"></a>

### 驳回举报

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/admin/content-reports/{id}/dismiss` | 管理员 | 200 |

**请求参数**

`POST /api/admin/content-reports/{id}/dismiss`需管理员，请求体可省略，或为`{"note":"未认定违规"}`；note选填，去首尾空白后最多200码点，空白视为未填写。同一内容（target_type与target_id相同）下所有PENDING举报一并改为DISMISSED，记录相同处理人、时间和备注，避免逐条重复处理（RISK-026）。

**响应**

首次与重复驳回均200，data为`{"reportId":"1","status":"DISMISSED","affectedReports":2,"handledAt":"2026-11-10T08:05:00Z"}`；重复驳回时affectedReports为0。不存在404 `REPORT_NOT_FOUND`；举报已因内容删除变为RESOLVED时409 `REPORT_STATE_CONFLICT`，不能反向改写处置结果。驳回不改变内容可见性、评分或作者状态；结案后每位举报者收到一条`DISMISSED`结果通知，重复驳回不重复发送。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="remove"></a>

### 删除违规内容

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/admin/content-reports/{id}/remove` | 管理员 | 200 |

**请求参数**

`POST /api/admin/content-reports/{id}/remove`需管理员，请求体可省略，或为`{"note":"确认违规"}`；note规则与驳回相同。确认违规后永久删除被举报的点评或回复（RISK-004），不提供恢复；点评删除时关联回复、点评图片一并移除，商户评分按同作者最新有效点评重算（RISK-005），被删内容的点赞不再计入获赞（INT-015）。回复删除只移除该条回复，不改变父点评和评分。

**响应**

首次200，data示例：

```json
{"reportId":"1","targetType":"REVIEW","targetId":"101","status":"RESOLVED","affectedReports":2,"contentDeleted":true,"handledAt":"2026-11-10T08:10:00Z"}
```

**错误码**

重复删除同一举报返回200、status=RESOLVED、contentDeleted=false、affectedReports=0；内容此前已被作者删除时同样只完成结案。举报已驳回409 `REPORT_STATE_CONFLICT`；举报不存在404 `REPORT_NOT_FOUND`；目标点评或回复无法定位404对应内容错误码；事务冲突或超时409/503沿用review模块的`BUSY`语义。结案后每位举报者收到一条`REMOVED`结果通知，重复删除不重复发送；删除不通知被处置作者，也不执行账号处罚。

**副作用与限制**

同一内容（target_type与target_id相同）下所有PENDING举报一并改为RESOLVED，记录相同处理人、时间和备注（RISK-026）。内容删除与举报结案分属两个事务：若结案阶段失败，管理员重试会得到contentDeleted=false并完成结案，不重复删除。

<a id="scope"></a>

### 范围边界

普通用户在他人点评/回复操作区提交举报，管理员在`/admin/reports`查看与处置；账号处罚、禁言、停用和申诉不在本轮范围。
