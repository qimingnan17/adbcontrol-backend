# AdbControl backend deploy/update script (cloud PC side)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File C:\adbcontrol\deploy.ps1 [-ZipPath C:\adbcontrol\upload\backend.zip]
# Steps: stop task -> kill java -> extract zip -> start task -> health check on /health
param(
    [string]$ZipPath = "C:\adbcontrol\upload\backend.zip",
    [string]$AppHome = "C:\adbcontrol",
    [string]$TaskName = "AdbControlBackend"
)
$ErrorActionPreference = 'Continue'
if (-not (Test-Path $ZipPath)) { throw "zip not found: $ZipPath" }

Write-Output '[1/5] stopping service...'
schtasks /end /tn $TaskName 2>&1 | Out-Null
# the only java process on this box is our backend, safe to kill by name
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
$ErrorActionPreference = 'Stop'

Write-Output '[2/5] extracting...'
if (Test-Path "$AppHome\app.old") { Remove-Item "$AppHome\app.old" -Recurse -Force }
if (Test-Path "$AppHome\app")     { Remove-Item "$AppHome\app" -Recurse -Force }
New-Item -ItemType Directory -Path "$AppHome\app" -Force | Out-Null
tar -xf $ZipPath -C "$AppHome\app"
if (Test-Path "$AppHome\app\backend\bin\backend.bat") {
    Move-Item "$AppHome\app\backend\*" "$AppHome\app\" -Force
    Remove-Item "$AppHome\app\backend" -Recurse -Force -ErrorAction SilentlyContinue
}
if (-not (Test-Path "$AppHome\app\bin\backend.bat")) { throw "extraction failed: backend.bat missing" }
Remove-Item "$AppHome\app.old" -Recurse -Force -ErrorAction SilentlyContinue

Write-Output '[3/5] starting scheduled task...'
schtasks /run /tn $TaskName | Out-Null

Write-Output '[4/5] health check (up to 60s)...'
# 门禁用 /api/health(进程存活,恒 200):外部 DB 抖动不应让部署失败。
# 随后只读式输出 /health(含 db 组件状态,DB 不可达时为 503)作参考。
$ok = $false
foreach ($i in 1..30) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/health' -UseBasicParsing -TimeoutSec 3
        Write-Output "LIVE_OK: $($r.Content)"
        $ok = $true
        break
    } catch { Write-Output "  waiting... ($i)" }
}
if (-not $ok) {
    Write-Output 'HEALTH_FAIL - last 50 lines of log:'
    Get-Content "$AppHome\logs\backend.log" -Tail 50
    exit 1
}
try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8080/health' -UseBasicParsing -TimeoutSec 8
    Write-Output "READINESS: $($r.StatusCode) $($r.Content)"
} catch {
    Write-Output "READINESS: degraded (DB or component down) - $($_.Exception.Message)"
}
Write-Output '[5/5] deploy done.'
