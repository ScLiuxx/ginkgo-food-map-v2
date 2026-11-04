# Elasticsearch 商户搜索数据结构

## 当前状态与职责

2026-11-04本地Elasticsearch 9.5.4（IK 9.5.4）的现行索引为`restaurant-search-v002`，别名`restaurant-search`已原子切换至该版本，正式文档数为0。v001定义及空物理索引保留作结构追溯，不再承接业务别名。真实商户导入、查询入口切换、相关性权重、增量同步和故障回退尚未实现；网站继续使用MySQL查询。

MySQL保存正式业务资料，ES保存可重建的搜索投影。每家普通餐厅或食堂对应一份文档，数据来源为`restaurants`、`restaurant_categories`、`restaurant_tags`、`restaurant_review_statistics`，由restaurant模块统一组装。窗口与菜品不混入当前索引。

- 版本定义：[restaurant-search-v002.json](../../backend/src/main/resources/elasticsearch/restaurant-search-v002.json)，是settings、mapping和初始别名的唯一来源，可打包为Java类路径资源。
- 初始化工具：[setup_restaurant_search_index.py](../../backend/tools/setup_restaurant_search_index.py)，只执行显式的本地建索引或检查，不写入商户文档。
- 内部投影组装：[RestaurantSearchProjectionService](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantSearchProjectionService.java)，每批最多100店，在独立REPEATABLE_READ只读事务中读取商户/字典、原始统计、有序标签及现有公开相册封面；[投影规则](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/policy/RestaurantSearchProjectionPolicy.java)校验后生成独立正文对象，不访问ES或OSS，没有HTTP入口或自动任务。
- 真实字段语义测试：[test_restaurant_search_index.py](../../backend/tools/test_restaurant_search_index.py)，只在显式开启后创建本次随机隔离索引，完成后清理。
- 业务主库结构见[餐厅/食堂/窗口](restaurant-schema.md)，后续实施步骤见[搜索方案](../plans/elasticsearch-search.md)。

ES结构通过版本化JSON管理，不在Flyway迁移中访问ES。现有Spring Data Elasticsearch连接仍由开关控制，不在应用启动时自动创建索引；后续Java业务查询继续使用已有ElasticsearchOperations能力。Python工具属于本地初始化操作，不是新的业务请求链路。

## 索引、别名与设置

| 项目 | 当前定义 | 含义 |
| --- | --- | --- |
| 物理索引 | `restaurant-search-v002` | 固定当前字段与分词配置版本 |
| 业务别名 | `restaurant-search` | 后续应用统一通过别名访问 |
| 写索引标记 | `is_write_index=true` | 别名当前唯一指向v002 |
| 主分片 | 1 | 当前本地数据规模使用单主分片 |
| 副本 | 0 | 单节点本地配置，上线按节点与资源另行确定，不代表高可用 |
| 刷新间隔 | `1s` | ES默认可见性刷新周期，不是端到端同步延迟承诺 |
| 动态字段 | `strict` | 未声明字段直接拒绝，不自动扩展mapping |
| 数值转换 | 每个数值字段`coerce=false` | 拒绝字符串数字及整数金额的小数截断 |
| 文档原文 | 保留默认`_source` | 保留原始名称、数组和空值供核对 |
| 普通分页窗口 | 保留ES默认10000 | 当前未新增页码协议，后续接搜索时明确边界或使用游标 |

创建索引时一次提交settings、mapping与别名。初始化工具发现同名索引时只比对，不修改已有结构；发现同名别名指向其他索引或结构漂移时停止。结构升级应设计新物理版本、重新构建及验证，再单独执行别名切换，不在v001上偷偷变更分词规则。

别名没有隐含的删除过滤器。后续公开搜索必须显式使用`deleted=false`，并回查MySQL的当前可见性；不能把别名或mapping当作权限控制。

## 文档身份

ES元数据`_id`使用商户ID的十进制字符串，如`"450"`；正文`id`为`long`，如`450`。二者代表同一主键，重复同步更新同一文档，不使用自动生成的ES ID。

正文id用于回查与数字ID的稳定排序。来源必须是现有Java/API可接受的正Long商户ID，不混入窗口ID，不使用JavaScript浮点转换大整数ID；具体序列化由后续导入器处理。`_id`与正文id一致性、正数及必填规则由投影组装器校验，mapping本身不提供这类跨字段约束。

## 字段定义

共21个主字段，另有派生子字段`name.exact`。当前结构不包含杯型、四维分数或创建/更新时间，增加仅供列表展示的coverPhotoId。表中的必填、范围、枚举及字段组合由内部投影规则校验，不代表ES已实现MySQL CHECK。独立正文对象使用Long数字身份、String封面ID、lat/lon对象；null字段省略，空标签保留[]。此阶段尚无业务导入器。

