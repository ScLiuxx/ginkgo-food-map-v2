# Grafana检索看板

本地入口`http://127.0.0.1:3000/`，看板路径`/d/ginkgo-es-search/`，账号`admin`。Grafana账号独立于业务网站账号，密码在被Git忽略的`deploy/monitoring/secrets/grafana_admin_password`文件中，不使用默认密码。Grafana未部署线上，未嵌入业务管理员界面。

## 自动配置

- [Compose](../../deploy/monitoring/compose.yaml)新增Grafana v12.2.1，仅绑定本机3000端口，数据使用独立命名卷持久化，容器上限256MiB、0.5CPU。
- [数据源](../../deploy/monitoring/grafana/provisioning/datasources/prometheus.yml)通过Compose内网访问`http://prometheus:9090`，无需读取后端采集密码。
- [看板提供器](../../deploy/monitoring/grafana/provisioning/dashboards/default.yml)自动加载[看板JSON](../../deploy/monitoring/grafana/dashboards/restaurant-search.json)，JSON为维护来源，UI不可覆盖仓库配置。
- 禁用匿名访问、用户自助注册和iframe嵌入，默认首页为检索看板；不新增业务接口或Java埋点。

看板共39个面板、49条查询，标题为“Ginkgo · ES 检索与同步监控”，UID及入口保持不变。顶部是已触发告警数、等待确认告警数及告警明细表，随后展示采集状态、QPS、本次启动累计查询、ES承担比例、降级比例、最终失败率、普通浏览/关键词流量、列表及ES阶段P50/P95/P99、实际返回来源、降级原因、关键词零结果比例、每阶段SDK调用数及共享熔断状态；下方“ES 数据同步（Outbox）”展示[同步积压、消费结果、耗时、任务合并及Bulk请求等指标](elasticsearch-sync-metrics.md)。可选择单个后端实例，默认最近1小时，每30秒刷新。

比例仅在分母有流量时展示；无流量显示暂无数据，无降级事件时对应序列可能尚未生成。累计次数是当前进程累计值，后端重启归零，不是所选时段的增量。熔断器由列表与地图共享，其他新增检索图表只覆盖首页列表。指标口径见[指标说明](elasticsearch-metrics.md)。

## 告警区域

直接查询现有Prometheus的`ALERTS`序列，显示[八条已配置规则](search-alerts.md)（原五条加三条同步告警），不创建Grafana托管规则或重复评估。告警区域随实例选择过滤，明细表展示中文告警名称、实例、级别和状态：Firing为红色“已触发”，Pending为橙色“等待确认”；critical显示“严重”，warning显示“警告”。点击告警名称或顶部链接进入Prometheus规则页查看规则及排查注释，本地链接为`http://127.0.0.1:9090/alerts`，其他环境需同步调整。

三条新增查询均为instant查询，按所选时间范围的**结束时刻**计算，默认现在；历史时间范围显示该时点的状态，而非时段内发生过的告警次数。只有Pending/Firing存在于ALERTS中；无活跃告警时明细为空，计数在所选实例仍有up序列时显示0。0或空表不代表采集和规则健康，仍需核对采集状态及Prometheus规则页；实例序列也不存在或数据源异常时不使用无条件vector(0)伪造健康结果。未接入通知推送。

## Docker方式

先按[Prometheus说明](prometheus.md)准备采集密码和后端，再生成Grafana随机密码；仅首次执行，已有密码不覆盖：

```powershell
New-Item -ItemType Directory -Path deploy/monitoring/secrets -Force | Out-Null
$grafanaSecretPath = Join-Path (Get-Location) 'deploy/monitoring/secrets/grafana_admin_password'
if (-not (Test-Path -LiteralPath $grafanaSecretPath)) {
    $grafanaBytes = New-Object byte[] 24
    $grafanaRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $grafanaRng.GetBytes($grafanaBytes)
    $grafanaRng.Dispose()
    [System.IO.File]::WriteAllText($grafanaSecretPath, [BitConverter]::ToString($grafanaBytes).Replace('-', '').ToLowerInvariant())
}
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml up -d
```

密码文件只在首次初始化Grafana账号时生效；已有数据卷上的密码轮换需使用Grafana账号管理或官方CLI，不能只改secret文件。停止时不要使用`down -v`，避免删除Grafana设置和Prometheus历史。

## Windows本机方式

Docker镜像下载受阻时，可使用Grafana官方Windows发行包。先从官方版本下载页获取12.2.1 amd64 ZIP，并核对该包官方SHA256；发行包不放入仓库。

```powershell
./deploy/monitoring/Start-GrafanaLocal.ps1 -GrafanaHome '<已解压发行包根目录>' -RuntimeDirectory '<仓库外的运行目录>'
```

[启动脚本](../../deploy/monitoring/Start-GrafanaLocal.ps1)使用相同的看板JSON和密码文件，运行时将数据源改为`http://127.0.0.1:9090`；配置、SQLite、插件及日志保存在指定运行目录。服务仅监听本机，隐藏窗口启动，脚本输出PID及密码文件位置，不打印密码。停止时先核对该PID属于此Grafana再终止；不要同时启动Docker和Windows两个3000端口实例。Windows进程不受Compose的容器资源限制。

## 验收

- 登录后可直接看到看板，数据源健康检查通过，看板查询无语法或数据源错误。
- 无流量时部分图表暂无数据是正常情况；发起浏览/搜索并等待至少两个采集点后，QPS和耗时图表应有数据。计数器无需多个点即可展示。
- 本机UI登录、Grafana代理查询、Prometheus采集属于不同权限边界；未授权访问看板API应被拒绝。后续接入业务管理员页面需单独设计认证及嵌入配置。
- 首次告警区域验证使用当时真实数据及2026-11-10故障演练保留的历史ALERTS：当时0/0，历史Pending为0/2、历史Firing为2/0；浏览器确认中文名称、状态、级别和表格显示。该阶段19个面板、22条查询通过，证据位于`D:/eat/.codex-previews/grafana-alert-panels/`。新增同步指标后的完整验证见[同步指标报告](../history/elasticsearch-sync-metrics-2026-11-10.md)。
