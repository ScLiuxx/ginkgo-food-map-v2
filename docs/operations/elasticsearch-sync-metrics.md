# ES 数据同步指标

同步指标已通过既有受保护的`/actuator/prometheus`导出，并直接加入[Grafana检索与同步看板](grafana.md)。仅当`app.search.sync.enabled=true`时注册；仍采用单实例串行Outbox消费，不改变事务、重试次数、确认或版本校验规则。

## 提交后唤醒与合并

业务事务同时写入Outbox并发布事件，只有提交成功才立即通知后台单工作线程；空闲唤醒后由后台等待固定200毫秒窗口，后续通知不延长截止时间，提交线程不等待窗口、MySQL扫描或ES请求。每30秒定时唤醒作为通知丢失、进程重启和到期重试的兜底，间隔仍为`app.search.sync.delay-ms`。通知合并为一个待处理标志，线程与排队容量均有界。空闲时兜底扫描由每分钟约12次降为2次；正常提交触发不受影响。重试延迟仍为30秒，到期后如无其他唤醒，通常再等待0～30秒扫描（不含排队和执行耗时）。30秒周期小于现有90秒观测陈旧阈值，暂不调整告警。

每次仍获取最多`batch-size`条到期任务，再按餐厅分组。例如同批捕获某餐厅v10、v11、v12，仅组装一次MySQL最新投影（版本至少为v12）作为一个操作放入Bulk，成功后逐条确认这三条任务ID。不会只处理最新任务而遗留旧任务，也不会顺带确认本批未捕获、尚未到重试时间或消费期间新增的任务。失败仍分别保存各任务的重试次数；ES成功但确认失败只保留该任务等待幂等重放。

首次空闲唤醒使用`coalesce-window-ms`固定窗口（默认200毫秒，可设0关闭），通知不反复重置计时。满批且存在已持久化的处理进展时立即接着消费，每轮最多10批后重新排入同一后台线程，不再等待窗口。未满批但消费期间又收到通知时，在下一次扫描前等待一个固定窗口；无新通知则结束。扫描异常或完全无持久进展时不自主热循环，等待后续通知/定时兜底。当前仍限单实例消费；没有强制refresh，因此提交后通知不承诺搜索结果提交即刻可见。

## Bulk写入边界

每批合并后的文档统一使用Bulk，即使只有一份也复用同一路径。保持最多20条原始任务、单请求并发；`bulk-max-bytes`默认1048576（1MiB），按当前SDK mapper实际序列化的未压缩NDJSON UTF-8元数据、正文及换行计数并拆分。单份文档超限明确失败，不反复拆分；发送前某餐厅组装/校验失败不阻止其他健康餐厅进入Bulk。当前保留逐餐厅MySQL快照读取，不在本轮改成批量SQL。

HTTP成功不等于所有item成功：逐项校验餐厅ID、状态和确认版本，分别确认或安排重试；一个请求超时或返回条数不符时，该请求的文档均不确认，按原失败预算重试。已成功但响应丢失可通过严格外部版本安全重放。409必须读取当前正文，核对ID、正文版本与元数据：更高版本可覆盖旧任务，同版本必须全文一致。核对失败只影响该餐厅，不放宽为external_gte。写入要求既有别名，不自动建索引；删除继续写墓碑，恢复继续用新版本。

ES成功但MySQL确认失败保留原任务和attempts，不调用fail消耗写入失败预算。大小拆分后的一个请求失败不跳过后续请求。现有attempts上限及30秒重试等待不变，不新增指数退避或历史任务清理。

## 数据存量与新鲜度

| Prometheus指标 | 含义 |
| --- | --- |
| `restaurant_search_sync_enabled` | 同步组件已启用时为1；没有该序列不等于空队列 |
| `restaurant_search_sync_queue_tasks{status="pending/failed"}` | PENDING及FAILED任务存量，不是商户数 |
| `restaurant_search_sync_due_tasks` | PENDING且失败次数未到上限、下次尝试时间已到的任务数 |
| `restaurant_search_sync_retry_pending_tasks` | PENDING且已有持久失败次数的任务，包括尚未到重试时间的任务 |
| `restaurant_search_sync_oldest_pending_age_seconds` | 采样时最老PENDING任务从创建开始的年龄，包含重试等待；空队列为0 |
| `restaurant_search_sync_snapshot_timestamp_seconds` | 最近一次成功数据库快照的应用时间戳；首次成功前为0 |
| `restaurant_search_sync_snapshot_errors_total` | 数据库快照失败次数 |
| `restaurant_search_sync_last_batch_success_timestamp_seconds` | 最近完成扫描及逐条处理的批次时间；空批次也更新，不表示所有任务成功 |

每30秒汇总一次，配置为`app.search.sync.metrics-delay-ms`，允许1秒至1天；默认延迟30秒开始第一次采样。修改采样周期时需同步调整告警中的90秒陈旧阈值。汇总只过滤PENDING/FAILED，不对SUCCEEDED历史全量统计，无数据库迁移；活跃任务大量积压时仍需关注聚合成本。最老年龄使用数据库时钟计算；时间戳用于应用与Prometheus之间的新鲜度判断，两端时钟应同步。

指标抓取只读取内存，不触发SQL。首次采样前存量为NaN，不能把未知状态展示成0；采样失败保留旧值和原时间戳并累计错误。看板显示快照距今，快照陈旧时不能直接使用旧存量判断当前积压。指标不读取或暴露商户正文、任务ID或搜索词。

