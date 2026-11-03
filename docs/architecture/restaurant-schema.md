# 餐厅、食堂与窗口表结构

餐厅资料、分类、标签、窗口和评分统计归restaurant模块。坐标统一GCJ-02。用户距离按请求原点临时计算，不保存距离列；用户位置不落库。

共用约定：InnoDB、utf8mb4；主键与资源ID使用BIGINT，公开接口用字符串传输。时间使用TIMESTAMP(6)。同模块外键禁止级联删除，跨模块通过公开服务访问。

审核表使用单个`restaurant_id`与`request_type`区分新建/纠错。V18新增`restaurant_photos`正式相册关联；审核通过与相册发布使用同一事务。其他环境以实际Flyway版本为准；迁移只建空表，不将历史通过图片自动公开。

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
| `restaurant_id` | `bigint unsigned` | 是 | UPDATE始终为原商户ID；CREATE待审/驳回为空，通过时填入新建的正式商户ID |
| `request_type` | `varchar(16)`，ascii_bin | 否 | CREATE新店投稿、UPDATE已有商户纠错；无默认值，由服务端提交入口确定，审核后保持不变 |
| `submitted_by` | `bigint unsigned` | 否 | 提交账号逻辑ID，由服务端填写 |
| `client_request_id` | `char(36)` | 否 | 客户端UUID，同账号内唯一 |
| `request_hash` | `binary(32)` | 否 | 规范化申请内容的SHA-256，判断重试内容一致性 |
| `proposed_data` | `json` | 否 | 用户原始业务申请快照，管理员修订不得覆盖；原始图片顺序和提交摘要继续用于重试核对 |
| `status` | `varchar(16)`，ascii_bin | 否 | PENDING待审、APPROVED通过、REJECTED驳回；默认PENDING，当前提交接口仍只创建待审申请 |
| `created_at` | `timestamp(6)` | 否 | 提交时间；默认CURRENT_TIMESTAMP(6) |
| `reviewed_by` | `bigint unsigned` | 是 | 审核人逻辑ID；已处理时必填且大于0，由服务端身份确定 |
| `reviewed_at` | `timestamp(6)` | 是 | 审核完成时间，由服务端填写 |
| `reject_reason` | `varchar(200)` | 是 | 仅驳回时必填，禁止空字符串及纯空白 |
| `approved_data` | `json` | 是 | 仅通过时必填的JSON对象：实际生效的完整业务资料，保留管理员修订；不写密码、权限或评分统计 |
| `before_data` | `json` | 是 | 仅UPDATE通过时必填的JSON对象：更新前实际正式业务资料，由后续审核事务读取生成；CREATE没有旧资料 |

唯一索引`(submitted_by,client_request_id)`；待审队列索引`(status,created_at,id)`；目标索引`(restaurant_id,created_at,id)`；已处理队列索引`(status,reviewed_at,id)`。外键`restaurant_id`指向`restaurants.id`，禁止级联删除。提交人、审核人均为跨模块账号逻辑引用，不建立user表外键。

### 状态与字段约束

| 状态 | 商户ID | 审核人/时间 | 驳回原因 | 通过快照 | 更新前快照 |
| --- | --- | --- | --- | --- | --- |
| CREATE + PENDING | 必须NULL | 必须NULL | NULL | NULL | NULL |
| UPDATE + PENDING | 原商户ID | 必须NULL | NULL | NULL | NULL |
| CREATE + APPROVED | 必填，新建商户ID | 必填 | NULL | 必填JSON对象 | NULL |
| UPDATE + APPROVED | 必填，原商户ID | 必填 | NULL | 必填JSON对象 | 必填JSON对象 |
| CREATE + REJECTED | 必须NULL | 必填 | 必填非空白 | NULL | NULL |
| UPDATE + REJECTED | 原商户ID | 必填 | 必填非空白 | NULL | NULL |

类型和状态必须使用上述精确编码，不能带首尾空格。CHECK校验当前行字段组合，不会自动实施管理员权限、限制只能审核一次、验证JSON里的业务字段或更新正式商户；这些仍须由后续审核服务在事务中实现。新建商户和将申请更新为APPROVED时必须同事务完成，不能先提交只有商户ID、没有审核结果的申请中间状态。不新增`published_restaurant_id`或独立审核流水表。

### 原始申请与提交重试

1. 新店提交由服务端写入CREATE，纠错写入UPDATE，不接受客户端`requestType`或审核字段。
2. 保留原唯一键、`proposed_data`和`request_hash`，不将新类型字段混入旧摘要，避免历史请求重试失效。V17只根据旧申请的商户ID是否为空回填类型，其余已有字段和图片关联保持原样。
3. 重试先比较原申请类型及原始资料摘要；UPDATE还比较原商户ID。CREATE不比较审核后生成的商户ID，因此通过后回填ID不会导致原投稿重试冲突。管理员通过快照不参与用户原始请求的摘要比较。
4. 图片仍按原申请关联顺序核对，不重新绑定已经绑定的文件。新增审核存储能力不等于已经实现驳回清理；后续清理必须保留或正确处理原始申请关系，不能破坏重试识别。
5. 用户提交接口返回初次受理回执（id及PENDING），不作为最新审核状态查询。管理员通过列表/详情读取真实状态，通过/驳回接口返回终态回执；用户原始快照、摘要和图片顺序在审核后保持不变。

