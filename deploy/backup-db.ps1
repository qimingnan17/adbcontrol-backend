# AdbControl 本地数据库自动备份脚本（仅适用于数据库就在本机的部署）
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\backup-db.ps1
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\backup-db.ps1 -DbUser adbcontrol -DbPass xxx
#
# 账号密码默认从 C:\adbcontrol\secrets.properties 的 db.user / db.password 读取,
# 与后端运行时用的是同一份配置 —— 不在脚本里留任何明文口令。
# 后端支持 ADB_DB_* / ADB_MYSQL_* 两套历史命名,这里两套都认。
param(
    [string]$AppHome = "C:\adbcontrol",
    [string]$DbName = "",
    [string]$DbUser = "",
    [string]$DbPass = "",
    [int]$RetentionDays = 7
)

$ErrorActionPreference = 'Stop'
$backupDir = Join-Path $AppHome "backup"
if (-not (Test-Path $backupDir)) {
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
}

# ---- 从 secrets.properties 补齐未显式传入的连接参数 ----
$secretsFile = Join-Path $AppHome "secrets.properties"
if (Test-Path $secretsFile) {
    $props = @{}
    foreach ($line in Get-Content $secretsFile -Encoding UTF8) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
        $idx = $t.IndexOfAny([char[]]@('=', ':'))
        if ($idx -le 0) { continue }
        $props[$t.Substring(0, $idx).Trim()] = $t.Substring($idx + 1).Trim()
    }
    if (-not $DbName) { $DbName = if ($props['db.name']) { $props['db.name'] } else { 'adbcontrol' } }
    if (-not $DbUser) { $DbUser = if ($props['db.user']) { $props['db.user'] } else { 'root' } }
    if (-not $DbPass) { $DbPass = if ($props['db.password']) { $props['db.password'] } else { '' } }
}
if (-not $DbName) { $DbName = 'adbcontrol' }
if (-not $DbUser) { $DbUser = 'root' }
if (-not $DbPass) {
    throw "未找到数据库密码:请在 $secretsFile 填 db.password,或用 -DbPass 显式传入"
}

$dateStr = Get-Date -Format "yyyyMMdd_HHmmss"
$sqlFile = Join-Path $backupDir "${DbName}_${dateStr}.sql"

# 优先用 C:\adbcontrol\mariadb\bin\*(免安装包放这里可免配置),
# 其次回退到 PATH,最后再试 winget 安装的默认位置。
$dumpCandidates = @(
    (Join-Path $AppHome "mariadb\bin\mariadb-dump.exe"),
    (Join-Path $AppHome "mariadb\bin\mysqldump.exe"),
    "C:\Program Files\MariaDB $env:MARIADB_MAJOR_VERSION\bin\mariadb-dump.exe"
)
$dumpExe = $null
foreach ($c in $dumpCandidates) {
    if ($c -and (Test-Path $c)) { $dumpExe = $c; break }
}
if (-not $dumpExe) {
    $onPath = Get-Command mariadb-dump.exe -ErrorAction SilentlyContinue
    if (-not $onPath) { $onPath = Get-Command mysqldump.exe -ErrorAction SilentlyContinue }
    if ($onPath) { $dumpExe = $onPath.Source }
}
if (-not $dumpExe) {
    throw @"
mariadb-dump.exe / mysqldump.exe 未找到。已尝试:
  $($dumpCandidates -join [Environment]::NewLine)
  以及 PATH。
请把 MariaDB 免安装包解压到 $AppHome\mariadb\,或安装后重试。
"@
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  AdbControl Database Auto Backup" -ForegroundColor Cyan
Write-Host "  Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
Write-Host "  DB  : $DbName @ $DbUser (dump: $dumpExe)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# --defaults-extra-file 传密码:避免口令出现在进程命令行里(tasklist /wmic 可见)
$cnfFile = Join-Path $env:TEMP ("adbcontrol-dump-" + [guid]::NewGuid().ToString("N") + ".cnf")
"[client]`nuser=$DbUser`npassword=$DbPass" | Set-Content -LiteralPath $cnfFile -Encoding ASCII

try {
    Write-Host "[1/3] Dumping database [$DbName] to $sqlFile ..." -ForegroundColor Green
    & $dumpExe "--defaults-extra-file=$cnfFile" --databases $DbName --routines --triggers --result-file="$sqlFile"
    $dumpExit = $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $cnfFile -Force -ErrorAction SilentlyContinue
}
if ($dumpExit -ne 0) { throw "mariadb-dump 退出码 $dumpExit" }

if (-not (Test-Path $sqlFile) -or (Get-Item $sqlFile).Length -eq 0) {
    throw "Database dump failed or output file is empty!"
}

$sizeKb = [math]::Round((Get-Item $sqlFile).Length / 1KB, 2)
Write-Host "Dump successful! File size: $sizeKb KB" -ForegroundColor Green

Write-Host "`n[2/3] Verification passed: $sqlFile" -ForegroundColor Green

# Remove backups older than retention days
Write-Host "`n[3/3] Cleaning up backups older than $RetentionDays days..." -ForegroundColor Green
$cutoff = (Get-Date).AddDays(-$RetentionDays)
$oldFiles = Get-ChildItem -Path $backupDir -Filter "${DbName}_*.sql" | Where-Object { $_.CreationTime -lt $cutoff }
foreach ($file in $oldFiles) {
    Write-Host "Removing expired backup: $($file.Name)" -ForegroundColor Yellow
    Remove-Item $file.FullName -Force
}

Write-Host "Database backup workflow completed successfully!" -ForegroundColor Green
