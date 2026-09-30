# adbcontrol-backend

> **AdbControl 云端核心服务**：基于 **Kotlin / Ktor 3.x** 构建的高性能异步管理中枢，集成设备配对签发、EMQX 凭证生命周期管理、双向 MQTT 遥测摄取归档、Cron 任务计划调度、OTA 代理分发、SPA Web 前端静态一体化托管及 Tailscale 零信任配置保护。

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/Ktor-3.0+-087CFA.svg?logo=ktor&logoColor=white)](https://ktor.io/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0+%20%2F%20MariaDB-4479A1.svg?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)](#)

---

## 📑 模块职责与定位

`adbcontrol-backend` 是整个 AdbControl 平台的“云端大脑”，在多端拓扑中承担以下关键角色：

1. **设备身份认证与配对签发**：
   - 签发具备时效性的单次配对令牌（`pairToken`），生成配对二维码数据载荷；
   - 校验设备端发起的 `/pair` 请求，通过调用 EMQX REST API 自动为设备创建唯一的内置 MQTT 账号与密码；
   - 生成 32 字节高强度随机对称会话密钥（`sessionKey`），并持久化到 `pair_session` 数据库表中（服务重启后自动从数据库热恢复，防止已配对设备失联）。
2. **命令下发与消息桥接（[`DeviceCommandBridge`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/DeviceCommandBridge.kt)）**：
   - 接收 Web 控制台对受控端下发的远程 Shell、应用启停、屏幕截图或任务栏通知指令；
   - 使用设备专属 `sessionKey` 完成 HMAC-SHA256 签名，规范化封装为协议模型并通过 EMQX REST 发布接口即时推送至 `cmd/{deviceId}` 或 `reminder/{deviceId}` 主题。
3. **高吞吐遥测摄取归档（[`TelemetryIngestService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/TelemetryIngestService.kt)）**：
   - 后端内置 Paho MQTT 客户端作为内部 Ingestor 监听 `status/+`、`location/+`、`activity/+`、`usage/+` 等主题；
   - 对受控设备上报的实时电量、网络强弱、前台焦点应用、GPS 定位历史、每日应用使用时长以及通知栏按钮点击签收（`REMINDER_RESULT`）进行全局幂等归档（通过 MySQL UNIQUE KEY 防 QoS 1 重试重复入库）。
4. **分布式任务调度引擎（[`TaskSchedulerService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/TaskSchedulerService.kt)）**：
   - 内置基于 `cron-utils` 的后台调度任务协程，支持秒级/分级 Cron 表达式；
   - 定时巡检生效中的任务，自动下发周期性通知、Shell 命令、应用时长配额和跨零点时间窗限制策略。
5. **OTA 分发与网络加速代理（[`UpdateService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/UpdateService.kt) & [`ApkProxyRoutes`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/routes/ApkProxyRoutes.kt)）**：
   - 维护版本发布清单（`app_version_manifest`），提供受控端版本检查接口 `/update/check`；
   - 内置高速 APK 代理接口 `/update/apk?url=...`，在设备端拉取 GitHub Release 遭遇国内网络阻断时充当透明下载中转源。
6. **一体化 SPA 前端静态资源托管（[`WebAppRoutes`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/route/WebAppRoutes.kt)）**：
   - 优先挂载外部静态目录（`C:\adbcontrol\web` 或 `./web`），回退至 JAR 内置资源（`static/`）；
   - 支持完整的 HTML5 History 路由回退，前端无需额外 Nginx 即可实现单服务一键全功能交付；
   - 静态资源与 API 响应默认 **gzip 压缩**（`ktor-server-compression`），弱网下首屏关键资源体积由 ~1.66MB 降至 ~466KB（约 3.6×）。
7. **Tailscale 零信任配置保护（[`SettingsRoutes`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/route/SettingsRoutes.kt) & [`NetworkSecurity`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/security/NetworkSecurity.kt)）**：
   - 敏感配置操作（查看/修改 EMQX 密码、MySQL 连接串、R2 密钥）通过网络守卫层，严格限定来自 Tailscale 虚拟专网（`100.64.0.0/10`）或本地回环（`127.0.0.1`），杜绝公网直接暴露风险。
8. **Cloudflare 云资源集成与 SSO 登录（[`CloudflareService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/CloudflareService.kt)）**：
   - 支持三种免密/少密登录方式：Cloudflare Zero Trust (Access) 请求头探测登录、API Token 校验登录、Zero Trust OIDC 授权码跳转登录；
   - 凭借 Cloudflare API Token 自动发现账户下全部资源（Access 组织、域名 Zones、Tunnels 隧道、R2 存储桶、D1 数据库）；
   - 资源发现为**只读**（列出账户下有什么，不写配置）；所有绑定均为**逐个手动**确认（隧道域名 → `server.url`、R2 桶 → 截图存储、D1 → 数据库绑定），避免自动绑错资源，绑定结果持久化至 `secrets.properties`。
9. **全双工 SSE 实时事件流（[`RealtimeEventService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/RealtimeEventService.kt) & [`RealtimeRoutes`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/route/RealtimeRoutes.kt)）**：
   - 提供 `/api/events` Server-Sent Events 端点，实现毫秒级事件推送；
   - 涵盖设备心跳与电量网络 (`device_status`)、设备掉线 (`device_offline`)、指令执行结果 (`command_result`)、任务栏通知签收回报 (`reminder_ack`)，配合前端实现零等待响应。
10. **自适应配对二维码与动态 Origin 检测（[`PairingService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/PairingService.kt)）**：
   - 生成配对令牌时，自动结合客户端请求来源（`X-Forwarded-Host` / `Host` / `Origin`）智能推导真实服务端外部访问地址；
   - 彻底解决默认配置占位域名（`example.com`）导致移动端 App 扫码请求报错 `UnknownHostException` 的问题。
11. **纯内存用户与凭据降级存储（[`DatabaseService`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/service/DatabaseService.kt)）**：
   - 在开发环境、CI 测试或轻量化部署未接入 MySQL 实例时，自动启用并发线程安全的内存用户管理与令牌存储，保障控制台登录、配对与鉴权全链路无阻断运行。


---

## 📂 项目模块结构

```text
adbcontrol-backend/
├── backend/                              # Ktor 服务主工程
│   ├── src/main/kotlin/com/adbcontrol/backend/
│   │   ├── config/BackendConfig.kt       # 环境变量与 secrets 加载器
│   │   ├── data/MysqlSchema.kt           # 数据库 Schema 常量
│   │   ├── model/                        # 数据传输对象 (DTO) 与实体
│   │   ├── plugin/                       # CORS 跨域与 Session 鉴权插件
│   │   ├── route/                        # Web API 路由 (Auth, Admin, Settings, SPA)
│   │   ├── routes/                       # 协议路由 (Pairing, Health, Update, EMQX)
│   │   ├── security/                     # 密码哈希、HMAC、限流与 Tailscale 守卫
│   │   └── service/                      # 核心业务服务层
│   └── src/main/resources/
│       ├── application.conf              # Netty 启动监听配置 (0.0.0.0:8080)
│       ├── logback.xml                   # 日志输出配置
│       ├── db/schema.sql                 # 幂等建表 DDL 脚本
│       └── static/                       # 内置打包的 Web SPA 前端资源
├── shared/                               # 跨端契约共享模块
│   └── src/main/kotlin/com/adbcontrol/shared/
│       ├── Protocol.kt                   # 基础协议常量
│       ├── net/MqttTopics.kt             # MQTT Topic 集中定义
│       ├── security/HmacSigner.kt        # HMAC 验签公用工具
│       └── model/                        # Command, Telemetry, Reminder 等模型
├── deploy/                               # 生产部署支持脚本
│   ├── backend-run.bat                   # Windows 服务看门狗循环启动脚本
│   ├── deploy.ps1                        # 云电脑端静默热解压升级脚本
│   ├── backup-db.ps1                     # 数据库每日自动备份与滚动清理脚本
│   └── update-from-github.ps1            # 从 GitHub Release 拉取自更新脚本
├── Dockerfile                            # Docker 容器化构建定义
├── fly.toml                              # Fly.io Serverless 部署元配置
├── secrets.properties.template           # 敏感凭据配置模板
└── build.gradle.kts                      # Gradle 构建脚本
```

---

## ⚙️ 配置说明

后端配置遵循 **环境变量 > `secrets.properties` 文件 > 内部已知默认值** 的逐级覆盖策略。

### 候选配置文件搜索路径
[`BackendConfig.kt`](file:///D:/手机控制/adbcontrol-backend/backend/src/main/kotlin/com/adbcontrol/backend/config/BackendConfig.kt) 启动时会依序尝试加载以下路径的 `secrets.properties`：
1. `C:\adbcontrol\secrets.properties`（云电脑主力路径）
2. `secrets.properties`（当前工作目录）
3. `../secrets.properties` 至 `../../../secrets.properties`（开发环境逐级上探）
4. `/workspace/secrets.properties`（容器或沙箱路径）

### 常用核心配置项

| 环境变量名 | `secrets.properties` 键名 | 默认值 / 示例 | 说明 |
| :--- | :--- | :--- | :--- |
| `ADB_SERVER_URL` | `server.url` | `https://api.adbcontrol.example.com` | 后端对外可访问的 URL 基地址（用于二维码生成） |
| `ADB_EMQX_HOST` | `emqx.host` | `o8cc1111.ala.cn-hangzhou.emqxsl.cn` | EMQX Broker 域名或主机 IP |
| `ADB_EMQX_PORT` | `emqx.port` | `8883` | EMQX 裸 TCP TLS 端口 |
| `ADB_EMQX_APP_ID` | `emqx.appid` | `o8cc1111` | EMQX 控制台 Application 模块生成的 App ID |
| `ADB_EMQX_APP_SECRET` | `emqx.app_secret` | *(必填敏感项)* | 用于调用 EMQX REST API 动态创建/删除账号 |
| `ADB_EMQX_REST_ENDPOINT`| `emqx.rest_endpoint` | `https://...:8443` | EMQX REST API 网关基地址 |
| `ADB_EMQX_INGEST_USERNAME`| `emqx.ingest_username` | `ingestor` | 遥测采集客户端连接 MQTT 所使用的用户名 |
| `ADB_EMQX_INGEST_PASSWORD`| `emqx.ingest_password` | *(必填敏感项)* | 遥测采集客户端连接 MQTT 所使用的密码 |
| `ADB_MYSQL_HOST` | `db.host` | `127.0.0.1` / `mysql6.sqlpub.com` | MySQL / MariaDB 数据库主机 |
| `ADB_MYSQL_PORT` | `db.port` | `3306` / `3311` | 数据库端口 |
| `ADB_MYSQL_NAME` | `db.name` | `adbcontrol` | 数据库名 |
| `ADB_MYSQL_USER` | `db.user` | `root` | 数据库用户 |
| `ADB_MYSQL_PASSWORD` | `db.password` | *(必填敏感项)* | 数据库密码 |
| `ADB_R2_ENDPOINT` | `r2.endpoint` | `https://...r2.cloudflarestorage.com`| Cloudflare R2 对象存储端点 |
| `ADB_R2_BUCKET` | `r2.bucket` | `slss-boby` | 存储截图与日志的 R2 存储桶名 |
| `ADB_R2_ACCESS_KEY` | `r2.access_key` | *(必填敏感项)* | R2 API Token AccessKey |
| `ADB_R2_ACCESS_SECRET`| `r2.access_secret`| *(必填敏感项)* | R2 API Token AccessSecret |
| —（仅文件键） | `cf.api_token` | *(可选敏感项)* | Cloudflare API Token（资源发现/绑定与 Token 登录使用，可经 Web 设置页写入） |
| —（仅文件键） | `cf.team_domain` | *(可选)* | Cloudflare Zero Trust 团队域名（如 `xxx.cloudflareaccess.com`，OIDC 登录依赖） |
| —（仅文件键） | `cf.oidc_client_id` | *(可选)* | Zero Trust OIDC 登录应用 Client ID |
| —（仅文件键） | `cf.oidc_client_secret` | *(可选敏感项)* | Zero Trust OIDC 登录应用 Client Secret |
| —（仅文件键） | `cf.oidc_redirect_uri` | *(可选)* | 自定义 OIDC 回调地址（缺省为 `<站点>/api/auth/cf-oidc/callback`） |
| `ADB_D1_DATABASE_ID` | `d1.database_id` | *(可选)* | 绑定的 Cloudflare D1 数据库 UUID |
| `ADB_D1_DATABASE_NAME` | `d1.database_name` | *(可选)* | 绑定的 D1 数据库名称 |
| `ADB_D1_ACCOUNT_ID` | `d1.account_id` | *(可选)* | D1 所属 Cloudflare Account ID |
| `SESSION_SECRET` | `session.secret` | *(自动或 64 字节随机串)* | 用于 Ktor Session Cookie 签名的密钥 |

---

## 🛠️ 构建与本地运行

### 1. 本地启动运行
```bash
# 确保在 adbcontrol-backend 目录下
./gradlew :backend:run
```

### 2. 构建可执行分发包 (Zip)
```bash
./gradlew :backend:distZip
# 产物生成于: backend/build/distributions/backend.zip
# 包含跨平台 bin 启动脚本及所有运行时依赖 Jar
```

### 3. 解压安装目录构建 (用于 Docker 镜像或本地测试)
```bash
./gradlew :backend:installDist
# 产物生成于: backend/build/install/backend/
```

### 4. 运行单元测试
```bash
./gradlew test
```

---

## 🌐 核心 REST API 清单

### 基础与健康检查
- `GET /health`：详细健康检查接口（含数据库连通性诊断，DB 不可达时返回 503 降级说明）。
- `GET /api/health`：轻量级健康存活探针（恒返回 `{"status":"ok"}`，用于部署热检查）。

### 认证与管理初始化
- `GET /api/setup-status`：检查系统是否已初始化管理员（若未初始化，前端自动切换至首次设置页）。
- `POST /api/setup`：首次设置管理员用户名与密码（仅当 `admin_user` 表为空时开放，防篡改）。
- `POST /api/login`：管理员登录验证，成功后下发加密安全的 Session Cookie。
- `GET /api/me`：获取当前登录管理员身份及权限。
- `POST /api/logout`：注销当前会话。

### Cloudflare SSO 登录
- `GET /api/auth/cf-access`：探测请求是否携带 Cloudflare Zero Trust (Access) 认证头 `Cf-Access-Authenticated-User-Email`（经 Cloudflare Tunnel + Access 保护时注入），命中时前端可提示一键免密登录。
- `POST /api/auth/cf-access-login`：基于 Access 认证头免密登录；若系统尚未初始化管理员，将以认证邮箱自动创建初始管理员。
- `POST /api/auth/cf-token-login`：凭 Cloudflare API Token 登录——校验 Token 有效性后发放管理员会话，并自动将 Token 保存至 `cf.api_token`（独立 IP 限流）。
- `GET /api/auth/cf-oidc/authorize-url`：获取 Zero Trust OIDC 授权跳转地址（需已配置 `cf.team_domain` 与 `cf.oidc_client_id`；带 `state` 防 CSRF，10 分钟有效期）。
- `GET /api/auth/cf-oidc/callback`：OIDC 授权码回调端点——置换身份（userinfo 接口优先、id_token JWT 兜底）后建立管理员会话并跳回前端 `/dashboard`；若管理员未初始化则按邮箱自动创建。

### 业务与设备管理
- `GET /api/devices`：查询已注册设备清单及最新在线状态与电量。
- `GET /api/devices/{deviceId}/status`：查询单台设备详细遥测数据（网络、前台应用、权限等）。
- `POST /api/devices/{deviceId}/command`：向设备下发指令（Shell、应用控制、截图、输入模拟）。
- `POST /api/devices/{deviceId}/screenshot`：下发截屏触发指令。
- `DELETE /api/devices/{deviceId}`：从系统解绑并删除指定设备，同步注销其 MQTT 账号。

### 任务计划与签收
- `GET /api/tasks`：查询 Cron 定时任务列表。
- `POST /api/tasks`：新建定时任务（通知、Shell 命令、应用时长限制、时段禁闭）。
- `DELETE /api/tasks/{taskId}`：删除指定任务。
- `GET /api/tasks/{taskId}/ack`：查看该任务在各受控设备上的按钮签收状态与时间明细。

### 令牌与配对交互
- `GET /api/pairing-tokens`：获取令牌列表（分为待使用、已使用及已过期列表）。
- `POST /api/pairing-tokens`：生成新的单次配对令牌及对应 QR Code 载荷。
- `POST /pair`：受控端初次连接时调用，校验 Token 并签发 MQTT 凭证与 `sessionKey`。
- `POST /renew`：受控端凭证到期前申请换发新的 MQTT 临时凭证。

### 安全设置（仅限 Tailscale / 本地访问）
- `GET /api/admin/settings/network`：网络诊断探针，返回当前访问 IP 及是否判定为 Tailscale 授信内网。
- `GET /api/admin/settings`：获取当前云端脱敏配置信息（含 `cf` Cloudflare 凭据、`d1` D1 绑定状态区块）。
- `POST /api/admin/settings`：更新系统敏感凭据（EMQX、MySQL、R2），支持热重启后端进程。
- `POST /api/admin/settings/test-emqx`：即时测试 EMQX 凭据有效性。
- `POST /api/admin/settings/test-r2`：即时测试 R2 读写连通性。

### Cloudflare 资源发现与绑定（仅限 Tailscale / 本地，或具备管理员会话；挂载于 `/api/admin/settings` 下）
- `POST /cloudflare/sync`：**只读**同步 Cloudflare 资源——校验 Token 并拉取账户、Access 组织、域名 Zones、Tunnels、R2 桶、D1 数据库全量清单；Token 与团队域名自动持久化，**不写入任何绑定**。
- `GET /cloudflare/bindings`：查询当前云端资产绑定状态（服务地址/隧道、R2 桶、D1 数据库是否已绑定及具体值）。
- `POST /cloudflare/provision-tunnel`：隧道穿透绑定——建/选隧道 → **合并**写入 ingress（不覆盖已有主机名）→ 绑定 DNS 路由 → 写回 `server.url`；可选 `enableMqttWss` 同时追加 `/mqtt → EMQX:8084` 入口，返回逐步结果与连接器命令。
- `POST /cloudflare/create-tunnel`：新建远程托管隧道（仅建隧道，不含入口规则）。
- `POST /cloudflare/auto-bind` / `POST /cloudflare/manual-bind`：一键 / 批量绑定端点（**保留兼容**，Web 设置页已不再调用，改为逐项手动绑定）。
- `POST /cloudflare/apply-tunnel`：将指定隧道域名应用为系统服务地址（`server.url`）。
- `POST /cloudflare/apply-r2`：将指定 R2 Endpoint/Bucket/密钥应用为截图存储配置。
- `POST /cloudflare/apply-oidc`：保存 Zero Trust OIDC 登录配置（团队域名、Client ID/Secret、自定义回调）。
- `POST /cloudflare/apply-d1`：绑定指定 D1 数据库（Database ID / 名称 / Account ID）。

### 升级与代理通道
- `GET /update/check`：受控端版本更新检测接口。
- `GET /update/apk?url=...`：APK 极速中转下载代理接口（支持多镜像容灾）。

---

## 🚀 运维与部署进阶

详细部署指南请参考：
- 完整部署架构说明：[docs/DEPLOY.md](docs/DEPLOY.md)
- 端到端联调运行指南：[docs/RUN.md](docs/RUN.md)
- 云电脑部署看门狗脚本：[deploy/backend-run.bat](deploy/backend-run.bat)
- 数据库定时备份维护：[deploy/backup-db.ps1](deploy/backup-db.ps1)
