# AdbControl 后端轮询自更新脚本 (云电脑端,出站拉取,无需公网入站)
#
# 背景:云电脑在 NAT 后没有公网入站时,CI 推送后的 /api/admin/upgrade 主动通知
# 全部不可达。本脚本反向拉取:定时请求 GitHub latest Release,发现新构建就下载
# 并调用既有 deploy.ps1 完成热替换 + 重启 + 健康检查。
#
# 注册为每 10 分钟运行的 Windows 计划任务(见同目录 setup-update-task.ps1):
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\setup-update-task.ps1
#
# 手动立即执行一次:
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\update-poll.ps1 -Force
param(
    [string]$Repo = "qimingnan17/adbcontrol-backend",
    [string]$AppHome = "C:\adbcontrol",
    # 每次轮询都下载会浪费流量;开启防抖后用本地指纹判断是否有新版本
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# ---- 常量 ----
$mirrors = @(
    "https://ghfast.top/",
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
    ""
)
$rawUrl = "https://github.com/$Repo/releases/download/latest/backend.zip"
# 指纹文件:记录上次成功部署的 zip 的 SHA256。Release 是滚动 tag,名字不变,
# 只能靠内容哈希判断"是不是新构建"。
$stateFile = Join-Path $AppHome "upload\last-deployed.sha256"
$tmpZip = Join-Path $AppHome "upload\backend.zip.poll"

function Write-PollLog([string]$msg) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg"
    Write-Host $line
    $log = Join-Path $AppHome "logs\update-poll.log"
    try {
        New-Item -ItemType Directory -Path (Split-Path $log) -Force | Out-Null
        Add-Content -Path $log -Value $line -ErrorAction SilentlyContinue
        # 日志滚动:超过 2MB 截断保留尾部
        if ((Test-Path $log) -and ((Get-Item $log).Length -gt 2MB)) {
            $tail = Get-Content $log -Tail 500 -ErrorAction SilentlyContinue
            Set-Content -Path $log -Value $tail -ErrorAction SilentlyContinue
        }
    } catch {}
}

function Get-FileSha256([string]$path) {
    return (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

Write-PollLog "=== poll start (repo=$Repo force=$Force) ==="

try {
    # ---- 1. 下载(走多镜像竞速兜底,与 update-from-github.ps1 同源) ----
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $downloaded = $false
    foreach ($mirror in $mirrors) {
        $targetUrl = "${mirror}${rawUrl}?t=${ts}"
        try {
            if (Test-Path $tmpZip) { Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue }
            if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
                # -f:HTTP 错误码直接失败(404=还没有 latest Release)
                curl.exe -f -L -s --connect-timeout 10 -m 120 "$targetUrl" -o "$tmpZip"
            } else {
                Invoke-WebRequest -Uri $targetUrl -OutFile $tmpZip -TimeoutSec 130 -UseBasicParsing
            }
            if (Test-Path $tmpZip) {
                $len = (Get-Item $tmpZip).Length
                if ($len -gt 15728640) {
                    # 完整性校验:zip 能被 tar 列出首个条目
                    tar -tf "$tmpZip" 2>&1 | Select-Object -First 1 | Out-Null
                    if ($LASTEXITCODE -eq 0) {
                        $mb = [math]::Round($len / 1MB, 2)
                        Write-PollLog "downloaded ok via [$mirror] size=$mb MB"
                        $downloaded = $true
                        break
                    }
                }
                Write-PollLog "incomplete/corrupt via [$mirror] size=$len, trying next"
            }
        } catch {
            Write-PollLog "download failed via [$mirror]: $($_.Exception.Message)"
        }
    }
    if (-not $downloaded) {
        Write-PollLog "no release available (first run?) or all mirrors failed; will retry next tick"
        exit 0
    }

    # ---- 2. 防抖:与上次部署的哈希比对 ----
    $newHash = Get-FileSha256 $tmpZip
    $oldHash = if (Test-Path $stateFile) { (Get-Content $stateFile -Raw).Trim() } else { "" }
    if (-not $Force -and $newHash -eq $oldHash) {
        Write-PollLog "no change (sha=$($newHash.Substring(0,12))...)"
        Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue
        exit 0
    }
    Write-PollLog "new build detected (old=$($oldHash.Substring(0,[Math]::Min(12,$oldHash.Length))) new=$($newHash.Substring(0,12)))"

    # ---- 3. 部署:复用既有热替换脚本(停服务->解压->起服务->健康检查) ----
    $destZip = Join-Path $AppHome "upload\backend.zip"
    Move-Item $tmpZip $destZip -Force
    & (Join-Path $AppHome "deploy.ps1") -ZipPath $destZip -AppHome $AppHome
    if ($LASTEXITCODE -ne 0) {
        Write-PollLog "deploy.ps1 FAILED (exit=$LASTEXITCODE); keeping hash unchanged so next tick retries"
        exit 1
    }

    # ---- 4. 记录指纹 ----
    Set-Content -Path $stateFile -Value $newHash -Force
    Write-PollLog "deploy done, sha256 saved"
} finally {
    if (Test-Path $tmpZip) { Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue }
    Write-PollLog "=== poll end ==="
}
