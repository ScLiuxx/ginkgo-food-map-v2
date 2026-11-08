# MySQL 表结构

本文集中维护 MySQL 业务表的当前字段、类型、默认值、枚举、主键、索引、外键与字段约束。覆盖当前迁移定义的 29 张应用表，按下列分组查阅；Flyway 自建的迁移历史表不属于应用表。

## 目录

- [应用元数据表结构](#app-metadata)
- [用户账号、角色与安全审计表结构](#user-profile)
- [餐厅、食堂与窗口表结构](#restaurant-schema)
- [店铺收藏表结构](#restaurant-favorites)
- [商户搜索同步任务表结构](#restaurant-search-outbox)
- [点评、回复与媒体表结构](#review-schema)
- [用户关注表结构](#user-follows)
- [内容点赞表结构](#content-likes)
- [成长经验表结构](#growth-schema)
- [通知表结构](#notification-inbox)

<a id="app-metadata"></a>

## 应用元数据表结构

存储引擎InnoDB，字符集utf8mb4，排序规则utf8mb4_0900_ai_ci。

### `app_metadata`：应用元数据

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `metadata_key` | `varchar(100)` | 否 | 元数据键；无默认值。 |
| `metadata_value` | `varchar(500)` | 否 | 元数据值；无默认值。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；记录创建时间。 |

主键`(metadata_key)`；无其他索引、CHECK约束或外键。预置元数据键`schema_version`的值为字符串`1`，不是Flyway迁移版本号。

<a id="user-profile"></a>

## 用户账号、角色与安全审计表结构

user模块拥有`users`、`roles`、`user_roles`和`security_audit_events`，分别保存账号资料、角色、授权关系及安全审计记录。存储引擎InnoDB，字符集utf8mb4，默认排序规则utf8mb4_0900_ai_ci；username列采用utf8mb4_0900_as_cs，区分大小写。

### `users`：用户账号与资料

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键，用户ID。 |
| `username` | `varchar(32)` | 否 | 全局唯一用户名，区分大小写。 |
| `email` | `varchar(254)` | 否 | 全局唯一邮箱。 |
| `password_hash` | `varchar(255)` | 否 | BCrypt密码哈希。 |
| `email_verified` | `tinyint(1)` | 否 | 默认0；邮箱是否已验证。 |
| `email_verified_at` | `timestamp(6)` | 是 | 默认NULL；邮箱验证完成时间。 |
| `status` | `varchar(20)` | 否 | 默认ACTIVE；ACTIVE正常、LOCKED锁定、DISABLED禁用、DELETED已删除。 |
| `student_verification_status` | `varchar(20)` | 否 | 默认PENDING；PENDING待认证、VERIFIED已认证、REJECTED认证驳回。 |
| `credential_version` | `bigint unsigned` | 否 | 默认0；账号凭证版本。 |
| `row_version` | `bigint unsigned` | 否 | 默认0；记录版本号。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；账号创建时间。 |
| `updated_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)，更新时自动刷新；最后修改时间。 |
| `bio` | `varchar(100)` | 否 | 默认空字符串；个人简介。 |
| `favorites_public` | `tinyint(1)` | 否 | 默认0；0收藏列表仅本人可见，1允许其他登录用户查看，游客不可查看。 |
| `following_public` | `tinyint(1)` | 否 | 默认0；0关注名单仅本人可见，1允许其他登录用户查看，游客不可查看。 |
| `followers_public` | `tinyint(1)` | 否 | 默认0；0粉丝名单仅本人可见，1允许其他登录用户查看，游客不可查看。 |

主键`(id)`；唯一键`uk_users_username(username)`、`uk_users_email(email)`。CHECK限定账号状态和学生认证状态为表中枚举值；`chk_users_favorites_public`、`chk_users_following_public`、`chk_users_followers_public`分别限定三个隐私字段为0或1。

### `roles`：系统角色

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键；无显式默认值。 |
| `code` | `varchar(50)` | 否 | 稳定角色编码；无默认值。 |
| `name` | `varchar(100)` | 否 | 角色展示名称；无默认值。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；角色创建时间。 |

主键`(id)`；唯一键`uk_roles_code(code)`；无CHECK约束或外键。排序规则utf8mb4_0900_ai_ci，角色编码唯一性按该规则比较。预置`ROLE_USER`（普通用户）、`ROLE_ADMIN`（管理员），数据库未将code限制为这两个值。

### `user_roles`：用户角色关系

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `user_id` | `bigint unsigned` | 否 | 用户ID；无默认值。 |
| `role_id` | `bigint unsigned` | 否 | 角色ID；无默认值。 |
| `assigned_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；角色分配时间。 |

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(user_id,role_id)` | 联合主键，同一用户与角色只能关联一次 |
| idx_user_roles_role_id | `(role_id)` | 普通索引 |

| 外键名称 | 关联 | ON DELETE | ON UPDATE |
| --- | --- | --- | --- |
| fk_user_roles_user | user_id → users.id | CASCADE | RESTRICT（未显式声明，MySQL默认行为） |
| fk_user_roles_role | role_id → roles.id | RESTRICT | RESTRICT（未显式声明，MySQL默认行为） |

无额外CHECK约束。

### `security_audit_events`：认证与账号安全审计事件

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键；无显式默认值。 |
| `user_id` | `bigint unsigned` | 是 | 默认NULL；已识别用户ID，匿名或未注册请求可为空。 |
| `event_type` | `varchar(64)` | 否 | 事件类型编码，如LOGIN_SUCCESS、PASSWORD_RESET；无默认值，数据库未限定枚举。 |
| `outcome` | `varchar(16)` | 否 | SUCCESS成功、FAILURE失败；无默认值。 |
| `reason_code` | `varchar(64)` | 是 | 默认NULL；不含敏感信息的原因码。 |
| `subject_hash` | `char(64)`，ascii_bin | 是 | 默认NULL；邮箱或用户名的HMAC/SHA-256摘要，不保存原始标识。 |
| `ip_hash` | `char(64)`，ascii_bin | 是 | 默认NULL；客户端IP的服务端密钥摘要，不保存原始IP。 |
| `session_id_hash` | `char(64)`，ascii_bin | 是 | 默认NULL；JWT jti摘要，不保存完整令牌或可直接使用的会话标识。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；事件创建时间。 |

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| idx_security_audit_user_created | `(user_id,created_at)` | 普通索引 |
| idx_security_audit_type_created | `(event_type,created_at)` | 普通索引 |

CHECK约束`chk_security_audit_outcome`限定outcome为SUCCESS或FAILURE，按列的utf8mb4_0900_ai_ci排序规则比较；未额外要求大小写精确匹配。外键`fk_security_audit_user`：user_id → users.id，ON DELETE SET NULL，ON UPDATE RESTRICT（未显式声明，MySQL默认行为）。三个摘要列使用ASCII字符集及ascii_bin排序规则，数据库未额外校验其是否为十六进制摘要。

<a id="restaurant-schema"></a>

## 餐厅、食堂与窗口表结构

restaurant模块拥有餐厅资料、分类、标签、审核申请、相册、食堂窗口与评分统计表。存储引擎InnoDB，字符集utf8mb4，默认排序规则utf8mb4_0900_ai_ci；时间使用TIMESTAMP(6)，坐标使用GCJ-02，跨模块账号及文件使用逻辑ID引用。

### `restaurant_categories`：餐厅分类

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `code` | `varchar(32)` | 否 | 稳定分类编码 |
| `name` | `varchar(20)` | 否 | 名称 |
| `sort_order` | `smallint unsigned` | 否 | 展示顺序，同值按编码排序；默认0 |
| `enabled` | `tinyint(1)` | 否 | 是否允许用于新建或切换分类，停用不删除历史引用；默认1 |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |

主键 `(code)`；唯一键 `(name)`。

启用分类编码及名称：FAST_FOOD小吃快餐、CHINESE中餐地方菜、HOT_POT火锅串串、BARBECUE烧烤烤肉、DRY_POT_FISH香锅烤鱼、INTERNATIONAL国际美食、DESSERT甜品烘焙、DRINK饮品、OTHER其他美食。停用分类保留已有引用。

### `restaurants`：餐厅

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `name` | `varchar(40)` | 否 | 名称 |
| `search_projection_version` | `bigint` | 否 | 搜索投影版本，默认1且必须大于0 |
| `category_code` | `varchar(32)` | 是 | 普通餐厅分类必填；食堂固定NULL，不适用美食品类 |
| `restaurant_type` | `varchar(16)` | 否 | REGULAR普通餐厅 / CANTEEN食堂；默认REGULAR |
| `price_per_person` | `smallint unsigned` | 是 | 整数元人均，未知为NULL，不使用0代替 |
| `price_kind` | `varchar(16)` | 否 | UNKNOWN未知、EXACT填报金额、ESTIMATE估值；填报不代表已核实；默认UNKNOWN |
| `address` | `varchar(80)` | 是 | 真实地址，未知为NULL |
| `latitude` | `decimal(8,6)` | 是 | GCJ-02纬度 |
| `longitude` | `decimal(9,6)` | 是 | GCJ-02经度 |
| `needs_location` | `tinyint(1)` | 否 | 是否待定位，须与坐标空值一致；默认1 |
| `business_status` | `varchar(16)` | 否 | OPERATING正常经营记录、CLOSED停业，不代表此刻营业或信息已核实；默认OPERATING |
| `created_by` | `bigint unsigned` | 是 | 创建账号逻辑ID，未知为NULL |
| `updated_by` | `bigint unsigned` | 是 | 最近修改账号逻辑ID |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |
| `deleted_at` | `timestamp(6)` | 是 | 软删除时间，NULL表示可见 |
| `deleted_by` | `bigint unsigned` | 是 | 删除账号逻辑ID |
| `delete_reason_code` | `varchar(32)` | 是 | 删除原因编码 |
| `supports_dine_in` | `tinyint(1)` | 是 | 是否支持堂食，NULL表示未知 |
| `supports_delivery` | `tinyint(1)` | 是 | 是否支持外卖，NULL表示未知 |

索引 `(category_code,deleted_at,name,id)`；索引 `(created_by,deleted_at,id)`；索引 `(restaurant_type,deleted_at,id)`；索引 `(deleted_at,name,id)`；主键 `(id)`；唯一键 `(id,restaurant_type)`。

外键：`category_code` → `restaurant_categories.code`。

REGULAR必须有category_code，CANTEEN的category_code必须为NULL。有效金额范围1～5000元，UNKNOWN时金额为NULL。经纬度成对存在，待定位时均为空；经营状态为OPERATING/CLOSED。

### `restaurant_tags`：餐厅标签

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `restaurant_id` | `bigint unsigned` | 否 | 所属餐厅 |
| `tag` | `varchar(80)` | 否 | 去首尾空白的标签，同店按数据库排序规则去重 |
| `position` | `tinyint unsigned` | 否 | 展示位置 |

主键 `(restaurant_id,tag)`；唯一键 `(restaurant_id,position)`。

外键：`restaurant_id` → `restaurants.id`。

position范围0～7；同一餐厅内标签及位置分别唯一。

### `restaurant_review_statistics`：餐厅点评统计

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `restaurant_id` | `bigint unsigned` | 否 | 一店一行 |
| `review_count` | `int unsigned` | 否 | 有效点评条数；默认0 |
| `participant_count` | `int unsigned` | 否 | 计分参与人数；默认0 |
| `recommend_count` | `int unsigned` | 否 | 最新计分点评中的推荐人数；默认0 |
| `taste_sum` | `decimal(16,2)` | 否 | 味道累计分；默认0.00 |
| `value_sum` | `decimal(16,2)` | 否 | 性价比累计分；默认0.00 |
| `service_sum` | `decimal(16,2)` | 否 | 服务累计分；默认0.00 |
| `ambience_sum` | `decimal(16,2)` | 否 | 环境累计分；默认0.00 |
| `overall_score_sum` | `decimal(16,2)` | 否 | 单条综合分累计值；默认0.00 |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |

主键 `(restaurant_id)`。

外键：`restaurant_id` → `restaurants.id`。

一店一行。review_count是有效点评总条数；其余计分累计值对应每位作者最新有效点评。

### `restaurant_change_requests`：商户资料待审核申请

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键，申请编号 |
| `restaurant_id` | `bigint unsigned` | 是 | UPDATE始终为原商户ID；CREATE待审/驳回为空，通过时填入新建的正式商户ID |
| `request_type` | `varchar(16)`，ascii_bin | 否 | CREATE新店投稿、UPDATE已有商户纠错；无默认值，由服务端提交入口确定，审核后保持不变 |
| `submitted_by` | `bigint unsigned` | 否 | 提交账号逻辑ID，由服务端填写 |
| `client_request_id` | `char(36)` | 否 | 客户端UUID，同账号内唯一 |
| `request_hash` | `binary(32)` | 否 | 规范化申请内容的SHA-256，判断重试内容一致性 |
| `proposed_data` | `json` | 否 | 用户原始业务申请快照，管理员修订不得覆盖；原始图片顺序和提交摘要继续用于重试核对 |
| `status` | `varchar(16)`，ascii_bin | 否 | PENDING待审、APPROVED通过、REJECTED驳回；默认PENDING |
| `created_at` | `timestamp(6)` | 否 | 提交时间；默认CURRENT_TIMESTAMP(6) |
| `reviewed_by` | `bigint unsigned` | 是 | 审核人逻辑ID；已处理时必填且大于0，由服务端身份确定 |
| `reviewed_at` | `timestamp(6)` | 是 | 审核完成时间，由服务端填写 |
| `reject_reason` | `varchar(200)` | 是 | 仅驳回时必填，禁止空字符串及纯空白 |
| `approved_data` | `json` | 是 | 仅通过时必填的JSON对象：实际生效的完整业务资料，保留管理员修订；不写密码、权限或评分统计 |
| `before_data` | `json` | 是 | 仅UPDATE通过时必填的JSON对象：更新前实际正式业务资料，CREATE没有旧资料 |

主键`(id)`；唯一索引`(submitted_by,client_request_id)`；普通索引`(status,created_at,id)`、`(restaurant_id,created_at,id)`、`(status,reviewed_at,id)`、`(submitted_by,created_at,id)`。

外键：restaurant_id → restaurants.id，删除/更新RESTRICT；submitted_by、reviewed_by为用户逻辑ID，无跨模块外键。

#### 状态与字段约束

| 状态 | 商户ID | 审核人/时间 | 驳回原因 | 通过快照 | 更新前快照 |
| --- | --- | --- | --- | --- | --- |
| CREATE + PENDING | 必须NULL | 必须NULL | NULL | NULL | NULL |
| UPDATE + PENDING | 原商户ID | 必须NULL | NULL | NULL | NULL |
| CREATE + APPROVED | 必填，新建商户ID | 必填 | NULL | 必填JSON对象 | NULL |
| UPDATE + APPROVED | 必填，原商户ID | 必填 | NULL | 必填JSON对象 | 必填JSON对象 |
| CREATE + REJECTED | 必须NULL | 必填 | 必填非空白 | NULL | NULL |
| UPDATE + REJECTED | 原商户ID | 必填 | 必填非空白 | NULL | NULL |

类型和状态使用精确编码；proposed_data保存原始申请资料，approved_data保存通过后的资料，before_data保存纠错前资料。

### `restaurant_change_photos`：商户申请图片

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `request_id` | `bigint unsigned` | 否 | 所属待审申请 |
| `position` | `tinyint unsigned` | 否 | 图片顺序0–4，最多5张 |
| `media_file_id` | `bigint unsigned` | 否 | file模块文件逻辑ID，正long范围 |

主键`(request_id,position)`；唯一键`(media_file_id)`。

外键：request_id → restaurant_change_requests.id，删除/更新RESTRICT。media_file_id为文件逻辑ID，无跨模块外键。

position范围0～4。

### `restaurant_photos`：正式商户相册

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 稳定正式图片关联ID，自增 |
| `restaurant_id` | `bigint unsigned` | 否 | 所属正式商户，正long范围 |
| `media_file_id` | `bigint unsigned` | 否 | file模块逻辑ID，正long范围 |
| `position` | `int unsigned` | 否 | 商户内顺序0–2147483647；0为默认封面 |
| `source_request_id` | `bigint unsigned` | 否 | 来源申请，正long范围 |
| `created_at` | `timestamp(6)` | 否 | 正式关联创建时间，由数据库生成 |

主键`(id)`；唯一键`(media_file_id)`、`(restaurant_id,position)`；普通索引`(source_request_id,position,id)`。

外键：restaurant_id → restaurants.id，source_request_id → restaurant_change_requests.id，删除/更新RESTRICT。media_file_id为文件逻辑ID，无跨模块外键。

position范围0～2147483647，同一商户内唯一；0表示默认封面。

### `canteen_windows`：食堂窗口

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `canteen_id` | `bigint unsigned` | 否 | 所属食堂ID |
| `canteen_type` | `varchar(16)` | 否 | 外键类型锚点，固定CANTEEN；默认CANTEEN |
| `name` | `varchar(40)` | 否 | 名称 |
| `floor_number` | `smallint` | 否 | 楼层数，负数表示地下，0表示地面层 |
| `category_code` | `varchar(32)` | 是 | 分类编码 |
| `description` | `varchar(500)` | 是 | 可选介绍，不代替菜品表 |
| `price_per_person` | `smallint unsigned` | 是 | 参考整数元人均，未知为空 |
| `price_kind` | `varchar(16)` | 否 | UNKNOWN未知、EXACT填报、ESTIMATE估值；默认UNKNOWN |
| `cover_file_id` | `bigint unsigned` | 是 | file模块逻辑ID |
| `business_status` | `varchar(16)` | 否 | OPERATING营业、CLOSED暂停或停止营业，保留历史点评；默认OPERATING |
| `created_by` | `bigint unsigned` | 否 | 创建账号逻辑ID，必须为正数 |
| `updated_by` | `bigint unsigned` | 是 | 最近修改账号逻辑ID |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |
| `deleted_at` | `timestamp(6)` | 是 | 软删除时间，NULL表示可见 |
| `deleted_by` | `bigint unsigned` | 是 | 删除账号逻辑ID |

索引 `(canteen_id,canteen_type)`；索引 `(category_code)`；索引 `(canteen_id,floor_number,category_code,id)`；主键 `(id)`。

canteen_type固定CANTEEN，与canteen_id组成复合外键引用restaurants(id,restaurant_type)；category_code引用restaurant_categories.code。楼层范围-10～100，参考价格范围1～5000元，UNKNOWN时金额为空。

### `window_review_statistics`：窗口点评统计

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `window_id` | `bigint unsigned` | 否 | 一窗口一行，和餐厅统计分离 |
| `review_count` | `int unsigned` | 否 | 有效点评条数；默认0 |
| `participant_count` | `int unsigned` | 否 | 计分参与人数；默认0 |
| `recommend_count` | `int unsigned` | 否 | 最新计分点评中的推荐人数；默认0 |
| `taste_sum` | `decimal(16,2)` | 否 | 味道累计分；默认0.00 |
| `value_sum` | `decimal(16,2)` | 否 | 性价比累计分；默认0.00 |
| `service_sum` | `decimal(16,2)` | 否 | 服务累计分；默认0.00 |
| `ambience_sum` | `decimal(16,2)` | 否 | 环境累计分；默认0.00 |
| `overall_score_sum` | `decimal(16,2)` | 否 | 单条综合分累计值；默认0.00 |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |

主键 `(window_id)`。

外键：`window_id` → `canteen_windows.id`。

一窗口一行，累计值含义与餐厅点评统计一致。

<a id="restaurant-favorites"></a>

## 店铺收藏表结构

restaurant模块拥有`restaurant_favorites`表。存储引擎InnoDB，字符集utf8mb4，排序规则utf8mb4_0900_ai_ci。

### `restaurant_favorites`：店铺收藏关系

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键；无显式默认值。 |
| `user_id` | `bigint unsigned` | 否 | 收藏者用户逻辑ID，范围1～9223372036854775807；无默认值。 |
| `restaurant_id` | `bigint unsigned` | 否 | 普通餐厅或食堂ID，范围1～9223372036854775807；无默认值，不是食堂窗口ID。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；收藏时间。 |

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| uk_restaurant_favorite_user_target | `(user_id,restaurant_id)` | 唯一键 |
| idx_restaurant_favorites_target | `(restaurant_id)` | 普通索引，支撑同模块外键 |
| idx_restaurant_favorites_user_time | `(user_id,created_at DESC,id DESC)` | 普通索引 |

CHECK约束`chk_restaurant_favorites_ids`限定user_id及restaurant_id均在正Long范围内。外键`fk_restaurant_favorites_restaurant`：restaurant_id → restaurants.id，ON DELETE RESTRICT、ON UPDATE RESTRICT。user_id为跨模块逻辑引用，无物理外键。收藏列表可见性保存在users.favorites_public，不在每条关系中重复存储。

<a id="restaurant-search-outbox"></a>

## 商户搜索同步任务表结构

restaurant模块拥有`restaurant_search_outbox`表。存储引擎InnoDB，字符集utf8mb4，排序规则utf8mb4_0900_ai_ci。

### `restaurant_search_outbox`：搜索同步任务

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint` | 否 | 自增主键，任务ID。 |
| `restaurant_id` | `bigint unsigned` | 否 | 商户逻辑ID，范围1～9223372036854775807。 |
| `projection_version` | `bigint` | 否 | 任务对应的商户搜索投影版本，必须大于0。 |
| `status` | `varchar(16)` | 否 | 默认PENDING；任务状态，见下表。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；任务创建时间。 |
| `attempts` | `int` | 否 | 默认0；已记录的失败次数，必须非负。 |
| `next_attempt_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；下次可尝试时间。 |
| `last_error` | `varchar(200)` | 是 | 默认NULL；脱敏错误类型。 |
| `completed_at` | `timestamp(6)` | 是 | 默认NULL；任务确认完成时间。 |

#### 状态含义

| status | 含义 |
| --- | --- |
| PENDING | 待处理或待重试 |
| PROCESSING | 处理中，保留状态 |
| SUCCEEDED | 同步成功 |
| FAILED | 同步失败 |

#### 主键、索引与关联

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| uk_restaurant_search_outbox_version | `(restaurant_id,projection_version)` | 唯一键 |
| idx_restaurant_search_outbox_pending | `(status,id)` | 普通索引 |
| idx_restaurant_search_outbox_due | `(status,next_attempt_at,id)` | 普通索引 |

restaurant_id为逻辑引用，无物理外键。

<a id="review-schema"></a>

## 点评、回复与媒体表结构

点评、回复、照片关联及发布回执归review模块；媒体文件登记归file模块。跨模块账号、餐厅、窗口和文件引用使用逻辑ID，由应用服务校验。

共用约定：InnoDB、utf8mb4；主键与资源ID使用BIGINT。时间使用TIMESTAMP(6)。同模块外键禁止级联删除，跨模块使用逻辑ID引用。

### `reviews`：点评

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `restaurant_id` | `bigint unsigned` | 是 | 普通餐厅或历史食堂逻辑ID，与window_id二选一 |
| `window_id` | `bigint unsigned` | 是 | 独立窗口逻辑ID，不填父食堂ID |
| `author_user_id` | `bigint unsigned` | 否 | 作者账号逻辑ID |
| `taste_score` | `decimal(3,1)` | 否 | 味道0至10，步进0.5 |
| `value_score` | `decimal(3,1)` | 否 | 性价比0至10，步进0.5 |
| `service_score` | `decimal(3,1)` | 否 | 服务0至10，步进0.5 |
| `ambience_score` | `decimal(3,1)` | 否 | 环境0至10，步进0.5 |
| `overall_score` | `decimal(4,2)` | 是 | 四维平均后HALF_UP两位；数据库生成 |
| `is_recommended` | `tinyint(1)` | 否 | 独立推荐判断 |
| `body` | `varchar(500)` | 否 | 去首尾空白的5至500码点纯文本 |
| `client_request_id` | `binary(16)` | 否 | 本次发布UUID |
| `request_hash` | `binary(32)` | 否 | 服务端计算的规范化内容SHA-256 |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |

索引 `(restaurant_id,author_user_id,created_at,id)`；索引 `(restaurant_id,created_at,id)`；索引 `(window_id,author_user_id,created_at,id)`；索引 `(window_id,created_at,id)`；索引 `(author_user_id,restaurant_id,created_at,id)`；主键 `(id)`；唯一键 `(author_user_id,client_request_id)`。

restaurant_id与window_id二选一。四维评分0–10，步长0.5；overall_score为四维均值，保留两位。正文5–500字符。相同账号与请求UUID唯一，允许多次真实点评。删除为物理删除。

### `review_photos`：点评照片关联

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `review_id` | `bigint unsigned` | 否 | 所属评价 |
| `position` | `tinyint unsigned` | 否 | 照片顺序，0–4 |
| `media_file_id` | `bigint unsigned` | 否 | file模块媒体逻辑ID |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |

主键 `(review_id,position)`；唯一键 `(media_file_id)`。

position范围0–4；media_file_id唯一。

外键：`review_id` → `reviews.id`。

### `review_replies`：点评回复

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `review_id` | `bigint unsigned` | 否 | 所属评价 |
| `author_user_id` | `bigint unsigned` | 否 | 作者账号逻辑ID |
| `body` | `varchar(200)` | 否 | 去首尾空白的1至200码点纯文本 |
| `client_request_id` | `binary(16)` | 否 | 本次发布UUID |
| `request_hash` | `binary(32)` | 否 | 服务端计算的规范化内容SHA-256 |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |

索引 `(review_id,created_at,id)`；主键 `(id)`；唯一键 `(author_user_id,client_request_id)`。

单层回复，不保存父回复ID；正文1–200字符。同一账号与请求UUID唯一。

外键：`review_id` → `reviews.id`。

### `review_request_receipts`：发布请求回执

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `author_user_id` | `bigint unsigned` | 否 | 作者账号逻辑ID |
| `client_request_id` | `binary(16)` | 否 | 本次发布UUID |
| `review_id` | `bigint unsigned` | 否 | 发布的点评逻辑ID |
| `restaurant_id` | `bigint unsigned` | 是 | 普通餐厅或历史食堂逻辑ID，与window_id二选一 |
| `window_id` | `bigint unsigned` | 是 | 窗口逻辑ID，回执不随点评删除 |
| `deleted_at` | `timestamp(6)` | 是 | 资源删除时间，NULL表示尚未删除 |

主键 `(author_user_id,client_request_id)`；唯一键 `(review_id)`。

保存发布请求UUID、资源引用及删除时间。餐厅与窗口目标互斥；作者与请求UUID唯一。

### `reply_request_receipts`：回复请求回执

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `author_user_id` | `bigint unsigned` | 否 | 无默认值；回复作者逻辑ID |
| `client_request_id` | `binary(16)` | 否 | 无默认值；发布请求UUID |
| `reply_id` | `bigint unsigned` | 否 | 无默认值；回复逻辑ID |
| `review_id` | `bigint unsigned` | 否 | 无默认值；所属点评逻辑ID |
| `deleted_at` | `timestamp(6)` | 是 | 默认NULL；回复删除时间 |

主键 `(author_user_id,client_request_id)`；唯一键 `uk_reply_receipt_reply (reply_id)`；普通索引 `idx_reply_receipt_review (review_id)`。无物理外键及额外CHECK约束，不含正文或内容摘要。InnoDB、utf8mb4、utf8mb4_0900_ai_ci。

### `media_files`：媒体文件

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `uploader_user_id` | `bigint unsigned` | 否 | 登录上传者逻辑ID |
| `bucket_name` | `varchar(63)` | 否 | 服务端选定OSS Bucket |
| `storage_key` | `varchar(255)` | 否 | 应用生成的OSS Object Key |
| `content_type` | `varchar(32)` | 否 | 服务端验证后的图片类型 |
| `byte_size` | `int unsigned` | 否 | 最终图片不超过300KiB |
| `width` | `smallint unsigned` | 否 | 解码宽度不超过1200 |
| `height` | `smallint unsigned` | 否 | 解码高度不超过1200 |
| `sha256` | `binary(32)` | 否 | 最终文件内容摘要，不唯一 |
| `status` | `varchar(16)` | 否 | UPLOADING / TEMPORARY / BOUND / PENDING_DELETE |
| `purpose` | `varchar(16)` | 否 | REVIEW点评图 / RESTAURANT商户待审图；默认REVIEW |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |
| `expires_at` | `timestamp(6)` | 是 | 临时上传过期时间，由服务端设置 |
| `bound_at` | `timestamp(6)` | 是 | 绑定时间 |

索引 `(status,expires_at,id)`；索引 `(uploader_user_id,status,created_at,id)`；主键 `(id)`；唯一键 `(bucket_name,storage_key)`。

图片本体存储于OSS，本表保存文件元数据。status取值为UPLOADING（上传中）、TEMPORARY（临时）、BOUND（已绑定）、PENDING_DELETE（待删除）。文件ID由业务关联表逻辑引用，无跨模块外键。

<a id="user-follows"></a>

## 用户关注表结构

community模块拥有`user_follows`表。存储引擎InnoDB，字符集utf8mb4，排序规则utf8mb4_0900_ai_ci。

### `user_follows`：用户有向关注关系

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增关系主键；无显式默认值。 |
| `follower_user_id` | `bigint unsigned` | 否 | 发起关注的用户逻辑ID，范围1～9223372036854775807；无默认值。 |
| `followed_user_id` | `bigint unsigned` | 否 | 被关注的用户逻辑ID，范围1～9223372036854775807；无默认值。 |
| `active` | `tinyint(1)` | 否 | 默认1；1当前关注、0已取消。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；首次关系创建时间。 |
| `updated_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)，更新时自动刷新；最近关系修改时间。 |

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| uk_user_follows_pair | `(follower_user_id,followed_user_id)` | 唯一键，有向关系 |
| idx_user_follows_follower | `(follower_user_id,active,id DESC)` | 普通索引 |
| idx_user_follows_followed | `(followed_user_id,active,id DESC)` | 普通索引 |

CHECK约束：`chk_user_follows_ids`限定两端用户ID为正Long；`chk_user_follows_not_self`限定两端不同；`chk_user_follows_active`限定active为0或1；`chk_user_follows_time`限定updated_at不早于created_at。两端用户ID均为跨模块逻辑引用，无物理外键。关注与粉丝名单可见性分别保存于users.following_public和users.followers_public，不在每条关系中重复存储。

<a id="content-likes"></a>

## 内容点赞表结构

review模块拥有`content_likes`表，保存点评和回复的点赞关系。存储引擎InnoDB，字符集utf8mb4，默认排序规则utf8mb4_0900_ai_ci。

### `content_likes`：内容点赞关系

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键，点赞关系ID。 |
| `target_type` | `varchar(8)`，ascii_bin | 否 | REVIEW点评、REPLY回复。 |
| `target_id` | `bigint unsigned` | 否 | 被赞点评或回复ID。 |
| `user_id` | `bigint unsigned` | 否 | 点赞者用户逻辑ID。 |
| `active` | `tinyint(1)` | 否 | 默认1；1当前已赞、0当前未赞。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；首次点赞时间。 |
| `updated_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)，更新时自动刷新；最近状态修改时间。 |

#### 主键、索引与约束

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| uk_content_like_actor | `(target_type,target_id,user_id)` | 唯一键 |
| idx_content_like_target | `(target_type,target_id,active)` | 普通索引 |
| idx_content_like_user | `(user_id,active,id)` | 普通索引 |

类型编码精确匹配；target_id和user_id范围为1～9223372036854775807，active只允许0/1，updated_at不得早于created_at。target_id引用reviews.id或review_replies.id，user_id引用用户逻辑ID，均无物理外键。

<a id="growth-schema"></a>

## 成长经验表结构

growth模块拥有经验账户与奖励记录两张表。存储引擎InnoDB，字符集utf8mb4，默认排序规则utf8mb4_0900_ai_ci；编码字段采用ascii_bin。

### `growth_accounts`：经验账户

每位用户最多一行，保存当前净经验。

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `user_id` | `bigint unsigned` | 否 | 受益用户逻辑ID，同时作为主键；范围1～Long.MAX_VALUE。 |
| `experience` | `bigint` | 否 | 默认0；当前净经验，范围0～Long.MAX_VALUE。 |
| `updated_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)，行数据更新时自动更新时间。 |

主键`(user_id)`。CHECK限制user_id为1～9223372036854775807、experience为非负数。user_id是用户逻辑ID，无跨模块外键。

### `growth_rewards`：奖励记录

每行保存一次奖励及其撤销状态。

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增奖励主键。 |
| `user_id` | `bigint unsigned` | 否 | 受益账户ID，范围1～Long.MAX_VALUE；审核奖励属于原提交者。 |
| `reward_type` | `varchar(32)`，ASCII | 否 | 奖励业务类型，固定枚举见下表；精确区分大小写。 |
| `business_key` | `varchar(96)`，ASCII | 否 | 服务端生成的奖励资格键，1～96字符；首字符为英文字母或数字，整体只允许英文字母、数字、冒号、下划线和短横线。 |
| `source_type` | `varchar(24)`，ASCII | 否 | REVIEW或RESTAURANT_REQUEST；精确比较。 |
| `source_id` | `bigint unsigned` | 否 | 原点评或申请逻辑ID，范围1～Long.MAX_VALUE；源内容删除后仍保留。 |
| `amount` | `int` | 否 | 原来实际发放的经验，范围1～Integer.MAX_VALUE；无默认值。 |
| `status` | `varchar(16)`，ASCII | 否 | 默认GRANTED；仅允许GRANTED、REVOKED。 |
| `granted_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；原发奖时间。 |
| `revoked_at` | `timestamp(6)` | 是 | 默认NULL；撤销时间，不得早于granted_at。 |
| `actor_user_id` | `bigint unsigned` | 是 | 默认NULL；获赞奖励对应的点赞者逻辑ID。 |
| `business_date` | `date` | 是 | 默认NULL；获赞奖励所属业务日期。 |
| `disposition_id` | `bigint unsigned` | 是 | 默认NULL；风控处置逻辑ID，非空时范围1～Long.MAX_VALUE。 |

#### 奖励类型与来源

| reward_type | 含义 | source_type |
| --- | --- | --- |
| FIRST_STORE_REVIEW | 每用户每店首次点评奖励 | REVIEW |
| NEW_STORE_APPROVED | 新店投稿审核通过奖励 | RESTAURANT_REQUEST |
| CORRECTION_APPROVED | 同人同店首次纠错审核通过奖励 | RESTAURANT_REQUEST |
| REVIEW_LIKED | 点评获赞奖励 | REVIEW |

编码字段采用精确比较。business_key长度1～96字符，首字符为英文字母或数字，其余只允许英文字母、数字、冒号、下划线和短横线。奖励类型与来源类型必须符合上表组合。

#### 状态约束

| status | revoked_at | disposition_id |
| --- | --- | --- |
| GRANTED | NULL | NULL |
| REVOKED | 必填且不早于granted_at | 必填，范围1～9223372036854775807 |

#### 主键、索引与关联

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| uk_growth_reward_business | `(user_id,reward_type,business_key)` | 唯一键 |
| idx_growth_rewards_user_status | `(user_id,status)` | 普通索引 |
| idx_growth_like_daily | `(user_id,reward_type,business_date,actor_user_id)` | 普通索引 |
| idx_growth_rewards_source | `(source_type,source_id,user_id)` | 普通索引 |

外键`fk_growth_rewards_account`：user_id → growth_accounts.user_id，ON DELETE/ON UPDATE均为RESTRICT。用户、点评、申请与处置ID无跨模块外键。

获赞奖励的actor_user_id与business_date必须同时为空或同时非空；非空actor_user_id为1～9223372036854775807且不等于user_id。其他奖励类型的两列均为空。

<a id="notification-inbox"></a>

## 通知表结构

notification模块拥有`notification_messages`、`notification_announcements`和`notification_announcement_user_states`，分别保存业务通知、全体公告及公告个人状态。存储引擎InnoDB，字符集utf8mb4，默认排序规则utf8mb4_0900_ai_ci；编码字段使用ascii_bin。

### `notification_messages`：通知消息

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键，消息ID。 |
| `recipient_user_id` | `bigint unsigned` | 否 | 收件人用户逻辑ID。 |
| `notification_type` | `varchar(24)`，ASCII | 否 | 默认RESTAURANT_DECISION；RESTAURANT_DECISION审核结果、REVIEW_REPLY点评回复、REVIEW_LIKED点评获赞、REPLY_LIKED回复获赞、USER_FOLLOWED首次关注。 |
| `source_id` | `bigint unsigned` | 否 | 来源ID；审核消息为申请ID，回复消息为回复ID，获赞消息为点赞关系ID，关注消息为关注关系ID。 |
| `request_id` | `bigint unsigned` | 是 | 审核申请逻辑ID。 |
| `request_type` | `varchar(8)`，ASCII | 是 | CREATE新店投稿、UPDATE纠错。 |
| `decision` | `varchar(8)`，ASCII | 是 | APPROVED审核通过、REJECTED驳回。 |
| `restaurant_name` | `varchar(120)` | 是 | 审核时店名快照。 |
| `reject_reason` | `varchar(200)` | 是 | 驳回原因快照。 |
| `review_id` | `bigint unsigned` | 是 | 回复或被赞内容所属点评逻辑ID。 |
| `actor_user_id` | `bigint unsigned` | 是 | 回复者、点赞者或关注者用户逻辑ID。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；消息创建时间。 |
| `read_at` | `timestamp(6)` | 是 | 默认NULL；首次已读时间，NULL表示未读。 |
| `deleted_at` | `timestamp(6)` | 是 | 默认NULL；收件人删除时间，NULL表示收件箱可见。 |

#### 主键与索引

| 名称 | 列 | 类型 |
| --- | --- | --- |
| PRIMARY | `(id)` | 主键 |
| uk_notification_source | `(recipient_user_id,notification_type,source_id)` | 唯一键 |
| idx_notification_recipient_time | `(recipient_user_id,deleted_at,created_at DESC,id DESC)` | 普通索引 |
| idx_notification_recipient_unread | `(recipient_user_id,deleted_at,read_at,id)` | 普通索引 |
| idx_notification_recipient_type_time | `(recipient_user_id,deleted_at,notification_type,created_at DESC,id DESC)` | 普通索引 |
| idx_notification_recipient_type_unread | `(recipient_user_id,deleted_at,notification_type,read_at,id)` | 普通索引 |

#### 字段约束与关联

- 收件人、来源及非空申请/点评/回复者ID范围为1～9223372036854775807。
- 审核消息：request_id、request_type、decision、restaurant_name必填，source_id=request_id；review_id、actor_user_id为空。店名长度1～120字符；通过时reject_reason为空，驳回时必填且长度1～200字符。
- 回复及获赞消息：review_id、actor_user_id必填，操作者不等于收件人；request_id、request_type、decision、restaurant_name、reject_reason为空。
- 关注消息：actor_user_id必填且不等于收件人；review_id及全部审核字段为空，source_id为关注关系逻辑ID。
- 类型与状态编码精确匹配；非空read_at不得早于created_at；约束chk_notification_deleted要求非空deleted_at不得早于created_at。
- 用户、申请、点评、回复、点赞及关注关系均为跨模块逻辑引用，无物理外键。

### `notification_announcements`：全体公告

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键；公告ID。 |
| `client_request_id` | `binary(16)` | 否 | 无默认值；发布请求UUID。 |
| `request_hash` | `binary(32)` | 否 | 无默认值；规范化发布内容的SHA-256摘要。 |
| `title` | `varchar(120)` | 否 | 无默认值；公告标题。 |
| `content` | `text` | 否 | 无默认值；公告正文，最多10000字符。 |
| `status` | `varchar(16)`，ASCII/ascii_bin | 否 | 默认PUBLISHED；PUBLISHED已发布、WITHDRAWN已撤回。 |
| `published_by` | `bigint unsigned` | 否 | 无默认值；发布管理员用户逻辑ID，1～9223372036854775807。 |
| `published_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；发布时间。 |
| `withdrawn_by` | `bigint unsigned` | 是 | 默认NULL；撤回管理员用户逻辑ID，非空时1～9223372036854775807。 |
| `withdrawn_at` | `timestamp(6)` | 是 | 默认NULL；首次撤回时间。 |

主键`(id)`；唯一键`uk_announcement_publish_request(published_by,client_request_id)`；普通索引`idx_announcement_status_time(status,published_at DESC,id DESC)`；无物理外键，管理员ID为用户模块逻辑引用。无接收范围字段、指定接收人关联或到期字段。

CHECK约束：

- `chk_announcement_publisher`：发布人限定正Long范围。
- `chk_announcement_title`：TRIM后标题长度1～120。
- `chk_announcement_content`：TRIM后正文至少1字符，原正文不超过10000字符。
- `chk_announcement_lifecycle`：状态使用精确大小写枚举；PUBLISHED的撤回人/时间均为空；WITHDRAWN的撤回人/时间均非空、撤回人在正Long范围，撤回时间不早于发布时间。

### `notification_announcement_user_states`：公告个人状态

| 字段 | 类型 | 可空 | 默认值及含义 |
| --- | --- | --- | --- |
| `user_id` | `bigint unsigned` | 否 | 无默认值；用户逻辑ID，1～9223372036854775807。 |
| `announcement_id` | `bigint unsigned` | 否 | 无默认值；公告ID，1～9223372036854775807。 |
| `read_at` | `timestamp(6)` | 是 | 默认NULL；首次已读时间。 |
| `deleted_at` | `timestamp(6)` | 是 | 默认NULL；本人删除时间。 |
| `created_at` | `timestamp(6)` | 否 | 默认CURRENT_TIMESTAMP(6)；首次个人状态写入时间。 |

联合主键`(user_id,announcement_id)`；普通索引`idx_announcement_state_announcement(announcement_id)`；同模块外键`fk_announcement_state_announcement`：announcement_id → notification_announcements.id，ON DELETE/ON UPDATE均为RESTRICT。用户ID无跨模块物理外键。

CHECK约束：

- `chk_announcement_state_ids`：用户ID及公告ID限定正Long范围。
- `chk_announcement_state_action`：read_at和deleted_at至少一项非空。
- `chk_announcement_state_read`、`chk_announcement_state_deleted`：非空操作时间不能早于created_at。

无个人状态记录表示未读；read_at非空表示已读，deleted_at非空表示本人删除。删除状态允许read_at为空。
