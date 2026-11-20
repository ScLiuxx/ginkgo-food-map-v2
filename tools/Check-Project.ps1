[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('BackendTest', 'BackendVerify', 'FrontendTest', 'FrontendBuild', 'FrontendCheck', 'DeploymentTest')]
    [string]$Task,
    [string[]]$Tests = @(),
    [string[]]$MavenArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($repoRoot.ToUpperInvariant()))).Replace('-', '')
} finally {
    $sha.Dispose()
}

# Named mutex survives neither exit nor crash, so an interrupted check cannot leave a stale lock.
$mutex = [Threading.Mutex]::new($false, "Local\FoodmapCheck-$($hash.Substring(0, 20))")
$acquired = $false
$locationChanged = $false
$result = 1
try {
    try {
        $acquired = $mutex.WaitOne(0)
    } catch [Threading.AbandonedMutexException] {
        $acquired = $true
    }
    if (-not $acquired) {
        throw 'Another project check is running. Wait for it to finish; do not start a second test or build.'
    }
    if ($Tests.Count -gt 0 -and $Task -notin @('BackendTest', 'BackendVerify', 'FrontendTest')) {
        throw '-Tests only applies to BackendTest, BackendVerify, or FrontendTest.'
    }
    if ($MavenArguments.Count -gt 0 -and $Task -notlike 'Backend*') {
        throw '-MavenArguments only applies to backend checks.'
    }

    # Check committed-memory headroom, not only free physical RAM; a small Windows pagefile matters here.
    $os = Get-CimInstance Win32_OperatingSystem
    $freeCommitMiB = [math]::Floor($os.FreeVirtualMemory / 1024)
    $freePhysicalMiB = [math]::Floor($os.FreePhysicalMemory / 1024)
    $requiredCommitMiB = if ($Task -in @('FrontendBuild', 'FrontendCheck')) { 3072 } else { 2048 }
    if ($freeCommitMiB -lt $requiredCommitMiB -or $freePhysicalMiB -lt 1024) {
        throw "Insufficient memory headroom: available commit ${freeCommitMiB} MiB (requires $requiredCommitMiB), free RAM ${freePhysicalMiB} MiB (requires 1024). Close unused apps or enable a system-managed Windows pagefile before retrying. No checks started."
    }

    Write-Host "Check: $Task; available commit: $freeCommitMiB MiB; test workers: 1."
    if ($Task -like 'Backend*') {
        Push-Location (Join-Path $repoRoot 'backend')
        $locationChanged = $true
        $maven = @('--batch-mode', '--no-transfer-progress', '-Plocal-check')
        if ($Tests.Count -gt 0) { $maven += "-Dtest=$($Tests -join ',')" }
        $maven += $MavenArguments
        $maven += $(if ($Task -eq 'BackendVerify') { 'verify' } else { 'test' })
        Get-Command mvn -ErrorAction Stop | Out-Null
        # Windows PowerShell turns native stderr warnings into ErrorRecords when redirected.
        # Preserve those logs and judge the child by its exit code instead of treating warnings as crashes.
        $ErrorActionPreference = 'Continue'
        & mvn @maven
        $result = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
    } elseif ($Task -eq 'DeploymentTest') {
        Push-Location $repoRoot
        $locationChanged = $true
        Get-Command python -ErrorAction Stop | Out-Null
        $ErrorActionPreference = 'Continue'
        & python -m unittest discover -s deploy/tests -v
        $result = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
    } else {
        Push-Location (Join-Path $repoRoot 'frontend')
        $locationChanged = $true
        Get-Command npm -ErrorAction Stop | Out-Null
        $ErrorActionPreference = 'Continue'
        switch ($Task) {
            'FrontendTest' { & npm test -- @Tests }
            'FrontendBuild' { & npm run build }
            'FrontendCheck' { & npm run check }
        }
        $result = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
    }
} catch {
    Write-Host "Check stopped: $($_.Exception.Message)" -ForegroundColor Red
    $result = 1
} finally {
    if ($locationChanged) { Pop-Location }
    if ($acquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
exit $result
