# 本地测试与构建

Windows从仓库根目录使用[统一检查入口](../../tools/Check-Project.ps1)，选择与改动相应的任务。前后端测试、构建和浏览器验证串行执行；检查通过后不重复跑同范围用例。脚本不会启动或清理数据库、修改系统配置、停止开发服务，也不会自动重试失败。

```powershell
# 后端定向测试；多个类用逗号连接。
./tools/Check-Project.ps1 -Task BackendTest -Tests UserRegistrationServiceTest,AccountAccessServiceTest
# 同一轮测试并打包，无需先重复执行BackendTest。
./tools/Check-Project.ps1 -Task BackendVerify -Tests UserRegistrationServiceTest,AccountAccessServiceTest

# 前端定向测试；多个文件作为PowerShell字符串数组传入。
./tools/Check-Project.ps1 -Task FrontendTest -Tests @('src/modules/auth/model/useActivityReporting.spec.ts', 'src/modules/admin/api/activityStatisticsApi.spec.ts')
./tools/Check-Project.ps1 -Task FrontendBuild
# 发布前按范围需要时执行完整前端检查，已包含Lint、测试、类型检查及构建。
./tools/Check-Project.ps1 -Task FrontendCheck
```

集成测试仍需显式开启对应开关、提供隔离服务器及随机测试库配置；入口不自动启用或跳过集成测试。可通过`-MavenArguments @('-Dactivity.backend.integration=true')`传入开关，凭据使用本地环境变量，不写入命令、日志或仓库。不要将开发业务库、真实OSS或现有ES索引用于测试。

## 默认资源与输出

| 进程或资源 | 项目默认 |
| --- | --- |
| Maven进程 | `.mvn/jvm.config`：384MiB最大堆、2个可用处理器 |
| 后端测试JVM | 单fork复用、384MiB堆、2个可用处理器，JUnit不并行 |
| Spring测试上下文 | 最多缓存2个，避免连接池与客户端持续积累 |
| Mockito | 测试JVM启动时加载agent，路径随Maven仓库与依赖版本解析 |
| Vitest | 主进程768MiB堆、单worker、worker512MiB堆，文件不并行，保留逐文件隔离 |
| 类型检查／Vite构建 | 顺序执行，最大堆分别1536MiB／768MiB |
| 本地后端产物 | 入口使用`local-check`Profile，输出到`backend/target/local-check/` |
| CI后端产物 | 沿用`backend/target/`，不改变发布JAR名称 |

这些数值是进程堆上限，不是整个进程或整台电脑的内存保证；JVM元空间、线程、Node原生模块、Docker和浏览器仍占用内存。不要再为日常命令临时增加`MAVEN_OPTS`、`NODE_OPTIONS`或`-DargLine`；若专项检查确需调整，记录原因及实际结果。

统一入口用工作区级命名互斥锁阻止第二个检查同时启动，任务结束或进程退出自动释放。检查启动前要求至少1GiB空闲物理内存；普通测试另需2GiB可提交内存余量，前端构建／完整检查需3GiB。条件不足时直接说明原因，返回非零状态且不启动检查，不将其误报为测试失败。直接执行Maven或npm仍使用项目资源限制，但不经过互斥与系统内存检查；Windows本地优先使用统一入口。浏览器验证也应在入口任务结束后再运行。

## 故障分类

- 断言失败：查看JUnit／Vitest的具体用例，修复代码或与既有协议不符的测试夹具，再跑受影响范围；重试不会修复断言。
- Mockito初始化失败：检查测试JVM实际命令中的`-javaagent`。项目已显式配置，不依赖Java21的动态自附加，也不依赖某个开发者的固定磁盘路径。
- `Unable to rename ...jar ...jar.original`：属于Windows产物占用。统一入口独立输出，不需要为此停止正在运行的后端；默认`target`产物仍可能被手动启动的服务占用。
- 内存不足或JVM异常退出：保留终端日志、`target/local-check/surefire-reports/`和存在时的`hs_err_pid*.log`，检查实际系统可提交内存。普通Surefire的Manifest路径警告及`.dumpstream`文件本身不能证明JVM崩溃。

2026-11-19实测本机32GiB物理内存、分页文件固定400MiB且关闭自动管理，排查时系统提交量约29.5GiB、上限约32GiB；Windows事件2004记录了近期虚拟内存不足。空闲物理内存仍多也可能无法再承诺内存；仅限制测试堆无法解决整机提交上限不足。建议在Windows“高级系统设置 → 性能 → 高级 → 虚拟内存”调整分页文件，按系统提示重启后核对。自动管理会按需占用磁盘，不能保证高负载时流畅；本机C盘仅余约18GiB，D盘余约151GiB且位于同一NVMe盘，可优先考虑在D盘承载主要分页文件。该系统设置未由项目脚本修改，需用户确认，不能把项目配置修复等同于整机内存问题已消除。