消费由专用单工作线程执行，定时调度只唤醒消费者；采样仍用现有调度线程。长消费批次可能延迟批次心跳，调度线程繁忙可能延迟快照。时间戳年龄能反映这种延迟，但不是单独证明数据库故障。最老待处理年龄不等于真实端到端可见延迟，未新增ES refresh或写入后轮询。

## 消费事件

| Prometheus指标 | 含义 |
| --- | --- |
| `restaurant_search_sync_tasks_total{outcome}` | 每次实际任务消费记录一次；success、write_error、ack_error三个固定结果 |
| `restaurant_search_sync_task_duration_seconds_{count,sum,bucket,max}{outcome}` | 按原任务记录：同批共享Bulk及其他餐厅准备，再包含截至本任务确认的耗时；不包含Outbox排队或合并窗口等待，不等同于物理ES请求耗时 |
| `restaurant_search_sync_groups_total` | 实际尝试处理的餐厅分组次数，含写入失败组 |
| `restaurant_search_sync_coalesced_tasks_total` | 各组任务数减一的累计值，表示逻辑合并数，不等同于HTTP请求节省量 |
| `restaurant_search_sync_retries_total` | 消费前attempts>0的再次处理次数；不是安排重试数或全部幂等重放次数 |
| `restaurant_search_sync_version_conflicts_total` | ES写入返回409次数，包含最终判为成功的同版本重放或更新版本覆盖 |
| `restaurant_search_sync_state_update_failures_total{state="success/failure"}` | 希望保存成功/失败状态时，数据库抛错或影响行数不为1的次数 |
| `restaurant_search_sync_batches_total{outcome="success/error"}` | 完成批次与扫描/批处理异常次数；互斥拒绝的重入不算批次 |

`success`必须同时完成ES处理和本任务的数据库成功确认；`write_error`包括投影组装、身份/版本校验及ES写入失败；`ack_error`表示ES已成功但成功状态无法确认。失败状态本身保存异常另记state=failure，不把原写入失败误算成ES成功。

确认失败不会额外调用fail消耗失败预算，下一批仍可重放；此时attempts可能仍为0，因此不会计入retries。409计数不能直接用作错误告警，最终是否成功仍看tasks结果和Outbox状态。任务耗时直方图使用1毫秒至30秒的预期桶范围，不限制实际耗时；没有任务消费时P95无数据。固定标签提前注册为0，指标记录异常不改变业务结果。

Counter是当前进程累计量，后端重启归零；看板使用rate计算速率。Gauge是定期数据库存量快照，两者不能直接相减证明数据一致性。本轮未增加数据库与ES全文数据对账。

## Bulk请求指标

| Prometheus指标 | 含义 |
| --- | --- |
| `restaurant_search_sync_bulk_requests_total{outcome="response/error"}` | 实际Bulk调用数；response收到条数完整的响应，可包含失败item；error为传输/请求或响应条数异常 |
| `restaurant_search_sync_bulk_duration_seconds_{count,sum,bucket,max}{outcome}` | Bulk调用客户端耗时，含网络等待；不含组装、409读取核对和数据库确认 |
| `restaurant_search_sync_bulk_documents_{count,sum,max}` | 每次实际请求的文档数量，包含传输失败请求 |
| `restaurant_search_sync_bulk_bytes_{count,sum,max}` | 每次实际请求的未压缩NDJSON字节数，不含HTTP头 |
| `restaurant_search_sync_bulk_items_total{outcome="success/error"}` | 实际发送文档的处理结果；包含409核对结果及传输未知，不包括发送前失败 |
| `restaurant_search_sync_bulk_rejected_items_total` | 明确收到429的item数，是item error的子集，不包含请求级429 |

请求计时不包含409核对；物理请求与原任务数口径不同，不能相减证明同步一致性。没有任务时平均批量和P95无数据属于正常，未填0；指标只有固定标签，不使用餐厅ID或正文。任务计时包含其他餐厅准备和共享Bulk等待，不能当作独立ES请求耗时。

## 看板与告警

现有UID`ginkgo-es-search`保持不变，标题更新为“Ginkgo · ES 检索与同步监控”。向下滚动到“ES 数据同步（Outbox）”，查看启用状态、待同步/失败/到期/重试任务、最老等待、快照和批次年龄、处理结果、耗时、重试、409、落库/采样异常及同餐厅任务合并速率。整个看板共39个面板、49条查询，按同一实例筛选。

新增三条本地初始告警，顶部告警区域同时显示原五条及这三条，仍由Prometheus计算。积压超时、失败任务和观测陈旧的阈值、抑制及排查见[告警说明](search-alerts.md)；未接入消息推送。快照超过90秒时不再用旧存量触发积压/失败告警，由快照或心跳陈旧告警提示观测异常。

## 验证入口

单元测试覆盖成功确认、投影/ES失败、确认失败、重试、异常影响行数、批次扫描失败与恢复、未知/陈旧快照、采集不查库及指标故障隔离；真实MySQL/ES测试使用UUID隔离库和索引，覆盖存量/年龄SQL及现有同步、版本、墓碑与重放行为。验证结论与证据见[同步指标验证报告](../history/elasticsearch-sync-metrics-2026-11-10.md)。

提交后唤醒及合并行为的后续验证见[唤醒与合并报告](../history/elasticsearch-sync-wakeup-2026-11-10.md)。

固定窗口与Bulk后续验证见[Bulk验证报告](../history/elasticsearch-bulk-2026-11-10.md)。
