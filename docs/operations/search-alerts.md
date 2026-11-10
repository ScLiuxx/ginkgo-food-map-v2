# 检索告警运行说明

本地 Prometheus 已加载八条规则，在 `http://127.0.0.1:9090/alerts` 查看规则及排查信息；[Grafana检索与同步看板](grafana.md#告警区域)顶部也展示这些规则的等待/触发状态。规则仍由Prometheus统一计算，未接入 Alertmanager、邮件或其他消息推送，不会自动通知管理员。未部署线上，未嵌入业务管理端。同步指标定义见[同步观测](elasticsearch-sync-metrics.md)。

## 规则与范围

规则源文件：[restaurant-search.yml](../../deploy/monitoring/alerts/restaurant-search.yml)。以下均为本地演练的初始阈值，缺少生产流量基线，不是SLO承诺。

| 规则 | 条件 | 持续时间 | 级别 |
| --- | --- | --- | --- |
| GinkgoBackendScrapeUnavailable | 后端采集目标up=0 | 1分钟 | critical |
| GinkgoSearchCircuitOpen | 共享熔断状态为打开 | 1分钟 | warning |
| GinkgoSearchFallbackHigh | 最近5分钟计划走ES的列表请求至少20次，降级比例>20% | 2分钟 | warning |
| GinkgoSearchFailureHigh | 最近5分钟列表请求至少20次，最终失败比例>5% | 1分钟 | critical |
| GinkgoSearchLatencyHigh | 最近5分钟列表计时样本至少20次，整体P95>1秒 | 2分钟 | warning |
| GinkgoSyncBacklogOld | 存在待同步任务且最老年龄>120秒，成功快照距今<90秒 | 2分钟 | warning |
| GinkgoSyncFailedTasks | FAILED存量>0，成功快照距今<90秒 | 1分钟 | warning |
| GinkgoSyncObservationStale | 同步启用时，快照或成功扫描批次时间距今>90秒 | 1分钟 | warning |

列表指标不覆盖地图；共享熔断同时受列表与地图调用影响。所有计算按job和instance分别聚合，避免不同实例混合；业务告警仅在该实例up=1时评估有效，采集失败时避免陈旧业务指标产生重复判断。

降级比例的分母只包含route=es的已完成列表请求，主动SQL不稀释故障比例，地图降级不进入分子。最终失败统计outcome=error；ES失败但SQL成功属于success。整体延迟覆盖成功/失败及ES/SQL来源，先聚合直方图桶再计算P95；不计算各分位数的平均值。

最低次数使用increase估算，可能因边界外推出现小数；首次出现前的事件不能由新建计数器的rate/increase完整还原。它是抑制低流量噪声的门槛，不是精确审计次数。零流量或没有足够采样点时，比例和延迟不告警，不代表已证明服务健康。

## 状态与时延

- Inactive：当前条件未满足。
- Pending：条件满足，但尚未连续达到for要求。
- Firing：条件连续满足，告警生效。
- 条件不再满足，下一次评估回到Inactive；没有额外keep_firing_for。

采集间隔30秒，规则评估间隔15秒。状态变化还受首次采样、5分钟统计窗口、最低请求量及持续时间影响，不保证故障发生后恰好1或2分钟触发。15秒评估不会产生更密的原始样本。恢复后，熔断告警可较快解除，比例/延迟仍可能因为窗口内的旧故障样本继续生效，这是预期行为。

## 校验与加载

在仓库根目录执行，不打印或修改采集密码：

```powershell
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml config --quiet
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml run --rm --no-deps --entrypoint promtool prometheus test rules /etc/prometheus/alerts/restaurant-search.test.yml
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml up -d --no-deps prometheus
```

首次增加规则挂载需执行上述up重建Prometheus容器，命名卷保留。后续仅改规则内容可在校验后执行 `docker kill --signal=SIGHUP ginkgo-monitoring-prometheus-1` 重载；也可重启。当前未开放web生命周期重载接口。运行状态检查：`http://127.0.0.1:9090/api/v1/rules?type=alert`，应有8条规则且health=ok、lastError为空。容器健康检查仅代表进程就绪，不能替代规则健康检查。

规则目录只读挂载，rule_files只加载正式规则，不加载测试文件。测试不缩短生产规则中的for，使用模拟时间推进。告警标签只包含固定范围和实例等维度，不包含搜索词、用户或异常原文；注释提供当前值、看板地址和排查文档。看板地址是本机默认入口，部署其他环境时需同步替换。

## 采集失败

查看Targets的lastError，依次检查后端进程、8080端口、Docker到宿主机的网络、指标机器认证。401优先核对密码和开关。up=0代表采集失败，不能单独证明业务宕机。若目标从配置中移除或Prometheus本身停止，此规则无法自我监控，需后续外部监控补充。

## 熔断持续打开

查看共享熔断及ES调用失败指标，检查ES端点、超时和索引别名。恢复探测由真实请求触发：无流量时可能一直显示OPEN，不能解释成持续探测确认ES仍故障。恢复ES后发起正常检索，核对半开探测及关闭状态；不要为了消除告警直接重启后端清空熔断状态。

## 降级比例偏高

打开[检索看板](grafana.md)，核对ES_UNAVAILABLE和ES_CIRCUIT_OPEN来源，结合整体耗时、ES阶段耗时和实际数据来源检查ES故障。确认SQL兜底正常及数据库负载。ES恢复后等待新流量和统计窗口自然更新，不清空TSDB或临时提高阈值消除告警。

## 最终失败比例偏高

表示用户请求最终未获得正常结果，优先检查后端、数据库连接与查询错误。ES故障但SQL兜底成功不会触发本规则。当前未接入Logs/Trace平台，需要使用既有本机日志排障；不要在指标标签中加入异常正文。

## 查询延迟偏高

比较整体与ES阶段耗时、来源及降级面板；整体慢不一定是ES慢，还可能是数据库或应用资源问题。P95由桶估算，低流量波动较大；最低样本门槛不能消除所有统计误差。3秒HTTP响应上限不等于整个接口时限，详见[指标口径](elasticsearch-metrics.md)。

## 验证边界

规则测试覆盖正常/低流量/零流量、持续与短暂异常、触发及恢复、SQL成功不算最终失败、地图和主动SQL排除、实例隔离、采集失败抑制及计数器重置；另覆盖同步积压、失败及恢复、陈旧快照抑制、消费心跳陈旧和观测恢复。首次查询告警真实演练只停止本机指定ES并执行只读查询，使用finally恢复；不人为破坏数据库制造最终失败，结果见[告警演练报告](../history/elasticsearch-alert-drill-2026-11-10.md)。同步告警触发采用离线规则测试，真实同步行为在UUID隔离库/索引测试，见[同步观测报告](../history/elasticsearch-sync-metrics-2026-11-10.md)。

## 同步积压与失败

先核对快照年龄小于90秒，再查看待同步、到期、重试任务和最老等待。持续积压可能来自ES失败、退避等待或消费速度不足，结合处理结果与批次心跳排查。FAILED表示达到重试上限，本轮不新增自动重放接口；处理前核对脱敏last_error、来源版本与当前投影，不直接删除失败记录来消除告警。409可能是正常幂等重放，不单独告警。

## 同步观测陈旧

同步开启时，成功快照或成功扫描批次超过90秒未更新并持续1分钟触发。时间戳初始0表示尚未成功，不能当作1970年发生了同步。检查快照错误、批次扫描错误、长批次及共享调度线程；快照过旧会抑制积压/FAILED存量告警，避免旧值引起误判，但本规则仍提示观测异常。所有业务告警仍要求对应实例up=1；不支持同步多副本同时消费。
