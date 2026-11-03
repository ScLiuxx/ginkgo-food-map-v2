# 餐厅、食堂与窗口表结构

餐厅资料、分类、标签、窗口和评分统计归restaurant模块。坐标统一GCJ-02。用户距离按请求原点临时计算，不保存距离列；用户位置不落库。

共用约定：InnoDB、utf8mb4；主键与资源ID使用BIGINT，公开接口用字符串传输。时间使用TIMESTAMP(6)。同模块外键禁止级联删除，跨模块通过公开服务访问。

## `restaurant_categories`：餐厅分类

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `code` | `varchar(32)` | 否 | 稳定分类编码 |
| `name` | `varchar(20)` | 否 | 名称 |
| `sort_order` | `smallint unsigned` | 否 | 展示顺序，同值按编码排序；默认0 |
| `enabled` | `tinyint(1)` | 否 | 是否允许用于新建或切换分类，停用不删除历史引用；默认1 |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |

主键 `(code)`；唯一键 `(name)`。

分类编码唯一；停用分类保留给已有餐厅使用。

## `restaurants`：餐厅

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `name` | `varchar(40)` | 否 | 名称 |
| `category_code` | `varchar(32)` | 否 | 分类编码 |
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

价格未知时金额为NULL；有效金额为1–5000元。坐标必须成对存在，待定位记录的经纬度均为NULL。经营状态OPERATING/CLOSED。软删除时保留记录及关联资料。

外键：`category_code` → `restaurant_categories.code`。

## `restaurant_tags`：餐厅标签

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `restaurant_id` | `bigint unsigned` | 否 | 所属餐厅 |
| `tag` | `varchar(80)` | 否 | 去首尾空白的标签，同店按数据库排序规则去重 |
| `position` | `tinyint unsigned` | 否 | 展示位置 |

主键 `(restaurant_id,tag)`；唯一键 `(restaurant_id,position)`。

主键为餐厅ID与标签；同店位置唯一，范围0–7，最多8项。标签总长度由应用校验。

外键：`restaurant_id` → `restaurants.id`。

## `restaurant_review_statistics`：餐厅点评统计

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

一店一行；review_count为有效点评条数，participant_count为参与人数。每账号每店仅最新有效点评计分，均分由累计值除参与人数计算，未评分为NULL。

外键：`restaurant_id` → `restaurants.id`。

## `restaurant_change_requests`：商户资料待审核申请

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 自增主键，申请编号 |
| `restaurant_id` | `bigint unsigned` | 是 | 纠错目标商户；NULL表示新店投稿，审核前没有正式商户ID |
| `submitted_by` | `bigint unsigned` | 否 | 提交账号逻辑ID，由服务端填写 |
| `client_request_id` | `char(36)` | 否 | 客户端UUID，同账号内唯一 |
| `request_hash` | `binary(32)` | 否 | 规范化申请内容的SHA-256，判断重试内容一致性 |
| `proposed_data` | `json` | 否 | 完整业务资料申请快照，不覆盖正式商户 |
| `status` | `varchar(16)` | 否 | 当前仅支持PENDING；默认PENDING |
| `created_at` | `timestamp(6)` | 否 | 提交时间；默认CURRENT_TIMESTAMP(6) |

唯一索引`(submitted_by,client_request_id)`；待审队列索引`(status,created_at,id)`；目标索引`(restaurant_id,created_at,id)`。外键`restaurant_id`指向`restaurants.id`，禁止级联删除。提交账号必须为正数；账号为跨模块逻辑引用。申请仅保存用户提出的商户业务资料，审核处理接口尚未实现。

## `restaurant_change_photos`：商户申请图片

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `request_id` | `bigint unsigned` | 否 | 所属待审申请 |
| `position` | `tinyint unsigned` | 否 | 图片顺序0–4，最多5张 |
| `media_file_id` | `bigint unsigned` | 否 | file模块文件逻辑ID，正long范围 |

主键`(request_id,position)`；唯一键`(media_file_id)`；外键`request_id`指向`restaurant_change_requests.id`，禁止级联删除。文件归属、RESTAURANT用途、临时状态及有效期通过file模块公开服务在申请事务内校验。图片属于待审申请，不作为公开商户图片；关联文件不得直接删除登记，后续业务释放须与关联变更保持事务一致。

## `canteen_windows`：食堂窗口

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 主键；自增 |
| `canteen_id` | `bigint unsigned` | 否 | 所属食堂ID |
| `canteen_type` | `varchar(16)` | 否 | 外键类型锚点，固定CANTEEN，不作为客户端输入；默认CANTEEN |
| `name` | `varchar(40)` | 否 | 名称 |
| `floor_number` | `smallint` | 否 | 楼层数，负数表示地下，0表示地面层 |
| `category_code` | `varchar(32)` | 是 | 分类编码 |
| `description` | `varchar(500)` | 是 | 可选介绍，不代替菜品表 |
| `price_per_person` | `smallint unsigned` | 是 | 参考整数元人均，未知为空 |
| `price_kind` | `varchar(16)` | 否 | UNKNOWN未知、EXACT填报、ESTIMATE估值；默认UNKNOWN |
| `cover_file_id` | `bigint unsigned` | 是 | file模块逻辑ID，绑定和权限由应用服务验证 |
| `business_status` | `varchar(16)` | 否 | OPERATING营业、CLOSED暂停或停止营业，保留历史点评；默认OPERATING |
| `created_by` | `bigint unsigned` | 否 | 创建账号逻辑ID，必须为正数 |
| `updated_by` | `bigint unsigned` | 是 | 最近修改账号逻辑ID |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |
| `deleted_at` | `timestamp(6)` | 是 | 软删除时间，NULL表示可见 |
| `deleted_by` | `bigint unsigned` | 是 | 删除账号逻辑ID |

索引 `(canteen_id,canteen_type)`；索引 `(category_code)`；索引 `(canteen_id,floor_number,category_code,id)`；主键 `(id)`。

窗口只能归属CANTEEN餐厅；楼层范围-10至100；参考价格规则与餐厅一致。窗口名称与楼层不作为唯一身份。

外键：`canteen_id` → `restaurants.id`；`canteen_type` → `restaurants.restaurant_type`；`category_code` → `restaurant_categories.code`。

## `window_review_statistics`：窗口点评统计

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

一窗口一行；计分规则与餐厅统计一致，窗口与餐厅使用独立统计。

外键：`window_id` → `canteen_windows.id`。
