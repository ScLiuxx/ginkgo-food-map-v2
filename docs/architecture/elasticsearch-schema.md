# Elasticsearch商户搜索索引结构

物理索引`restaurant-search-v003`，别名`restaurant-search`。每家普通餐厅或食堂对应一份文档，`_id`为商户ID的十进制字符串，正文id为long。字段定义来源于[索引定义JSON](../../backend/src/main/resources/elasticsearch/restaurant-search-v003.json)。

## 索引设置

| 项目 | 当前定义 | 含义 |
| --- | --- | --- |
| 物理索引 | `restaurant-search-v003` | 固定当前字段与分词配置版本 |
| 业务别名 | `restaurant-search` | 业务访问名称 |
| 写索引标记 | `is_write_index=true` | 别名当前唯一指向v003 |
| 主分片 | 1 | 主分片数 |
| 副本 | 0 | 副本数 |
| 刷新间隔 | `1s` | 刷新周期 |
| 动态字段 | `strict` | 未声明字段直接拒绝，不自动扩展mapping |
| 数值转换 | 每个数值字段`coerce=false` | 拒绝字符串数字及整数金额的小数截断 |
| 文档原文 | 保留默认`_source` | 保留原始名称、数组和空值供核对 |
| 普通分页窗口 | 保留ES默认10000 | 默认结果窗口上限 |

## 字段定义

### 身份与关键词匹配

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `id` | `long` | `restaurants.id` | 商户身份；正文为数值，拒绝字符串数字；与`_id`一致 |
| `name` | `text` | `restaurants.name` | 商户名，主要全文匹配字段；保留原店名 |
| `name.exact` | `keyword`子字段 | 由name自动建立 | 规范化后完整店名匹配；不作为独立JSON字段提交 |
| `categoryName` | `text` | 分类字典name | 辅助关键词匹配；食堂固定为空 |
| `tags` | `text`，接受字符串数组 | 按position读取商户标签 | 辅助关键词匹配；无标签使用空数组，不上传标签关联行对象 |
| `address` | `text` | `restaurants.address` | 地名、道路等辅助匹配；未知为空 |

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

### 地理查询

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `location` | `geo_point` | 由latitude/longitude组装 | `{"lat":30.75,"lon":103.93}`，用于视口、距离筛选及距离排序；无有效位置时为空或省略 |
| `needsLocation` | `boolean` | `restaurants.needs_location` | 是否待定位；待定位不能填校园中心代替真实位置 |

### 评分与排序

| 字段 | ES类型 | 来源/计算 | 用途 |
| --- | --- | --- | --- |
| `ratingScore` | `double` | overall_score_sum / participant_count | 商户综合评分排序，使用后端0–10口径 |
| `recommendRate` | `double` | recommend_count / participant_count | 好评优先，0–1比例 |
| `participantCount` | `long` | participant_count | 计分人数 |
| `reviewCount` | `long` | review_count | 有效点评总条数 |

### 列表封面

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `coverPhotoId` | `keyword`，index=false、doc_values=false | 公开正式相册中position=0的restaurant_photos.id | 可空的正Long字符串，保留大整数精度；仅用于组装列表封面，不参与搜索、筛选和排序 |

### 删除与同步版本

| 字段 | ES类型 | 来源 | 规则与用途 |
| --- | --- | --- | --- |
| `deleted` | `boolean` | deleted_at是否非空 | 删除文档排除公开搜索，停业与删除分别处理 |
| `projectionVersion` | `long` | MySQL restaurants.search_projection_version | 每商户单调增长的投影版本，正数；用于防止旧任务覆盖新投影 |

## 子字段及空值含义

- name.exact为keyword子字段，使用restaurant_name_exact规范化器，过滤器为lowercase、asciifolding。
- name、categoryName、tags、address主字段使用ik_max_word索引分词与ik_smart查询分词；各自的.chars子字段只保留Han字符，使用长度1的ngram，index_options=docs、norms=false。
- categoryCode/categoryName为空表示无分类；supportsDineIn/supportsDelivery为空表示未知。
- 价格、位置、评分未知时为空，不以0代替；人数及条数无记录时为0，真实0分保留。
- tags无内容时为[]。未设置null_value；null及空数组不建立值词项。
- location为GCJ-02的lat/lon对象，ignore_malformed=false、ignore_z_value=false。
- coverPhotoId是正式相册关联ID字符串，仅保存在_source，index=false、doc_values=false。
- 删除墓碑文档只有id、deleted=true、projectionVersion三个字段。

## 文档示例

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
