# 帮我选一家与餐厅榜单

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/api/restaurant-picker/candidates` | 公开 | 200 | [帮我选一家候选查询](#picker) |
| GET | `/api/restaurant-rankings` | 公开 | 200 | [近期餐厅榜单读取](#rankings) |

<a id="picker"></a>

## 帮我选一家候选查询

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/restaurant-picker/candidates` | 公开 | 200 |

**请求参数**

`POST /api/restaurant-picker/candidates`允许匿名读取，仅查询现有数据，不保存位置、偏好或本轮历史，不创建数据库/Redis会话。业务规则见[选店计划](../plans/restaurant-picker.md)。请求体：

```json
{
  "range": "QINGSHUIHE",
  "distanceMeters": 3000,
  "budget": "30",
  "minRating": "3.5",
  "category": "ANY",
  "diningMode": "ANY",
  "excludeIds": ["101", "102"],
  "checkIds": []
}
```

| 字段 | 约束及含义 |
| --- | --- |
| range | 必填；QINGSHUIHE、SHAHE或USER；校区中心复用服务端`app.ranking.qingshuihe/shahe`经纬度，距离上限由本请求决定 |
| distanceMeters | 必填整数；仅1000、3000、5000，含边界 |
| budget | 必填字符串；ANY、15、30、50、100；显式ANY才不限，未知价格不满足明确上限 |
| minRating | 必填字符串；ANY、3、3.5、4；五分制综合口碑分的最低门槛，含边界。明确门槛时排除暂无评分，ANY允许暂无评分；比较现有`reputation_score`原始精度，不以展示的一位小数或十分制均分比较 |
| category | 必填；ANY或当前启用的普通美食分类编码，不接受食堂/停用/不存在编码 |
| diningMode | 必填；ANY、DINE_IN、DELIVERY；明确方式只匹配对应字段为true的店，未知不满足 |
| latitude、longitude | USER时必须同时提供有效GCJ-02坐标，校区范围不接受自定义坐标；不写入账号或业务表 |
| excludeIds | 可省略为空数组，不可null；最多50000个合法Long范围正整数字符串；包括本轮已展示与已预取ID，重复ID按集合处理 |
| checkIds | 可省略为空数组，不可null；最多5个合法ID；非空进入复核模式，去重并保留请求顺序 |

**响应**

`data`示意（空结果示例）：

```json
{"records":[],"matchedCount":0,"remainingCount":0}
```

`records`采用公开餐厅对象，与目录相同的字段白名单，包含`coverPhoto`、标签和`distanceKm`。仅抽中的至多5家补充完整卡片；同次ID、资料、计数使用REPEATABLE_READ只读快照。

- `matchedCount`：本次快照满足全部硬条件的餐厅总数，包含已被excludeIds排除的合格项。
- `remainingCount`：抽取模式中，排除excludeIds及本次返回项之后的数量；复核模式中，仅排除excludeIds之后的数量。不是实时永久总数，数据变化后下一次查询可能变化。
- `checkIds`非空时只返回其中仍符合本次条件的店，不受excludeIds影响，不补入随机餐厅；未返回的ID表示当前不存在、不可见或不再符合条件，不能当作接口故障。
- 前端预取和已展示ID共同排除；批次只读无消费副作用，失败重试不会消耗服务端记录。客户端保存历史确保本轮不重复，任意改写排除列表不代表服务端承诺独立维护会话。

**错误码**

成功含空结果均为200；缺必选项、非法枚举/距离、错误坐标、非法ID/数组规模等返回400 `COMMON_VALIDATION_FAILED`，JSON类型/语法错误400 `COMMON_MALFORMED_REQUEST`；停用/不存在类别或越过Long范围的ID返回400 `RESTAURANT_INVALID_INPUT`。数据库或未预期故障500，不能当成无候选。其他方法/路径不因该POST公开而放开。公开调用不附带过期Bearer，主动提供无效Bearer仍可能401。

**副作用与限制**

