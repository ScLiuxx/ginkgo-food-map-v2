# 状态、指标与代理入口

[API 入口](../api.md) · [通用约定](common.md#conventions) · [通用错误](common.md#errors)

应用接口、Actuator、Nginx和Vite代理各自提供不同响应格式，调用时按下表选择入口。

## 接口索引

| 方法 | 路径 | 提供方 | 权限 | 成功状态 | 详细协议 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/public/status` | Spring业务接口 | 公开 | 200 JSON | [应用状态](#status) |
| GET | `/actuator/health` | Actuator | 公开 | 200 JSON | [健康状态](#health) |
| GET | `/actuator/info` | Actuator | Bearer | 200 JSON | [应用信息](#info) |
| GET | `/actuator/prometheus` | Actuator | 管理员Bearer／可选采集Basic | 200 文本 | [指标](#metrics) |
| GET | `/version.txt` | 部署站点Nginx | 公开 | 200 文本 | [静态探针](#probes) |
| GET | `/nginx-health` | 前端Docker镜像Nginx | 公开 | 200 文本 | [静态探针](#probes) |
| GET | `/_AMapService/v3/place/text` | Vite开发代理 | 开发环境入口 | 200 高德原生响应 | [地点搜索代理](#amap-proxy) |

<a id="status"></a>

## 应用状态

**接口与权限**

`GET /api/public/status`，公开。

**请求参数**

无业务参数或请求体。

**响应**

成功200，统一封装的`data`为：

```json
{"application":"foodmap","architecture":"modular-monolith"}
```

**错误码**

沿用[通用错误](common.md#errors)。

**副作用与限制**

只确认应用可响应，不检查数据库、Redis或邮件依赖，不修改业务数据。

<a id="health"></a>

## 健康状态

**接口与权限**

`GET /actuator/health`，公开；Vite不代理Actuator，本地须直连后端。

**请求参数**

无业务参数。

**响应**

健康时200，Actuator原生JSON：`{"status":"UP"}`，不使用业务响应封装；依赖DOWN通常返回503。

**副作用与限制**

邮件健康检查验证SMTP连接、TLS及认证，不发送邮件。该接口不等同于应用状态或Nginx探针。

<a id="info"></a>

## 应用信息

`GET /actuator/info`需要Bearer，采用Actuator原生格式。认证失败沿用401／503约定；不将Actuator响应解释为业务`data`。

<a id="metrics"></a>

## 指标采集

`GET /actuator/prometheus`为内部指标端点，成功200返回Prometheus文本格式。支持管理员Bearer或按配置启用的独立采集Basic凭据；采集Basic不授予业务接口权限。

认证、暴露范围及错误响应见[独立采集认证](../operations/prometheus.md#独立采集认证)和[部署访问说明](../operations/deployment.md#prometheus指标采集端点)。指标口径分别见[检索指标](../operations/elasticsearch-metrics.md)及[Outbox同步指标](../operations/elasticsearch-sync-metrics.md)。

<a id="probes"></a>

## 静态探针

| 方法与路径 | 响应 | 范围 |
| --- | --- | --- |
| `GET /version.txt` | 40位提交号纯文本，`Cache-Control: no-store` | 部署站点Nginx提供，本地Vite及后端业务接口不提供 |
| `GET /nginx-health` | 纯文本`ok` | 前端Docker镜像Nginx提供，只检查Nginx，不检查数据库或后端 |

<a id="amap-proxy"></a>

## 地点搜索代理

**接口与权限**

`GET /_AMapService/v3/place/text`由Vite开发代理插件提供。凭据由服务端配置，不进入业务响应。

**请求参数**

| 参数 | 规则 |
| --- | --- |
| `keywords` | 必填，去空白后最多100字符 |
| `page` | 默认1，范围1–100 |

**响应**

返回高德原生格式，不使用业务响应封装。代理固定餐饮类型050000、每页10条、城市成都市、`citylimit=false`。

**副作用与限制**

选择高德门店不会自动写入本站餐厅，只有提交申请才持久化资料。配置及真实底图验收见[高德配置](../operations/amap-setup.md)。
