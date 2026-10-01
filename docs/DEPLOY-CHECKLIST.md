# 新云电脑从零部署 Checklist

> 面向「全新 Windows 云电脑 → AdbControl 生产可用」的完整初始化清单，逐项勾选执行。
> 旧机器日常升级**不需要**本文档，直接跑 `deploy/push-deploy.ps1` 即可。
> 架构与原理见 [DEPLOY.md](DEPLOY.md)，运行期排查见 [RUN.md](RUN.md)。

---

## 阶段 0 · 本地准备（开发机）

- [ ] 生成 SSH 密钥对：`ssh-keygen -t ed25519`（后续部署通道用）
- [ ] 能远程桌面 / 控制台登录云电脑，知道初始账号密码（如 `administrator`）
- [ ] 本地构建通过：`cd adbcontrol-backend && ./gradlew :backend:distZip`
  （产物 `backend/build/distributions/backend.zip`，内含 Web 前端静态资源）
  > Web 前端无需单独部署，`syncWebDist` 任务已把 `adbcontrol-web/dist`
  > 同步进后端 `resources/static/`。

## 阶段 1 · 云电脑基础环境（远程桌面操作，一次性）

> **Win10 专属注意**（Windows Server 同样适用，但 Win10 必查）：
> 1. 版本需 ≥ 1809 才有内置 OpenSSH 可选功能（`winver` 查看）；更老版本需手动装 [Win32-OpenSSH](https://github.com/PowerShell/Win32-OpenSSH/releases)；
> 2. 电源计划设为**从不睡眠/不休眠**（设置 → 系统 → 电源），否则云电脑睡死即掉线；
> 3. Windows Update 自动重启会中断服务——属预期行为，开机自启的看门狗任务（1.4）会自动拉回后端；家庭版/专业版均可，脚本不依赖组策略或域功能。

### 1.1 运行时与网络

- [ ] 安装 JDK 17（Temurin 17 JRE/JDK 即可），命令行 `java -version` 可用
- [ ] 安装 Tailscale 并登录：`tailscale login`，记录虚拟 IP（`tailscale ip -4`，下文记作 `100.x.x.x`）
- [ ] 安装 OpenSSH Server（管理员 PowerShell）：
  ```powershell
  Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
  Start-Service sshd ; Set-Service sshd -StartupType Automatic
  ```
- [ ] 装公钥实现免密登录（administrator 属管理员组，公钥放专用文件）：
  把本地 `~/.ssh/id_ed25519.pub` 内容追加到云电脑
  `C:\ProgramData\ssh\administrators_authorized_keys`（注意修复该文件 ACL），本地验证：
  ```bash
  ssh administrator@100.x.x.x "echo ok"
  ```

### 1.2 数据库（二选一）

数据库不随代码分发，需自行准备。表结构由后端启动时自动建（`CREATE TABLE IF NOT EXISTS`），
**无需手动导入 `schema.sql`**，也无需手动建表。

#### 方案 A：本地 MariaDB / MySQL（推荐单机部署）

- [ ] 安装 MariaDB（推荐，自带 `mariadb-dump.exe`）：
  ```powershell
  # 静默安装并设为开机自启
  winget install MariaDB.Server --silent --accept-package-agreements
  Set-Service -Name MariaDB -StartupType Automatic
  ```
  > 也可直接解压官方免安装包到 `C:\adbcontrol\mariadb\`，用
  > `mysqld.exe --install` 注册为服务。**放在这个路径下备份脚本能免配置直接用**。
- [ ] 建库 + 建账号（用管理员账号执行）：
  ```sql
  CREATE DATABASE adbcontrol CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE USER 'adbcontrol'@'127.0.0.1' IDENTIFIED BY '换成强密码';
  GRANT ALL PRIVILEGES ON adbcontrol.* TO 'adbcontrol'@'127.0.0.1';
  FLUSH PRIVILEGES;
  ```
  > 监听确认：`netstat -ano | findstr 3306`。
  > 若只监听 `127.0.0.1` 最安全；若监听了 `0.0.0.0`，务必在防火墙里只放行本机。
- [ ] 对应 `secrets.properties`：
  ```properties
  db.host = 127.0.0.1
  db.port = 3306
  db.name = adbcontrol
  db.user = adbcontrol
  db.password = 换成强密码
  ```
  > 填 `127.0.0.1`（而非 `localhost`）可确保走 TCP 而非 socket，
  > 与连接池行为一致。`db.ssl_verify` 对本机无需设置，代码会自动关 SSL。

#### 方案 B：外部 MySQL（如 SQLPub / 云 RDS）

- [ ] 拿到 host / port / 库名 / 账号 / 密码，填入 `secrets.properties` 同样五个键
- [ ] 公网库建议加 `db.ssl_verify = true`（加密并校验证书）；
  留空/false 时是「加密但不认证」——可被中间人截获口令与 `pair_session` 里的明文密钥
- [ ] 注意：**曾在 git 历史里出现过的密码必须先重置**，不要沿用

> 外部库记得确认已开启 `utf8mb4`；备份改用方案 B 时需自备备份手段
> （云厂商快照或定时 `mysqldump`），`backup-db.ps1` 是为本地库写的。

### 1.3 目录与防火墙

- [ ] 建目录：`C:\adbcontrol\{app, upload, logs, backup}`
  （用方案 A 且把 MariaDB 免安装包解压到此目录时，再加 `mariadb`）
- [ ] 放行 8080（Tailscale/内网直访问）：
  ```powershell
  netsh advfirewall firewall add rule name="AdbControl 8080" dir=in action=allow protocol=TCP localport=8080
  ```
  > **不要**为 3306 放行公网/内网入站。数据库只监听 `127.0.0.1` 即可，
  > 后端与它同机，走回环连接。

### 1.4 看门狗计划任务（先于首次部署创建）

- [ ] 创建常驻任务（崩溃 5s 自动重启、开机自启由计划任务触发器保证）：
  ```powershell
  schtasks /create /tn AdbControlBackend /tr "cmd.exe /c C:\adbcontrol\backend-run.bat" /sc onstart /ru SYSTEM /rl HIGHEST /f
  ```
  > `backend-run.bat` 会在 `C:\adbcontrol\app\bin` 就绪前空转重试，属正常现象。

### 1.5 最小 secrets

后端启动只强依赖数据库密码；其余凭据缺失会降级运行（不阻断启动），后续在 Web 设置页补。

- [ ] 复制模板到云电脑（模板在本地仓库里，需先 scp 过去）：
  ```powershell
  scp -i <私钥> secrets.properties.template administrator@100.x.x.x:C:/adbcontrol/secrets.properties
  ```
- [ ] 必填五项（否则后端退回内存存储，重启即丢设备台账与管理员账号）：
  - `db.host` / `db.port` / `db.name` / `db.user` / `db.password`
  （方案 A 本地库时为 `127.0.0.1` / `3306` / `adbcontrol` / 你建的用户名密码）
- [ ] 建议先留占位，部署后在 Web 设置页改：
  - `server.url`（绑隧道后由设置页自动写入）
  - 其余 EMQX / R2 / Cloudflare 全部留空即可

> 用方案 A（本地新建库）时密码不会被泄露，无须重置。
> 若沿用方案 B 且用的是仓库模板里预填的 SQLPub 账号，**务必先在 SQLPub 控制台重置密码** ——
> 旧密码曾进过 git 历史。
> 若暂时不接数据库：后端会退回内存模式，此时创建的**管理员账号重启后消失**，
> 需重新走 `/api/setup` 初始化。正式部署务必配好数据库。

## 阶段 2 · 首次代码部署（本地开发机执行）

- [ ] 首选（脚本会一并同步 `deploy\*.ps1` 与 `backend-run.bat` 到云电脑）：
  ```powershell
  # 在 adbcontrol-backend 目录下
  powershell -NoProfile -ExecutionPolicy Bypass -File deploy\push-deploy.ps1 -WithScripts
  ```
  流程：`distZip` 构建 → scp 上传 `upload\backend.zip` → 同步脚本 → 远端 `deploy.ps1`
  （停任务 → kill java → 解压 → 起任务 → `/api/health` 健康检查最长 60s）
  > `-WithScripts` 会把 `deploy\*.ps1` **和** `backend-run.bat` 一起传上去。后者是
  > `AdbControlBackend` 计划任务的执行体，漏了它后端永远起不来。
- [ ] 备选：仓库根 `.\deploy-to-cloud.ps1 -RemoteHost 100.x.x.x -KeyPath <私钥>`（不同步脚本，首次需手工拷贝 `deploy\*.ps1` 与 `deploy\backend-run.bat`）
- [ ] 验证存活：`curl http://100.x.x.x:8080/api/health` 返回 `{"status":"ok"}`

## 阶段 3 · 管理员初始化（浏览器）

- [ ] 用 **Tailscale 内网地址**打开 `http://100.x.x.x:8080/`
- [ ] `admin_user` 表为空 → 自动跳「首次使用：设置管理员账号」
- [ ] 创建用户名 + 强密码（≥8 位）；此后 `/api/setup` **永久关闭**
- [ ] 忘记密码兜底：直连数据库 `DELETE FROM admin_user;` 后刷新页面重走初始化
- [ ] 登录后在「系统设置」补齐 EMQX / R2 / D1 等凭据（保存即写 `secrets.properties` 并热重启）

## 阶段 4 · Cloudflare 集成（可选，不影响登录）

- [ ] OAuth 一键授权：Cloudflare 控制台建 OAuth client（Redirect 填
  `<站点>/api/admin/settings/cloudflare/oauth/callback`）→ 设置页粘贴 Client ID/Secret → 连接
- [ ] 隧道与域名：「添加主机名」绑定对外地址（可勾选 MQTT over WSS，追加 `/mqtt → EMQX:8084`）；弹窗内可自动安装 cloudflared 连接器（需管理员）
- [ ] 存储 R2：一键全自动配置（建桶 → r2.dev → 桶级 S3 凭据 → 实测 → 落盘）；
  自定义公共域名在「存储 R2」页绑定（需 API Token/OAuth 有 Zone 权限）
- [ ] D1：数据库页逐库绑定
- [ ] 绑定后回到「总览」确认各模块卡片（隧道 / R2 / EMQX / 数据库 / CI 部署）均为已配置
- [ ] 公网自测：手机浏览器打开 `https://<你的域名>/` 应能看到登录页。
  未登录时点「系统设置」会被 403 拦截（`FORBIDDEN_AUTH_REQUIRED`）——这是预期行为，
  登录后即放行

## 阶段 5 · 自动更新链路

- [ ] 确认 `C:\adbcontrol\update-poll.ps1` 存在（阶段 2 的 `-WithScripts` 已同步）
- [ ] 管理员 PowerShell 注册轮询任务：
  ```powershell
  powershell -ExecutionPolicy Bypass -File C:\adbcontrol\setup-update-task.ps1
  ```
  （每 10 分钟 SYSTEM 出站拉 GitHub `latest` Release，按 SHA256 指纹防抖；日志 `logs\update-poll.log`）
- [ ] 打开设置页「CI 部署」tab，把两项填进 GitHub 仓库 Secrets
  （Settings and variables → Actions → New repository secret）：
  - `CLOUD_PC_UPGRADE_URL` = 页面上的 CI 回调地址（点「复制回调地址」）
  - `UPGRADE_TOKEN` = 点「生成令牌」后复制的新令牌
- [ ] 令牌只在轮换那一次明文显示，刷新页面就取不回来了。**若不填这两项，CI 仍会正常
  发布 Release，只是云电脑要等下一个 10 分钟轮询周期才部署**（不会中断）
- [ ] 注意回调地址取自命名隧道主机名，稳定可用；不要用 `*.trycloudflare.com`
  快速隧道域名，它每次连接器重连都会变
- [ ] 验证：push 一次 → Actions 的 "Notify Cloud PC" 步骤应打印 `HTTP 200`。
  打印 401 说明令牌与 `ci.upgrade_token` 不一致；打印 000 说明隧道不可达

## 阶段 6 · 验收

- [ ] `GET /api/health` 恒 200；`GET /health` 含 DB 组件状态（应 200）
- [ ] Web 登录、设置页在 Tailscale 内网可读写
- [ ] 守卫验证：未登录状态下从公网域名访问 `/api/admin/settings/secrets` 应返回 403；
  登录后同地址应 200
- [ ] 令牌隔离验证：用 `pm.token` 调 `POST /api/admin/upgrade` 应 401（部署令牌已分离）；
  用 `ci.upgrade_token` 调 `POST /api/updates/publish` 也应 401
- [ ] 手机端扫码配对成功，设备列表出现并上报心跳/电量
- [ ] 下发一次截图 / Shell 指令验证 EMQX 链路
- [ ] `schtasks /query /tn AdbControlBackend /v`、`/tn AdbControlUpdatePoll /v` 均在运行
- [ ] 数据库备份：手动跑一次 `C:\adbcontrol\backup-db.ps1`，检查 `backup\` 生成 dump
  （建议再建每日 03:00 计划任务）

---

## 附 · 常用命令速查

| 场景 | 命令 |
| :--- | :--- |
| 手动升级（本地） | `deploy\push-deploy.ps1`（`-SkipBuild` / `-ZipPath` / `-WithScripts`） |
| 手动升级（云电脑本机） | `powershell -File C:\adbcontrol\deploy.ps1 -ZipPath C:\adbcontrol\upload\backend.zip` |
| 重启后端 | `schtasks /end /tn AdbControlBackend ; schtasks /run /tn AdbControlBackend` |
| 立即检查更新 | `schtasks /run /tn AdbControlUpdatePoll` |
| 查看更新日志 | `Get-Content C:\adbcontrol\logs\update-poll.log -Tail 30` |
| 查看后端日志 | `C:\adbcontrol\logs\backend.log`（>20MB 自动滚动为 backend.old.log） |
| 手动触发备份 | `powershell -File C:\adbcontrol\backup-db.ps1` |
| 查看当前隧道地址 | `Select-String -Path C:\adbcontrol\secrets.properties -Pattern 'server\.url'` |
| 手动触发 CI 式升级 | `curl -X POST https://<你的域名>/api/admin/upgrade -H "X-Admin-Token: <ci.upgrade_token>"` |
