# AdbControl Local Database Automated Backup Script
# Usage: powershell -ExecutionPolicy Bypass -File C:\adbcontrol\backup-db.ps1
param(
    [string]$AppHome = "C:\adbcontrol",
    [string]$DbName = "adbcontrol",
    [string]$DbUser = "root",
    [string]$DbPass = "adbcontrol_local_db_2026",
    [int]$RetentionDays = 7
)

$ErrorActionPreference = 'Stop'
$backupDir = Join-Path $AppHome "backup"
if (-not (Test-Path $backupDir)) {
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
}

$dateStr = Get-Date -Format "yyyyMMdd_HHmmss"
$sqlFile = Join-Path $backupDir "${DbName}_${dateStr}.sql"
$dumpExe = Join-Path $AppHome "mariadb\bin\mariadb-dump.exe"
if (-not (Test-Path $dumpExe)) {
    $dumpExe = Join-Path $AppHome "mariadb\bin\mysqldump.exe"
}

if (-not (Test-Path $dumpExe)) {
    throw "mariadb-dump.exe or mysqldump.exe not found at: $dumpExe"
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  AdbControl Database Auto Backup" -ForegroundColor Cyan
Write-Host "  Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Execute database dump
Write-Host "[1/3] Dumping database [$DbName] to $sqlFile ..." -ForegroundColor Green
& $dumpExe -u $DbUser "-p$DbPass" --databases $DbName --routines --triggers --result-file="$sqlFile"

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
