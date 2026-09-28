# AdbControl 后端从 GitHub Release 自动拉取更新脚本 (云电脑端运行)
# 用法: powershell -ExecutionPolicy Bypass -File C:\adbcontrol\update-from-github.ps1
param(
    [string]$Repo = "qimingnan17/adbcontrol-backend",
    [string]$Tag = "latest",
    [string]$AppHome = "C:\adbcontrol"
)

$ErrorActionPreference = 'Stop'
$uploadDir = Join-Path $AppHome "upload"
if (-not (Test-Path $uploadDir)) { New-Item -ItemType Directory -Path $uploadDir -Force | Out-Null }
$destZip = Join-Path $uploadDir "backend.zip"

$mirrors = @(
    "https://ghproxy.net/",
    "https://gh-proxy.com/",
    "" # 直连兜底
)

$rawUrl = "https://github.com/$Repo/releases/download/$Tag/backend.zip"
$downloaded = $false

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  AdbControl GitHub Release 自动更新" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

foreach ($mirror in $mirrors) {
    $targetUrl = "$mirror$rawUrl"
    Write-Host "`n[1/3] 尝试从源下载: $targetUrl" -ForegroundColor Green
    try {
        if (Test-Path $destZip) { Remove-Item $destZip -Force }
        Invoke-WebRequest -Uri $targetUrl -OutFile $destZip -TimeoutSec 120 -UseBasicParsing
        if ((Test-Path $destZip) -and ((Get-Item $destZip).Length -gt 1048576)) {
            $mb = [math]::Round((Get-Item $destZip).Length / 1MB, 2)
            Write-Host "下载成功！产物大小: $mb MB" -ForegroundColor Green
            $downloaded = $true
            break
        } else {
            Write-Host "下载的文件过小或无效，尝试下一个镜像..." -ForegroundColor Yellow
        }
    } catch {
        Write-Host "下载失败: $($_.Exception.Message)，尝试下一个镜像..." -ForegroundColor Yellow
    }
}

if (-not $downloaded) {
    throw "所有镜像下载均失败，请检查 GitHub Release 是否已生成 tag: $Tag 的 backend.zip"
}

Write-Host "`n[2/3] 执行热替换并重启服务..." -ForegroundColor Green
& "$AppHome\deploy.ps1" -ZipPath $destZip -AppHome $AppHome

Write-Host "`n[3/3] 全部更新流程已完成！" -ForegroundColor Green
