# 使用统计

[API入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/api/me/activity` | 普通用户Bearer，排除管理员 | 200 JSON | [主动访问上报](#activity-report) |
| GET | `/api/admin/activity-statistics` | 管理员Bearer | 200 JSON | [汇总读取](#activity-read) |

<a id="activity-report"></a>

## 主动访问上报

**接口与权限**

`POST /api/me/activity`。要求有效JWT、Redis会话及数据库当前普通用户角色；具有管理员角色时拒绝，即使同时具有普通用户角色。

**请求参数**

无业务参数或请求体。账号与日期由服务端确定，不接收客户端自报账号、日期、路径或角色。前端仅在主动进入／返回用户端页面、登录完成进入用户端及从隐藏恢复可见时调用；没有定时心跳，后台轮询和单纯挂页不触发。

**响应**

成功200，统一响应的`data`为NULL，`Cache-Control: no-store`。未启用时也是成功空响应，不创建统计数据。

**错误码**

401 `AUTH_UNAUTHENTICATED`，403 `AUTH_FORBIDDEN`，503 `AUTH_SERVICE_UNAVAILABLE`（认证基础设施故障）或`ACTIVITY_STATISTICS_UNAVAILABLE`（活跃记录无法确认）。沿用通用响应格式，不暴露Redis错误详情。

**副作用与限制**

统计已启用时，将服务端认证账号加入北京时间当天的去重集合，重复上报不增加人数；不提供单个账号的访问记录查询。前端上报失败静默处理，下一次主动访问可再尝试，不因此中断浏览。前端触发属于尽力上报，服务端不能证明每次请求都来自真实人工操作，也无法追回未送达的访问。

<a id="activity-read"></a>

## 汇总读取

**接口与权限**

`GET /api/admin/activity-statistics`，要求当前管理员角色。响应禁止缓存。

**请求参数**

无日期、账号或分页参数；固定读取今天及前29个北京时间自然日。

**响应**

成功200，统一封装的`data`示例：

```json
{
  "enabled": true,
  "timezone": "Asia/Shanghai",
  "startedAt": "2026-11-19T06:00:00Z",
  "currentDate": "2026-11-19",
  "fetchedAt": "2026-11-19T06:32:00Z",
  "sevenDayActiveUserCount": 128,
  "sevenDayCollectionStatus": "PARTIAL",
  "days": [
    {
      "date": "2026-11-19",
      "activeUserCount": 128,
      "newUserCount": 12,
      "publishedReviewCount": 37,
      "collectionStatus": "PARTIAL",
      "activeSyncedAt": "2026-11-19T06:31:00Z"
    }
  ]
}
```

| 字段 | 含义 |
| --- | --- |
| `enabled` | 是否正式启用过；从未启用为false，暂停采集不抹除历史启用状态 |
| `timezone` | 固定`Asia/Shanghai` |
| `startedAt` | 首次启用UTC时刻，未启用为NULL；重启、暂停、升级不重置 |
| `currentDate` | 本次读取的北京时间日期，YYYY-MM-DD |
| `fetchedAt` | 本次读取UTC时刻，与活跃同步时间不同 |
| `sevenDayActiveUserCount` | 今天及前6天已采集账号的集合并集人数，无法确认时NULL；不累加日活 |
| `sevenDayCollectionStatus` | NORMAL完整、PARTIAL部分、UNKNOWN无法确认 |
| `days` | 升序日汇总，最多30项，启用前不返回行；历史未采集日期可能缺行或返回未知行 |

日汇总字段为`date`、`activeUserCount`、`newUserCount`、`publishedReviewCount`、`collectionStatus`、`activeSyncedAt`。计数为非负JSON安全整数或NULL；NULL表示未知，真实0仍为0。活跃计数和同步时间同时为空或同时有值；NORMAL要求三个计数已知。PARTIAL的已知数值只表示已确认采集的部分，不能当作完整统计。同步约60秒一次，因此今日活跃可能延迟；`fetchedAt`不保证所有采集结果都已同步。

从未启用时`startedAt:null`、`days:[]`、七日人数NULL且状态UNKNOWN。启用首日、启用不足7天、采集暂停／中断或已知数据丢失时保守标记不完整；关闭采集后仍可读取历史，七日并集返回NULL／UNKNOWN。前端缺失日期不补0、折线留空，首次失败可重试，后续刷新失败保留上次结果与原读取时间。

**错误码**

401／403及通用错误沿用通用约定；汇总无法读取、元数据非法或计数超出JSON安全整数范围时503 `ACTIVITY_STATISTICS_UNAVAILABLE`。Redis不可用但MySQL汇总可读时仍可返回200，保留已保存数字，七日并集返回NULL／UNKNOWN，受影响数据标为PARTIAL。

**副作用与限制**

只返回汇总，不返回账号集合。读取不增加活跃、新增或点评计数；读取过程中发现Redis运行标识变化时会持久标记受影响日期为部分数据。所有指标从正式启用后开始，不回补历史；删评不回扣曾发布数量。采集启用、故障边界及部署约定见[存储协作](../architecture/activity-statistics.md)。
