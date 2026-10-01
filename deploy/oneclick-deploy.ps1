# AdbControl one-click deploy launcher (for double-click exe/bat usage).
# Locates repo root -> runs push-deploy.ps1 (build + upload + hot swap + health check) -> pauses at end.
# Same params as push-deploy.ps1. Repo root is found by walking up for gradlew.bat,
# or set -RepoRoot / env ADB_REPO_ROOT explicitly.
# NOTE: keep this file ASCII-only (PowerShell 5.1 reads BOM-less files as ANSI).
param(
    [string]$RepoRoot = "",
    [string]$RemoteHost = "100.91.103.13",
    [string]$RemoteUser = "administrator",
    [string]$KeyPath = "",
    [string]$RemoteDir = "C:\adbcontrol",
    [switch]$SkipBuild,
    [switch]$NoPause
)
$ErrorActionPreference = 'Stop'

function Find-RepoRoot([string]$start) {
    $dir = $start
    while ($dir) {
        if (Test-Path (Join-Path $dir "gradlew.bat")) { return $dir }
        $parent = Split-Path $dir -Parent
        if ($parent -eq $dir) { break }
        $dir = $parent
    }
    return $null
}

function Pause-End {
    if (-not $NoPause) { Read-Host "Press Enter to exit" | Out-Null }
}

if (-not $RepoRoot -and $env:ADB_REPO_ROOT) { $RepoRoot = $env:ADB_REPO_ROOT }
if (-not $RepoRoot) {
    $start = $PSScriptRoot
    if (-not $start) { $start = (Get-Location).Path }
    $RepoRoot = Find-RepoRoot $start
}
if (-not $RepoRoot) {
    Write-Host "[X] repo root not found (gradlew.bat missing)." -ForegroundColor Red
    Write-Host "    Keep this tool inside adbcontrol-backend\deploy\ or pass -RepoRoot."
    Pause-End
    exit 1
}

$target = Join-Path $RepoRoot "deploy\push-deploy.ps1"
if (-not (Test-Path $target)) {
    Write-Host "[X] deploy script missing: $target" -ForegroundColor Red
    Pause-End
    exit 1
}

Write-Host "=== AdbControl One-Click Deploy ===" -ForegroundColor Cyan
Write-Host "Repo root : $RepoRoot"
Write-Host "Target    : ${RemoteUser}@${RemoteHost}"
Write-Host ""

$exitCode = 0
try {
    $p = @{
        RemoteHost  = $RemoteHost
        RemoteUser  = $RemoteUser
        KeyPath     = $KeyPath
        RemoteDir   = $RemoteDir
        WithScripts = $true
    }
    if ($SkipBuild) { $p.SkipBuild = $true }
    & $target @p
} catch {
    $exitCode = 1
    Write-Host ""
    Write-Host "[X] deploy failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "    check: ssh key auth / cloud PC online / gradle build" -ForegroundColor Yellow
} finally {
    Write-Host ""
    Pause-End
}
exit $exitCode
