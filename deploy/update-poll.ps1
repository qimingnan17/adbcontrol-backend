# AdbControl 后端轮询自更新脚本 (云电脑端,出站拉取,无需公网入站)
#
# 背景:云电脑在 NAT 后没有公网入站时,CI 推送后的 /api/admin/upgrade 主动通知
# 不可达。本脚本反向拉取:定时请求 GitHub latest Release,发现新构建就下载
# 并调用既有 deploy.ps1 完成热替换 + 重启 + 健康检查。
#
# 防抖:先查 GitHub Release API 拿资产 digest(sha256)与 size,
#       与本地记录一致就直接退出,**完全不下载**。
#       (此前每轮都先下满 22MB 再比哈希,一天约 3.3GB 无谓流量。)
#       API 不可用时退回旧路径:下载后比哈希。
#
# 注册为每 10 分钟运行的 Windows 计划任务(见同目录 setup-update-task.ps1):
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\setup-update-task.ps1
#
# 手动立即执行一次:
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\update-poll.ps1 -Force
param(
    [string]$Repo = "qimingnan17/adbcontrol-backend",
    [string]$AppHome = "C:\adbcontrol",
    # 忽略防抖,强制重新下载并部署
    [switch]$Force,
    # 跳过 GitHub API 预检(排障用)
    [switch]$SkipProbe
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
$apiUrl = "https://api.github.com/repos/$Repo/releases/tags/latest"
# 指纹文件:记录上次成功部署的 zip 的 SHA256。Release 是滚动 tag,名字不变,
# 只能靠内容哈希判断"是不是新构建"。
$stateFile = Join-Path $AppHome "upload\last-deployed.sha256"
$tmpZip = Join-Path $AppHome "upload\backend.zip.poll"
# 体积下限:正常构建约 20MB+,低于此值视为下载被截断
$minSizeBytes = 15MB

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

function Get-DeployedHash {
    if (Test-Path $stateFile) { return (Get-Content $stateFile -Raw).Trim() }
    return ""
}

# 探测 GitHub Release 资产,返回 @{ Digest; Size } 或 $null。
# 免鉴权即可读公开仓库;仓库转私有或被限流时返回 $null,调用方退回下载路径。
function Get-RemoteAssetInfo {
    try {
        $h = @{ 'User-Agent' = 'adbcontrol-update-poll'; 'Accept' = 'application/vnd.github+json' }
        $rel = Invoke-RestMethod -Uri $apiUrl -Headers $h -TimeoutSec 15
        $asset = $rel.assets | Where-Object { $_.name -eq 'backend.zip' } | Select-Object -First 1
        if (-not $asset) { return $null }
        $digest = $null
        if ($asset.digest -and $asset.digest -match '^sha256:([0-9a-fA-F]{64})$') { $digest = $Matches[1].ToLowerInvariant() }
        return @{ Digest = $digest; Size = [int64]$asset.size }
    } catch {
        Write-PollLog "  probe failed: $($_.Exception.Message)"
        return $null
    }
}

# 校验下载物是否是一个完整可读的 zip。
# 不要用 `tar -tf file | Select-Object -First 1` —— Select-Object 拿到首条就会
# 中断上游管道把 tar 强杀,$LASTEXITCODE 随机非零,表现为"下载完整却被判损坏"
# 并白白重试下一个镜像。这里改用 .NET 直接读中央目录,确定性且不依赖 tar。
function Test-ZipIntegrity([string]$path) {
    try {
        # ZipArchiveMode 在 System.IO.Compression,ZipFile/ZipArchive 构造辅助在 FileSystem,
        # 两个都要加载 —— 只加 FileSystem 会出现"找不到类型 ZipArchiveMode"。
        Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $fs = [System.IO.File]::OpenRead($path)
        try {
            $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Read)
            try {
                $count = $zip.Entries.Count
                # 至少要有启动脚本,否则不是后端分发包
                $hasBin = $zip.Entries | Where-Object { $_.FullName -match 'bin/backend\.bat$' } | Select-Object -First 1
                if (-not $hasBin) { Write-PollLog "  integrity: no bin/backend.bat in archive"; return $false }
                Write-PollLog "  integrity ok: $count entries"
                return $true
            } finally { $zip.Dispose() }
        } finally { $fs.Dispose() }
    } catch {
        Write-PollLog "  integrity failed: $($_.Exception.Message)"
        return $false
    }
}

