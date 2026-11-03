# 点评、回复与媒体表结构

点评、回复、照片关联及发布回执归review模块；媒体文件登记归file模块。跨模块账号、餐厅、窗口和文件引用使用逻辑ID，由应用服务校验。

共用约定：InnoDB、utf8mb4；主键与资源ID使用BIGINT，公开接口用字符串传输。时间使用TIMESTAMP(6)。同模块外键禁止级联删除，跨模块通过公开服务访问。

## `reviews`：点评

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
| `overall_score` | `decimal(4,2)` | 是 | 四维平均后HALF_UP两位，不接受手工总分；数据库生成，客户端不可写 |
| `is_recommended` | `tinyint(1)` | 否 | 独立推荐判断 |
| `body` | `varchar(500)` | 否 | 去首尾空白的5至500码点纯文本 |
| `client_request_id` | `binary(16)` | 否 | 本次发布UUID |
| `request_hash` | `binary(32)` | 否 | 服务端计算的规范化内容SHA-256 |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |
| `updated_at` | `timestamp(6)` | 否 | 最后修改时间；默认CURRENT_TIMESTAMP(6) |

索引 `(restaurant_id,author_user_id,created_at,id)`；索引 `(restaurant_id,created_at,id)`；索引 `(window_id,author_user_id,created_at,id)`；索引 `(window_id,created_at,id)`；主键 `(id)`；唯一键 `(author_user_id,client_request_id)`。

restaurant_id与window_id二选一。四维评分0–10，步长0.5；overall_score为四维均值，保留两位。正文5–500字符。相同账号与请求UUID唯一，允许多次真实点评。删除为物理删除。

## `review_photos`：点评照片关联

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `review_id` | `bigint unsigned` | 否 | 所属评价 |
| `position` | `tinyint unsigned` | 否 | 照片顺序，0–4 |
| `media_file_id` | `bigint unsigned` | 否 | file模块媒体逻辑ID |
| `created_at` | `timestamp(6)` | 否 | 创建时间；默认CURRENT_TIMESTAMP(6) |

主键 `(review_id,position)`；唯一键 `(media_file_id)`。

主键(review_id,position)，position为0–4，每条点评最多5张；media_file_id唯一，同一上传文件只绑定一次。

外键：`review_id` → `reviews.id`。

## `review_replies`：点评回复

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

单层回复，不保存父回复ID；正文1–200字符。同一账号与请求UUID唯一；回复不参与计分。

外键：`review_id` → `reviews.id`。

## `review_request_receipts`：发布请求回执

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `author_user_id` | `bigint unsigned` | 否 | 作者账号逻辑ID |
| `client_request_id` | `binary(16)` | 否 | 本次发布UUID |
| `review_id` | `bigint unsigned` | 否 | 发布的点评逻辑ID |
| `restaurant_id` | `bigint unsigned` | 是 | 普通餐厅或历史食堂逻辑ID，与window_id二选一 |
| `window_id` | `bigint unsigned` | 是 | 窗口逻辑ID，回执不随点评删除 |
| `deleted_at` | `timestamp(6)` | 是 | 资源删除时间，NULL表示尚未删除 |

主键 `(author_user_id,client_request_id)`；唯一键 `(review_id)`。

保存发布请求UUID、资源引用及删除时间；资源删除后仍保留回执，防止迟到重试重新发布。餐厅与窗口目标互斥；作者与请求UUID唯一。

## `media_files`：媒体文件

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

图片本体在OSS，表中保存元数据和清理状态，不保存签名URL。状态为UPLOADING、TEMPORARY、BOUND、PENDING_DELETE。临时文件到期可清理；点评正式读取需校验关联、点评可见性及REVIEW用途。商户图片使用独立目录、RESTAURANT用途，只绑定待审申请，不提供公开读取。已绑定图片不进入临时回收；后续审核拒绝或撤回须在业务事务内释放后交由现有后台清理。
