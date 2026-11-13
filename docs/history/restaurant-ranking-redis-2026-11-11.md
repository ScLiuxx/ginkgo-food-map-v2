# 近期餐厅榜单：Redis阶段验证（2026-11-11）

时间边界：本报告记录第二阶段Redis Cache Aside完成时的实现与本机实测。第一阶段的独立运行保留在[MySQL基线报告](restaurant-ranking-read-2026-11-11.md)，不拿两个不同进程的数字直接计算提升。当前规则和后续待办以[榜单计划](../plans/hot-restaurants.md)为准。

## 已实现与边界

读取顺序为Redis→MySQL快照，返回契约不变；没有Caffeine或其他本机榜单结果缓存。Redis String保存完整元数据、规则对象和原始条目数组，不使用ZSET。仅操作两个校区×周/月四个合法键。

正常结果及成功空榜缓存2小时，准备中5秒；命中不续期，正常TTL不超过24小时数据年龄的剩余时长，每次命中再次检查。单实例一把固定ReentrantLock覆盖Redis读取、MySQL回源、回填及发布，默认等锁1秒。榜单专用连接复用现有Redis地址、认证、DB与Netty资源，连接/命令超时各200毫秒，不改认证连接工厂。

内部发布服务只接收已计算的同轮四榜，一个短事务真实提交后失效Redis；任何写入失败四行回滚，保留旧缓存。较早统计截止时间不能覆盖较新快照。DEL失败标记绕过Redis，读最新MySQL，默认5秒重试、3次后冷却1分钟；启动清理固定四键，首次读取也有门禁，恢复前不使用残留值。没有公开写接口，聚合、生成任务与页面尚未接入。

本地开发库未写入合成榜单、未再次升级结构。测试使用随机MySQL隔离库和仅回环访问、无持久化的私有Redis，结束清理；未操作登录会话或验证码。没有发布前后端或提交代码。

## 对照方法

- Windows 11 amd64，16个逻辑处理器，Java 21.0.6，Spring Boot 4.0.9；MySQL 8.0.34、InnoDB buffer pool 128MiB，Hikari最大10连接；Redis 8.6.1。
- 应用、客户端、MySQL、Redis均在本机，通过回环通信，共享开发机资源。MySQL按校区/周期联合主键读取一行，EXPLAIN为PRIMARY/const/rows=1。
- 隔离库4份合成快照，每份20条，MySQL两列JSON合计17600字节；HTTP响应3948字节。只测已生成结果的读取，不测点评聚合、小时刷新、冷磁盘或图片。
- 同一JVM、相同夹具与公开投影，切换MYSQL_ONLY/REDIS。每种模式每个场景先预热至少15秒且至少2000次，然后5轮×1000次，偶数轮反转模式顺序。正式样本共30000次，预热不计入结果。
- 服务场景只计业务读取；HTTP场景包含回环网络、安全过滤、序列化与完整响应接收，使用匿名请求，无JWT会话读取；结果校验不计入单次延迟。8并发为闭环有限并发，不是最大吞吐压测。
- SQL探针统计真实SELECT；私有Redis的INFO commandstats前后差统计GET/SET/DEL，统计发生在计时外。热缓存正式计时期间没有SET/DEL。
- 表中分位数由每组全部5000个原始延迟按nearest-rank计算，不对五个分位数取平均；逐轮全部保留。两模式均使用相同解析、校验、舍入和接口代码。

## 实测结果

单位为毫秒；均为本次同进程对照，错误数为0。

| 场景 | 模式 | 均值 | P50 | P95 | P99 |
| --- | --- | --- | --- | --- | --- |
| 服务串行 | MySQL | 0.243389 | 0.2012 | 0.4336 | 0.6130 |
| 服务串行 | Redis | 0.189706 | 0.1898 | 0.2628 | 0.3745 |
| HTTP串行 | MySQL | 0.927658 | 0.8357 | 1.5509 | 2.0418 |
| HTTP串行 | Redis | 0.495273 | 0.4438 | 0.8140 | 1.1005 |
| HTTP 8并发 | MySQL | 1.249386 | 1.1539 | 1.8575 | 2.9589 |
| HTTP 8并发 | Redis | 1.934709 | 1.8620 | 2.7879 | 3.5453 |

按`MySQL均值−Redis均值`计算，服务串行节省0.053683毫秒（22.1%），HTTP串行节省0.432385毫秒（46.6%）；8并发**增加**0.685323毫秒（54.9%），不能概括为Redis全面提速。五轮均值范围：服务MySQL 0.222～0.283、Redis 0.166～0.206；HTTP串行MySQL 0.849～1.030、Redis 0.489～0.509；8并发MySQL 1.199～1.291、Redis 1.803～2.129。

每个场景5000次：MySQL模式5000条SELECT、0次Redis GET；Redis模式0条SELECT、5000次GET。三场景合计热读取把15000条SELECT替换为15000次GET；这表示本次热命中期间数据库读取减少100%，不等于整个系统数据库负载降低100%，也不涵盖冷加载、故障或生成写入。

MySQL基线已经只是四份小JSON的主键查询，页与连接池均预热，没有重复聚合。Redis仍有一次网络通信及JSON解析；当前全局锁还串行覆盖热GET，等待计入请求耗时，8并发中体现了该代价。HTTP也受JIT、调度、序列化和本机资源竞争影响，不能将差值全部归因于数据库耗时。为遵循既定单锁规则，本阶段保留机制并如实记录；下一步接Caffeine后重新做三模式对照，不能预先承诺收益。

本结果不是2核8G服务器或生产容量结论，未测最大吞吐、长期稳定性、跨机网络、命中率、真实用户数据或内存收益。历史基线运行的绝对值与本轮不同属于独立运行边界，不能挑选较慢旧值作为分母。

