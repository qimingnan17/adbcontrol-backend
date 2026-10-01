# AdbControl 部署运维手册

> 本文档详细指导 AdbControl 系统的生产环境部署、高可用配置、自动化发布流水线及数据库日常运维。

---

## 第 1 章 架构选型与环境准备清单

AdbControl 采用**单方案部署**：一台 Windows 云电脑承载后端 + 嵌入式 Web 前端。

- **特点**：后端服务 + Web 前端 + MySQL 部署于同一台云电脑；Web 前端编译产物已打进后端 dist 包，无需单独部署
- **网络**：通过 **Tailscale** 建立零信任安全私网进行管理控制与敏感配置修改，通过 **Cloudflare 命名隧道**（穿透 8080）对外发布供公网访问与设备通信
- **发布**：本地执行 PowerShell 脚本一键打包、上传、静默重载、健康自检；CI 构建产物由云电脑出站轮询或隧道回调触发自更新
- **数据库**：本机 MariaDB 或外部 MySQL（如 SQLPub）

> 早期文档中的 Fly.io / Serverless 多云方案已废弃，相关 `fly.toml`、`Dockerfile`
> 与 workflow 已从仓库移除。

### 凭证与依赖准备清单

| 项目 | 要求 / 获取方式 | 作用说明 |
| :--- | :--- | :--- |
| **JDK** | 17 或更高版本（Temurin / OpenJDK） | 编译与运行 Ktor 后端 |
| **Node.js** | 18.0 或更高版本 | 编译 Vue 3 Web 前端 |
| **EMQX Cloud** | Host / Port / App ID / App Secret / REST Endpoint | MQTT 5.0 消息总线及动态凭据签发 |
| **Cloudflare R2**| Endpoint / Bucket 名 / Access Key / Access Secret | 存放远程截图、设备运行日志 |
| **Cloudflare API Token** | Cloudflare 控制台 → My Profile → API Tokens 创建，权限需覆盖 Zones / Tunnels / R2 / D1 读取 | Web 端「系统设置 → 隧道与域名」资源发现（只读）与隧道穿透绑定（可选，也可用下方 OAuth 一键授权代替） |
| **Cloudflare OAuth Client** | Cloudflare 控制台 → Manage Account → OAuth clients 创建私有应用，Redirect URL 填 `<站点>/api/admin/settings/cloudflare/oauth/callback` | 「系统设置 → 隧道与域名」一键授权 Cloudflare，免手工创建 / 粘贴 API Token（可选） |
| **MySQL / MariaDB**| Host / Port / 数据库名 / 用户名 / 密码 | 持久化设备台账、任务规则、遥测与签收数据 |
| **Tailscale** | 官方安装并登录同一 Tailnet 账号 | 零信任内网安全管理隧道 |
| **SSH 密钥** | ed25519 或 RSA 密钥对 | 用于自动化部署流水线鉴权 |

---

## 第 2 章 主力方案：Cloud PC / 主机自动化部署

