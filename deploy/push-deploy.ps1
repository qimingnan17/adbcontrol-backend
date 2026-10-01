# AdbControl manual push-deploy: build backend.zip locally, push to the cloud PC over
# Tailscale SSH, then run the remote deploy.ps1 (stop task -> kill java -> extract -> start -> health check).
# Usage (from repo root adbcontrol-backend):
#   powershell -NoProfile -ExecutionPolicy Bypass -File deploy\push-deploy.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File deploy\push-deploy.ps1 -SkipBuild -WithScripts
# Prereqs: cloud PC runs OpenSSH Server with key auth (no password prompt);
#          C:\adbcontrol\deploy.ps1 already installed there.
param(
    [string]$RemoteHost = "100.91.103.13",   # cloud PC Tailscale IP
    [string]$RemoteUser = "administrator",
    [string]$KeyPath = "",                   # SSH private key (empty = default key / agent)
    [string]$RemoteDir = "C:\adbcontrol",    # app home on the cloud PC (no spaces)
    [string]$ZipPath = "",                   # push an existing zip instead of building
    [switch]$SkipBuild,
    [switch]$WithScripts                     # also sync deploy\*.ps1 to the cloud PC
)
$ErrorActionPreference = 'Stop'

# --- 0. paths ---
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path   # adbcontrol-backend repo root
$defaultZip = Join-Path $RepoRoot "backend\build\distributions\backend.zip"
$doBuild = (-not $SkipBuild) -and (-not $ZipPath)
if (-not $ZipPath) { $ZipPath = $defaultZip }
$sshArgs = @()
if ($KeyPath) { $sshArgs += @('-i', $KeyPath) }
$sshTarget = "${RemoteUser}@${RemoteHost}"
$remoteDirPosix = $RemoteDir -replace '\\', '/'
$remoteZipPosix = "$remoteDirPosix/upload/backend.zip"

# --- 1. build ---
if ($doBuild) {
    Write-Output '[1/4] building :backend:distZip ...'
    Push-Location $RepoRoot
    try {
        & .\gradlew.bat :backend:distZip
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
} else {
    Write-Output '[1/4] skip build'
}
if (-not (Test-Path $ZipPath)) { throw "zip not found: $ZipPath" }

# --- 2. push zip over Tailscale SSH ---
$sizeMB = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Output "[2/4] uploading to $sshTarget ($sizeMB MB, may take minutes on slow links - no progress bar here) ..."
# no embedded quotes/pipes: remote default shell (cmd.exe) parses the line first
$mkDir = "powershell -NoProfile -Command New-Item -ItemType Directory -Force -Path $RemoteDir\upload"
& ssh @sshArgs $sshTarget $mkDir
if ($LASTEXITCODE -ne 0) { throw "ssh failed (exit $LASTEXITCODE) - check OpenSSH Server / key auth / Tailscale" }
& scp @sshArgs $ZipPath "${sshTarget}:$remoteZipPosix"
if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)" }

# --- 3. optional: sync deploy scripts ---
if ($WithScripts) {
    Write-Output '[3/4] syncing deploy scripts ...'
    # *.bat 也要传:backend-run.bat 是 AdbControlBackend 计划任务的执行体,
    # 首次部署若漏了它,计划任务起来后 cmd 找不到文件,后端永远起不来。
    Get-ChildItem (Join-Path $RepoRoot "deploy") -Include *.ps1, *.bat -File -Recurse | ForEach-Object {
        try {
            & scp @sshArgs $_.FullName "${sshTarget}:$remoteDirPosix/$($_.Name)"
            if ($LASTEXITCODE -ne 0) { throw "scp failed for $($_.Name)" }
            Write-Output "  ok: $($_.Name)"
        } catch {
            Write-Warning "  skip $($_.Name): $($_.Exception.Message)"
        }
    }
} else {
    Write-Output '[3/4] skip script sync'
}

# --- 4. remote hot-swap via deploy.ps1 ---
Write-Output '[4/4] remote deploy.ps1 (hot swap + health check) ...'
$remoteCmd = "powershell -NoProfile -ExecutionPolicy Bypass -File $RemoteDir\deploy.ps1 -ZipPath $RemoteDir\upload\backend.zip"
& ssh @sshArgs $sshTarget $remoteCmd
if ($LASTEXITCODE -ne 0) { throw "remote deploy failed (exit $LASTEXITCODE)" }

Write-Output "DONE: http://${RemoteHost}:8080/"
