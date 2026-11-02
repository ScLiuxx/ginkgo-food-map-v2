# 餐厅、食堂与窗口表结构

## 当前增量：V9 / V10（2026-11-01）

已新增 Flyway 迁移 `V9__campus_dining_windows.sql`、`V10__window_review_targets.sql`，不修改 V1–V8。当前业务库仍为 V8；本轮仅在隔离 MySQL 8.4 验证迁移，不自动更新正在运行的数据库。下方首批设计保留为历史依据，以本节增量及迁移 SQL 为当前结构准绳。接口与服务尚未接入窗口，不代表窗口业务已上线。

### 餐厅表新增字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `restaurant_type` | VARCHAR(16)，非空，默认 REGULAR | REGULAR 普通餐厅 / CANTEEN 食堂；独立于美食品类 |
| `supports_dine_in` | BOOLEAN，可空 | 支持堂食；NULL 未知，不猜测食堂默认值 |
| `supports_delivery` | BOOLEAN，可空 | 支持外卖；NULL 未知；不表示平台提供配送 |
| `campus_code` | VARCHAR(32)，可空 | QINGSHUIHE / SHAHE；未知为空 |

V9 仅将旧 `category_code=CANTEEN` 的餐厅回填为食堂，保留原分类和更新时间。其他记录默认 REGULAR，不按名称猜测类型。暂保留旧“食堂”分类以兼容旧接口；后续维护接口应以类型字段判断食堂，不再依赖分类。类型、校区与布尔字段均有 CHECK，增加 `(restaurant_type, deleted_at, id)` 查询索引。

### 独立窗口表 `canteen_windows`

| 字段 | 类型 / 可空 | 约束与用途 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED，主键自增 | 稳定身份，不能用名称＋楼层拼接 |
| `canteen_id` | BIGINT UNSIGNED，非空 | 所属食堂 |
| `canteen_type` | VARCHAR(16)，非空，固定 CANTEEN | 内部外键锚点，不作为客户端可编辑字段 |
| `name` | VARCHAR(40)，非空 | 去首尾空白，允许同名窗口 |
| `floor_number` | SMALLINT，非空 | -10 至 100，负数地下层，0 地面层；楼层导航由窗口数据生成 |
| `category_code` | VARCHAR(32)，可空 | 引用现有美食品类字典，不允许 CANTEEN |
| `description` | VARCHAR(500)，可空 | 简介，非空时须去首尾空白 |
| `price_per_person` / `price_kind` | SMALLINT 可空 / VARCHAR(16) 非空 | 复用 UNKNOWN / EXACT / ESTIMATE；未知为空，否则整数元 1–5000 |
| `cover_file_id` | BIGINT UNSIGNED，可空 | file 模块逻辑 ID，归属、访问及生命周期由公开服务校验 |
| `business_status` | VARCHAR(16)，非空 | OPERATING / CLOSED；关闭不删除历史评价 |
| `created_by` / `updated_by` | BIGINT UNSIGNED，前者非空、后者可空 | 真实账号逻辑 ID，服务端赋值 |
| `created_at` / `updated_at` | TIMESTAMP(6)，非空 | UTC 创建和更新时间 |
| `deleted_at` / `deleted_by` | TIMESTAMP(6) / BIGINT UNSIGNED，可空 | 软删除时同时填写，正常状态同时为空 |

`restaurants` 增加组合唯一键 `(id, restaurant_type)`，窗口通过 `(canteen_id, canteen_type)` 建同模块 RESTRICT 外键，保证父对象确实是食堂，也防止已有窗口的食堂被直接改为普通餐厅。分类同模块物理外键；账号、文件为跨模块逻辑引用。增加 `(canteen_id, floor_number, category_code, id)` 索引。软删除与停业状态仍需服务查询校验，物理外键不检查可见性。

不增加楼层表、菜品表或窗口堂食/外卖字段，不生成样例窗口。更名保留 ID；更换经营主体应关闭旧记录并新建 ID，避免旧评价归到新经营者。窗口只能由正式维护/审核流程收录，当前迁移未开放投稿接口或绕过审核的写入入口。

### 窗口评分与模块边界

