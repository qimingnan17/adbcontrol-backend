# AdbControl 端到端运行与联调验证手册

> 本文档指导开发者与运维人员从代码构建开始，完整启动云端后端、访问 Web 控制台、安装受控端 APK、完成设备配对，并执行端到端功能验证与故障排查。

---

## 0. 前置环境与设备清单

| 检查项 | 要求与说明 |
| :--- | :--- |
| **Android 设备** | Android 11+ (API 30+)，开启「USB 调试」或「无线调试」；推荐小米 MIUI / HyperOS |
| **开发环境** | 已安装 `adb` 命令行工具，`adb devices` 可正常检测到目标设备 |
| **编译工具** | JDK 17+、Node.js 18+、Gradle 8.x |
| **云端凭证** | `secrets.properties` 已填入 EMQX App Secret、R2 访问密钥与 MySQL 密码 |
| **Shizuku** | 目标手机已安装并激活 [Shizuku](https://shizuku.rikka.app/)（支持无线调试一键激活，免电脑免 Root） |

---

## 1. 构建产物说明

在项目工程中执行构建后，核心产物路径如下：

```text
手机控制/
├── adbcontrol-backend/backend/build/distributions/backend.zip        # 后端完整分发包(含静态前端)
├── adbcontrol-controlled/controlled/build/outputs/apk/debug/controlled-debug.apk # 受控端 Agent
└── adbcontrol-web/dist/                                              # Web 控制台编译静态包
```

**一键本地构建指令**：
```bash
# 1. 编译 Web 前端
cd adbcontrol-web
npm install && npm run build
cd ..

# 2. 编译受控端 APK
cd adbcontrol-controlled
./gradlew :controlled:assembleDebug
cd ..

# 3. 编译后端分发包
cd adbcontrol-backend
./gradlew :backend:distZip
cd ..
```

---

## 2. 后端服务启动与验证

### 2.1 准备私密凭据
首次运行需从模板复制并填入凭证：
```bash
cd adbcontrol-backend
cp secrets.properties.template secrets.properties
```
修改 `secrets.properties` 中的必填项：
- `emqx.app_secret`：EMQX 控制台 Application 模块的密钥
- `emqx.ingest_password`：为 `ingestor` 遥测账号配置的密码
- `r2.access_key` / `r2.access_secret`：Cloudflare R2 API 令牌
- `db.password`：MySQL 数据库密码

### 2.2 启动后端服务
```bash
# 本地直接运行
./gradlew :backend:run

# 或解压 backend.zip 后执行
./backend/build/install/backend/bin/backend
```
服务默认监听 `0.0.0.0:8080`。

### 2.3 健康检查验证
```bash
# 快速存活检查 (进程健康)
curl -s http://localhost:8080/api/health
# 期望返回: {"status":"ok","time":"..."}

# 全组件状态检查 (含 MySQL 数据库连接状态)
curl -s http://localhost:8080/health
# 期望返回: {"status":"UP","version":"0.1.0","database":"UP"}
```

---

## 3. Web 控制台访问与首次管理员初始化

1. 打开浏览器访问 `http://localhost:8080/`（或部署后的 Tailscale IP / Cloudflare Tunnel 域名）。
2. **首次初始化引导**：
   - 系统检测到数据库管理员表未初始化，自动呈现“首次使用：请设置管理员账号”界面；
   - 填写管理员用户名（3-32 位）和密码（至少 8 位），点击“初始化并进入控制台”；
   - 后端在 `admin_user` 表中创建唯一管理员记录并自动建立登录状态。
3. 登录成功后进入 **仪表盘（Dashboard）**。
4. **登录方式**：系统仅支持账号密码登录。Cloudflare 的 Access 免密登录、API Token 登录与 OIDC 单点登录已移除；Cloudflare OAuth 一键授权仅用于云资源管理凭据（在「系统设置 → 隧道与域名」中操作，仅管理员可用），与登录无关。详见 [DEPLOY.md 第 7 章](DEPLOY.md#第-7-章-cloudflare-集成授权与云资源绑定)。

---

## 4. Android 受控端安装与特权配置

### 4.1 连接与安装 APK
```bash
# 检查设备连接
adb devices

# 安装受控端 APK (-r 允许覆盖更新)
adb install -r adbcontrol-controlled/controlled/build/outputs/apk/debug/controlled-debug.apk

# 启动应用
adb shell am start -n com.adbcontrol.controlled/.ui.MainActivity
```

### 4.2 激活并授权 Shizuku（核心特权桥）
受控端依赖 Shizuku 执行 ADB 级系统指令：
1. **无线调试自启动**：
   - 手机进入「系统设置 ➔ 开发者选项 ➔ 开启无线调试」；
   - 打开手机上的 Shizuku App，点击“通过无线调试启动”，按照引导配对并启动服务；
2. **授权受控端**：
   - 在 Shizuku App 的“已授权应用”列表中，勾选允许 `AdbControl Controlled` 访问；
   - 返回 AdbControl 受控端首页，特权状态列表中的 **Shizuku** 将显示为“已授权”。

### 4.3 基础保活与辅助授权（MIUI / HyperOS）
- **电池策略**：在系统应用设置中将 AdbControl 设置为“无限制”（自启动与后台高耗电允许）；
- **应用使用情况访问权限**：开启后受控端方可统计每日各应用的使用时长。

---

## 5. 设备配对绑定全流程

1. **云端签发 Token**：
   - 登录 Web 控制台 ➔ 进入 **令牌管理** 页面；
   - 点击“新增配对令牌”，页面将展示有效倒计时的配对 Token 以及二维码弹窗。
2. **受控端扫码/输入**：
   - 在手机受控端主界面点击“配对设备”；
   - 使用内置扫码器扫描 Web 界面二维码，或手动输入配对 Token 与后端地址。
3. **协议握手闭环**：
   - 受控端向后端 `POST /pair` 发起请求；
   - 后端校验 Token 唯一性与时效性，通过 EMQX REST API 为该设备动态创建 MQTT 认证账号，并生成专属 `sessionKey`；
   - 受控端将凭据使用 AES-GCM 安全保存至受保护存储（`ConfigStore`）；
   - 受控端以 TLS 8883 连接 EMQX，Web 控制台的设备列表中即时呈现该设备为“在线”。

---

## 6. 端到端核心功能验证清单

| 验证项 | 测试操作步骤 | 预期结果与判定标准 |
| :--- | :--- | :--- |
| **设备在线感知** | 刷新 Web 控制台「设备列表」 | 目标设备显示绿色“在线”标牌，正确显示当前电量、前台应用与网络类型 |
| **特权状态透视** | 点击进入「设备详情」页 | Shizuku 显示“已启用”，各权限徽标状态与真机一致 |
| **实时屏幕截图** | 设备详情页点击「获取当前截图」 | 约 2~3 秒内弹出高清截屏图片，图片已成功通过 R2 存储中转 |
| **远程命令下发** | 在命令控制台下发 `dumpsys battery` 或预设指令 | 受控端静默执行并在 Web 面板回显执行输出结果 |
| **任务栏通知推送** | 设备详情页点击「发送任务栏通知」，输入标题与按钮 | 手机锁屏/前台即时收到常驻 Heads-up 悬浮通知，带自定义交互按钮 |
| **通知按钮签收** | 手机端点击通知上的操作按钮（如“确认”） | Web 任务列表「签收」弹窗中实时出现该设备点击的按钮名称与精确时间戳；后端 `task_ack` 表新增记录 |
| **单日应用限时** | 新建任务：规则 `app_time_limit`，微信 1 分钟 | 当日使用微信满 1 分钟后，受控端调用 `am suspend`，微信图标变灰且不可开启；次日 00:00 自动恢复 |
| **时段禁闭策略** | 新建任务：规则 `app_time_window`（如包含当前时间点） | 处于时间窗内目标应用即刻被挂起，移出时间窗后自动恢复正常 |
| **应用使用按天统计**| 设备详情页选择昨日日期 | 图表渲染昨日各应用使用时长，正确展示应用图标与本地化应用名 |
| **OTA 自动升级** | CI 或手动触发发布新版本 APK | 受控端检测到新版本后静默拉取中转分发包并完成会话流无感安装 |

---

## 7. 常用排查与维护 ADB 指令速查

```bash
# 实时捕获 AdbControl 受控端关键日志
adb logcat -s "ControlledApp" "CommandDispatcher" "MqttManager" "AppTimeController" "UpdateRunner"

# 查看受控端后台服务与前台通知状态
adb shell dumpsys activity services com.adbcontrol.controlled

# 检查当前设备处于挂起（suspend）状态的应用包
adb shell pm list packages -u

# 查看设备当前电池状态模拟
adb shell dumpsys battery

# 手动模拟发送解挂应用指令 (调试应急)
adb shell am unsuspend com.tencent.mm

# 清理受控端应用数据 (相当于重置恢复出厂)
adb shell pm clear com.adbcontrol.controlled
```

---

## 8. 常见故障排查表

| 异常现象 | 可能原因 | 解决排查方案 |
| :--- | :--- | :--- |
| **Web 无法访问设置页面** | 提示 `FORBIDDEN_TAILSCALE_ONLY` | 该接口受零信任保护，请确保当前通过 Tailscale 内网 IP（`100.x.x.x`）或本地回环访问 |
| **后端启动报凭证缺失** | `secrets.properties` 未正确配置 | 检查 `secrets.properties` 是否存在于 `C:\adbcontrol\` 或工作目录下，确保无 `REPLACE_ME` 占位符 |
| **受控端 MQTT 连接失败** | 凭证过期或 8883 端口受阻 | 检查手机网络是否放行 8883 端口；或在 Web 端重新生成令牌重新配对 |
| **Shizuku 执行命令超时** | Shizuku 授权被系统收回或服务未运行 | 打开 Shizuku App 确认服务运行正常，并在授权列表中重新勾选受控端 |
| **截图上传失败** | Cloudflare R2 密钥配置错误 | 在 Web 设置页点击「测试 R2 读写」，确认 AccessKey/Secret 与 Bucket 正确有效 |
| **Tailscale 访问超时/极慢**（页面打不开但 curl 小请求能通） | 打洞失败，流量绕道海外 DERP 中继（~400ms、<50KB/s） | 本机跑 `tailscale ping <对端IP>`：显示 `via DERP` 即中继。修复：在云电脑安全组放行 UDP 41641 出入站后重试；或改走 Cloudflare Tunnel 公网域名访问，绕开内网链路 |
| **通知签收无回执** | MQTT 消息丢包或唯一约束拦截 | 检查手机 logcat 是否输出 `REMINDER_RESULT published`；检查数据库 `task_ack` 表是否有重复 `ack_id` |
