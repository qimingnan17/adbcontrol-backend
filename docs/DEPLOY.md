# AdbControl 部署运维手册

> 本文档详细指导 AdbControl 系统的生产环境部署、高可用配置、自动化发布流水线及数据库日常运维。

---

## 第 1 章 架构选型与环境准备清单

AdbControl 支持以下两种生产部署架构：

1. **主力方案：Cloud PC / 私有云主机一体化部署（推荐）**
   - **特点**：后端服务 + 嵌入式 Web 前端 + 本地 MariaDB/MySQL 部署于同一台云电脑或 VPS（Windows Server / Linux）；
   - **网络**：通过 **Tailscale** 建立零信任安全私网进行管理控制与敏感配置修改，通过 **Cloudflare Tunnel**（穿透 8080）对外发布供公网访问与设备通信；
   - **发布**：本地执行 PowerShell 脚本一键全自动打包、上传、静默重载、健康自检。
2. **多云方案：Serverless 云原生微服务部署**
   - **后端**：打包 Docker 镜像后托管于 Fly.io（香港节点 `hkg`）；
   - **前端**：独立编译后部署至 Cloudflare Workers / Pages；
   - **数据库**：使用云数据库服务（如 SQLPub、AWS RDS）。

### 凭证与依赖准备清单

| 项目 | 要求 / 获取方式 | 作用说明 |
| :--- | :--- | :--- |
| **JDK** | 17 或更高版本（Temurin / OpenJDK） | 编译与运行 Ktor 后端 |
| **Node.js** | 18.0 或更高版本 | 编译 Vue 3 Web 前端 |
| **EMQX Cloud** | Host / Port / App ID / App Secret / REST Endpoint | MQTT 5.0 消息总线及动态凭据签发 |
| **Cloudflare R2**| Endpoint / Bucket 名 / Access Key / Access Secret | 存放远程截图、设备运行日志 |
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
└── tunnel_url.txt            # Cloudflare Tunnel 生成的公网直连域名缓存
```

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
4. **阶段 4：回传访问信息**：读取云端 `tunnel_url.txt`，在终端输出当前 Tailscale 访问地址与 Cloudflare Tunnel 公网直连网址。

---

## 第 3 章 多云方案：Fly.io + Cloudflare Pages 部署

### 3.1 后端编译与 Dockerfile 测试
```bash
cd adbcontrol-backend

# 编译安装分发包
./gradlew :backend:installDist

# 本地容器构建验证 (可选)
docker build -t adbcontrol-backend .
docker run -p 8080:8080 adbcontrol-backend
```

### 3.2 部署到 Fly.io
```bash
# 首次部署
fly launch --name adbcontrol-api --region hkg

# 注入生产机密 (环境变量)
fly secrets set \
  ADB_SERVER_URL=https://api.yourdomain.com \
  ADB_EMQX_HOST=o8cc1111.ala.cn-hangzhou.emqxsl.cn \
  ADB_EMQX_PORT=8883 \
  ADB_EMQX_APP_ID=o8cc1111 \
  ADB_EMQX_APP_SECRET=从控制台复制 \
  ADB_EMQX_REST_ENDPOINT=https://o8cc1111.ala.cn-hangzhou.emqxsl.cn:8443 \
  ADB_EMQX_INGEST_USERNAME=ingestor \
  ADB_EMQX_INGEST_PASSWORD=从控制台复制 \
  ADB_R2_ENDPOINT=https://....r2.cloudflarestorage.com \
  ADB_R2_BUCKET=slss-boby \
  ADB_R2_ACCESS_KEY=从控制台复制 \
  ADB_R2_ACCESS_SECRET=从控制台复制 \
  ADB_MYSQL_HOST=mysql6.sqlpub.com \
  ADB_MYSQL_PORT=3311 \
  ADB_MYSQL_NAME=slss12 \
  ADB_MYSQL_USER=slss12 \
  ADB_MYSQL_PASSWORD=从控制台复制 \
  SESSION_SECRET=$(openssl rand -hex 48)

# 后续迭代部署
fly deploy --local-only
```

---

### 3.3 前端部署至 Cloudflare Workers 静态资产
```bash
cd adbcontrol-web

# 构建带后端 API 生产域名的静态包
VITE_API_BASE=https://api.yourdomain.com npm run build

# 部署至 Cloudflare
npx wrangler deploy
```

---

## 第 4 章 数据库日常维护与自动备份

为保证设备遥测数据与签收审计记录的安全性，系统在云主机端内置了全自动备份脚本 [`backup-db.ps1`](file:///D:/手机控制/adbcontrol-backend/deploy/backup-db.ps1)。

### 4.1 手动触发备份
```powershell
powershell -ExecutionPolicy Bypass -File C:\adbcontrol\backup-db.ps1
```

### 4.2 自动备份与滚动清理机制
- **备份策略**：利用 `mariadb-dump.exe` 导出完整包含结构、表数据、存储过程与触发器的 `.sql` 文件。
- **自动归档格式**：`C:\adbcontrol\backup\adbcontrol_YYYYMMDD_HHmmss.sql`。
- **滚动删除**：脚本自动检索历史备份文件，安全清理创建时间超过 **7 天**（`$RetentionDays = 7`）的过期 Dump，确保存储空间可控。
- **配置计划任务**：建议在 Windows 计划任务中设置每日凌晨 03:00 定时执行一次。

---

## 第 5 章 零信任网络安全与配置管理

后端接口 `/api/admin/settings/*` 控制着 EMQX 密钥、MySQL 密码与 R2 凭据等系统命脉，安全防护至关重要：

1. **网络守卫拦截规则**：
   - 守卫模块 [`NetworkSecurity`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/security/NetworkSecurity.kt) 对所有进入该路由的请求提取真实来源 IP；
   - 仅当客户端 IP 属于 **Tailscale 私网段（`100.64.0.0/10`）** 或 **本地回环（`127.0.0.1` / `::1`）** 时方允许放行；
   - 经由 Cloudflare Tunnel 或公网直接探测的访问将直接返回 `403 Forbidden`（错误码 `FORBIDDEN_TAILSCALE_ONLY`）。
2. **凭据热保存与平滑重启**：
   - 管理员在 Web 设置页面点击“保存配置并重启后端”；
   - 后端服务直接将变更写回 `C:\adbcontrol\secrets.properties`；
   - 启动异步协程在 1 秒延迟后退出进程，由系统看门狗（`backend-run.bat`）在 5 秒内自动重启拉起，实现无损平滑切换。

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