V10 新增 `window_review_statistics`，归 restaurant 模块，窗口 ID 是主键及同模块 RESTRICT 外键。保存点评条数、计分人数、推荐人数、四维累计分和总分累计值；约束及汇总公式见[评价结构](review-schema.md#当前增量v10窗口评价归属2026-11-01)。普通餐厅继续使用原统计表，食堂旧统计保留作历史，不混入窗口汇总。

服务接入后：食堂分取未删除、营业且有计分人的窗口均分的等权平均；未评分与关闭窗口跳过，真实零分参与。没有符合条件的窗口返回 NULL。暂不额外建食堂汇总表，避免第二份缓存的一致性成本。

窗口维护、可见性验证、发布/删除时统计更新与食堂汇总查询尚未接入 Java 服务；当前 migration 只建立结构，不能独立提供这些业务能力。

## 历史：首批餐厅结构（V3）

日期：2026-10-28。状态：用户已批准方案，V3、隔离MySQL测试、餐厅CRUD API及前端维护已实现；线上能力以实际部署版本为准。

本文对应[需求与重构说明](../requirements.md)的 M1餐厅只读及M2基础写入。分类、列表、详情及新增/编辑/软删除/管理资料/管理员恢复API已实现，协议见[接口文档](../api.md#11-餐厅公开只读接口)。用户随后取消餐厅版本号，应用不再读取、比较或递增原row_version列；保持V1/V2/V3原样，不连接生产库试建表。前端增删改查已接通，回收站页面和完整治理仍待后续。

## 1. 已确认规则与本次建议

- 用户已确认：未登录可以浏览餐厅目录及详情；新增餐厅、发布评价必须登录。登录不等于可以修改他人的资源，所有权和管理员权限仍由服务端校验。
- 本次建议只建 `restaurant_categories`、`restaurants`、`restaurant_tags` 三张表，均归 `restaurant` 模块。分类是受控字典，标签是店铺自己的有序文本，不提前建设全站标签管理系统。
- 评价、回复、图片、收藏及评分统计留到对应阶段；不向餐厅表加入可由客户端设置的分数、杯型、推荐率或评价数。
- 用户已同意按本方案实施：整数元人均1–5000、名称40字符、地址80字符、最多8个标签且总字符数80、受控来源与归属规则。D06按此锁定首期范围；其他独立决策不因此自动通过。

## 2. 共用约定

| 项目 | 建议 |
| --- | --- |
| 存储 | 与现有用户表一致，InnoDB、`utf8mb4`、`utf8mb4_0900_ai_ci`；稳定机器编码单独使用 ASCII 二进制排序规则 |
| 主键 | 餐厅使用 `BIGINT UNSIGNED AUTO_INCREMENT`；Java 侧使用 Long 并限制在可表示范围；对外 ID 建议用字符串传输，避免浏览器整数精度损失 |
| 时间 | `TIMESTAMP(6)`，沿用现有 `created_at`/`updated_at` 规则；Java 使用 Instant，API 输出 UTC。实施时验证数据库连接会话时区与驱动转换，不只依赖字段名 |
| 并发 | 用户决定采用最后成功保存覆盖；主行锁保护同店完整写入，主表/标签同事务。原row_version列保留但应用不用，不修改迁移 |
| 归属 | 只保存用户 ID，不复制邮箱、密码、JWT 或角色；用户 ID 由认证上下文或受控迁移确定 |
| 约束 | 服务端校验用于给出清楚的错误，数据库 NOT NULL、CHECK、唯一约束用于拦住非法状态；跨行数量与权限仍由服务端负责 |

## 3. `restaurant_categories`：餐厅分类字典

| 字段 | 类型及空值 | 含义与约束 |
| --- | --- | --- |
| `code` | `VARCHAR(32)`，非空，主键 | 稳定编码；ASCII 二进制排序，如 `HOT_POT`、`SICHUAN`，不能用可改的中文名称作关联键 |
| `name` | `VARCHAR(20)`，非空，唯一 | 展示名称，去首尾空白，不允许空字符串 |
| `sort_order` | `SMALLINT UNSIGNED`，非空，默认0 | 展示顺序；同值按 code 排序，不依赖插入顺序 |
| `enabled` | `BOOLEAN`，非空，默认 TRUE | 仅允许0/1；停用后不能用于新建/切换分类，已有餐厅仍显示原分类 |
| `created_at` | `TIMESTAMP(6)`，非空 | 创建时间 |
| `updated_at` | `TIMESTAMP(6)`，非空 | 最后修改时间 |

迁移只初始化13项分类字典：火锅、川菜、小吃、食堂、快餐、烧烤、甜品、冒菜、面馆、麻辣烫、粥粉面、轻食、其他。不自动创建餐厅、评价或管理员账号。首期不做分类管理页面。

## 4. `restaurants`：餐厅主表

| 字段 | 类型及空值 | 含义与约束 |
| --- | --- | --- |
| `id` | `BIGINT UNSIGNED`，非空，主键，自增 | V2餐厅身份，不复用原版 `r01` 等字符串 |
| `name` | `VARCHAR(40)`，非空 | 去首尾空白后1–40个Unicode字符；允许同名分店，不设名称唯一约束 |
| `category_code` | `VARCHAR(32)`，非空 | 引用分类 code，字符集与排序规则须一致 |
| `price_per_person` | `SMALLINT UNSIGNED`，可空 | 按整数元保存，1–5000；NULL表示未知，不能用0充当未知 |
| `price_kind` | `VARCHAR(16)`，非空，默认 `UNKNOWN` | `UNKNOWN`未知、`EXACT`填报具体金额、`ESTIMATE`估值；EXACT不表示平台已核实消费 |
| `address` | `VARCHAR(80)`，可空 | 最多80个Unicode字符，空白统一转NULL；读取时显示地址待补 |
| `latitude` | `DECIMAL(8,6)`，可空 | 纬度；沿原版国内坐标边界建议限制3–54 |
| `longitude` | `DECIMAL(9,6)`，可空 | 经度；建议限制73–136 |
| `coordinate_system` | `VARCHAR(16)`，非空，默认 `GCJ02` | 首期只允许GCJ-02；外部不同坐标系不能直接贴同一标签写入 |
| `needs_location` | `BOOLEAN`，非空，默认 TRUE | 待定位；必须与两个坐标是否为空一致 |
| `business_status` | `VARCHAR(16)`，非空，默认 `OPERATING` | `OPERATING`正常经营记录、`CLOSED`停业记录；不表示此刻正在营业，也不是已核实标志 |
| `source_type` | `VARCHAR(24)`，非空 | `USER`账号创建、`LEGACY_IMPORT`受控历史导入；没有前端演示数据来源类型 |
| `created_by` | `BIGINT UNSIGNED`，可空 | 账号创建必须有真实创建者；历史归属无法确认时允许NULL，不按昵称认领 |
| `updated_by` | `BIGINT UNSIGNED`，可空 | 最近修改操作者；历史/系统处理可为空，普通写入由服务端记录当前账号 |
| `row_version` | `BIGINT UNSIGNED`，非空，默认0 | 旧设计保留列，当前应用不读取/比较/递增，不作为餐厅编辑契约；后续如清理结构使用新迁移 |
| `created_at` | `TIMESTAMP(6)`，非空 | V2记录创建时间；迁移时保留的原版时间以后通过迁移信息单独记录 |
| `updated_at` | `TIMESTAMP(6)`，非空 | V2业务修改时间，标签变更也要更新；不代表最近一次评价时间 |
| `deleted_at` | `TIMESTAMP(6)`，可空 | NULL为未删除，非NULL为软删除；不另存可能冲突的 `is_deleted` |
| `deleted_by` | `BIGINT UNSIGNED`，可空 | 删除操作者；用户/管理员操作必须记录，历史或受控系统处理可为空 |
| `delete_reason_code` | `VARCHAR(32)`，可空 | 建议为 `OWNER_REQUEST`、`ADMIN_REMOVAL`、`LEGACY_IMPORT`、`SYSTEM`，不作为公开详情字段 |

建议首期不加腾讯地点ID、行政区划、营业时段、菜单、图片地址、审核状态、管理员置顶或收藏数等尚未确定的字段。原版ID与导入批次在正式迁移阶段使用独立映射表，不把旧ID当作账号归属或唯一店铺凭证。

### 4.1 数据库内应保证的状态

1. 人均：`UNKNOWN`必须配NULL；`EXACT/ESTIMATE`必须配非NULL且1–5000的金额。不能出现“未知但有金额”或“估值却没金额”。
2. 位置：`needs_location=1`时两个坐标都为NULL；为0时两个坐标都非NULL且满足范围；不接受只填经度/纬度，也不写入默认校园坐标。首期普通用户新建必须确认位置，`source_type=USER`同时要求 `needs_location=0`；待定位先限于受控历史导入，不因表中支持NULL就默认开放无位置发布。草稿作为后续独立能力设计。
3. 来源：`source_type=USER`时 `created_by`必须非NULL；历史导入可以有经核验的创建者，也可以保持未知。
4. 删除：未删除时删除操作者及原因均为空；删除后原因必填且在枚举内。普通删除要求当前操作者，NULL操作者只用于受控历史/系统流程。恢复时一起清空当前删除信息，完整历史以后由操作记录保留。
5. 编码、经营状态、价格类型、来源和布尔值使用CHECK约束；必填文本额外检查长度及去空白后非空。CHECK需要显式处理NULL，不能只依靠可能返回UNKNOWN的比较式。

跨模块用户ID不建立物理外键，避免业务表与用户库生命周期强绑定；不能将这一选择当成允许伪造归属。登录请求使用认证身份，受控导入通过用户模块公开接口核验归属，用户删除策略应由应用流程负责。分类与标签属于同一模块，使用内部外键，禁止级联删除餐厅内容。

## 5. `restaurant_tags`：餐厅标签

| 字段 | 类型及空值 | 含义与约束 |
| --- | --- | --- |
| `restaurant_id` | `BIGINT UNSIGNED`，非空 | 所属餐厅 |
| `tag` | `VARCHAR(80)`，非空 | 去首尾空白后的标签文字；最多8个、各标签字符数之和最多80，不计界面分隔符 |
| `position` | `TINYINT UNSIGNED`，非空 | 0–7，保留提交时的展示顺序 |

- 主键 `(restaurant_id, tag)`：同店标签按约定排序规则去重；不做全站唯一标签。
- 唯一键 `(restaurant_id, position)`：同店不能出现两个相同展示位置。
- 外键到 `restaurants.id`，删除策略 `RESTRICT`。软删除餐厅保留标签，公开读取检查父餐厅；永久清理由受控流程先删子记录。
- 位置唯一键与0–7的CHECK保证最多8个标签；总字符数是跨行约束，由应用在事务内校验。标签整体替换与主表修改共用同一主行锁和事务，并发保存按最后成功写入的完整集合生效，不能交叉混合标签；不使用版本号。总字符数不能仅凭DDL验收。
- 关键词搜索标签用EXISTS或先筛餐厅ID，不让连接后的标签行把餐厅计数和分页放大；返回一页餐厅后批量读取标签，不逐店查询。

这里使用子表而不是逗号分隔字符串或JSON数组，是为了保留明确的标签查询、去重和顺序约束；首期没有跨店维护、合并同义词等需求，不需要再拆“全局标签字典＋关系表”。

## 6. 首期索引与读取口径

| 表 | 索引 | 用途 |
| --- | --- | --- |
| `restaurant_categories` | 主键code、唯一name | 分类校验和名称去重 |
| `restaurants` | 主键id | 详情定位；查询仍须检查 `deleted_at IS NULL` |
| `restaurants` | `(deleted_at, name, id)` | 默认可见集合与稳定名称排序 |
| `restaurants` | `(category_code, deleted_at, name, id)` | 分类筛选后的稳定分页 |
| `restaurants` | `(created_by, deleted_at, id)` | 后续“我的餐厅”查询，避免按创建者全表扫描 |
| `restaurant_tags` | 主键、顺序唯一键 | 同店标签去重、批量读取及顺序约束 |

首期不为每个可能的筛选组合建索引，不提前添加空间索引或全文索引；金额、坐标和经营状态的索引在对应查询落地后通过执行计划决定。普通B-tree索引不能被当成任意“包含关键词”搜索的加速承诺；搜索先使用参数绑定和明确的LIKE字面量转义，小规模真实数据验证后再评估中文检索方案。

列表记录、总数、分类/杯型筛选和详情共用未删除集合；停业是独立可筛选状态，不等于删除。列表按名称、ID做稳定次序，标签多行不增加餐厅总数。首期COUNT和记录查询必须复用同一条件；并发修改下若要求同一快照，需在应用读取事务中明确隔离语义。

MyBatis-Plus不会因为存在 `deleted_at` 字段就自动过滤。当前读取Mapper不继承通用CRUD接口，详情、COUNT、分页与批量标签均显式检查未删除状态，COUNT与分页复用同一WHERE片段；MySQL测试覆盖所有已实现入口。后续新增地图等查询也必须遵循该边界，不能使用默认selectById绕开过滤。

当前读取服务使用REPEATABLE_READ只读事务，使总数、记录和标签处于同一快照；并发删除后下一次请求才反映新状态。列表只对当前页批量补标签，不在内存分页或页内重排。人均、直线距离在数据库全局升序排列，未知值在后；M1评分/四维/评价数相同，相关排序按名称、数值ID稳定返回。

## 7. 评分与后续扩展

首期未实现评价，所有餐厅的API阅读结果固定表达为：评价数0、综合分NULL、四维均分NULL、推荐率NULL，前端根据空评分显示待评级。不在数据库存一套可手工填写的杯型；推荐和踩雷筛选结果为空，不能将无评价餐厅补成推荐店。

评价阶段的结构与迁移见[评价表结构方案](review-schema.md)：reviews、照片关联及回复归review模块；可重建的restaurant_review_statistics归restaurant模块。通过公开应用服务在同一事务同步，餐厅自身表与统计表在分页前完成全局筛选排序，不能先取一页再拼评分。最新评审已撤回新增创建昵称字段，使用登录账号署名，不修改既有created_by归属或V3迁移；V4/V5及隔离结构测试已完成，文本发布与事务计分已实现；目录评分读取、分组及前端联动仍待接通。

地图阶段继续使用GCJ-02；当前经纬度不声明为WGS84，也不直接标为SRID 4326。范围查询、地点ID、城市筛选和地图索引在地图接口确定后新增迁移。历史真实餐厅的ID映射、导入批次及原始时间在迁移阶段独立设计。

## 8. 建表实施与验收计划

已新增 [V3__create_restaurant_catalog_schema.sql](../../backend/src/main/resources/db/migration/V3__create_restaurant_catalog_schema.sql)，按分类、餐厅、标签顺序建表，只插入13项分类。V1/V2保持原样；最初在隔离临时库验证，后续本地启动已由Flyway将开发库升级至V3。生产库未在本轮变更，已执行的迁移不得直接修改。

实施时使用隔离的真实MySQL测试验证：

1. V1/V2升级到V3、空库全量迁移、再次启动不重复迁移；已有用户和角色不变。
2. 允许同名分店；非法分类、空名称、价格超限、价格类型不匹配被拒绝。
3. 坐标成对、范围、待定位状态及NULL检查正确；禁止静默写入默认坐标。
4. 标签去重、排序、总数/总长度限制以及并发替换；标签搜索不放大总数。
5. 无评价为空态；删除店铺的详情/列表/计数一致不可见，停业仍是独立状态。
6. 账号创建有真实创建者、匿名写入被拒绝、不能指定他人身份；历史无归属不允许普通用户认领。
7. 无版本编辑允许后保存覆盖；并发主表/标签保持完整一组，删除后PUT不能意外恢复，恢复需管理员权限；读取不泄露删除原因或账号隐私。

身份、归属、标签总字符数和当前删除状态由写入服务校验。用户决定取消餐厅版本号：先锁主行，核验权限与状态，再按ID/归属/删除状态更新；不比较客户端版本，也不读取或递增原版本列。资料与整组标签在同一事务保存，最后成功保存的完整内容生效。标签按MySQL规则去重保留首项及顺序，总码点数80；分类共享锁保证启用校验与保存一致，停用分类只允许保持原值。软删除保留标签，管理员恢复清空当前删除信息但不改来源、归属或经营状态。

公开字段白名单、软删除过滤、评分空态及分类/搜索/排序/分页已由读取API验证，前端分类、目录和详情已接入。有评价后的删除治理、回收站分页、审计及子资源恢复尚未实现；迁移和启动不自动导入候选或创建虚构评价。

### 8.1 执行隔离数据库测试

[RestaurantSchemaIntegrationTest](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantSchemaIntegrationTest.java)直接使用Flyway和MySQL，不启动完整应用或读取本地业务配置。默认测试会跳过本组；显式开启后连接失败或凭据缺失会失败，不静默跳过。

```powershell
cd backend
$env:RESTAURANT_TEST_DB_SERVER = 'jdbc:mysql://127.0.0.1:3306/'
$env:RESTAURANT_TEST_DB_USERNAME = 'root'
$env:RESTAURANT_TEST_DB_PASSWORD = '<隔离测试实例的密码>'
mvn --batch-mode --no-transfer-progress verify '-Drestaurant.integration=true'
```

测试仅接受localhost或127.0.0.1的显式端口，数据库名由代码生成，不能传入业务库名。账号需具备创建/删除临时库权限；测试结束仅清理本次实际创建且符合严格前缀的随机库，不使用Flyway clean。约束测试采用独立事务回滚，失败升级的临时库也由清理流程处理。

同一 `restaurant.integration=true` 开关还会运行 [RestaurantReadApiIntegrationTest](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantReadApiIntegrationTest.java)，使用相同三项环境变量、独立随机库和内存临时RSA密钥，验证实际应用迁移、Mapper、安全链、分页和并发快照。默认快速测试另覆盖查询绑定与服务边界；未开启MySQL组时不能将其记为通过。

需要在本地跑完整认证/Redis/餐厅组合时，同时开启 `-Dauth.integration=true -Dredis.integration=true -Drestaurant.integration=true`。认证测试可用 `AUTH_TEST_DB_SERVER` 指向同一个独立回环MySQL实例（默认仍为 `jdbc:mysql://127.0.0.1:3306/`），通过 `AUTH_TEST_DB_PASSWORD` 提供测试密码；不允许URL带库名、凭据或远程地址。认证和餐厅各自创建/清理随机测试库，Redis测试只清理自有随机键，不使用Docker或FLUSHDB。不要传入生产凭据或将未执行组记为成功。

[RestaurantWriteApiIntegrationTest](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantWriteApiIntegrationTest.java)使用同一开关和环境变量、另建专用随机库，验证当前账号/实时角色、CRUD闭环、Unicode边界、价格/坐标、事务回滚、旧页面后保存覆盖及并发完整标签集。写入测试不使用外层回滚事务，实际HTTP保存独立提交，失败回滚由业务事务完成；测试结束只清理本次随机库。

CI在原有MySQL 8.4临时服务中启用该开关，同时保留真实认证与Redis测试。实际本地执行结果及跳过范围见[开发日志](../history/development-02.md)；未推送或未运行的完整CI不能记为通过。

### 8.2 本地联调模拟数据

按用户明确要求，使用 [seed_restaurants.py](../../backend/tools/seed_restaurants.py) 及 [restaurant-demo.json](../../backend/tools/restaurant-demo.json) 手动写入25家原版候选。工具不进入Flyway或应用启动流程，只读取local配置，限制本机开发库；命名锁防止脚本并发重复写，整批事务失败回滚，同名已有记录（含软删除）直接跳过，不覆盖或复活。

每家保留“联调模拟”标签，来源LEGACY_IMPORT、创建/修改者为空；人均为旧档位ESTIMATE，坐标未经核实故保持null/待定位。保留原版停业标记但不宣称已核实；没有创建账号、管理员、评价或手工评分。普通用户不能通过店名认领导入归属；模拟数据不是D12正式历史迁移或生产种子决定。操作步骤见 [README](../../README.md#本地启动)。

## 9. 技术依据

- 项目现有 [V2 用户表迁移](../../backend/src/main/resources/db/migration/V2__create_user_auth_schema.sql)与 [MyBatis-Plus配置](../../backend/src/main/java/com/ginkgo/foodmap/common/persistence/MybatisPlusConfiguration.java)。
- MySQL 8.4 [CHECK约束](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)：约束为TRUE或UNKNOWN时均可通过，因此需要显式处理NULL；CHECK不能代替跨行的标签总数校验。
- MySQL 8.4 [DECIMAL类型](https://dev.mysql.com/doc/refman/8.4/en/precision-math-decimal-characteristics.html)及[联合索引](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html)：坐标采用明确精度，索引与实际查询前缀对应，不宣称一个索引覆盖全部筛选与排序。