### 身份与关键词匹配

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `id` | `long` | `restaurants.id` | 商户身份；正文为数值，拒绝字符串数字；与`_id`一致 |
| `name` | `text` | `restaurants.name` | 商户名，主要全文匹配字段；保留原店名 |
| `name.exact` | `keyword`子字段 | 由name自动建立 | 规范化后完整店名匹配；不作为独立JSON字段提交 |
| `categoryName` | `text` | 分类字典name | 辅助关键词匹配；食堂固定为空 |
| `tags` | `text`，接受字符串数组 | 按position读取商户标签 | 辅助关键词匹配；无标签使用空数组，不上传标签关联行对象 |
| `address` | `text` | `restaurants.address` | 地名、道路等辅助匹配；未知为空 |

上述四个text字段（name、categoryName、tags、address）建索引用`ik_max_word`，查询默认用`ik_smart`。[真实商户评测](../plans/restaurant-search-evaluation.md)已用9562家商户、44条固定查询完成原词典与三词扩展词典的实际ES重建对照；本地现已启用“冒菜、螺蛳粉、天街”，修复对应词边界漏召回，多字段权重仍是实验参数。正式查询尚未接入；不代表已支持拼音、简称或错别字纠正。

扩展词典与加载配置由[deploy/elasticsearch](../../deploy/elasticsearch/README.md)单独管理并只读挂载，IK插件和基础词典使用持久卷。词典不包含在mapping JSON中，初始化--check不会判断外部词典内容；需另用真实_analyze核验。改词典不会重新分析已有文档，已有数据必须受控重建新索引再验收。当前正式v002为空，本次只重建并清理了实验索引，未修改字段结构或切换正式别名。

`name.exact`使用自定义normalizer `restaurant_name_exact`，过滤链为`lowercase`、`asciifolding`：例如`CAFÉ火锅`可按`cafe火锅`完整匹配，`_source.name`仍保留原文。它不删除分店后缀、标点或括号，也不把中文自动转换成拼音；“精确”指规范化后的完整值相等。

精确字段的词项排序与MySQL的utf8mb4排序规则未必一致；后续不能未经对照就声称店名排序完全兼容。

### 精确筛选

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `categoryCode` | `keyword` | `restaurants.category_code` | 普通餐厅使用真实分类编码；食堂固定为空，不能改成OTHER |
| `restaurantType` | `keyword` | `restaurants.restaurant_type` | REGULAR/CANTEEN；餐厅类型筛选 |
| `supportsDineIn` | `boolean` | 同名业务字段 | true支持、false不支持、null未知 |
| `supportsDelivery` | `boolean` | 同名业务字段 | true支持、false不支持、null未知 |
| `pricePerPerson` | `integer` | `restaurants.price_per_person` | 整数元1–5000；未知为空；价格范围筛选及排序 |
| `priceKind` | `keyword` | `restaurants.price_kind` | UNKNOWN/EXACT/ESTIMATE，保留金额口径 |
| `businessStatus` | `keyword` | `restaurants.business_status` | OPERATING/CLOSED；隐藏停业；不解释成实时营业状态 |

类型与分类、价格类型与金额等组合沿用MySQL业务规则。keyword只约束字段类型，不会校验值是否属于字典或上述枚举；boolean及数组的输入形式也须由组装器规范化。

具体美食品类过滤会自然排除空分类食堂；不传分类时保留食堂。类型、堂食、外卖与价格应在同次关键词搜索的分页前过滤，不能先从ES取一页，再在前端或MySQL按这些条件筛选。

### 地理查询

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `location` | `geo_point` | 由latitude/longitude组装 | `{"lat":30.75,"lon":103.93}`，用于视口、距离筛选及距离排序；无有效位置时为空或省略 |
| `needsLocation` | `boolean` | `restaurants.needs_location` | 是否待定位；待定位不能填校园中心代替真实位置 |

继续沿用GCJ-02约定，不新增商户坐标系字段；ES不会自动转换坐标。统一使用lat/lon对象避免数组经纬度顺序混淆。`ignore_malformed=false`拒绝非法坐标，`ignore_z_value=false`拒绝意外的第三维；ES合法地理范围不等于项目要求的中国坐标范围，后者由组装器校验。

用户位置属于查询参数，`distanceKm`属于当前请求的计算结果，均不作为商户固定字段存入索引。ES距离计算与现有MySQL实现的边界及精度需在查询阶段对照验证。

### 评分与排序