审核通过协议另外接收餐厅类型和堂食/外卖三态，正式新增/更新必须写入restaurants对应列，并纳入before_data/approved_data；原用户投稿DTO与序列化顺序保持不变，避免破坏既有幂等摘要。旧直接维护输入没有这些字段时保留已有值。食堂关联任何窗口时禁止改为REGULAR，服务在父行锁内检查并返回409，避免触发窗口父类型外键错误。

## `restaurant_change_photos`：商户申请图片

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `request_id` | `bigint unsigned` | 否 | 所属待审申请 |
| `position` | `tinyint unsigned` | 否 | 图片顺序0–4，最多5张 |
| `media_file_id` | `bigint unsigned` | 否 | file模块文件逻辑ID，正long范围 |

主键`(request_id,position)`；唯一键`(media_file_id)`；外键`request_id`指向`restaurant_change_requests.id`，禁止级联删除。文件归属、RESTAURANT用途、临时状态及有效期通过file模块公开服务在申请事务内校验。图片属于待审申请，不作为公开商户图片；关联文件不得直接删除登记，后续业务释放须与关联变更保持事务一致。

V18保留本表及原BOUND文件。首次通过将附图按顺序追加到正式相册，不复制或重绑文件，不在审核事务中访问OSS；驳回不发布、不删除图。历史已通过申请不回填正式关联，审核重试也不隐式补发布旧图。

管理员可通过受保护的申请列表、详情和按位置图片接口查看真实资料。restaurant查询服务只访问本模块表，署名经user公开服务批量查询，图片经file公开服务核验RESTAURANT用途、上传者和BOUND状态后读取。查询不改变申请或正式资料，也不向普通用户开放待审图。接口契约见[API文档](../api.md#5-管理员商户审核)。

审核写入使用同一MySQL事务保存正式资料、标签、相册关联、通过/更新前业务快照及申请终态。UPDATE先锁商户主行再锁申请，与纠错投稿顺序一致；CREATE先锁申请再创建新商户；文件校验按文件ID升序加锁。同店相册追加在主行锁内读取最大position并分配连续新位置。终态更新带status=PENDING条件，审核人取认证、时间由数据库生成。同一规范化通过内容/驳回原因重试返回首次结果；其他终态或不同内容409。新建正式商户created_by为实际管理员，投稿人保留在submitted_by；更新保留历史归属，普通创建者不再拥有直接维护权。暂不检测页面过期，before_data仍从事务内正式资料生成；已删除目标不可通过。正式业务、相册和审核写入任一步失败全部回滚，不引入Redis审核锁或审核消息队列。

## `restaurant_photos`：正式商户相册

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint unsigned` | 否 | 稳定正式图片关联ID，自增；公开接口以字符串返回 |
| `restaurant_id` | `bigint unsigned` | 否 | 所属正式商户，正long范围 |
| `media_file_id` | `bigint unsigned` | 否 | file模块逻辑ID，正long范围，不公开 |
| `position` | `int unsigned` | 否 | 商户内顺序0–2147483647；0为默认封面 |
| `source_request_id` | `bigint unsigned` | 否 | 来源申请，正long范围，不向公开相册暴露 |
| `created_at` | `timestamp(6)` | 否 | 正式关联创建时间，由数据库生成 |

主键`(id)`；唯一键`(media_file_id)`阻止一个文件重复发布，唯一键`(restaurant_id,position)`保证顺序唯一，并用于游标分页及最大顺序读取。索引`(source_request_id,position,id)`用于审核回执与管理员追溯。外键指向本模块restaurants.id及restaurant_change_requests.id，删除/更新RESTRICT，不跨模块为文件ID建立物理外键。

每次首次通过最多追加原申请的五张图，无图不清空已有相册，不支持替换/删除/重排/手动封面。文件服务在审核事务内锁定并验证BOUND、RESTAURANT用途和原提交人，保留原文件状态。回执和管理详情返回本次publishedPhotos；原申请photos继续支持受保护的私有查看。

公开列表以正式图片ID为游标，按position/id读取最多limit+1条，默认20、最大50。公开JPEG读取同时核对商户未删除、正式关联属于商户、来源申请已通过且目标一致，再通过file服务校验文件及原上传者；OSS读取不占数据库事务。软删除只撤销后续公开访问，恢复后重新可读；已经取得的字节无法撤回。普通餐厅与食堂共用此协议，不能用BOUND状态替代正式关联授权。

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
