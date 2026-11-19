# 个人主页、收藏与关注

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| GET | `/api/users/{userId}/profile` | Bearer | 200 | [公开资料](#public-info) |
| GET | `/api/users/{userId}/reviews` | Bearer | 200 | [按店铺分组的公开点评](#public-reviews) |
| GET | `/api/restaurants/{id}/favorite` | Bearer | 200 | [本人收藏状态与操作](#favorite-state) |
| PUT | `/api/restaurants/{id}/favorite` | Bearer | 200 | [本人收藏状态与操作](#favorite-state) |
| DELETE | `/api/restaurants/{id}/favorite` | Bearer | 200 | [本人收藏状态与操作](#favorite-state) |
| GET | `/api/me/favorites` | Bearer | 200 | [本人与公开收藏列表](#favorite-lists) |
| GET | `/api/users/{id}/favorites` | Bearer | 200 | [本人与公开收藏列表](#favorite-lists) |
| GET | `/api/me/favorites/visibility` | Bearer | 200 | [收藏列表可见性](#favorite-visibility) |
| PATCH | `/api/me/favorites/visibility` | Bearer | 200 | [收藏列表可见性](#favorite-visibility) |
| GET | `/api/users/{id}/social` | Bearer | 200 | [人数与关系操作](#follow-state) |
| PUT | `/api/users/{id}/follow` | Bearer | 200 | [人数与关系操作](#follow-state) |
| DELETE | `/api/users/{id}/follow` | Bearer | 200 | [人数与关系操作](#follow-state) |
| GET | `/api/users/{id}/following` | Bearer | 200 | [关注与粉丝名单](#follow-lists) |
| GET | `/api/users/{id}/followers` | Bearer | 200 | [关注与粉丝名单](#follow-lists) |
| GET | `/api/me/follows/privacy` | Bearer | 200 | [两种名单的隐私设置](#follow-privacy) |
| PATCH | `/api/me/follows/privacy` | Bearer | 200 | [两种名单的隐私设置](#follow-privacy) |

<a id="public-profile"></a>

## 登录可见个人主页

两个入口均需有效Bearer、当前可用账号及会话，匿名返回401；成功200并返回Cache-Control: no-store。查看对象来自路径userId，不代表写入身份。主页没有经验、资料或处罚写接口。

<a id="public-info"></a>

### 公开资料

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/users/{userId}/profile` | Bearer | 200 |

**请求参数**

`GET /api/users/{userId}/profile`，无查询参数。data示例：

**响应**

```json
{"userId":"22","username":"同学甲","bio":"喜欢面食","level":3,"receivedLikes":7}
```

只返回上述5项，不返回原始/脱敏邮箱、经验余额、奖励明细、角色、学生认证/账号状态、投稿或纠错记录。level按当前经验和部署门槛计算，无成长账户按零经验计算，不创建账户。receivedLikes沿用当前有效点评与回复获赞合计，取消赞或源内容删除后相应减少。资料、等级与获赞在同一只读快照查询。

页面头像通过独立的[当前用户头像接口](account.md#avatar)读取，本响应及作者署名不增加文件ID或OSS地址字段；头像允许随公开点评向游客展示，不改变个人主页的登录要求。

**错误码**

非法或超出正Long范围的userId返回400 `USER_PUBLIC_PROFILE_INVALID`；用户不存在、已删除或未验证邮箱时返回404 `USER_PUBLIC_PROFILE_NOT_FOUND`。停用账号正常历史内容不因此隐藏；本入口不授予被停用账号正常登录权限。

<a id="public-reviews"></a>

### 按店铺分组的公开点评

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/users/{userId}/reviews` | Bearer | 200 |

**请求参数**

`GET /api/users/{userId}/reviews?page=1&pageSize=8`，page为正整数，pageSize为1～50；只绑定分页，查询参数不能更换作者、目标或派生offset。响应复用[本人按店分组](reviews.md#my-reviews)的data结构，author为被查看用户，并新增数字level；不返回投稿或其他隐私记录。

**响应**

先排除不可见店铺，再按被查看作者的restaurant_id归组，每家店选最新有效点评作为主卡片；按主卡片createdAt、数字ID倒序后分页。返回真实totalGroups/totalReviews、historyCount和nextHistoryCursor，空/越界页仍保留计数；停业可见；窗口点评按WINDOW类型和window_id独立归组，不与同号餐厅混合。历史展开沿用[同作者历史接口](reviews.md#review-history)，删除仍仅允许内容本人，不能凭主页参数取得他人删除权限。目标用户错误同[公开资料](#public-info)，分页非法返回400通用校验错误。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

公开作者投影`{id,username}`现增加可选数字`level`（用户缺失的占位署名无等级）；店铺点评、本人/公开主页点评及历史响应中的真实作者均通过批量成长查询补全等级，不泄露原始经验。

<a id="favorites"></a>

## 店铺收藏与隐私

本节所有入口均需有效Bearer、可用账号及当前会话；匿名401，成功200且Cache-Control: no-store。收藏仅针对restaurants中的普通餐厅及食堂，不提供窗口或点评收藏。写入用户来自认证上下文，不接受客户端指定其他用户；不生成通知、经验或搜索同步任务，不返回店铺收藏人数或收藏者名单。

<a id="favorite-state"></a>

### 本人收藏状态与操作

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{id}/favorite` | Bearer | 200 |
| PUT | `/api/restaurants/{id}/favorite` | Bearer | 200 |
| DELETE | `/api/restaurants/{id}/favorite` | Bearer | 200 |

**请求参数**

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/restaurants/{id}/favorite` | 返回本人当前收藏状态；目标必须存在且可见 |
| `PUT /api/restaurants/{id}/favorite` | 收藏可见店铺；无请求体；重复请求保留原关系ID、收藏时间和排序位置 |
| `DELETE /api/restaurants/{id}/favorite` | 取消本人收藏；无请求体；允许清理已删除目标，未收藏或重复取消同样成功 |

**响应**

上述data为`{"restaurantId":"1","favorited":true}`，取消返回false。停业店铺仍可收藏；GET/PUT目标不存在或已软删除返回404 `RESTAURANT_NOT_FOUND`，非法或超正Long范围ID返回400 `RESTAURANT_INVALID_ID`。写入前锁定商户主行，与商户删除保持顺序；唯一键防止重复关系。取消后重新收藏生成新关系及时间；取消结果不确定时可重发相同DELETE，详情页翻转前需重读状态。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="favorite-lists"></a>

### 本人与公开收藏列表

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/favorites` | Bearer | 200 |
| GET | `/api/users/{id}/favorites` | Bearer | 200 |

**请求参数**

- `GET /api/me/favorites?page=1&pageSize=8`：仅本人，包含失效占位。
- `GET /api/users/{id}/favorites?page=1&pageSize=8`：本人可直接查看；其他登录用户仅当目标收藏已公开时可查看，且不返回失效记录。私密返回403 `RESTAURANT_FAVORITES_PRIVATE`，不返回数量或记录；主体不存在、已删除或未验证邮箱返回404 `USER_PUBLIC_PROFILE_NOT_FOUND`。

page为正整数，pageSize为1～50，只绑定这两个字段；非法分页400通用校验错误。按created_at DESC、数字收藏ID DESC排序，先过滤可见范围，再计数和分页。一次请求在RR快照中核验隐私及读取计数/记录；跨请求数据变化可能移动分页，每次请求重新核验隐私。

**响应**

```json
{"userId":"11","records":[{"restaurantId":"1","name":"校园小馆","restaurantType":"REGULAR","businessStatus":"OPERATING","available":true,"createdAt":"2026-11-07T00:00:00Z"}],"total":1,"page":1,"pageSize":8,"totalPages":1}
```

本人列表失效项仍保留restaurantId、createdAt及available=false，name/restaurantType/businessStatus为null，不继续展示商户历史资料。失效项可取消；店铺不提供恢复，原收藏关联保留至用户主动取消。停业不是失效。空页及越界页保留真实total/totalPages；公开列表total只计入当前可见店铺，不泄露失效项数量。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="favorite-visibility"></a>

### 收藏列表可见性

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/favorites/visibility` | Bearer | 200 |
| PATCH | `/api/me/favorites/visibility` | Bearer | 200 |

**请求参数**

`GET /api/me/favorites/visibility`读取，`PATCH /api/me/favorites/visibility`保存；PATCH只接受`{"favoritesPublic":true}`或false，必须提供真实JSON布尔值。缺少/null、字符串/数字布尔值及未知字段返回400通用校验错误；不能更换设置所属用户。data为`{"userId":"11","favoritesPublic":false}`。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

默认false表示仅本人可见，true表示其他登录用户可查看。真实修改仅更新该字段及row_version，不改变凭证、密码、简介；重复保存同值不推进版本。保存结果不确定时界面先重新读取，不把未确认状态当作成功。公开主页切换到收藏时重新请求列表，退出或切换会话/对象立即清空旧结果；已在其他页面展示的内容不会由后台推送自动撤回。

<a id="follows"></a>

## 基础关注、名单隐私与关注提醒

本节全部入口要求有效Bearer及当前可用账号，匿名401；成功200并禁止缓存。访问者身份来自认证上下文，查询参数或JSON不能指定actor。首版同步维护关系、计数及首次通知，不提供Feed或异步统计。

<a id="follow-state"></a>

### 人数与关系操作

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/users/{id}/social` | Bearer | 200 |
| PUT | `/api/users/{id}/follow` | Bearer | 200 |
| DELETE | `/api/users/{id}/follow` | Bearer | 200 |

**请求参数**

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/users/{id}/social` | 读取目标用户当前关注数、粉丝数及本人是否关注；人数不受名单私密设置影响 |
| `PUT /api/users/{id}/follow` | 无请求体，关注目标用户；只有首次创建有向关系才发通知 |
| `DELETE /api/users/{id}/follow` | 无请求体，取消本人关注；无关系或重复取消同样成功，不创建取消占位 |

**响应**

返回data：`{"userId":"22","followingCount":2,"followersCount":3,"following":true}`。仅active=1的关系计数，不使用历史累计次数。自己查看自己的following为false；尝试关注或取消自己返回400 `FOLLOW_SELF_FORBIDDEN`。非法/溢出ID返回400 `FOLLOW_INVALID_ID`；读取主体不存在、已删除或未验证邮箱返回404 `USER_PUBLIC_PROFILE_NOT_FOUND`；新关注还要求目标账号ACTIVE，停用目标可取消已有关系。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

关系操作按两个用户数字ID升序加锁，再写关系和通知，避免互相关注的反向取锁。首次插入、关注通知在同一READ_COMMITTED事务提交，通知失败整笔回滚；取消和重新关注保留原关系ID、created_at、原通知及首次已读时间，不重复提醒。重复提交同一目标状态不更新关系时间。取消后重关按原关系ID位置显示；并发不同目标状态按数据库实际事务顺序生效。没有收藏、经验、私信、拉黑或Feed副作用。

<a id="follow-lists"></a>

### 关注与粉丝名单

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/users/{id}/following` | Bearer | 200 |
| GET | `/api/users/{id}/followers` | Bearer | 200 |

**请求参数**

- `GET /api/users/{id}/following?page=1&pageSize=8`
- `GET /api/users/{id}/followers?page=1&pageSize=8`

page为正整数，pageSize为1～50，只绑定分页字段；超出范围400通用校验错误。本人可查看自己的两个名单；其他登录用户分别受following_public/followers_public控制，私密返回403 `FOLLOW_LIST_PRIVATE`，不返回名单数量或记录。每次请求重新校验权限；人数仍可通过[人数与关系操作](#follow-state)查询。

**响应**

```json
{"userId":"11","kind":"FOLLOWING","records":[{"userId":"22","username":"食友","level":2,"following":true}],"total":1,"totalPages":1,"page":1,"pageSize":8}
```

kind为FOLLOWING或FOLLOWERS。只对有效关系按关系ID倒序分页，先计数再LIMIT；本页署名、等级及访问者对各成员的关注状态批量补全。缺失逻辑账号显示“用户不可用”、level为null；不按用户名归并，不返回邮箱、账号权限或经验。空/越界页保留真实total。一次请求采用RR快照，跨请求关系变化可能移动分页，不承诺固定排名。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

<a id="follow-privacy"></a>

### 两种名单的隐私设置

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/me/follows/privacy` | Bearer | 200 |
| PATCH | `/api/me/follows/privacy` | Bearer | 200 |

**请求参数**

`GET /api/me/follows/privacy`读取本人设置；`PATCH /api/me/follows/privacy`要求完整显式JSON：`{"followingPublic":true,"followersPublic":false}`。两种名单独立，均默认false；缺少/null、非布尔值或未知字段返回400通用校验错误。data为`{"userId":"11","followingPublic":true,"followersPublic":false}`。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

只更新两个开关及row_version，保留收藏设置、简介和凭证；同值保存不推进版本。写入失败不伪造成功，客户端先重读设置。多个客户端提交不同完整设置时以最后提交的设置为准。

<a id="follow-notices"></a>

### 新增关注消息

关注消息的notificationType为USER_FOLLOWED，sourceId为首次关注关系ID，actorUserId为关注者，reviewId及审核字段全部为空；不补发历史关注通知。

现有[通知接口](notifications.md#inbox)的category新增FOLLOWERS；首页ALL包含该类，unreadCounts新增FOLLOWERS。单条/分类/全部已读与归属规则沿用原收件箱；进入列表不自动已读。提醒逐条显示“某用户关注了你”，先保存已读再打开关注者个人主页。取关不撤销旧通知或重置已读；重新关注不再生成通知。目标主页不可用时页面提示不可访问，不提供私有账号信息。本人可按[删除本人单条消息](notifications.md#delete)删除单条提醒。