| 字段 | ES类型 | 来源/计算 | 用途 |
| --- | --- | --- | --- |
| `ratingScore` | `double` | overall_score_sum / participant_count | 商户综合评分排序，使用后端0–10口径 |
| `recommendRate` | `double` | recommend_count / participant_count | 好评优先，0–1比例 |
| `participantCount` | `long` | participant_count | 计分人数，好评排序并列依据 |
| `reviewCount` | `long` | review_count | 有效点评总条数，兼容点评数排序 |

统计行不存在时人数和条数为0；participant_count=0时均分、推荐率为空，不做除零，也不填0。已有真实0分和0推荐率保留。使用原始累计值计算，不先将页面展示分四舍五入，再写ES。

`ratingScore`来自业务统计；ES的`_score`由每次关键词查询计算相关度，不作为正文持久化字段。

double与MySQL DECIMAL不保证任意近邻分值排序完全一致。接入时须验证并列、空值及精度；尚未通过对照的排序继续走SQL。四维原始评分及统计留在MySQL供详情和点评展示，当前API不接受按四维排序；杯型计算、响应字段及tiers/view旧分档筛选已移除，不建立兼容索引字段。

### 列表封面

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `coverPhotoId` | `keyword`，index=false、doc_values=false | 公开正式相册中position=0的restaurant_photos.id | 可空的正Long字符串，保留大整数精度；仅用于组装列表封面，不参与搜索、筛选和排序 |

使用正式相册关联ID，不使用原始上传文件ID或OSS对象地址。读取来源沿用正式相册的可见性规则：商户未删除、来源申请已通过、目标商户一致；未发布或无封面时为空。后续投影组装器必须复用这套规则，不直接把任意图片ID公开。

根据商户id与coverPhotoId即可组装现有封面结构：`{"id":"801","position":0,"cover":true,"url":"/api/restaurants/450/photos/801"}`；图片字节继续由原公开图片接口读取。ES只保存ID，不保存图片内容和完整相册。

该字段仅保存在_source中，没有索引或doc_values，不能用term/exists做封面筛选；是否有封面由返回原文中的ID判断。后续若新增“有图片”筛选，需要另行设计字段，而不是默默改变当前结构。

### 删除与同步版本

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `deleted` | `boolean` | deleted_at是否非空 | 删除文档排除公开搜索，停业与删除分别处理 |
| `projectionVersion` | `long` | MySQL restaurants.search_projection_version | 每商户单调增长的投影版本，正数；用于防止旧任务覆盖新投影 |

projectionVersion来源于V21新增的restaurants.search_projection_version（有符号BIGINT，CHECK > 0，默认1）。存量含删除商户以1为基线；新建从1开始。资料更新、标签替换、软删除和恢复在原业务UPDATE中递增；评价发布/删除的统计变化在同一事务推进版本，并保留资料updated_at。所有这些写入先锁商户父行，按每店提交顺序递增；失败连同版本回滚，Long.MAX_VALUE时拒绝继续修改而不溢出。成功的完整资料保存即使内容相同也推进一次；幂等评价/审核重试、驳回及回复不推进。

审核通过的资料、相册及最终审核状态在同一事务提交，共用该次创建/更新版本。组装器assemble(List<Long>)只接收ID，版本与资料、统计、标签及封面从同一独立RR快照读取；禁止把事件版本配到较新的实时资料上。

当前尚无事务Outbox、消费者或ES写入器。未来写入还须把版本传给ES写入API的external version；单纯保存普通long字段不会自动拒绝旧写入。不使用更新时间、旧业务版本或事件自增ID冒充每店提交版本。直接SQL、维护脚本以及未来新增的分类改名/相册维护入口也必须在同一事务推进受影响商户版本；数据库不会通过触发器自动覆盖这些旁路。此步尚未对业务库执行V21或导入正式ES。

当前组装方法要求内部调用方显式传入ID→正版本号，不提供默认值、不持久化版本，也不验证其与来源快照的对应关系。该契约只供后续同步接入准备，不能据此直接上线写ES；同步实现时必须绑定持久版本与同一来源快照。软删除记录生成最小三字段墓碑，不存在记录整批失败，不把物理缺失猜作删除。组装服务使用REQUIRES_NEW仅读已提交资料，不应在业务写事务中调用。

删除墓碑可只保留id、deleted=true、projectionVersion；恢复时用更高版本重建完整投影。墓碑写入及清理策略尚未实现，本轮正式索引也未创建任何墓碑文档。

## 空值和业务约束

