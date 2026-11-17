# 近期餐厅榜单：Caffeine两级缓存验证（2026-11-11）

本报告记录第三阶段完成时的实现与本机实测。前两阶段的[MySQL基线](restaurant-ranking-read-2026-11-11.md)、[Redis报告](restaurant-ranking-redis-2026-11-11.md)保留各自时间边界；这里的三模式比较全部来自本轮同一进程，不拿不同运行的绝对值相除。当前规则与后续工作见[榜单计划](../plans/hot-restaurants.md)，接口见[近期餐厅榜单读取](../api/discovery.md#rankings)。

## 实现与一致性

读取顺序为Caffeine→Redis→MySQL快照。Caffeine 3.2.4由Boot BOM管理版本，只在已有JVM内运行，不新增服务；最多保存四份已解析的完整展示对象及原统计区间、生成时间。内部记录和条目不可变，每次返回独立VO及条目，避免调用者修改共享缓存。

本机使用`expireAfterWrite`，默认30分钟，不因命中续期；业务时钟还会逐次检查数据年龄与短缓存有效期。本机命中不解析JSON、不拿加载锁、不访问Redis/MySQL。未命中后进入原来的全局锁，先复查本机，再读下层并锁内回填，16个并发冷请求仅一次GET及一次SELECT。

正常空榜照常缓存；无快照PREPARING最多5秒，Redis向本机回填携带原准备中到期时刻，SET耗时也不能让本机再多活5秒。每次本机读取都按原`windowEndAt`检查24小时数据年龄；刚好边界可返回，超过后不展示，UNAVAILABLE及故障不缓存。

发布继续共用同一把锁，四行MySQL实际提交后先清Caffeine、再DEL Redis，回滚保留两级旧结果。DEL失败仍须清旧本机缓存并绕过Redis，可将最新MySQL结果保存到本机，后续发布仍会清除；有限DEL修复不携带旧结果异步回填。与发布重叠、已经取得旧本机条目的在途请求可返回旧榜，不承诺跨请求的四榜原子切换。

单实例边界不变；没有为多实例增加失效广播。Caffeine不跨进程保留，启动时仍清四个固定Redis键。仅关闭Redis时也能Caffeine→MySQL且发布仍清本机；关闭Caffeine为Redis模式，两个开关均关闭为MySQL基线。不改变其他模块缓存、认证会话、接口参数或响应字段。

开发库没有写合成榜单，本轮无迁移。自动聚合、小时生成与前端页面仍未接入，没有提交、推送、部署或重启正式前后端。

## 同进程对照方法

- Windows 11 amd64、16逻辑处理器、Java 21.0.6、Spring Boot 4.0.9、Caffeine 3.2.4、MySQL 8.0.34、Redis 8.6.1；全部通过本机回环通信，共享开发机资源。Hikari最大10连接，InnoDB buffer pool 128MiB。
- 随机MySQL隔离库，四份合成快照×20项，规则及条目JSON共17600字节，HTTP响应3948字节；主键查询EXPLAIN为PRIMARY/const/rows=1。不测点评聚合、冷磁盘、生成写入或图片。
- 同一JVM切换MYSQL_ONLY、REDIS、CAFFEINE，夹具、公开投影、读取校验与HTTP响应一致。每模式每场景预热至少15秒且至少2000次，再六轮×1000次；六轮覆盖三种模式的全部排列，使每种模式在每个位置出现两次。
- 三个场景为服务串行读取、串行HTTP、8并发闭环HTTP。每组6000个正式延迟，共54000个样本，预热不计；分位数对全部原始样本采用nearest-rank，不平均每轮分位数。
- 服务场景不含HTTP/响应序列化；HTTP包含回环网络、安全过滤、序列化与完整响应接收。匿名请求，不含JWT会话读取；结果断言在单次计时之外。
- SQL探针统计真实SELECT，私有Redis的INFO commandstats差值统计实际GET/SET/DEL，计数在计时外。正式阶段测预热后的命中，不将冷加载或修复故障混入计时。

## 实测结果

单位为毫秒，各组6000次，错误均为0。

| 场景 | 模式 | 均值 | P50 | P95 | P99 |
| --- | --- | --- | --- | --- | --- |
| 服务串行 | MySQL | 0.399911 | 0.3384 | 0.8668 | 1.4767 |
| 服务串行 | Redis | 0.249862 | 0.2200 | 0.4207 | 0.6429 |
| 服务串行 | Caffeine | 0.000492 | 0.0004 | 0.0007 | 0.0036 |
| HTTP串行 | MySQL | 1.086437 | 0.9925 | 1.7522 | 2.6881 |
| HTTP串行 | Redis | 0.821216 | 0.6725 | 1.6460 | 2.6676 |
| HTTP串行 | Caffeine | 0.410303 | 0.3180 | 0.8519 | 1.7884 |
| HTTP 8并发 | MySQL | 1.903875 | 1.6888 | 3.3212 | 5.6378 |
| HTTP 8并发 | Redis | 2.964287 | 2.8834 | 4.3445 | 5.2810 |
| HTTP 8并发 | Caffeine | 0.707958 | 0.6104 | 1.4487 | 2.0847 |

逐阶段均值差值如下，百分比以同一行的前阶段为分母：

| 场景 | MySQL→Redis | Redis→Caffeine | MySQL→Caffeine总降幅 |
| --- | --- | --- | --- |
| 服务串行 | 节省0.150049ms（37.5%） | 节省0.249370ms（99.8%） | 99.9% |
| HTTP串行 | 节省0.265221ms（24.4%） | 节省0.410913ms（50.0%） | 62.2% |
| HTTP 8并发 | 增加1.060411ms（55.7%） | 节省2.256329ms（76.1%） | 62.8% |

每个场景实际调用：MySQL模式6000次SELECT、0次GET；Redis模式0次SELECT、6000次GET；Caffeine模式两者均为0。三场景合计，Caffeine热命中的18000次正式请求全部未访问MySQL或Redis，未发生SET/DEL。这不表示全系统数据库/Redis负载降为0，冷加载、发布及其他业务仍会访问它们。

六轮HTTP串行均值范围：MySQL 0.990～1.250ms、Redis 0.661～1.035ms、Caffeine 0.313～0.631ms；8并发分别1.760～2.126ms、2.754～3.558ms、0.585～0.985ms，保留波动。Redis模式在全局锁内执行每次网络GET，8并发仍比已预热MySQL主键查询慢；本机命中跳过该等待及网络往返，结果反映了既定方案的取舍。

服务本机均值0.000492ms约为0.492微秒，只有方法读取边界，不包含HTTP和序列化，容易受JIT、逃逸分析及计时粒度影响；不能称为接口耗时或以99.8%代表用户体验。对外介绍优先引用本轮HTTP边界及实际调用数，并注明4×20合成数据、预热、有限并发。未测线上命中率、最大吞吐、长稳、跨机网络、2核8G服务器容量或堆内存收益，不据此推定生产效果。

## 验证与证据

本轮显式专项87项全部执行，0失败/错误：读取单元36项、缓存单元及并发27项、真实MySQL/Redis/HTTP与基准24项。首次功能复跑不重复计入覆盖总数。

- 可控业务时钟验证30分钟写后到期、正常空榜、PREPARING跨层及慢SET不续期、24小时年龄边界、UNAVAILABLE及故障不缓存；修改返回VO或条目不污染后续请求。
- 真实线程验证本机热命中无需等待发布锁、16个冷读锁内复查并只加载一次、提交后两级清除、关闭Redis仍清本机、重启不继承旧本机或Redis结果。
- 真实MySQL/Redis以关闭/开启Caffeine两种配置复测整轮发布、旧轮拒绝、第二行写失败全部回滚保旧，以及beforeCommit阻塞下的冷读等待；不是只验证方法体顺序。
- 私有Redis临时拒绝DEL，验证真实MySQL已提交、Redis仍旧，但两级读取返回新快照，权限恢复后失效修复。暂停ALL 2秒时，关闭/开启Caffeine且本机未命中的回源分别耗时218/215ms，均小于1.5秒；恢复后重新加载。ACL在finally恢复，仅对私有临时实例执行。
- 真实HTTP检查本机命中响应与基线一致、保留no-store及公开字段边界；下层JSON损坏不影响有效本机命中。本机未命中后仍由原路径处理Redis坏值。

最终完整`mvn verify`共849项，实际执行368项、默认跳过481项，失败/错误为0，构建通过；与显式专项分别统计，不把重复执行累加。阶段变化见[开发记录](../development.md)。证据位于仓库外`D:\eat\.codex-benchmarks\ranking-caffeine\20261111\comparison\`：`mysql-redis-caffeine.json`含全部原始延迟、逐轮数据、环境、调用数及查询计划；`summary.json`为合并统计；`maven.log`为87项专项与故障耗时；`verify.log`为完整构建；`run.json`为运行时间、源码指纹及未提交状态。上一层`functional/`和`unit.log`仅为阶段复跑，私有Redis启动及清理证据单独留存。

复现入口（先启动可销毁的私有回环Redis；数据库账号须能创建隔离库，凭据仅经环境或忽略的本地配置读取，不打印）：

```powershell
$env:RANKING_TEST_REDIS_PORT = '16379'
python -X utf8 backend/tools/benchmark_restaurant_rankings.py --local-config --redis --caffeine --redis-faults --output D:\eat\.codex-benchmarks\ranking-caffeine\rerun
```

`--integration-only`只验功能；`--redis-faults`会暂停Redis及改变DEL权限，仅用于私有临时实例；不传`--caffeine`仍支持前两阶段入口。

## 本阶段修改文件

| 文件 | 用途 |
| --- | --- |
| [pom.xml](../../backend/pom.xml) | 引入Boot管理版本的Caffeine |
| [RestaurantRankingLocalCache.java](../../backend/src/main/java/com/foodmap/restaurant/cache/RestaurantRankingLocalCache.java) | 四条容量、写后过期、不可变展示对象、防御性返回及逐次年龄检查 |
| [RestaurantRankingCache.java](../../backend/src/main/java/com/foodmap/restaurant/cache/RestaurantRankingCache.java) | 本机快路径、锁内复查与回填、两级失效及Redis故障后的最新快照缓存 |
| [RestaurantRankingSnapshotCodec.java](../../backend/src/main/java/com/foodmap/restaurant/support/RestaurantRankingSnapshotCodec.java) | 携带原PREPARING到期时间，避免跨层短缓存续期 |
| [RestaurantRankingProperties.java](../../backend/src/main/java/com/foodmap/restaurant/config/RestaurantRankingProperties.java) | 本机开关、30分钟TTL及有效配置校验 |
| [application.yml](../../backend/src/main/resources/application.yml) / [application-test.yml](../../backend/src/test/resources/application-test.yml) | 默认启用两级缓存，普通测试保持关闭以维持原基线 |
| [缓存测试](../../backend/src/test/java/com/foodmap/restaurant/RestaurantRankingCacheTest.java) / [读取测试](../../backend/src/test/java/com/foodmap/restaurant/RestaurantRankingReadServiceTest.java) | 新增本机、并发、TTL与不可变性验证，适配原读取校验 |
| [真实集成测试](../../backend/src/test/java/com/foodmap/restaurant/RestaurantRankingReadMySqlIntegrationTest.java) | 两种缓存配置复测事务/故障，增加真实HTTP及三模式对照 |
| [基准入口](../../backend/tools/benchmark_restaurant_rankings.py) | 增加--caffeine、三模式元数据与完整样本归档 |
| [计划](../plans/hot-restaurants.md)、[近期餐厅榜单读取](../api/discovery.md#rankings)、[功能说明](../features.md)、[开发记录](../development.md)、[导航](../README.md)及本报告 | 同步当前状态、契约、验证结论与证据入口 |