Write-PollLog "=== poll start (repo=$Repo force=$Force) ==="

# ---- 0. 预检:API 拿 digest,没变就不下载 ----
$deployedHash = Get-DeployedHash
$expectedDigest = $null
$expectedSize = 0
if (-not $Force -and -not $SkipProbe) {
    $info = Get-RemoteAssetInfo
    if ($info) {
        $expectedDigest = $info.Digest
        $expectedSize = $info.Size
        Write-PollLog ("  remote: size=" + $expectedSize + " digest=" + $(if ($expectedDigest) { $expectedDigest.Substring(0,12) } else { '<none>' }))
        if ($expectedDigest -and $deployedHash -eq $expectedDigest) {
            Write-PollLog "no change (digest matches, skipped download)"
            exit 0
        }
    } else {
        Write-PollLog "  probe unavailable, fall back to download+hash"
    }
}

try {
    # ---- 1. 下载(走多镜像竞速兜底,update-from-github.ps1 同源) ----
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $downloaded = $false
    foreach ($mirror in $mirrors) {
        $targetUrl = "${mirror}${rawUrl}?t=${ts}"
        try {
            if (Test-Path $tmpZip) { Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue }
            if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
                # -f:HTTP 错误码直接失败(404=还没有 latest Release)
                curl.exe -f -L -s --connect-timeout 10 -m 300 "$targetUrl" -o "$tmpZip"
            } else {
                Invoke-WebRequest -Uri $targetUrl -OutFile $tmpZip -TimeoutSec 310 -UseBasicParsing
            }
            if (Test-Path $tmpZip) {
                $len = (Get-Item $tmpZip).Length
                # 有 API 尺寸时以它为准,否则用体积下限
                $floor = if ($expectedSize -gt 0) { $expectedSize } else { $minSizeBytes }
                if ($len -lt $floor) {
                    Write-PollLog ("incomplete via [$mirror] size=$len (expected >= $floor), trying next")
                    continue
                }
                if (-not (Test-ZipIntegrity $tmpZip)) {
                    Write-PollLog "corrupt archive via [$mirror] size=$len, trying next"
                    continue
                }
                $mb = [math]::Round($len / 1MB, 2)
                Write-PollLog "downloaded ok via [$mirror] size=$mb MB"
                $downloaded = $true
                break
            }
        } catch {
            Write-PollLog "download failed via [$mirror]: $($_.Exception.Message)"
        }
    }
    if (-not $downloaded) {
        Write-PollLog "all mirrors failed; will retry next tick"
        exit 1
    }

    # ---- 2. 防抖:与上次部署的哈希比对 ----
    $newHash = Get-FileSha256 $tmpZip
    if (-not $Force -and $newHash -eq $deployedHash) {
        Write-PollLog "no change (sha=$($newHash.Substring(0,12))...)"
        Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue
        exit 0
    }
    # 交叉核对:API digest 与本地算出的哈希应当一致
    if ($expectedDigest -and $newHash -ne $expectedDigest) {
        Write-PollLog "HASH MISMATCH: local=$($newHash.Substring(0,12)) remote=$($expectedDigest.Substring(0,12)) -- aborting"
        Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue
        exit 1
    }
    Write-PollLog ("new build detected (old=" + $(if ($deployedHash.Length -ge 12) { $deployedHash.Substring(0,12) } else { "" }) + " new=" + $newHash.Substring(0,12) + ")")

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