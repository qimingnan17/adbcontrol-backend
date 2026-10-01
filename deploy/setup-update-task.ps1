# 一键注册"AdbControl 后端自动更新轮询"Windows 计划任务(云电脑端运行一次)
#
# 功能:每 10 分钟以 SYSTEM 身份静默执行 update-poll.ps1,出站拉取 GitHub
# latest Release;发现新构建自动热替换重启。云电脑无需任何公网入站。
#
# 用法(管理员 PowerShell):
#   powershell -ExecutionPolicy Bypass -File C:\adbcontrol\setup-update-task.ps1
#
# 卸载:
#   schtasks /end /tn AdbControlUpdatePoll ; schtasks /delete /tn AdbControlUpdatePoll /f
param(
    [string]$Repo = "qimingnan17/adbcontrol-backend",
    [string]$AppHome = "C:\adbcontrol",
    [string]$TaskName = "AdbControlUpdatePoll",
    # 轮询间隔(分钟)
    [int]$IntervalMinutes = 10
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $AppHome "update-poll.ps1"
if (-not (Test-Path $script)) { throw "script not found: $script (请先把 update-poll.ps1 复制到 $AppHome)" }

# 幂等:已存在则先删旧任务。
# 关键:首次运行时任务本来就不存在,schtasks /query 会返回非零并往 stderr 写
# "系统找不到指定的文件"。在 $ErrorActionPreference='Stop' 下,这会被当成
# 终止性错误直接打断脚本 —— 也就是"首次注册"这条主路径从来没走通过。
# 这里显式用 SilentlyContinue + 临时放宽 ErrorActionPreference 吞掉预期失败。
$ErrorActionPreference = 'Continue'
schtasks /query /tn $TaskName 2>&1 | Out-Null
$exists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = 'Stop'
if ($exists) {
    $ErrorActionPreference = 'Continue'
    schtasks /end /tn $TaskName 2>&1 | Out-Null
    schtasks /delete /tn $TaskName /f 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    Write-Host "已存在同名任务,先删除再重建。" -ForegroundColor Yellow
} else {
    Write-Host "任务 '$TaskName' 不存在,直接创建(首次部署属正常)。" -ForegroundColor Cyan
}

# 每隔 N 分钟运行一次,不限时长;SYSTEM 身份无需登录即可运行。
#
# 引号处理:schtasks 会按空格切分 /tr 的值,原写法在 /tr 内部再嵌一层双引号
# 包住脚本路径,结果 schtasks 把命令里的 "-Repo" 当成自己的选项直接报
# "无效的参数/选项"。因此这里改成:仅当参数真含空格时才加引号
# (默认路径 C:\adbcontrol 与仓库名都不含空格,天然规避)。
function Format-TaskArg([string]$value) {
    if ($value -match '\s') { return '"' + $value + '"' }
    return $value
}
$action = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File $(Format-TaskArg $script)" +
          " -Repo $(Format-TaskArg $Repo)" +
          " -AppHome $(Format-TaskArg $AppHome)"
$arg = "/create /tn $TaskName /tr `"$action`" /sc minute /mo $IntervalMinutes /ru SYSTEM /rl HIGHEST /f"
Write-Host "任务动作: $action" -ForegroundColor DarkGray
$ErrorActionPreference = 'Continue'
cmd.exe /c "schtasks $arg" 2>&1 | Out-Null
$createExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($createExit -ne 0) { throw "schtasks create failed (exit=$createExit)" }

# 立即触发一次验证链路(首次运行若还没有 latest Release 会在日志里记"no release")
$ErrorActionPreference = 'Continue'
schtasks /run /tn $TaskName 2>&1 | Out-Null
$ErrorActionPreference = 'Stop'
Start-Sleep -Seconds 5
$log = Join-Path $AppHome "logs\update-poll.log"
if (Test-Path $log) {
    Write-Host "=== update-poll.log (tail) ===" -ForegroundColor Cyan
    Get-Content $log -Tail 10
} else {
    Write-Host "任务已注册;日志将在首次执行时生成: $log" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "OK: 计划任务 '$TaskName' 已注册,每 $IntervalMinutes 分钟轮询一次。" -ForegroundColor Green
Write-Host "查看状态: schtasks /query /tn $TaskName /v"
Write-Host "手动执行: schtasks /run /tn $TaskName"
Write-Host "停止更新: schtasks /end /tn $TaskName ; schtasks /delete /tn $TaskName /f"
