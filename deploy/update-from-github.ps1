# AdbControl 后端从 GitHub Release 自动拉取更新脚本 (云电脑端运行)
# 用法: powershell -ExecutionPolicy Bypass -File C:\adbcontrol\update-from-github.ps1
param(
    [string]$Repo = "qimingnan17/adbcontrol-backend",
    [string]$Tag = "latest",
    [string]$AppHome = "C:\adbcontrol"
)

$ErrorActionPreference = 'Stop'
$logDir = Join-Path $AppHome "logs"
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}
$logFile = Join-Path $logDir "update.log"
Start-Transcript -Path $logFile -Append -Force -ErrorAction SilentlyContinue

$uploadDir = Join-Path $AppHome "upload"
if (-not (Test-Path $uploadDir)) {
    New-Item -ItemType Directory -Path $uploadDir -Force | Out-Null
}
$destZip = Join-Path $uploadDir "backend.zip"

$mirrors = @(
    "https://ghfast.top/",
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
    ""
)

$rawUrl = "https://github.com/$Repo/releases/download/$Tag/backend.zip"
$downloaded = $false

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  AdbControl GitHub Release 自动更新" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
foreach ($mirror in $mirrors) {
    $targetUrl = "${mirror}${rawUrl}?t=${ts}"
    Write-Host "`n[1/3] 尝试从源下载: $targetUrl" -ForegroundColor Green
    try {
        if (Test-Path $destZip) {
            Remove-Item $destZip -Force -ErrorAction SilentlyContinue
        }

        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            curl.exe -f -L -s --connect-timeout 10 -m 90 "$targetUrl" -o "$destZip"
        } else {
            Invoke-WebRequest -Uri $targetUrl -OutFile $destZip -TimeoutSec 120 -UseBasicParsing
        }

        if (Test-Path $destZip) {
            $len = (Get-Item $destZip).Length
            if ($len -gt 15728640) {
                # 校验 zip 文件完整性
                $valid = $false
                try {
                    tar -tf "$destZip" 2>&1 | Select-Object -First 1 | Out-Null
                    $valid = ($LASTEXITCODE -eq 0)
                } catch { $valid = $false }

                if ($valid) {
                    $mb = [math]::Round($len / 1MB, 2)
                    Write-Host "下载并校验成功！产物完整大小: $mb MB" -ForegroundColor Green
                    $downloaded = $true
                    break
                } else {
                    Write-Host "下载的文件归档损坏 (tar 校验失败)，尝试下一个镜像..." -ForegroundColor Yellow
                }
            } else {
                $curMb = [math]::Round($len / 1MB, 2)
                Write-Host "下载的文件不完整 (当前大小: $curMb MB < 15 MB)，尝试下一个镜像..." -ForegroundColor Yellow
            }
        }
    } catch {
        Write-Host "下载失败: $($_.Exception.Message)，尝试下一个镜像..." -ForegroundColor Yellow
    }
}

if (-not $downloaded) {
    Stop-Transcript -ErrorAction SilentlyContinue
    throw "所有镜像下载均失败，请检查 GitHub Release 是否已生成 tag: $Tag 的 backend.zip"
}

Write-Host "`n[2/3] 执行热替换并重启服务..." -ForegroundColor Green
& "$AppHome\deploy.ps1" -ZipPath $destZip -AppHome $AppHome

Write-Host "`n[3/3] 全部更新流程已完成！" -ForegroundColor Green
Stop-Transcript -ErrorAction SilentlyContinue
