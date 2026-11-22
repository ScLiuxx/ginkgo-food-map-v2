# 餐厅、地图与管理

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| GET | `/api/restaurant-categories` | 公开 | 200 | [分类](#categories) |
| GET | `/api/restaurants` | 公开 | 200 | [目录](#catalog) |
| GET | `/api/restaurants/map-points` | 公开 | 200 | [详情与地图点位](#detail-map) |
| GET | `/api/restaurants/{id}` | 公开 | 200 | [详情与地图点位](#detail-map) |
| GET | `/api/restaurants/{id}/photos` | 公开 | 200 | [正式商户相册列表](#album) |
| GET | `/api/restaurants/{id}/photos/{photoId}` | 公开 | 200 JPEG | [正式商户图片读取](#photo) |
| GET | `/api/restaurants/{id}/management` | 管理员 | 200 | [直接维护餐厅](#management) |
| POST | `/api/restaurants` | 管理员 | 201 | [直接维护餐厅](#management) |
| PUT | `/api/restaurants/{id}` | 管理员 | 200 | [直接维护餐厅](#management) |
| DELETE | `/api/restaurants/{id}` | 管理员 | 200 | [直接维护餐厅](#management) |
| GET | `/api/admin/restaurants/{id}/review-photos` | 管理员 | 200 | [点评选图](#admin-review-photos) |

<a id="public-read"></a>

## 餐厅公开读取

<a id="categories"></a>

### 分类

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurant-categories` | 公开 | 200 |

**响应**

`GET /api/restaurant-categories`无参数，成功200、data为`[{"code":"FAST_FOOD","name":"小吃快餐","sortOrder":1,"enabled":true}]`，按sortOrder、code排序。包括停用分类以展示历史资料；前台导航和新建选择只使用enabled=true，停用分类不能用于新建或切换分类。

| 启用编码 | 名称 |
| --- | --- |
| FAST_FOOD | 小吃快餐 |
| CHINESE | 中餐地方菜 |
| HOT_POT | 火锅串串 |
| BARBECUE | 烧烤烤肉 |
| DRY_POT_FISH | 香锅烤鱼 |
| INTERNATIONAL | 国际美食 |
| DESSERT | 甜品烘焙 |
| DRINK | 饮品 |
| OTHER | 其他美食 |

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

普通餐厅只指定一个主营类目，没有二级分类；食堂由restaurantType=CANTEEN表示，categoryCode和categoryName均为null，不归入九类。旧申请的原始分类编码和已完成审核快照保留；旧待审食堂首次通过时使用空分类，普通餐厅需选用可用分类。

<a id="catalog"></a>

### 目录

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants` | 公开 | 200 |

**请求参数**

`GET /api/restaurants`

| 参数 | 默认值 | 规则 |
| --- | --- | --- |
| `keyword` | 空 | 最长100字符；ES多字跨名称/分类/标签/地址AND分词匹配，单个汉字查四字段单字索引；SQL降级使用原有包含匹配；输入不作为查询语法 |
| `category` | 空 | 大写分类编码；空表示不限 |
| `restaurantType` | 空 | REGULAR普通餐厅 / CANTEEN食堂；空或省略不限，大小写严格匹配 |
| `diningMode` | 空 | dineIn支持堂食 / delivery支持外卖；空或省略不限，只匹配明确true，false及null不命中 |
| `hasReviews` | 无 | true仅有点评、false仅无点评，省略或空值不限；按当前有效reviewCount是否大于0判断，缺统计行为无点评；目录、关键词与地图均在计数和分页前过滤，ES沿用短暂同步延迟 |
| `sort` | auto | auto/relevance/score/reviews/price/distance/recommend；auto在多字关键词时请求相关度，空关键词或单个汉字使用综合分；显式排序保持有效；四维排序仍返回400 |
| `hideClosed` | false | true时隐藏停业商户 |
| `maxPrice` | 无 | 大于0，最多8位整数和2位小数；未知价格不符合上限筛选 |
| `latitude` / `longitude` | 无 | 距离参照点的GCJ-02原点，可为固定校区点或主动授权的用户位置，范围[-90,90]/[-180,180]，必须成对提供 |
| `maxDistanceMeters` | 无 | 整数1–20040000，以请求参照原点计算；缺位置商户不符合距离筛选 |
| `west/east/south/north` | 无 | 可选视口，四边同时提供，经度[-180,180]、纬度[-90,90]，west<east且south<north |
| `page` | 1 | 正整数，最多2147483647 |
| `pageSize` | 8 | 1–100 |

条件取交集，在完整可见集合上排序、计数后分页。score按五分制reputationScore八位小数降序，真正同分按participantCount降序、数字ID升序，未知末尾；页面显示一位小数，不用于截断排序精度。reviews按reviewCount降序、数字ID升序表示热度；旧recommend参数兼容同一热度规则，不再按推荐率排序。距离按请求参照原点升序，未定位商户排后；无原点时不应在前端冒充距离排序。前端校区选项只发送固定GCJ-02坐标，不请求定位；仅距我选项触发定位，坐标不保存到账号。视口不支持跨日期变更线，边界包含，提供视口时排除待定位记录。

餐厅类型读取正式restaurant_type，独立于美食品类；食堂本身无美食分类，因此CANTEEN与具体美食品类组合时返回空集合，不自动忽略任一条件。类型及用餐方式过滤同时作用于COUNT、排序分页和地图点位。非法restaurantType/diningMode返回400 `COMMON_VALIDATION_FAILED`，不静默忽略。查询引擎和搜索降级的响应约定见[搜索契约](#search)。

**响应**

成功200，data为`{records,total,page,pageSize,totalPages,accessiblePages,search}`。所有列表请求统一要求`(page-1)*pageSize+pageSize <= 10000`，超过返回400 `RESTAURANT_INVALID_INPUT`（不会因ES开关或降级而改变）；窗口内越界页为空、200。`total`/`totalPages`是真实总量，`accessiblePages=min(totalPages,floor(10000/pageSize))`；无关键词浏览同样限制窗口。单条餐厅结构：

```json
{
  "id":"101","name":"商户名称","categoryCode":"OTHER","categoryName":"其他","restaurantType":"REGULAR",
  "supportsDineIn":true,"supportsDelivery":null,"pricePerPerson":25,"priceKind":"EXACT",
  "address":"商户地址","latitude":30.670582,"longitude":104.107260,"needsLocation":false,
  "businessStatus":"OPERATING","createdAt":"2026-11-03T06:00:00Z","updatedAt":"2026-11-03T06:00:00Z",
  "distanceKm":null,"tags":["家常菜"],"score":null,"reputationScore":null,"reviewCount":0,"participantCount":0,
  "dimensions":{"taste":null,"value":null,"service":null,"ambience":null}
}
```

`reputationScore`为五分制综合口碑分，普通餐厅使用`((0.70*taste_sum+0.15*value_sum+0.10*service_sum+0.05*ambience_sum)/2+17.5)/(participant_count+5)`，由MySQL存储生成列维护，固定C=3.5、m=5，保留八位小数。原score保持十分制等权均分语义，不覆盖单条点评；前端主要综合分使用reputationScore，五分制只换算一次。食堂与普通餐厅使用同一套直接点评及口碑分计算规则。

无人计分的分数/四维为null，真实0分保持0。reviewCount计全部有效点评，participantCount计每人最新有效代表。score为代表点评综合分累计值/人数，四舍五入保留一位小数。历史点评不重复贡献评分，原始累计值不变。餐厅及食堂的列表、详情与管理回执不再返回recommendRate；推荐态度仅保留在单条点评，不派生商户百分比，也不参与排序、筛选或榜单。地址、价格和位置未知保持null，不用模拟值补齐。列表/详情不返回创建者、删除元数据或数据库版本。ES列表没有四维和审计时间投影，因此dimensions四项及createdAt/updatedAt为null；详情仍读取MySQL并返回实际值。

食堂响应使用`"restaurantType":"CANTEEN","categoryCode":null,"categoryName":null`。不传category时目录及地图包含食堂；指定美食品类时不包含食堂。餐厅类型通过现有前端独立筛选选择，不把CANTEEN作为美食分类参数。

目录、详情及管理回执的restaurant新增coverPhoto：无正式相册首图为null，否则为`{id,position:0,cover:true,url}`，例如`{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}`。首页/地图卡片直接使用此正式封面；ES列表直接按投影中的coverPhotoId组装同源图片地址，图片读取仍校验当前可见性；SQL降级在分页完成后按当前页商户ID集合一次批量补齐元数据，不逐卡片读取相册/点评，不在列表查询中访问云存储。查询要求商户未删除，且为管理员直接照片或来源申请已通过且目标一致；仅有待审照片或点评照片不会产生封面。

**错误码**

杯型计算与响应字段tier已移除；目录和地图请求若携带view、tiers或tiers数组参数，返回400 `RESTAURANT_INVALID_INPUT`，不静默忽略旧筛选。详情与点评保留四维评分展示。

<a id="search"></a>

### 搜索匹配、降级与一致性

完整规范化店名优先，其余按相关度及数字ID排序；所有关键词须在名称、分类、标签和地址的并集中满足，短语命中加权。显式业务排序不混入相关度或页内重排。

- 单个汉字按Unicode码点和Han脚本判断，包含补充平面汉字；直接term查询name.chars/address.chars/categoryName.chars/tags.chars，任一字段包含即满足。auto保持原有score默认排序，显式relevance及业务排序照常执行。列表与地图共用此路径，不再因单字预定走SQL。查询前核对四字段可检索并固定本次物理索引，旧结构未就绪时整次SQL降级，避免未映射字段返回假空集合。
- 单字ES匹配采用原字语义，不继承MySQL排序规则的字符等价扩展，例如〇不会命中ASCII数字0。真实库中〇的ES字面命中为1家，旧SQL为3748家；SQL降级仍可能受数据库排序规则影响，因此基础匹配提示保留。
- score直接按v004预计算的reputationScore排序，再按participantCount及数字ID；reviews/recommend按reviewCount、数字ID排序。SQL使用同一生成列和同分规则，不再在ES查询中生成评分或推荐率runtime排序键。旧索引缺少新字段时需受控升级，不能以旧ratingScore冒充口碑分。
- SQL路径保持原LIKE包含匹配；请求相关度但实际走SQL时，实际排序为score。首版不做无结果放宽、拼音、简称、错字或全局同义扩展。
- 所有硬筛选在ES计数及分页之前执行。列表直接读取ES公开投影，不回查MySQL资料、标签、封面或Outbox，接受评分、资料与删除的短暂同步延迟；详情及写操作仍以MySQL为准。地图点位保留MySQL当前页资料和版本回查，不以LIKE再次过滤ES分词命中。不拿ES前100条交给SQL筛选后当作全量。
- ES不可达、客户端不存在、索引不存在、超时、部分分片失败、非精确总数或不完整结果，均整次执行原SQL；ES本身返回空集合不触发降级。仅地图点位在查询前或回查快照存在当前版本PENDING/FAILED任务时保守走SQL；地图回查有删除、缺失、版本或硬条件变化时丢弃整页ES结果和total，完整重查SQL。列表不因同步积压降级，但非法或不完整的投影正文仍作为ES错误整次降级。
- `search`示例：`{"engine":"ES","degraded":false,"reason":"SEARCH","effectiveSort":"relevance","resultWindow":10000}`。降级示例：`{"engine":"MYSQL","degraded":true,"reason":"ES_UNAVAILABLE","effectiveSort":"score","resultWindow":10000}`。reason枚举：CATALOG、NO_LOCATION、DISABLED、SEARCH、ES_UNAVAILABLE、ES_CIRCUIT_OPEN、SYNC_PENDING、DATA_CHANGED；CATALOG对列表表示ES无关键词浏览，对地图点位表示预定SQL路径；NO_LOCATION及DISABLED为预定SQL路径，不标记degraded。ES_CIRCUIT_OPEN表示熔断打开或半开探测名额已用尽，响应仍200、engine=MYSQL、degraded=true；触发熔断的失败请求本身仍为ES_UNAVAILABLE。不再返回SINGLE_HAN。列表无关键词时resultWindow同样为10000；无关键词地图点位为null。
- 页面基于accessiblePages导航，保留真实total，并在需要时提示基础关键词匹配。切换关键词不会覆盖用户已选业务排序，auto按空关键词、单汉字、多字选择默认意图。
- 正常ES搜索为最终一致性；任务已成功到refresh可见之间仍有窗口。回查只能检查本页命中，不能发现所有未命中的资料变化；直接SQL绕过Outbox仍需补同步。独立翻页与列表/地图请求不承诺跨请求快照固定。

搜索分析器、熔断、超时及地理计算实现见[搜索架构](../architecture/restaurant-search.md)。

<a id="detail-map"></a>

### 详情与地图点位

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/map-points` | 公开 | 200 |
| GET | `/api/restaurants/{id}` | 公开 | 200 |

**响应**

`GET /api/restaurants/{id}`成功200，data为上述单条餐厅；不存在/已删除404 `RESTAURANT_NOT_FOUND`，停业可读。详情没有用户查询原点，distanceKm为null。

`GET /api/restaurants/map-points`允许匿名读取，必须提供四个视口边界，其他筛选及排序与目录相同；关键词也在视口内匹配。page/pageSize不改变候选集合，固定最多100家。data为`{points,records,matchedCount,returnedCount,limit:100,truncated,search}`：points包含id/name/latitude/longitude/score/reputationScore/pricePerPerson/businessStatus；records使用上述公开餐厅结构，包含标签、距离及正式封面，和points逐项同ID、同顺序，两者长度均为returnedCount。无结果时两数组为空。matchedCount为完整符合条件数量，truncated表示未全部返回；恰好100家且无更多结果时为false。前端明确显示完整数量和当前展示数量，不把截断集合称为全部。

**错误码**

无视口400 `RESTAURANT_INVALID_INPUT`；缺边、非法范围/参数400 `COMMON_VALIDATION_FAILED`。查询不会访问高德或写入资料。

**副作用与限制**

同一响应由同一批已确认坐标的店铺生成点位及卡片；SQL路径在一个RR只读快照中读取计数、店铺、标签与封面，ES关键词路径沿用版本回查与整次SQL降级规则。地图页仅使用本接口，不再并行查询目录分页或混入高德全城地点搜索；普通首页目录及投稿选址的地点搜索仍按原入口使用。用户移动/缩放地图后300毫秒防抖更新，关键词和筛选保留；程序点选定位、面板展开及卡片滚动不发起新范围查询。SDK尚未提供有效范围时不查询全站，失败保留并标明上次地图和卡片结果。HTTP成功200，只读，无新数据表或迁移。

<a id="album"></a>

### 正式商户相册列表

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{id}/photos` | 公开 | 200 |

**请求参数**

`GET /api/restaurants/{id}/photos`允许匿名读取，无请求体，成功200且Cache-Control:no-store。默认limit=20，范围1–50；cursor可省略或原样使用上次nextCursor，为当前相册图片关联ID正整数字符串，不是media_file_id或position。

**响应**

```json
{"records":[{"id":"801","position":0,"cover":true,"url":"/api/restaurants/101/photos/801"}],"nextCursor":"801"}
```

按position、id升序，在SQL中最多读取limit+1条判断后续；仅有后续记录时nextCursor为本页最后一张关联ID，否则null。上例表示仍有下一页，不表示相册只有一张。cover仅在position=0为true；后续页第一条不会被误作封面。无图或已读到末尾为records:[]、nextCursor:null，不改变数据或访问云存储。

**错误码**

仅返回已通过且来源目标与本商户一致的正式关联，不返回原文件ID、来源申请ID、上传者或云存储地址。商户不存在/已删除404 RESTAURANT_NOT_FOUND；非法资源ID或超出long范围400 RESTAURANT_INVALID_ID；cursor格式或limit无效400 COMMON_VALIDATION_FAILED；不存在或属于其他商户的游标404 RESTAURANT_PHOTO_NOT_FOUND。每页独立只读快照，后续新追加记录可在继续翻页时出现。

**副作用与限制**

前端纠错页也使用本接口只读回显已有正式照片；读取和浏览不构成图片编辑，不将正式关联ID混入申请photoIds。photoIds仍仅接受本次新上传的文件ID，新增图审核通过后追加；旧图保持不变，新店投稿不查询已有相册。

<a id="photo"></a>

### 正式商户图片读取

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{id}/photos/{photoId}` | 公开 | 200 JPEG |

**响应**

`GET /api/restaurants/{id}/photos/{photoId}`允许匿名读取。photoId为[正式商户相册列表](#album)返回的稳定正式关联ID，禁止传任意文件ID读取。成功200、原始image/jpeg字节、Cache-Control:no-store及X-Content-Type-Options:nosniff，不使用JSON封装，不重定向或返回签名云存储链接。

**错误码**

非法ID400 RESTAURANT_INVALID_ID；图片/商户不可访问、图片不属于指定商户或来源不符合条件404 RESTAURANT_PHOTO_NOT_FOUND；关联文件不存在、状态/归属/用途不符404 FILE_NOT_FOUND；存储读取失败503 FILE_STORAGE_UNAVAILABLE，保留相册以便重试。客户端已取得的字节无法撤回。

**副作用与限制**

逐次检查商户未删除、图片属于商户正式相册、来源申请已通过且目标一致，然后通过file服务验证原上传人、RESTAURANT用途和BOUND状态，最后在数据库事务外读取私有云存储。商户软删除后列表/图片停止公开，关联与文件保留，不提供店铺恢复。停业商户仍可读取。普通餐厅和食堂共用此协议。

<a id="management"></a>

## 直接维护餐厅

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{id}/management` | 管理员 | 200 |
| POST | `/api/restaurants` | 管理员 | 201 |
| PUT | `/api/restaurants/{id}` | 管理员 | 200 |
| DELETE | `/api/restaurants/{id}` | 管理员 | 200 |

**请求参数**

直接维护接收restaurantType（REGULAR/CANTEEN）及supportsDineIn、supportsDelivery（true支持、false不支持、null未知）。管理页面始终显式提交类型及两个三态字段；提供类型时这三个字段完整替换，省略用餐方式等价于null，可将已有支持/不支持清为未知。食堂categoryCode必须为null，普通餐厅必须有有效分类；切换类型及分类在同一事务中保存。成功保存推进一次搜索投影版本，任何失败整体回滚。

兼容旧客户端：restaurantType省略或null且两个用餐方式均为null/省略时，新建为普通餐厅/用餐方式未知，更新保持已有这三个字段；省略类型却提交非null用餐方式返回400。非法类型400；未知、身份和申请UUID字段仍不接受。不改变用户投稿和审核的独立DTO及历史幂等摘要。

管理员直接新建食堂的示例（PUT同样支持）：

```json
{"name":"校内餐厅","restaurantType":"CANTEEN","categoryCode":null,"supportsDineIn":true,"supportsDelivery":false,"priceKind":"UNKNOWN","pricePerPerson":null,"address":"校内地址","latitude":30.7544,"longitude":103.9312,"locationConfirmed":true,"businessStatus":"OPERATING","tags":[]}
```

可选`photos`为按展示顺序排列的完整相册，最多1000项；省略或null保留相册，空数组清空。每项必须且只能提供`photoId`（本店已有正式关联）或`fileId`（当前管理员上传的RESTAURANT临时文件），ID均为字符串。首项成为封面，重复文件或跨店照片拒绝；如`[{"photoId":"801"},{"fileId":"902"}]`。资料、相册关联、文件绑定／释放及搜索Outbox在同一事务保存，失败整体回滚。更新成功响应丢失时，本店已绑定的当前管理员文件可用原fileId重试；不允许复用其他店或其他管理员已绑定文件。已有photoId及创建时间保持稳定；移除管理员图片登记媒体清理，移除历史投稿图片仅取消公开关联，保留申请图片。点评选图先读取JPEG，再独立上传RESTAURANT文件，不共享点评文件生命周期。新建无幂等键，超时须核对结果。

**响应**

| 接口 | 规则与响应 |
| --- | --- |
| `GET /api/restaurants/{id}/management` | 仅管理员可读，包含已删除记录；成功data为`{restaurant,deleted}` |
| `POST /api/restaurants` | 基础字段加restaurantType、supportsDineIn、supportsDelivery；可附带photos，不含申请UUID；201及Location，data为管理资料 |
| `PUT /api/restaurants/{id}` | 与直接新建相同字段，完整替换，成功200及管理资料；省略可选项按默认值处理，不能当PATCH |
| `DELETE /api/restaurants/{id}` | 无请求体/版本；软删除，成功200、deleted=true，不删除标签与统计，公开入口隐藏 |

管理资料中的restaurant字段与公开餐厅结构相同，响应禁止缓存。编辑不要求版本号，最后成功保存的整份资料及标签生效；事务锁住同一商户主行，不能部分保存或混合两次标签。新分类必须启用，当前停用分类可保持原值。删除保留原经营状态和历史关联，不提供恢复接口。

**错误码**

不存在404 `RESTAURANT_NOT_FOUND`；普通账号403 `AUTH_FORBIDDEN`；服务内部身份防线可返回403 `RESTAURANT_FORBIDDEN`。已删除编辑、重复删除409 `RESTAURANT_STATE_CONFLICT`。未知、内部或版本字段400 `COMMON_MALFORMED_REQUEST`。直接新建无请求幂等键，超时须先核对结果。

**副作用与限制**

这些接口均需有效Bearer及数据库当前管理员角色。普通用户即使是历史创建者也返回403 AUTH_FORBIDDEN，只能使用[用户商户申请](submissions.md#create-and-change)申请接口；创建者和操作者来自服务端认证上下文，不接受客户端身份。

<a id="admin-review-photos"></a>

### 管理员点评选图

`GET /api/admin/restaurants/{id}/review-photos?page=1`要求管理员及未删除的目标商户。page为1–834，每页12张，按点评发布时间、点评ID倒序及照片位置正序；返回`{records:[{reviewId,position,url,username,body,createdAt}],hasMore}`，url为`/api/reviews/{reviewId}/photos/{position}`。仅包含本店当前有效点评的照片，不暴露媒体文件ID。空列表200，页码非法400，商户不存在／已删除404，非管理员403；响应禁止缓存。图片读取和重新上传仍走原有受控媒体接口。
