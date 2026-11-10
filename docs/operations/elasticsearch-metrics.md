# ES列表检索指标

告警规则、初始阈值和排查方法见[检索告警](search-alerts.md)，不重复定义指标或增加埋点。

当前代码已实现列表检索埋点，Prometheus本地采集配置及独立认证已接入，Grafana看板已提供配置，访问及启动见[看板说明](grafana.md)。通过[受保护的采集端点](deployment.md#prometheus指标采集端点)读取，[Prometheus运行说明](prometheus.md)包含启动、存储及访问配置；部分序列在首次请求后出现。

## 指标与口径

客户端连接等待为2秒，单次HTTP响应等待为3秒，socket与Rest5请求级响应超时同时设置。该限制不等于整个接口的总时限，SQL兜底和多阶段调用另需时间。真实无响应故障与修复复测见[演练报告](../history/elasticsearch-metrics-fault-drill-2026-11-10.md)。

| Micrometer名称 | 类型 | 标签与含义 |
| --- | --- | --- |
| `restaurant.search.requests` | Counter | `query_type`、`source`、`outcome`、`route`；每次进入列表服务最终记录一次 |
| `restaurant.search.duration` | Timer | 同请求计数标签；服务入口至返回/异常，包含校验、ES、映射及SQL兜底，不含完整HTTP链路 |
| `restaurant.search.es.duration` | Timer | `query_type`、`outcome`、`reason`；实际进入Reader的完整ES阶段，含检查、分析、搜索和解析 |
| `restaurant.search.results` | Counter | `query_type`、`source`、`empty`；仅成功返回结果，按完整总数是否为0区分 |
| `restaurant.search.fallbacks` | Counter | `scope=list/map`、`reason`；沿用已有降级计数，新增scope区分列表与地图 |
| `restaurant.search.es.requests` | Counter | `query_type`、`operation=field_caps/analyze/search`；列表应用层SDK调用尝试次数 |
| `restaurant.search.es.requests.per.query` | DistributionSummary | 与ES阶段Timer同标签；每个实际进入的ES阶段发起的SDK调用数量 |

固定标签含义：

- `query_type=browse/keyword`：无关键词浏览/关键词检索，不记录关键词本身。
- `source=es/sql/none`：实际返回的数据来源，最终失败为none。
- `outcome=success/error`：ES失败但SQL兜底成功时，整体success、ES阶段error；零命中是success。
- `route=es/sql/none`：计划使用ES、主动SQL、未完成路线判断。ES客户端缺失和熔断拒绝属于es；参数校验失败为none，不归因于ES故障。
- ES阶段`reason=none/timeout/connection/io/invalid_response/other`：有限异常分类，不记录异常正文。`invalid_response`包括响应不完整、索引能力异常、身份/版本或投影字段错误；ES服务端timed_out响应也在此类，客户端超时异常为timeout。
- 降级`reason`沿用接口枚举：列表为`ES_UNAVAILABLE`、`ES_CIRCUIT_OPEN`；地图另有`DATA_CHANGED`、`SYNC_PENDING`。主动关闭ES、位置条件不满足不算故障降级。

地图不进入新增列表指标；既有熔断指标由列表与地图共享：`restaurant.search.es.calls{outcome=success/failure/rejected}`，`restaurant.search.es.circuit.state`数值0正常、1打开、2半开、3其他。不新增另一套熔断状态序列。

请求次数是SDK方法调用尝试数，包含抛错的尝试，不是底层HTTP请求数；客户端内部重试不单独计数。熔断拒绝或客户端缺失未进入Reader时，没有ES阶段Timer或SDK调用样本。能力检查失败后未执行search，就只计一次field_caps。

两个Timer启用可聚合直方图，预期桶范围1毫秒至30秒；这不是请求超时设置，也不截断超出范围的总耗时。无预设SLO或告警阈值。指标写入异常不改变检索结果或原异常，未修改匹配、排序及降级策略。

## 看板查询示例

以下为Prometheus表达式，可在本地Prometheus查询页使用，[Grafana看板](grafana.md)按这些口径展示。多服务环境需按实际添加job/实例筛选；零分母显示无数据，不伪造为健康或故障。Counter导出带`_total`，Timer单位为秒。

```promql
# 列表QPS，包含最终失败与服务内校验失败
sum(rate(restaurant_search_requests_total[5m]))

# 成功返回中ES承担比例
sum(rate(restaurant_search_requests_total{source="es",outcome="success"}[5m]))
/ sum(rate(restaurant_search_requests_total{outcome="success"}[5m]))

# 列表总耗时P95；P50/P99替换0.95
histogram_quantile(0.95, sum by (le) (rate(restaurant_search_duration_seconds_bucket[5m])))

# ES阶段P95
histogram_quantile(0.95, sum by (le) (rate(restaurant_search_es_duration_seconds_bucket[5m])))

# 按原因观察降级量
sum by (reason) (rate(restaurant_search_fallbacks_total{scope="list"}[5m]))

# 计划走ES的请求中发生降级的比例，主动SQL不参与
sum(rate(restaurant_search_fallbacks_total{scope="list"}[5m]))
/ sum(rate(restaurant_search_requests_total{route="es"}[5m]))

# 最终失败率（排除未完成路线判断的校验错误）
sum(rate(restaurant_search_requests_total{outcome="error",route!="none"}[5m]))
/ sum(rate(restaurant_search_requests_total{route!="none"}[5m]))

# 成功关键词查询的零结果比例，不代表搜索相关性准确率
sum(rate(restaurant_search_results_total{query_type="keyword",empty="true"}[5m]))
/ sum(rate(restaurant_search_results_total{query_type="keyword"}[5m]))

# 每次实际进入ES阶段的平均SDK调用次数，包含失败阶段
sum(rate(restaurant_search_es_requests_per_query_sum[5m]))
/ sum(rate(restaurant_search_es_requests_per_query_count[5m]))
```

这些比例和分位数由采集后的时间序列计算，Java不额外维护QPS或P95。降级在转SQL时计数，总请求在退出时计数，短窗口或少量请求下比例可能暂时波动。
