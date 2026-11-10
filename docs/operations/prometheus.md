# Prometheus本地采集

本配置用于本地Docker Desktop，采集运行在宿主机8080端口的后端；未部署到线上。指标定义见[检索指标](elasticsearch-metrics.md)和[同步指标](elasticsearch-sync-metrics.md)，可视化见[Grafana看板](grafana.md)，八条本地告警及排查方法见[告警说明](search-alerts.md)。Logs、Trace、消息推送和ES集群Exporter暂不接入。

## 访问和存储

- Compose入口：`deploy/monitoring/compose.yaml`，独立于业务及ES容器。
- Prometheus固定镜像`quay.io/prometheus/prometheus:v3.5.1`（项目官方Quay仓库），本地入口`http://127.0.0.1:9090/`，不监听公网地址。
- 每30秒采集，单次超时10秒，任务名`ginkgo-backend`，目标`host.docker.internal:8080/actuator/prometheus`。
- 告警每15秒评估，规则目录只读挂载；`/alerts`查看等待、触发及恢复，不会自动发送消息。
- TSDB使用命名卷持久化，保留7天或达到2GB阈值时按规则清理；2GB不包括WAL等全部占用，需预留额外磁盘。
- 容器上限512MiB内存、0.5CPU。实际需求受时间序列数量和查询影响，按运行情况调整，不代表固定资源消耗。
- Prometheus本地UI/API没有额外登录认证，只能在可信本机使用；远程访问需另行配置认证或SSH隧道。宿主机后端需能被Docker访问，不能只监听127.0.0.1；本地默认监听所有地址，网络边界及防火墙由运行环境负责。

## 独立采集认证

后端默认关闭机器采集。设置`METRICS_SCRAPE_ENABLED=true`，并通过`METRICS_SCRAPE_PASSWORD`注入32～64位随机字母数字、下划线或短横线密码后启用。缺失或格式不合法会拒绝启动，不生成默认凭据。

用户名固定`prometheus`，使用HTTP Basic认证，只能`GET /actuator/prometheus`。账号在进程内校验，无需Redis或数据库；没有业务接口权限。管理员Bearer JWT读取保持原有规则。匿名不放行，密码错误返回401，正确机器凭据请求非GET返回403；Basic错误响应使用标准HTTP认证格式，不使用业务JSON封装。

采集密码放在被Git忽略的`deploy/monitoring/secrets/metrics_password`，Compose以只读secret文件挂载，Prometheus通过`password_file`读取；不能提交或打印密码。此处Compose secret是本地文件挂载，不代表加密存储。后端只保留认证用的密码哈希和配置内存值。当前明文HTTP仅用于本机Docker链路，跨主机采集需TLS或受保护隧道。

## 首次启动

在项目根目录执行以下PowerShell，生成随机密码（只执行一次，不覆盖已有凭据）：

```powershell
New-Item -ItemType Directory -Path deploy/monitoring/secrets -Force | Out-Null
$metricsSecretPath = Join-Path (Get-Location) 'deploy/monitoring/secrets/metrics_password'
if (-not (Test-Path -LiteralPath $metricsSecretPath)) {
    $metricsBytes = New-Object byte[] 24
    $metricsRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $metricsRng.GetBytes($metricsBytes)
    $metricsRng.Dispose()
    $metricsSecret = [BitConverter]::ToString($metricsBytes).Replace('-', '').ToLowerInvariant()
    [System.IO.File]::WriteAllText($metricsSecretPath, $metricsSecret)
}
$env:METRICS_SCRAPE_ENABLED = 'true'
$env:METRICS_SCRAPE_PASSWORD = Get-Content -LiteralPath $metricsSecretPath -Raw
```

用这个PowerShell会话启动/重启后端，使其继承上述环境变量（例如从`backend`目录运行已构建JAR并指定原来的local profile）。不会自动修改`application-local.yml`或线上环境。其他启动方式如IDE需要分别配置这两个变量。

再启动Prometheus并检查配置（只启动Prometheus时指定服务名；启动全套需另备Grafana密码，见[看板说明](grafana.md)）：

```powershell
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml up -d prometheus
```

访问`http://127.0.0.1:9090/targets`，等待最多约一个采集周期，`ginkgo-backend`应为UP。查询`up{job="ginkgo-backend"}`应返回1。执行几次列表浏览或关键词检索，等待采集后查询`restaurant_search_requests_total`；QPS和P95需至少多个采集点，低流量下分位数仅供参考。

停止容器但保留历史：

```powershell
docker compose -p ginkgo-monitoring -f deploy/monitoring/compose.yaml down
```

不要加`-v`，否则会删除监控数据卷。轮换密码时同步更新后端环境变量和secret文件，并重启后端及Prometheus；普通业务账号退出登录不会影响采集。

## 排障

- target显示401：核对机器采集开关、用户名以及后端和文件中的密码是否一致；管理员JWT不用于自动采集。
- connection refused：检查后端进程、8080监听地址、Docker到宿主机访问和防火墙。
- target为UP但没有搜索序列：确认后端已运行包含埋点的新版，且实际发生过列表请求。
- Docker Hub下载超时：先恢复Docker引擎的镜像仓库网络，不能只修改当前终端代理并假设引擎也生效。