该方案已内置完整的自动化发布脚本 [`deploy-to-cloud.ps1`](file:///D:/手机控制/deploy-to-cloud.ps1)，可在本地开发机上一键完成远程部署。

### 2.1 云主机端目录规范（`C:\adbcontrol\`）
云主机预先规划以下目录结构：
```text
C:\adbcontrol\
├── app\                      # 当前正在运行的后端程序解压目录 (包含 bin/ 与 lib/)
├── upload\                   # 接收上传分发包的临时中转目录 (backend.zip)
├── logs\                     # 运行日志目录 (backend.log)
├── backup\                   # 数据库自动每日备份 SQL 归档目录
├── mariadb\                  # 本地 MariaDB 运行实例目录 (含 bin/mariadb-dump.exe)
├── secrets.properties        # 云端私密配置文件 (持久化，不受代码更新影响)
├── deploy.ps1                # 云端热替换与自检脚本 (由 deploy/deploy.ps1 提供)
├── backend-run.bat           # 后台守护循环启动脚本 (由 deploy/backend-run.bat 提供)
├── backup-db.ps1             # 数据库备份脚本 (由 deploy/backup-db.ps1 提供)
├── update-poll.ps1           # 出站轮询自更新脚本 (由 deploy/update-poll.ps1 提供)
└── setup-update-task.ps1     # 注册轮询计划任务 (由 deploy/setup-update-task.ps1 提供)
```

> 隧道对外地址没有独立缓存文件：它写在 `secrets.properties` 的 `server.url` 键里，
> 由 Web 控制台「设置 → 隧道与域名」建隧道时写入。脚本读该键即可。

### 2.2 云主机端定时任务与守护设置
在云主机上，后端建议配置为 Windows 计划任务或 NSSM 服务常驻运行：
- **服务任务名称**：`AdbControlBackend`
- **执行程序**：`cmd.exe /c C:\adbcontrol\backend-run.bat`
- **触发条件**：系统启动时自动运行，崩溃后 5 秒自动重启，并内置日志轮转机制（单文件超过 20MB 自动归档为 `backend.old.log`）。

---

### 2.3 一键自动化发布流程（本地开发机执行）

在本地仓库根目录下运行 PowerShell 发布脚本：

```powershell
.\deploy-to-cloud.ps1 `
  -RemoteHost "100.91.103.13" `
  -RemoteUser "administrator" `
  -KeyPath "C:\Users\username\.ssh\id_ed25519"
```

流水线内部自动执行 4 个阶段：
1. **阶段 1：本地编译打包**：调用 `./gradlew :backend:distZip --no-daemon` 生成包含前端静态资源的完整 `backend.zip`（若只需重新部署已生成的包，可添加 `-SkipBuild` 开关）。
2. **阶段 2：SCP 安全传输**：通过 SSH 隧道将 ZIP 包上传至远程 `C:\adbcontrol\upload\backend.zip`。
3. **阶段 3：远程静默热替换**：调用远程 `C:\adbcontrol\deploy.ps1`，依次执行：
   - 停止 `AdbControlBackend` 计划任务并安全终止旧 Java 进程；
   - 备份旧版本并解压新版本至 `C:\adbcontrol\app\`；
   - 重新拉起计划任务；
   - 轮询等待 `/api/health` 存活检测（最长等待 60 秒），自检通过后输出数据库健康状态。
4. **阶段 4：回传访问信息**：从云端 `secrets.properties` 的 `server.url` 读出隧道对外地址，在终端输出当前 Tailscale 访问地址与 Cloudflare Tunnel 公网直连网址。

---

## 第 3 章 前端构建

Web 前端不单独部署 —— `adbcontrol-web/dist` 会由 Gradle 的 `syncWebDist` 任务
同步进后端 `resources/static/`，随 `backend.zip` 一起分发，由后端自己托管
（`route/WebAppRoutes.kt`）。因此：

- **本地开发**：`cd adbcontrol-web && npm run dev`（5173 端口，已在 CORS 白名单内）
- **生产**：只需执行 `./gradlew :backend:distZip`，前端自动打包进去
- 单独跑一次前端构建仅在改了前端但想快速验证时需要：`npm run build`

> 早期「前端部署至 Cloudflare Pages」的多云方案已废弃。若要恢复独立托管，
> 注意后端 CORS 白名单（`plugin/Cors.kt`）默认只放行 `localhost`，
> 生产域名必须显式配 `CORS_ORIGINS`，且不会对 `*.pages.dev` 整体放行。

---

## 第 4 章 数据库日常维护与自动备份

### 4.1 数据库选型

数据库**不随代码分发**，需自行准备。表结构由后端启动时自动创建
（`DatabaseService.runSchema()` 执行 `CREATE TABLE IF NOT EXISTS` + 增量补列），
无需手动导入 `schema.sql`。

| 方案 | 配置 | 适用 |
| :--- | :--- | :--- |
| **A. 本地 MariaDB**（推荐） | `db.host = 127.0.0.1` | 单机部署，与后端同机走回环，不出公网 |
| B. 外部 MySQL | `db.host = <远端地址>` | 多实例或需要独立扩容；公网库须设 `db.ssl_verify = true` |

本机地址（`127.0.0.1` / `localhost`）在代码里有专门分支：自动附加
`useSSL=false&allowPublicKeyRetrieval=true`，因此本机部署**不必**设置
`db.ssl_verify`。远程库默认走「加密但不认证」（`verifyServerCertificate=false`），
可被中间人截获口令与 `pair_session` 表中的明文密钥，生产环境建议置 `true`。

本地库只需监听 `127.0.0.1`，**不要**为 3306 放行任何入站防火墙规则。

### 4.2 备份（仅方案 A 适用）

内置备份脚本 [`backup-db.ps1`](file:///D:/手机控制/adbcontrol-backend/deploy/backup-db.ps1)：

```powershell
powershell -ExecutionPolicy Bypass -File C:\adbcontrol\backup-db.ps1
```

- **dump 路径查找**：依次尝试 `C:\adbcontrol\mariadb\bin\mariadb-dump.exe` 与
  `mysqldump.exe`。把 MariaDB 免安装包解压到 `C:\adbcontrol\mariadb\` 即可免配置使用；
  若用 `winget` 装到默认路径，需自行修改脚本中的 `$dumpExe`。
- **账号密码**：脚本顶部 `$DbUser` / `$DbPass` 默认值是历史遗留，**首次使用前必须改成
  你的实际值**，否则备份会因认证失败而中断。
- **自动归档格式**：`C:\adbcontrol\backup\adbcontrol_YYYYMMDD_HHmmss.sql`
- **滚动删除**：自动清理创建时间超过 **7 天**（`$RetentionDays`）的过期 Dump。
- **配置计划任务**：建议设置每日凌晨 03:00 执行：
  ```powershell
  schtasks /create /tn AdbControlDbBackup /tr "powershell -ExecutionPolicy Bypass -File C:\adbcontrol\backup-db.ps1" /sc daily /st 03:00 /ru SYSTEM /f
  ```

> 方案 B 请用云厂商的自动快照能力，或自建 `mysqldump` 定时任务 ——
> `backup-db.ps1` 假定数据库就在本机。

---

## 第 5 章 零信任网络安全与配置管理

后端接口 `/api/admin/settings/*` 控制着 EMQX 密钥、MySQL 密码与 R2 凭据等系统命脉，安全防护至关重要：

1. **双重门禁**（`route/SettingsRoutes.kt` 的 `ensureTailscaleOrLocal()`）：
   - 第一层：`authenticate("auth-session")` —— 必须携带有效的 `ADB_SESSION` 会话 Cookie。该 Cookie 由 HMAC-SHA256 签名（密钥持久化在 `session.secret`），且会话有效性以数据库为准：改密后所有旧会话立即失效。
   - 第二层：来源校验。二者满足其一即放行 —
     - **Tailscale / 内网直连**：`NetworkSecurity.isLocalOrTailscale()` 判定 TCP 对端属于 `100.64.0.0/10`、`fd7a:115c:a1e0::/48`、私有网段或回环；
     - **管理员会话**：已登录的管理员，**包括经 Cloudflare 隧道从公网访问**。
   - 两者都不满足时返回 `403 Forbidden`（错误码 `FORBIDDEN_AUTH_REQUIRED`）。
2. **伪造防护**：信任判定**只读 TCP 对端地址**，绝不采信 `X-Forwarded-For` / `X-Real-IP` —— 直连源站的攻击者伪造 `XFF: 100.64.x.x` 即可冒充 Tailscale 节点。同理，只要出现 `CF-Connecting-IP` / `CF-Ray` / `CF-Visitor` 任一请求头，就一律判定为公网流量（`isLocalOrTailscale` 直接返回 false），此时只有管理员会话能通过。
3. **凭据热保存与平滑重启**：
   - 管理员在 Web 设置页面保存配置；
   - 后端直接将变更写回 `C:\adbcontrol\secrets.properties`（临时文件 + `ATOMIC_MOVE` 原子替换，避免写盘中途崩溃导致密钥全丢）；
   - 多数配置项需点「重启后端」生效：先返回响应，1 秒后 `exitProcess(0)`，由看门狗（`backend-run.bat`）在 5 秒内自动拉起。会话密钥、CI 令牌等少数项实时读取，改完即生效。

---

## 第 6 章 管理员首次初始化与验证

1. **初始化检测**：
   - 首次部署完成后，后端数据库 `admin_user` 表为空；
   - 浏览器打开管理控制台首页（如 `http://100.91.103.13:8080/` 或公网域名），前端自动判定未初始化状态，重定向至“首次使用：请设置管理员账号”页面。
2. **完成创建**：
   - 输入管理员用户名与强密码（至少 8 位）；
   - 点击“初始化并进入控制台”，后端完成唯一管理员创建并建立安全会话；
   - 创建成功后，初始化接口 `/api/setup` 将**永久关闭**，防止任何未授权篡改或抢注。
3. **忘记密码重置方法**：
   - 若管理员遗忘密码，直连本地 MariaDB / MySQL 数据库执行：
     ```sql
     DELETE FROM admin_user;
     ```
   - 刷新 Web 页面即可重新进入首次初始化流程。

---

## 第 7 章 Cloudflare 集成：授权与云资源绑定

> 设计细节见 [DESIGN.md 10.7/10.8](../DESIGN.md)。本章只讲部署侧操作。Cloudflare 集成为可选，不配置不影响账号密码登录。
>
> **登录方式说明**：系统仅支持账号密码登录。原 Access 头免密登录、API Token 登录与 Zero Trust OIDC 单点登录已于 2026-09-30 移除；Cloudflare 凭据只用于云资源管理，不参与登录。

### 7.1 Cloudflare OAuth 一键授权（推荐）

相比手工创建并粘贴 API Token，推荐在 Web 界面完成一次 OAuth 授权：

1. 在 Cloudflare 控制台 → **Manage Account → OAuth clients** 创建一个私有应用，Redirect URL 填 `<站点地址>/api/admin/settings/cloudflare/oauth/callback`；
2. 登录后进入「系统设置 → 隧道与域名」，展开 Cloudflare 一键授权卡片，粘贴 Client ID / Client Secret 并保存；
3. 点击「连接 Cloudflare」跳转官方授权页，同意后自动跳回设置页，票据由后端落盘并自动续期；
4. 此后资源同步、隧道穿透绑定、R2 一键配置均自动使用该票据，无需再维护长期 API Token。

> OAuth 票据**没有** `API Tokens:Write` scope，无法签发长期 Token（安全上限）；因此 R2 一键配置生成 S3 凭据仍需一枚手工 bootstrap token（见 7.2）。

### 7.2 R2 存储一键全自动配置

「存储 R2」页提供一键配置：自动建桶（幂等复用）→ 开启 r2.dev 公共读域名 → 生成**仅限该桶**的 S3 凭据 → 实测校验通过后才写入配置。

- 生成凭据需一枚带「**Account API Tokens:Edit**」的 bootstrap token（仅此一步用到；保存后可复用，不再需要时建议到 Cloudflare 控制台吊销）；
- 未保存 bootstrap token 时，每次一键配置需临时在页面粘贴一次。

### 7.3 云资源绑定（推荐走 Web 界面，逐项手动）

> 设置页重整后**不再提供一键自动绑定**：资源发现只读，所有写入都要人工逐项确认，避免自动绑错资源。

1. 完成 7.1 的 OAuth 授权（或在「隧道与域名 → 高级」粘贴 API Token 保存）后，系统自动（只读）同步账户下的域名、隧道、R2 桶与 D1 数据库；
2. **对外域名**：在「隧道与域名 → 对外服务地址」用「更换地址 / 添加主机名 / 换用新隧道」；「添加主机名」走隧道穿透（建/选隧道 → 合并 ingress → 绑 DNS），可勾选同时开启 MQTT over WSS 双栈通道（追加 `/mqtt → EMQX:8084`）；绑定后可在弹窗中一键自动安装 cloudflared 连接器（需管理员权限，失败时会给出手动命令）；
3. **存储桶**：优先用「存储 R2」页的一键全自动配置（见 7.2）；或逐桶点「绑定此存储桶」再补齐 Access Key / Secret；
4. **D1**：在「数据库」页逐库点「绑定此库」（或手动填写并绑定）；
5. 绑定结果可在「总览」页各模块卡片查看（隧道 / R2 / D1 分别显示配置状态）。

> 弱网提示：若经 Cloudflare 隧道 / 公网访问，首屏需下载前端 JS/CSS；后端已开启 gzip（首屏关键资源 ~1.66MB → ~466KB）。若仍超时，请优先用 Tailscale 内网地址访问。

### 7.4 相关配置键速查

| secrets.properties 键 | 写入途径 | 用途 |
| :--- | :--- | :--- |
| `cf.api_token` | 设置页「隧道与域名 → 高级」保存 / 资源同步时自动持久化 | Cloudflare API 调用凭据（供只读发现与隧道穿透使用） |
| `cf.oauth_client_id` / `cf.oauth_client_secret` / `cf.oauth_scopes` | 设置页 OAuth 卡片保存 | OAuth 一键授权应用凭据 |
| `cf.oauth_access_token` / `cf.oauth_refresh_token` / `cf.oauth_expires_at` | 授权回调后自动写入并续期 | OAuth 票据（无需手工维护） |
| `cf.bootstrap_token` | R2 一键配置时保存 | **高危**：带「Account API Tokens:Edit」，仅用于生成 R2 桶级 S3 凭据 |
| `r2.public_base_url` | R2 一键配置自动写入 | r2.dev 公共访问基址 |
| `d1.database_id` / `d1.database_name` / `d1.account_id` | D1 绑定；亦可用环境变量 `ADB_D1_DATABASE_ID` / `ADB_D1_DATABASE_NAME` / `ADB_D1_ACCOUNT_ID` | D1 数据库绑定标识 |
| `server.url` | 「隧道与域名」绑定 / 更换对外地址 | 对外服务地址基址；CI 回调地址也由它推导 |
| `ci.upgrade_token` | 「CI 部署」tab 点「生成 / 轮换令牌」 | **仅**门控 `POST /api/admin/upgrade`（CI 触发部署）。明文只在生成时返回一次 |
| `pm.token` | 「登录与安全」页手动填写 | **仅**门控 `POST /api/updates/publish`（向全部已配对设备推 OTA）。与上一项是两把独立的钥匙 |

> 令牌权限分离的理由：CI 只需要让本机拉取 GitHub 最新构建，而 `pm.token` 能向所有
> 已配对设备下发任意 APK。两者影响面差一个量级，不应共用。

> 历史遗留提示：若 `secrets.properties` 中仍存在 `cf.team_domain` / `cf.oidc_client_id` / `cf.oidc_client_secret` / `cf.oidc_redirect_uri` / `cf.allowed_emails` / `cf.token_login_enabled` 等键，系统已不再读取，可手动删除。