- 无分类食堂：categoryCode/categoryName为null或省略，不使用空字符串、CANTEEN分类编码或OTHER代替。
- 堂食/外卖未知：使用null或省略，不填false；筛选“支持”只匹配true。
- 价格、评分未知：使用null或省略，不填0；有效0分仍可匹配和排序。
- 标签为空：使用`[]`，不写`[""]`。
- 位置未知：location为空或省略，不填写默认校园坐标。

没有设置`null_value`替代词项。ES默认不为null或空数组建立可匹配的值，但保留JSON `_source`中的原值；对可检索字段判断是否有值时使用exists。不存在字段与显式null在此查询层按同一未知语义处理；coverPhotoId仅展示，按上一节读取原文判断。

mapping不会检查必填、数值业务上下限、分类字典存在性、类型/分类组合、标签数量和总长，也不会验证数据是否已经审核发布；这些必须由MySQL约束及后续投影组装器共同保证。`dynamic=strict`只负责拒绝未声明字段；数值coerce=false只负责拒绝不合适的数值输入，不能代替完整业务校验。

## 文档示例

以下是说明结构的虚构普通餐厅，不代表实际商户，未写入正式索引：

```json
{
  "id": 10001,
  "name": "示意火锅店",
  "categoryCode": "HOT_POT",
  "categoryName": "火锅串串",
  "restaurantType": "REGULAR",
  "tags": ["学生聚餐"],
  "address": "示意地址",
  "supportsDineIn": true,
  "supportsDelivery": null,
  "pricePerPerson": 40,
  "priceKind": "ESTIMATE",
  "businessStatus": "OPERATING",
  "location": {"lat": 30.75, "lon": 103.93},
  "needsLocation": false,
  "ratingScore": 8.0,
  "recommendRate": 0.5,
  "participantCount": 2,
  "reviewCount": 3,
  "deleted": false,
  "projectionVersion": 1,
  "coverPhotoId": "801"
}
```

食堂关键字段片段：`{"restaurantType":"CANTEEN","categoryCode":null,"categoryName":null}`。不向_source提交name.exact或_score，也不存创建/更新时间、四维分数、杯型、用户位置、请求分页参数、查询距离、完整相册、OSS对象地址、点评正文、审核快照及账号信息。MySQL现有审计时间和四维评分保持原样，当前SQL接口的这些展示字段不在本轮提前删除；接入ES列表时再同步独立列表契约。

## 初始化与核验

从仓库根目录运行（工具仅依赖Python标准库）：

```powershell
python backend/tools/setup_restaurant_search_index.py
python backend/tools/setup_restaurant_search_index.py --check
```

默认目标为`http://127.0.0.1:9200`，支持通过`--url`指定本地端口；工具拒绝远端地址、认证信息及非根路径，不额外读取业务库或本地私密配置。其他环境的认证、部署参数与生产副本策略需另行实现，不能直接用此本地工具连接云主机。

首次执行创建空索引，重复执行仅核对定义，不删除、重建、导入或重绑。`--check`完全只读；缺失资源、别名冲突、结构漂移或连接错误都返回非零退出码。网络写入超时后先用--check确认状态，不自动重试创建。

本次v001→v002为一次显式空索引升级：确认无业务写入、旧索引和新索引文档数均为0，验证新结构后原子remove/add别名；未删除v001、未执行真实数据迁移。初始化工具仍拒绝自动替换其他别名指向。已有数据环境需另行执行受控重建/校验，不可照搬本次空索引操作。

真实ES验证需显式开启，只创建UUID随机测试索引，不往正式索引塞样例：

```powershell
$env:GINKGO_ES_SCHEMA_IT = 'true'
python -m unittest discover -s backend/tools -p test_restaurant_search_index.py -v
```

默认不设置该变量时只执行初始化安全测试，真实ES部分跳过。测试清理仅删除本次已确认创建且匹配固定前缀与UUID的索引。

本地核验：初始化和只读检查成功，重复执行未重建；11项测试全部通过（4项初始化安全、7项真实ES字段语义），覆盖IK、完整店名规范化、空分类与三态布尔、价格范围、数字ID顺序、真实0分、地理范围、未知字段与数值转换拒绝。正式文档数仍为0，原有练习及其他项目索引保留。

## 官方依据

- [Create index](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-create)：一次创建settings、mapping及alias。
- [IK Analysis](https://github.com/infinilabs/analysis-ik)：ik_max_word、ik_smart及插件版本。
- [Normalizer](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/normalizer)：keyword完整值规范化。
- [Dynamic mapping](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/dynamic)：strict字段检查。
- [Coerce](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/coerce)：关闭数值自动转换。
- [Null values](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/null-value)：空值索引语义。
- [Geo point](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/geo-point)：地理字段表示与能力。
