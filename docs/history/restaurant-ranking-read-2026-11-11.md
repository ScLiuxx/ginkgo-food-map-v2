# 餐厅榜单读取：MySQL基线与分阶段缓存测量

日期：2026-11-11。当前仅完成MySQL读取；Redis与Caffeine尚未接入，没有缓存提速结论。本报告衡量已生成快照的读取，不包含点评聚合、排名计算、图片下载或快照发布。

## 读取实现与验证范围

接口契约见[近期餐厅榜单读取](../api/discovery.md#rankings)。每次按校区、周期联合主键读取一行，解析JSON并组装公开字段；不逐店查询，不通过MyBatis二级缓存、Spring Cache、Redis或Caffeine复用结果。真实MySQL的EXPLAIN为`type=const`、`key=PRIMARY`、估计`rows=1`；估计行数不等同于实际IO次数。

读取区分尚未生成、成功空榜及结果过期；检查24小时数据年龄、时间区间、JSON格式和五分制，异常返回安全的503。SQL计数探针仅在测试上下文统计实际Statement prepare，正式链路无该探针。功能测试覆盖四榜隔离、大ID及微秒时间、公开GET/受保护写入路径、参数错误不查库、损坏快照、重复读取及提交后变更可见。

本步专项43项全部执行通过（36项单元、6项真实MySQL/HTTP功能、1项计时基准）。最终完整`mvn verify`共811项，执行341项、跳过470项，失败/错误0；真实MySQL专项默认跳过但已单独显式运行，不将两轮执行数量相加。构建打包通过。

## 环境、数据与方法

| 项目 | 实测条件 |
| --- | --- |
| 运行环境 | Windows 11 amd64，JDK 21.0.6，16个逻辑处理器；客户端、Spring Boot应用、MySQL同机 |
| 数据库 | MySQL 8.0.34，InnoDB buffer pool 128 MiB，Hikari最大10连接 |
| 数据 | 随机隔离库，4份合成快照，每份20项；两个校区×周/月，轮流等量访问 |
| 载荷 | MySQL四行JSON文本共17600字节（非物理磁盘占用）；示例完整HTTP响应3951字节 |
| HTTP | Java HttpClient，HTTP/1.1、连接复用、回环网络，匿名GET，经过实际Tomcat及安全过滤链 |
| 预热 | 各场景至少15秒且至少2000次；本轮实际为服务15500次、串行HTTP3500次、8并发HTTP37000次 |
| 正式采样 | 每场景5轮，每轮1000次，共5000次；三场景合计15000次，不计预热及功能测试请求 |
| 分位数 | 合并同场景五轮原始样本，使用nearest-rank计算P50/P95/P99 |

服务读取计时覆盖真实Service、MyBatis、连接池、MySQL往返、JSON解析、校验及公开对象组装；不含HTTP与响应序列化。HTTP计时从客户端发送到接收完整响应，包含网络、安全过滤链和响应序列化，不含计时之后的测试断言。闭环完成速率另外保存在原始结果中，包含夹具校验和线程管理，不能称为服务最大QPS。

初始500次预热的试跑出现明显逐轮加速，未作为正式基线；延长预热后保留全部五轮，不挑最快一轮。即使延长预热，同机HTTP测量仍有明显波动，尤其第五轮；后续每阶段必须复跑对照，不能把跨日期差值全部解释为缓存效果。

## MySQL读取实测

| 场景 | 平均ms | P50 ms | P95 ms | P99 ms | SQL SELECT / 请求 | 错误 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 服务读取，单线程 | 0.328 | 0.292 | 0.599 | 0.887 | 5000 / 5000 | 0 |
| 完整HTTP，串行 | 1.479 | 1.057 | 3.057 | 5.687 | 5000 / 5000 | 0 |
| 完整HTTP，8并发 | 2.191 | 1.642 | 4.424 | 12.708 | 5000 / 5000 | 0 |

五轮平均耗时范围：服务0.274～0.400ms，串行HTTP 0.904～2.770ms，8并发HTTP 1.554～4.408ms。原始每轮结果和全部延迟样本均保留。单线程与8并发反映不同排队和资源竞争，不能将两行直接计算为缓存提升。

本次只证明：在这些条件下可以每次一条SQL读取完整榜单；MySQL小表、热数据读取已经较快。后续Redis未必在本机单请求延迟上显著胜出，仍需如实报告延迟变化及数据库负载减少，不预设倍数。

## 后续阶段比较规则

| 阶段 | 当前状态 | 相对基线节省时间 |
| --- | --- | --- |
| MySQL | 已实现、已测量 | 对照基线，不记为优化收益 |
| MySQL＋Redis | 待实现与实测 | 待测，不填预估值 |
| MySQL＋Redis＋Caffeine | 待实现与实测 | 待测，不填预估值 |

- 保持同一响应、四份合成数据、校验与计时边界、预热、连接池、客户端、并发和每轮采样量；夹具摘要不含动态时间，便于跨阶段核对。原始文件记录环境、规则/数据摘要、Mapper摘要及工作区版本。
- 后续同一阶段复跑MySQL对照与新增缓存模式；先验证响应一致，再计算平均及P95差值。减少毫秒数=`对照耗时−缓存耗时`，减少比例=`(对照耗时−缓存耗时)/对照耗时`；若变慢保留负值，不删异常慢样本。
- 分别记录MySQL与Redis实际调用次数；热命中、首次未命中、过期、空榜、故障回源单列，不能把全部预热后的命中结果称作真实线上平均。
- 使用同样数据年龄和错误处理，不允许为了好看的数字绕过校验或偷偷给MySQL基线增加聚合与逐店查询。
- 聚合性能及生产容量另做实验。当前不是2核服务器实测，没有模拟真实用户流量，不能据此声称线上提速、生产QPS或支撑用户数。

## 复跑及证据

仓库根目录运行（需Maven、JDK 21、可创建隔离库的本地MySQL账号）：

```powershell
python backend/tools/benchmark_restaurant_rankings.py --local-config --output D:\eat\.codex-benchmarks\ranking-read\new-run
```

`--local-config`显式读取已忽略的开发配置，仅经子进程环境传递凭据，不打印内容；不使用该参数时设置`RANKING_TEST_DB_SERVER`（例如`jdbc:mysql://127.0.0.1:3306/`）、`RANKING_TEST_DB_USERNAME`及`RANKING_TEST_DB_PASSWORD`。URL限定回环服务器，库名由测试随机创建并严格清理；不使用开发业务库。只验证功能可增加`--integration-only`。专项测试默认不随普通`mvn verify`连接真实数据库，需显式启用。

本次证据根目录：`D:\eat\.codex-benchmarks\ranking-read\`。

- 正式基线：`20261111-mysql-warmed/mysql-only.json`，包含每次延迟、每轮SQL计数、预热量、环境、查询计划及摘要；`run.json`记录Git版本和未提交改动状态；`maven.log`记录专项执行。完成后另保存`source-hashes.json`核对本步后端源码（含尚未提交的文件），以及`verify.log`完整构建日志。
- 预热试跑：`20261111-mysql/`，保留但不纳入正式统计。

完整构建日志为`backend/target/ranking-read-verify.log`，另随正式基线证据归档；迁移及旧数据备份仍按[开发记录](../development.md)单独保存。

## 修改文件与职责

- HTTP与协议：[Controller](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/controller/RestaurantRankingController.java)、[查询DTO](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/dto/RestaurantRankingQuery.java)、[公开VO](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/vo/RestaurantRankingVo.java)，接入参数校验和公开字段；[安全配置](../../backend/src/main/java/com/ginkgo/foodmap/security/config/SecurityConfiguration.java)仅放行明确GET；[错误码](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/exception/RestaurantErrorCode.java)新增503。
- MySQL读取：[Service](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantRankingReadService.java)、[Mapper](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/mapper/RestaurantRankingSnapshotMapper.java)、[XML](../../backend/src/main/resources/mapper/restaurant/RestaurantRankingSnapshotMapper.xml)、[Entity](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/entity/RestaurantRankingSnapshotEntity.java)，单条查询读取完整快照；[JSON解析](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/support/RestaurantRankingSnapshotCodec.java)限制格式及公开输出。
- 参数与时间：[Properties](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/config/RestaurantRankingProperties.java)、[Configuration](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/config/RestaurantRankingConfiguration.java)、[application.yml](../../backend/src/main/resources/application.yml)，配置24小时数据年龄及可测试时钟。
- 验证：[单元测试](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantRankingReadServiceTest.java)、[真实MySQL/HTTP测试与基准](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantRankingReadMySqlIntegrationTest.java)、[脚本](../../backend/tools/benchmark_restaurant_rankings.py)，记录功能、耗时和SQL次数。
- 文档：[近期餐厅榜单读取](../api/discovery.md#rankings)、[计划](../plans/hot-restaurants.md)、[功能](../features.md)、[导航](../README.md)、[开发记录](../development.md)及本文同步已实现边界。本步无新增迁移；工作区原有V43及结构文档改动保留。

源码入口：[读取服务](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantRankingReadService.java)、[实际SQL](../../backend/src/main/resources/mapper/restaurant/RestaurantRankingSnapshotMapper.xml)、[隔离库功能及基准测试](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantRankingReadMySqlIntegrationTest.java)、[复跑脚本](../../backend/tools/benchmark_restaurant_rankings.py)。功能与缓存后续状态见[计划](../plans/hot-restaurants.md)。