## 正确性与真实故障

- 专项共67项，实际执行67项，0失败/错误：原读取单元36项、缓存单元/并发15项、真实MySQL/Redis及基准16项。后者包含原MySQL/HTTP功能6项、Redis功能7项、真实故障2项、同进程基准1项。
- 验证完整JSON与公开响应一致、正常空榜与PREPARING不同TTL、命中不续期、年龄及剩余TTL边界、坏/跨榜缓存回源、16请求冷缓存只SELECT一次、等锁超时及中断、启动清理、有限重试和冷却。
- 在真实MySQL事务beforeCommit处阻塞：缓存仍为旧值，其他连接仍读旧快照，服务读者等待；释放提交后读者得到新结果。第二行写入注入失败：四行全部回滚，旧Redis保留。不是仅以方法体执行顺序推定事务边界。
- 私有Redis临时拒绝DEL：发布事务仍成功，Redis确实保留旧值，但读取绕过旧缓存并返回新MySQL；恢复DEL后有限重试清理，恢复首次回源、再次热命中。
- 私有Redis真实暂停ALL 2秒：请求208毫秒返回最新MySQL结果，满足小于1.5秒的断言；恢复后完成失效修复并重新缓存。ACL在finally恢复，不在共享业务Redis执行此故障。
- 最终完整`mvn verify`：835项，执行356项、默认跳过479项，0失败/错误，构建成功。显式专项与完整检查分别记录，不把复跑简单相加。

## 文件职责与复现

本阶段在上一阶段未提交的MySQL读取代码上增量实现；下表列出本阶段新增或修改的文件，不把V43建表等此前成果重复算作本轮变更。

| 文件 | 用途 |
| --- | --- |
| [RestaurantRankingCache.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/cache/RestaurantRankingCache.java) | 单锁Cache Aside、绕过标记、失效、重启门禁与限速修复 |
| [RestaurantRankingRedisStore.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/cache/RestaurantRankingRedisStore.java) | 专用连接生命周期与String GET/SET PX/明确键DEL |
| [RestaurantRankingKey.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/support/RestaurantRankingKey.java) | 固定四榜标识、键后缀与周期校验 |
| [RestaurantRankingConfiguration.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/config/RestaurantRankingConfiguration.java) | 时钟和带独立超时、共享线程资源的Redis装配 |
| [RestaurantRankingProperties.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/config/RestaurantRankingProperties.java) | 缓存开关、TTL、超时、修复参数及配置校验 |
| [RestaurantRankingSnapshotCodec.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/support/RestaurantRankingSnapshotCodec.java) | MySQL/Redis共用读取校验、完整JSON编码与公开投影 |
| [RestaurantRankingReadService.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantRankingReadService.java) | 接入协调器，保留状态及安全503语义 |
| [RestaurantRankingSnapshotPublishService.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/service/RestaurantRankingSnapshotPublishService.java) | 同轮四榜校验、实际事务提交后失效、回滚保旧与旧轮拒绝 |
| [RestaurantRankingCacheMaintenanceJob.java](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/job/RestaurantRankingCacheMaintenanceJob.java) | 就绪清理及定时有限修复，不生成或异步回填 |
| [Mapper](../../backend/src/main/java/com/ginkgo/foodmap/restaurant/mapper/RestaurantRankingSnapshotMapper.java) / [SQL](../../backend/src/main/resources/mapper/restaurant/RestaurantRankingSnapshotMapper.xml) | 增加内部事务upsert，无新表迁移 |
| [application.yml](../../backend/src/main/resources/application.yml) / [application-test.yml](../../backend/src/test/resources/application-test.yml) | 默认启用榜单Redis，普通测试默认关闭真实连接 |
| [RestaurantRankingCacheTest.java](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantRankingCacheTest.java) | 缓存、并发和恢复单元验证 |
| [读取单元测试](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantRankingReadServiceTest.java) / [真实集成测试](../../backend/src/test/java/com/ginkgo/foodmap/restaurant/RestaurantRankingReadMySqlIntegrationTest.java) | 适配共用投影、真实读写/故障与同JVM对照基准 |
| [benchmark_restaurant_rankings.py](../../backend/tools/benchmark_restaurant_rankings.py) | 显式隔离实例入口、运行元数据与完整样本留存 |
| [计划](../plans/hot-restaurants.md)、[近期餐厅榜单读取](../api/discovery.md#rankings)、[功能说明](../features.md)、[开发记录](../development.md)、[导航](../README.md)与本报告 | 更新实现状态、契约、验证及证据索引 |

示例入口（须自行启动私有、可销毁的Redis，数据库账号须能创建隔离库；密码仅取环境或忽略的本地配置，不写入日志）：

```powershell
$env:RANKING_TEST_REDIS_PORT = '16379'
python -X utf8 backend/tools/benchmark_restaurant_rankings.py --local-config --redis --redis-faults --output D:\eat\.codex-benchmarks\ranking-redis\rerun
```

`--integration-only`只验功能；不传`--redis`仍可运行MySQL基线。`--redis-faults`会暂停实例及调整DEL权限，只用于私有临时实例。源码及运行参数以本次原始证据为准，后续代码变化须重新运行。

原始证据保留在仓库外`D:\eat\.codex-benchmarks\ranking-redis\20261111\`：`comparison/mysql-redis.json`保存全部样本、逐轮结果、实际调用数、环境与查询计划；`summary.json`保存由原始样本合并的统计；`maven.log`为67项专项及故障耗时；`verify.log`为完整构建；`run.json`包含运行时间、源码指纹与未提交状态。`functional/maven.log`为首次功能复跑，不能另加进覆盖总数。临时Redis启动记录单独保留，完成后关闭该实例。