服务端强制普通餐厅、未删除、标注经营中与可靠位置，同时应用全部硬条件；评分门槛在抽样前执行，复核模式同样检查当前评分。抽取直接访问完整合格ID集合，不受目录10000条窗口或地图100家上限影响；采用部分Fisher–Yates从排除后的集合均匀、不放回抽取至多5家，不按评分或评价数加权。

<a id="rankings"></a>

## 近期餐厅榜单读取

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurant-rankings` | 公开 | 200 |

**请求参数**

`GET /api/restaurant-rankings?campusCode=QINGSHUIHE&periodType=WEEK`，公开、200，无需登录；主动携带无效Bearer仍按通用认证规则拒绝。仅此GET公开，写入、生成及嵌套管理入口未提供。读取已生成的榜单快照，不在请求内生成榜单或重新聚合点评。响应沿用安全链的`Cache-Control: no-store`，它不禁止服务端缓存。

| 参数 | 必填 | 值 |
| --- | --- | --- |
| campusCode | 是 | `QINGSHUIHE`清水河、`SHAHE`沙河，精确大写 |
| periodType | 是 | `WEEK`近168小时、`MONTH`近720小时，精确大写 |

周期内至少1名有效计分用户、四维加权原始分严格大于五分制3.8才进入候选；再按人数平滑分排序取前20，资格不比较展示舍入值或最终平滑分。权重与同分排序保持不变，历史快照按生成时的规则读取，新规则从下一轮成功生成生效。不分页，最多20项；不接受客户端控制来源、状态、条数或有效期。缺少参数或枚举错误400 `COMMON_VALIDATION_FAILED`，不访问快照表。

**响应**

成功data示例（为说明格式的合成数据）：

```json
{
  "campusCode": "QINGSHUIHE",
  "periodType": "WEEK",
  "status": "READY",
  "scoreScale": 5,
  "windowStartAt": "2026-11-04T07:00:00Z",
  "windowEndAt": "2026-11-11T07:00:00Z",
  "generatedAt": "2026-11-11T07:00:30Z",
  "items": [
    {
      "rank": 1,
      "restaurantId": "9007199254740993",
      "name": "示例餐厅",
      "coverUrl": null,
      "tasteScore": 4.5,
      "recommendationScore": 4.1,
      "participantCount": 8
    }
  ]
}
```

- `READY`：存在有效快照；`items=[]`表示成功生成但无合格餐厅。
- `PREPARING`：尚无快照；`items=[]`，三个时间字段均为null。新版服务就绪后异步生成首轮结果，首轮完成前或关闭自动生成且没有快照时返回此状态。
- `UNAVAILABLE`：已有快照的数据年龄超过`app.ranking.max-snapshot-age`（默认24小时）；保留三个时间字段，`items=[]`，不展示过期条目。数据年龄以`windowEndAt`计算，刚好24小时仍可用，超过才失效；不因生成较晚或读取而续期。

`tasteScore`是五分制口味均分，读取时HALF_UP保留一位，**不得再次除以2**；recommendationScore为对应周期平滑推荐分，HALF_UP一位，用于推荐榜主分展示，与快照排序依据一致；它不是首页长期reputationScore。原始分子分母、规则及用户明细不输出。封面为本站稳定照片路径`/api/restaurants/{restaurantId}/photos/{photoId}`或null，访问图片时仍走既有公开可见性校验。ID为正Long字符串，人数为整数。

**错误码**

三个业务状态均返回200且保持区分；需要回源且数据库不可用、MySQL快照时间元数据非法、可展示快照的JSON格式损坏或版本不支持，以及加载等锁超时返回503 `RESTAURANT_RANKING_UNAVAILABLE`，不伪装为正常空榜，也不输出内部JSON或SQL。Redis坏值会清理并回源，Redis异常本身不导致请求失败，有效本机结果仍可读取。超过年龄上限后不再解析旧条目。返回顺序沿用快照名次，不在读取时重新排序。

**副作用与限制**

GET可回填缓存，不写MySQL、不刷新快照时间或触发榜单生成。缓存、发布和自动刷新机制见[榜单架构](../architecture/restaurant-ranking.md)。
