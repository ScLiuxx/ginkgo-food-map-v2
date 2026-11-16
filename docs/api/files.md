# 图片上传与读取

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

本文记录当前已实现的接口契约；示例中的 `data` 沿用统一响应封装，图片字节、204、SSE 和 Actuator 例外见各接口。

## 接口索引

| 方法 | 路径 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- |
| POST | `/api/files/images` | Bearer；WINDOW用途需管理员 | 201 | [临时图片](#temporary) |
| DELETE | `/api/files/images/{fileId}` | 上传者 | 204 / 202 | [临时图片](#temporary) |
| GET | `/api/reviews/{reviewId}/photos/{position}` | 公开 | 200 JPEG | [正式点评图与商户申请图](#review-photos) |

<a id="image-files"></a>

## 图片上传、绑定与读取

图片上传、取消及按用途读取在本分册维护。餐厅正式相册、申请私图和窗口相册分别由对应业务接口校验可见性，见各分册链接。

<a id="temporary"></a>

### 临时图片

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/api/files/images` | Bearer；WINDOW用途需管理员 | 201 |
| DELETE | `/api/files/images/{fileId}` | 上传者 | 204 / 202 |

**请求参数**

`POST /api/files/images?purpose=REVIEW|RESTAURANT|WINDOW`，Bearer、multipart/form-data、单文件字段`file`；省略purpose默认REVIEW，WINDOW用途额外要求管理员，普通账号403。成功201、禁止缓存，data：

只接受真实JPEG，单文件不超过300KiB、最长边1200px，multipart总大小不超过320KiB。服务端独立解码重编码，不信任文件名或Content-Type；PNG/WebP需由浏览器转换。前端原图限制20MiB/4000万像素。对象私有，不接收客户端Bucket、对象键或上传者，不返回OSS地址。

**响应**

```json
{"fileId":"501","byteSize":85600,"width":1200,"height":800,"expiresAt":"2026-11-04T06:00:00Z"}
```

`DELETE /api/files/images/{fileId}`只允许上传者取消未绑定文件。204无响应体表示完成；202表示删除意图已保存，云删除待后台重试。非法/他人文件404 `FILE_NOT_FOUND`；已绑定409 `FILE_ALREADY_BOUND`。文件清理先保存意图，再在事务外删除OSS，默认每60秒最多处理50条到期记录；BOUND不按临时期限清理。

**错误码**

错误：400 `FILE_INVALID_IMAGE`；413 `FILE_TOO_LARGE`；409 `FILE_NOT_BINDABLE`；503 `FILE_STORAGE_UNAVAILABLE`。上传、取消和绑定失败不会暴露OSS内部异常。

**副作用与限制**

图片默认24小时临时有效。REVIEW点评、RESTAURANT商家申请和WINDOW窗口相册不可交叉绑定；相同Bucket下分别使用`review-images/{UUID}.jpg`、`restaurant-images/{UUID}.jpg`和`windows-images/{UUID}.jpg`，临时绑定或审核通过不移动对象。响应丢失后重传不保证同一fileId，未绑定文件由到期清理兜底。配置与权限要求见[图片存储目录](../operations/deployment.md#图片存储目录)。

<a id="review-photos"></a>

### 正式点评图与商户申请图

**接口与权限**

| 方法 | 路径 | 权限 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/reviews/{reviewId}/photos/{position}` | 公开 | 200 JPEG |

**响应**

`GET /api/reviews/{reviewId}/photos/{position}`公开，position为0–4；先校验父店、点评、关联和REVIEW用途，再返回私有JPEG，禁止缓存及nosniff。隐藏/不存在404 `FILE_NOT_FOUND`，云不可用503。

**错误码**

[通用错误](common.md#errors)及本组协议中列出的业务错误适用。

**副作用与限制**

原商户申请图片通过[申请私有图片](submissions.md#admin-photos)管理员接口核对；审核正式发布后另通过[正式商户相册列表](restaurants.md#album)、[正式商户图片读取](restaurants.md#photo)的商户相册关联公开，不能用点评图片接口或任意文件ID读取。图片绑定、申请/点评及关联在各自数据库事务提交；正式发布与审核事务原子提交，BOUND本身不等于公开。已传给浏览器的字节无法撤回。

新店附带点评图片另走[本人附带点评图片](submissions.md#my-review-photos)，仅申请提交人且为点评作者可读，管理员无例外。该私有入口不因文件已BOUND而公开，也不复用正式点评图片入口。

[修改或重提附带点评](submissions.md#my-edit)可保留当前点评原有的BOUND图片，新图仍须本人有效的REVIEW临时上传；移除图进入PENDING_DELETE并由原清理流程处理。驳回和撤回保留私有内容与BOUND点评图，重提迁移同一条点评及图片关联，改用新申请ID读取。店铺申请图与点评图分开处理，店铺重提仍需新上传。
