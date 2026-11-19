# 食堂窗口与相册

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| GET | `/api/restaurants/{id}/windows` | 公开 | 200 | [公开资料](#public-read) |
| GET | `/api/canteen-windows/{id}` | 公开 | 200 | [公开资料](#public-read) |
| GET | `/api/admin/restaurants/{id}/windows` | 管理员 | 200 | [管理员维护](#management) |
| POST | `/api/admin/restaurants/{id}/windows` | 管理员 | 200 | [管理员维护](#management) |
| PUT | `/api/admin/canteen-windows/{id}` | 管理员 | 200 | [管理员维护](#management) |
| DELETE | `/api/admin/canteen-windows/{id}` | 管理员 | 204 | [管理员维护](#management) |
| POST | `/api/admin/canteen-windows/{id}/restore` | 管理员 | 204 | [管理员维护](#management) |
| GET | `/api/canteen-windows/{windowId}/reviews` | 公开 | 200 | [点评与互动](#reviews) |
| GET | `/api/canteen-windows/{windowId}/reviews/authors/{author}/history` | 公开 | 200 | [点评与互动](#reviews) |
| POST | `/api/canteen-windows/{windowId}/reviews` | Bearer | 200 | [点评与互动](#reviews) |
| GET | `/api/canteen-windows/{id}/photos` | 公开 | 200 | [管理员窗口相册](#album) |
| GET | `/api/canteen-windows/{id}/photos/{fileId}` | 公开 | 200 JPEG | [管理员窗口相册](#album) |
| POST | `/api/admin/canteen-windows/{id}/photos` | 管理员 | 200 | [管理员窗口相册](#album) |
| DELETE | `/api/admin/canteen-windows/{id}/photos/{fileId}` | 管理员 | 204 | [管理员窗口相册](#album) |

<a id="windows"></a>

## 食堂窗口资料、点评与汇总

<a id="public-read"></a>

### 公开资料

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/restaurants/{id}/windows` | 公开 | 200 |
| GET | `/api/canteen-windows/{id}` | 公开 | 200 |

**响应**

`GET /api/restaurants/{id}/windows`返回data数组，按floorNumber、数字ID升序，包含营业和停业窗口，排除删除项；必须是未删除的食堂。`GET /api/canteen-windows/{id}`返回单个窗口，窗口或父食堂删除均404 `RESTAURANT_NOT_FOUND`。没有窗口返回空数组，无评分返回null，真实零分返回0。成功200、no-store，公开GET无需Bearer。

单个data示例（接口使用十分制）：

```json
{"id":"12","canteenId":"901","canteenName":"第一食堂","campusCode":null,"name":"面食窗口","floorNumber":2,"description":"现煮面食","priceKind":"EXACT","pricePerPerson":15,"businessStatus":"OPERATING","deleted":false,"replayed":false,"coverUrl":null,"score":8,"taste":8,"value":8,"service":8,"ambience":8,"reviewCount":3,"participantCount":2,"address":"食堂地址","latitude":30.7,"longitude":104.1,"needsLocation":false,"createdAt":"2026-11-08T06:00:00Z","updatedAt":"2026-11-08T06:00:00Z"}
```

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

地址与坐标来自父食堂，campusCode当前为null；coverUrl为窗口相册首张图片的后端读取路径，无图或窗口已删除时为null。窗口图片见[管理员窗口相册](#album)，菜品尚无写入接口。业务ID为独立正Long字符串，窗口与餐厅即使数字相同也不能互换。

<a id="management"></a>

### 管理员维护

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/admin/restaurants/{id}/windows` | 管理员 | 200 |
| POST | `/api/admin/restaurants/{id}/windows` | 管理员 | 200 |
| PUT | `/api/admin/canteen-windows/{id}` | 管理员 | 200 |
| DELETE | `/api/admin/canteen-windows/{id}` | 管理员 | 204 |
| POST | `/api/admin/canteen-windows/{id}/restore` | 管理员 | 204 |

**请求参数**

所有`/api/admin/**`窗口接口要求当前数据库管理员角色；匿名401，普通用户403，不能由历史创建者代替管理员。管理集合`GET /api/admin/restaurants/{id}/windows`返回同一白名单，额外包含deleted=true的窗口；允许查看已删除食堂的管理集合，写入前必须恢复父食堂。

`POST /api/admin/restaurants/{id}/windows`输入示例：

```json
{"clientRequestId":"ad9166d8-a7b4-4055-8b93-274d16389102","name":"面食窗口","floorNumber":2,"description":"现煮面食","priceKind":"EXACT","pricePerPerson":15,"businessStatus":"OPERATING"}
```

name必填、去两端空白后1～40码点；floorNumber必填整数-10～100；description可空，空白转null，最多500码点。priceKind为UNKNOWN/EXACT/ESTIMATE，缺省UNKNOWN；UNKNOWN要求金额为空，其余要求1～5000整数元。businessStatus为OPERATING/CLOSED，缺省OPERATING。新建必须提供UUID；窗口不设分类，不接收categoryCode，也不返回categoryCode/categoryName；携带旧分类字段返回400 COMMON_MALFORMED_REQUEST。不接受评分、归属、操作者等未知字段。Bean Validation长度同时受Java字符串长度限制。

**响应**

创建及原请求重放均200，data为窗口详情，重放replayed=true；同一管理员UUID只允许相同规范化资料及所属食堂，冲突409 `COMMON_DATA_CONFLICT`。重试不会恢复已删除窗口或覆盖之后的编辑。`PUT /api/admin/canteen-windows/{id}`完整替换可维护资料，clientRequestId不参与更新；所属食堂不可修改，成功200。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

`DELETE /api/admin/canteen-windows/{id}`软删除，`POST /api/admin/canteen-windows/{id}/restore`恢复；均无请求体，首次及同状态重试204。删除停止公开、保留点评；恢复不改变原营业状态。不存在或父食堂不可写404，非法参数400 `COMMON_VALIDATION_FAILED`/`COMMON_MALFORMED_REQUEST`/`RESTAURANT_INVALID_INPUT`；500表示事务故障。维护操作与统计、搜索版本和Outbox同事务提交，失败整体回滚。

<a id="reviews"></a>

### 点评与互动

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/canteen-windows/{windowId}/reviews` | 公开 | 200 |
| GET | `/api/canteen-windows/{windowId}/reviews/authors/{author}/history` | 公开 | 200 |
| POST | `/api/canteen-windows/{windowId}/reviews` | Bearer | 200 |

**响应**

窗口点评列表、历史及发布使用总览中的独立窗口路由，参数和主体响应沿用[点评协议](reviews.md#reviews)：列表page/pageSize/sort，历史cursor/pageSize，发布clientRequestId/scores/recommendation/body/photoIds。发布要求Bearer，窗口营业且父层可见；本轮窗口首次发布与重放均200，data为`{"reviewId":"41","restaurantId":null,"windowId":"12","replayed":false}`。普通餐厅发布状态沿用原201/200。停业阻止新点评，返回409 `RESTAURANT_STATE_CONFLICT`；原成功请求可在停业后重放。直接新增食堂整体点评同样409，旧食堂整体点评仍可读和由本人删除。

**错误码**

发布UUID在作者的全部点评间唯一；同号餐厅和窗口属于不同目标，跨目标复用UUID返回409 `REVIEW_REQUEST_CONFLICT`，已删除请求返回409 `REVIEW_REQUEST_DELETED`。历史游标与对象类型、ID、作者、真实锚点绑定，跨类型复用400 `REVIEW_INVALID_CURSOR`。其他点评/图片错误沿用[点评](reviews.md#reviews)。

**副作用与限制**

本人和公开主页点评分组中的restaurant投影增加`targetType`（RESTAURANT/WINDOW）、`windowId`和`canteenId`；窗口组id为windowId，普通组保持原ID。同号对象分别计组、历史和评分。回复、照片、点赞及删除继续使用全局reviewId，不新建平行接口；回复和获赞消息源定位增加windowId，窗口目标restaurantId=null。窗口或父食堂删除后原内容不可公开读取。

<a id="scoring"></a>

### 评分与副作用

窗口内每位用户仅最新有效点评贡献分数，历史计入reviewCount；删当前点评回退上一条，删最后一条移除该用户贡献。父食堂总分及四维采用营业、未删除、有评分窗口均分的等权平均，零分计入、无分跳过，旧食堂整体点评不参与；人数与条数为有效营业窗口统计之和，人数不跨窗口去重。不计算或返回推荐率。没有计分窗口时分数null。

点评、图片绑定、窗口统计、食堂汇总、首次窗口点评奖励及搜索Outbox同事务提交；写入锁顺序为父食堂→窗口→点评等子资源。窗口维护亦使用父锁，使停业/删除与发评互斥。首次点评经验按每用户每窗口一次，使用WINDOW类型业务键；普通删评不退经验、不重领。详情、目录和地图SQL读同一统计；ES按现有Outbox最终一致性更新，不承诺立即可见。无公开手工改分接口。窗口资料统一由管理员录入和维护，当前不提供普通用户新增窗口、上传窗口资料或投稿审核接口；窗口点评及点评照片仍按本节开放。

<a id="album"></a>

### 管理员窗口相册

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/canteen-windows/{id}/photos` | 公开 | 200 |
| GET | `/api/canteen-windows/{id}/photos/{fileId}` | 公开 | 200 JPEG |
| POST | `/api/admin/canteen-windows/{id}/photos` | 管理员 | 200 |
| DELETE | `/api/admin/canteen-windows/{id}/photos/{fileId}` | 管理员 | 204 |

**请求参数**

窗口相册独立于点评照片，最多5张，按入册时间及文件ID排序，首张为封面。先由管理员调用[临时图片](files.md#temporary)上传`purpose=WINDOW`，再将临时文件回执入册；不能绑定REVIEW/RESTAURANT用途、其他人上传、已过期或已绑定到其他资源的文件。

| 方法 | 路径 | 认证 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/canteen-windows/{id}/photos` | 公开 | 200，data为相册数组，空相册为`[]` |
| GET | `/api/canteen-windows/{id}/photos/{fileId}` | 公开 | 200 JPEG，no-store、nosniff |
| POST | `/api/admin/canteen-windows/{id}/photos` | 管理员 | 200，data为当前完整相册 |
| DELETE | `/api/admin/canteen-windows/{id}/photos/{fileId}` | 管理员 | 204，无响应体 |

**响应**

POST只接受`{"fileId":"501"}`，fileId为正Long字符串。相同窗口和文件回执重复入册返回当前相册，不占用额外名额。首次入册及封面、文件绑定在同一事务提交，按父食堂→窗口→媒体行锁定；并发新增仍限制最多5张。成功data示例：

```json
[{"id":"501","url":"/api/canteen-windows/12/photos/501","cover":true}]
```

**错误码**

匿名管理操作401、普通用户403；非法ID或请求字段400；窗口/父食堂不可见或图片不属于窗口404；文件不可绑定409 `FILE_NOT_BINDABLE`；第六张409 `RESTAURANT_STATE_CONFLICT`；私有图片读取故障503 `FILE_STORAGE_UNAVAILABLE`。窗口停业仍允许管理员维护相册；相册不改变点评、评分或成长经验。当前不提供手动封面选择、重排、菜品图片或普通用户窗口资料上传。

**副作用与限制**

DELETE只解除本窗口的图片关联，并提交媒体待回收状态；云端文件由后台删除，失败继续重试。同窗口重复删除已移除的图片仍204，不操作其他窗口文件。删除首张后下一张接替封面，最后一张删除后coverUrl为null。窗口软删除保留相册，恢复后重新可读；窗口或父食堂已删除时公开相册及单图均404；已删除窗口需先恢复才能写入，父食堂已删除则不能写入且不提供食堂恢复。
