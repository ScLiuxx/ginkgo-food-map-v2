param(
    [Parameter(Mandatory = $true)]
    [string]$GrafanaHome,
    [string]$RuntimeDirectory = (Join-Path ([System.IO.Path]::GetTempPath()) 'ginkgo-grafana')
)

$ErrorActionPreference = 'Stop'
$grafanaRoot = (Resolve-Path -LiteralPath $GrafanaHome).Path
$grafanaExecutable = Join-Path $grafanaRoot 'bin/grafana.exe'
if (-not (Test-Path -LiteralPath $grafanaExecutable)) {
    throw 'GrafanaHome必须指向已解压的官方Windows发行包根目录。'
}
if (Get-NetTCPConnection -State Listen -LocalPort 3000 -ErrorAction SilentlyContinue) {
    throw '本机3000端口已被使用，请先确认已有Grafana实例。'
}

# 运行文件放到仓库外；复用与Docker方式相同的密码及看板JSON，不保存明文密码到运行配置。
$secretDirectory = Join-Path $PSScriptRoot 'secrets'
$secretPath = Join-Path $secretDirectory 'grafana_admin_password'
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $secretPath)) {
    $randomBytes = New-Object byte[] 24
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($randomBytes)
    $random.Dispose()
    $password = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
    [System.IO.File]::WriteAllText($secretPath, $password)
}
$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeDirectory).Replace('\', '/')
$dashboardDirectory = (Join-Path $PSScriptRoot 'grafana/dashboards').Replace('\', '/')
foreach ($directory in @('provisioning/datasources', 'provisioning/dashboards', 'data', 'logs', 'plugins')) {
    New-Item -ItemType Directory -Path (Join-Path $runtimeRoot $directory) -Force | Out-Null
}
$utf8 = [System.Text.UTF8Encoding]::new($false)
$sourceConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'grafana/provisioning/datasources/prometheus.yml') -Raw -Encoding utf8
$sourceConfig = $sourceConfig.Replace('http://prometheus:9090', 'http://127.0.0.1:9090')
[System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'provisioning/datasources/prometheus.yml'), $sourceConfig, $utf8)
$providerConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'grafana/provisioning/dashboards/default.yml') -Raw -Encoding utf8
$providerConfig = $providerConfig.Replace('/var/lib/grafana/dashboards', $dashboardDirectory)
[System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'provisioning/dashboards/default.yml'), $providerConfig, $utf8)
$config = @"
[paths]
data = $runtimeRoot/data
logs = $runtimeRoot/logs
plugins = $runtimeRoot/plugins
provisioning = $runtimeRoot/provisioning
[server]
http_addr = 127.0.0.1
http_port = 3000
[security]
admin_user = admin
allow_embedding = false
[auth.anonymous]
enabled = false
[users]
allow_sign_up = false
[analytics]
reporting_enabled = false
check_for_updates = false
check_for_plugin_updates = false
[dashboards]
default_home_dashboard_path = $dashboardDirectory/restaurant-search.json
[plugins]
preinstall_disabled = true
"@
$configPath = Join-Path $runtimeRoot 'grafana.ini'
[System.IO.File]::WriteAllText($configPath, $config, $utf8)
$previousPassword = $env:GF_SECURITY_ADMIN_PASSWORD
try {
    # 密码仅传入子进程环境，不放命令行；启动后恢复调用方环境。
    $env:GF_SECURITY_ADMIN_PASSWORD = Get-Content -LiteralPath $secretPath -Raw
    $process = Start-Process -FilePath $grafanaExecutable `
        -ArgumentList @('server', '--homepath', "`"$grafanaRoot`"", '--config', "`"$configPath`"") `
        -WorkingDirectory $grafanaRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runtimeRoot 'stdout.log') `
        -RedirectStandardError (Join-Path $runtimeRoot 'stderr.log') -PassThru
    Write-Output "Grafana PID: $($process.Id)；地址：http://127.0.0.1:3000/"
    Write-Output "账号：admin；密码文件：$secretPath"
} finally {
    $env:GF_SECURITY_ADMIN_PASSWORD = $previousPassword
}
