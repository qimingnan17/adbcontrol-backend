# AdbControlApp

基于 **MQTT + Shizuku** 的 Android 设备管理 Agent 平台。主控端通过 EMQX Cloud 实时下发指令,被控端经多通道执行器(Shizuku / Root / Accessibility)执行,并周期回传遥测数据(状态 / 位置 / 应用行为 / 健康),最终归档至远程 MySQL。

> 当前阶段:三端代码已实现并沙箱编译通过,真机端到端联调待用户填入 EMQX/R2 凭证后跑通。本 README 既是方案文档也反映实现进度(见第十四章)。

---

## 目录

1. [项目介绍](#一项目介绍)
2. [系统架构](#二系统架构)
3. [Android Agent 设计](#三android-agent-设计)
4. [MQTT 通信协议](#四mqtt-通信协议)
5. [设备遥测系统](#五设备遥测系统)
6. [调度系统](#六调度系统)
7. [数据库设计](#七数据库设计)
8. [安全设计](#八安全设计)
9. [权限要求](#九权限要求)
10. [部署方式](#十部署方式)
11. [软件更新机制](#十一软件更新机制)
12. [目录结构](#十二目录结构)
13. [开发计划](#十三开发计划)
14. [当前进度](#十四当前进度)
15. [UI 设计规范](#十五ui-设计规范)

---

## 一、项目介绍

### 1.1 定位

> 基于 MQTT + Shizuku 的 Android 设备管理 Agent 平台

不是单纯的"ADB 远控工具",而是一个具备**遥测采集、调度执行、安全通信、远程归档**的完整 Agent 平台。一台被控端可被一个或多个主控端管理,被控端的执行能力按"Shizuku → Root → Accessibility → 普通"梯度降级。

### 1.2 核心能力

| 能力域 | 说明 |
| --- | --- |
| 远程指令 | 应用管理、输入控制(点击/滑动/按键)、文件传输、系统控制、截屏录屏 |
| 软件定时使用 | 时间窗口禁用 / 累计使用时长限制 / 提前 10 分钟提醒 |
| 设备遥测 | 在线状态、电量、网络、GPS、App 前台行为、健康检查 |
| 调度系统 | 主控端 cron 调度,本地 + 远程库双存 |
| 安全 | TLS 8883、Device ID 认证、Payload 签名、QR 配对换 token |
| 多设备 | 主控端管理多台被控端,能力雷达可视化 |
| 更新 | 应用内自更新(Play Asset Delivery / 自建差分包) |

### 1.3 技术栈

- **UI**:Jetpack Compose + Material3 + Navigation
- **DI**:Hilt
- **本地存储**:Room + DataStore(凭证走 EncryptedFile)
- **远程存储**:MySQL via JDBC + HikariCP
- **通信**:Paho MQTT(TLS 8883)+ OkHttp(EMQX REST API)
- **调度**:AlarmManager(精确定时)+ WorkManager(周期兜底)
- **ADB 桥接**:Shizuku(主)/ Root(增强)/ Accessibility(兼容)
- **配置导入**:Google Code Scanner(QR)+ EncryptedFile 加密落盘
- **更新**:Play App Update API + 自建差分包 fallback
- **其他**:kotlinx.serialization JSON、cron-utils 9.x

---

## 二、系统架构

### 2.1 拓扑

```text
┌─────────────────────────┐
│   Controller App (主控)   │
│  - cron 调度             │
│  - 任务本地+DB 双存       │
│  - MQTT Client           │
│  - EMQX REST 在线查询    │
│  - 远程回报入库          │
│  - Compose UI            │
└────────────┬─────────────┘
             │ MQTT TLS (8883)
             │ + REST (8443)
             ▼
        ┌─────────┐
        │  EMQX   │  Cloud Serverless
        │ Broker  │
        └────┬────┘
             │
             ▼
┌─────────────────────────┐
│  Controlled Agent (被控)  │
│  +---------------------+ │
│  │ Foreground Service  │ │
│  │ MQTT Client         │ │
│  │ Command Engine      │ │
│  │ Telemetry Engine   │ │
│  │ Scheduler (cron)    │ │
│  └─────────┬───────────┘ │
│            │             │
│  +─────────▼───────────+ │
│  │ Shizuku API         │ │  ← 主桥接(无 root,普通权限)
│  │ Root (optional)     │ │  ← 增强(全 ADB 能力)
│  │ Accessibility       │ │  ← 兼容(无 root 无 Shizuku 时)
│  └─────────────────────+ │
└──────────────────────────┘
```

### 2.2 模块划分

| 模块 | 类型 | 职责 |
| --- | --- | --- |
| `:shared` | Kotlin/JVM | 协议、数据模型、cron 工具 |
| `:controller` | Android Application | 主控端 |
| `:controlled` | Android Application | 被控端 Agent |

### 2.3 三层执行桥接(关键)

| 层 | 方案 | 能力 | 获取方式 |
| --- | --- | --- | --- |
| L1 主桥接 | Shizuku | shell 权限(无 root 全 ADB 等价能力) | 用户装 Shizuku App 并授权本应用 |
| L2 增强 | Root | 全部 ADB 命令 + 直读系统文件 | 设备已 root |
| L3 兼容 | Accessibility | 窗口监听 / 手势 / 截屏 / UI 控件拦截,无 root 可用 | 用户在系统设置开启无障碍 |

> 普通应用无法 `Runtime.exec("adb ...")`。**Shizuku 是默认桥接方案**:它跑在 shell 进程中,本应用通过 Binder 拿到 `IShell` 接口,从而能执行 `am` / `pm` / `input` / `settings` / `screencap` 等命令,等价于 root 的执行能力,但不需要 root。Android 11+ 还可用无线调试启动 Shizuku,完全免 PC。

---

## 三、Android Agent 设计

被控端核心是常驻 Agent Service,内含 MQTT 客户端、命令引擎、遥测引擎、调度器;执行桥接由三层 Executor 组成。

### 3.1 Foreground Service(常驻保活)

| 层级 | 手段 | 说明 |
| --- | --- | --- |
| L1 前台通知 | `startForeground` + `setOngoing(true)` | 不可滑动清除的常驻通知,系统视为前台,降低被杀概率 |
| L2 通知渠道 | `NotificationChannel` IMPORTANCE_LOW | showBadge=false,无声音,不打扰用户 |
| L3 foregroundServiceType | `connectedDevice\|dataSync` | Android 14+ 必填 |
| L4 电池白名单 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导用户加入,避免 Doze 断连 |
| L5 开机自启 | `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` | 锁屏也能直启 |
| L6 任务滑掉重启 | `onTaskRemoved` | 重新 `startForegroundService(self)` |
| L7 WorkManager 周期兜底 | `PeriodicWorkRequest` 15 分钟 | `HeartbeatGuardWorker` 检查并重连 |
| L8 厂商后台管理 | 跳转厂商自启管理页 | 按 `Build.MANUFACTURER` 路由,合规适配(不写双进程守护) |
| L9 MQTT Auto Reconnect | Paho `isAutomaticReconnect=true` | 内置指数退避重连 |
| L10 Health Check | 主控端 60s 心跳 + EMQX REST 复核 | 双向判活,降低误判 |
| L11 LWT 兜底 | MQTT Last Will | 即便 Agent 被杀,主控端秒级感知 |

```kotlin
class ControlledService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTI_ID, buildForegroundNotification())   // L1+L2
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "heartbeat_guard", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HeartbeatGuardWorker>(15, TimeUnit.MINUTES).build()
        )
    }
    override fun onTaskRemoved(rootIntent: Intent?) {             // L6
        super.onTaskRemoved(rootIntent)
        startForegroundService(Intent(this, ControlledService::class.java))
    }
}
```

### 3.2 Shizuku 权限层(主桥接)

#### 3.2.1 工作原理

Shizuku App 在设备上以 shell 进程运行(由用户通过 adb 启动一次,或 Android 11+ 通过无线调试自启动),它暴露一个 Binder 服务。本应用绑定 Shizuku Provider 后拿到 `IUserService` 的远端接口,可在 shell 上下文执行任意 shell 命令、调用被隐藏 API 限制的系统服务。

#### 3.2.2 集成

```kotlin
// Manifest
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

```kotlin
class ShizukuExecutor : CommandExecutor {
    init {
        Shizuku.bindSenderService()
        Shizuku.addRequestActivityResultResultListener(::onPermissionResult)
    }

    fun isAvailable(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    override fun exec(cmd: Command): ExecutionResult {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", buildShellCommand(cmd)))
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        return ExecutionResult(success = code == 0, output = out)
    }
}
```

#### 3.2.3 状态管理

应用启动检测 Shizuku,失败/未授权时通知主控端,主控端 UI 高亮显示需要用户授权:

```json
{ "shizuku": "disconnected", "reason": "NOT_INSTALLED | NOT_RUNNING | NOT_AUTHORIZED" }
```

> Android 11+ 用户可在系统"无线调试"中扫码启动 Shizuku,完全免 PC;老系统需用 PC `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`。

### 3.3 Command Executor(命令分派)

```text
CommandExecutor
 ├── ShizukuExecutor      (主,无 root,shell 权限)
 ├── RootExecutor         (增强,全 ADB 能力)
 ├── AccessibilityExecutor(兼容,无 root 无 Shizuku 时)
 └── NormalExecutor      (兜底,仅应用内 Notification/Toast/ContentResolver)
```

能力检测上报主控端:

```json
{
  "root": false,
  "shizuku": true,
  "accessibility": true,
  "deviceAdmin": true,
  "usageStats": true,
  "notificationListener": true
}
```

主控端据此下发命令(不发给能力不足的设备)。命令分派优先级:

```text
1. Shizuku → 可用则执行(shell 权限,等价 ADB)
   ↓ 不可用
2. Root → 可用则执行
   ↓ 不可用
3. Accessibility → 可用则执行(窗口/手势/截屏/UI 拦截)
   ↓ 不可用
4. DeviceAdmin / 系统服务 API(已激活)
   ↓ 不可用
5. NormalExecutor(仅通知/Toast)
   ↓ 命令确实无法完成
6. 失败回执 COMMAND_RESULT(success=false, error="NO_PATH")
```

| 能力矩阵 | 命令 | Shizuku/Root | Accessibility | DeviceAdmin |
| --- | --- | --- | --- | --- |
| 应用管理 | `pm install`/`am force-stop` | ✅ | ❌ | 部分(`setUninstallBlocked`) |
| 输入控制 | 点击/滑动/按键 | ✅ `input tap` | ✅ `dispatchGesture` | ❌ |
| 截屏 | `screencap` | ✅ | ✅ API 30+ `takeScreenshot` | ❌ |
| 锁屏 | `input keyevent 26` | ✅ | ✅ `GLOBAL_ACTION_LOCK_SCREEN` | ✅ `lockNow()` |
| 系统设置 | `settings put` | ✅ | ❌ | ❌ |
| UI 拦截 | 屏蔽朋友圈入口 | ❌ | ✅ `findAccessibilityNodeInfosByText` | ❌ |
| 通知 | `cmd notification post` | ✅ | ❌ | ❌ |

---

## 四、MQTT 通信协议

### 4.1 Broker 配置(运行时扫码获取,见 [8.3 QR 配对](#83-qr-配对))

| 项 | 值 |
| --- | --- |
| Host | `o8cc1111.ala.cn-hangzhou.emqxsl.cn`(扫码导入) |
| Port | 8883 (TLS) 主用 / 8084 (WSS) 备用 |
| Username | `${appid}@${deviceId}`(EMQX Serverless username 规则) |
| Password | 配对时由服务器签发的临时凭证(非明文 secret) |
| ClientId | `controller-<uuid>` / `device-<uuid>`,全局唯一 |
| cleanSession | false(持久订阅,离线消息不丢) |
| LWT | `device/offline/{deviceId}` 或 `controller/offline/{deviceId}` |

### 4.2 消息载荷

所有 payload 统一为 `WsMessage` JSON,带签名(详见 [8.2 消息签名](#82-消息签名)):

```kotlin
@Serializable
data class WsMessage(
    val id: String,           // UUID
    val type: MessageType,
    val payload: String,      // 业务 JSON
    val timestamp: Long,
    val signature: String     // HMAC-SHA256(payload + timestamp + deviceId, sessionKey)
)

enum class MessageType {
    PING, PONG,
    COMMAND, COMMAND_RESULT,
    REMINDER, REMINDER_RESULT,
    PUSH_DATA,
    // 遥测
    STATUS, HEALTH, LOCATION, ACTIVITY, USAGE,
    // 配对
    PAIR_REQUEST, PAIR_RESPONSE,
    // 更新
    UPDATE_NOTIFY,
    ACK, ERROR
}
```

### 4.3 Topic 设计

| Topic | 方向 | QoS | 说明 |
| --- | --- | --- | --- |
| `cmd/{deviceId}` | 主控→被控 | 1 | COMMAND 载荷 |
| `reminder/{deviceId}` | 主控→被控 | 1 | REMINDER 载荷 |
| `push/{deviceId}` | 主控→被控 | 1 | PUSH_DATA 载荷 |
| `result/{deviceId}` | 被控→主控 | 1 | COMMAND_RESULT / REMINDER_RESULT |
| `ping/{deviceId}` | 主控→被控 | 0 | 心跳请求 |
| `pong/{deviceId}` | 被控→主控 | 0 | 心跳响应 |
| **遥测 topic** | | | |
| `status/{deviceId}` | 被控→主控 | 1 | STATUS(在线/电量/网络/充电) |
| `health/{deviceId}` | 被控→主控 | 1 | HEALTH(MQTT/Shizuku/Service 等健康项) |
| `location/{deviceId}` | 被控→主控 | 0 | LOCATION(GPS,高频丢无碍) |
| `activity/{deviceId}` | 被控→主控 | 1 | ACTIVITY(App 前台切换事件) |
| `usage/{deviceId}` | 被控→主控 | 1 | USAGE(每用户每 App 每日时长) |
| **状态 topic** | | | |
| `device/offline/{deviceId}` | 被控 LWT | 1 | 被控掉线 |
| `controller/offline/{deviceId}` | 主控 LWT | 1 | 主控掉线 |
| **配对 topic** | | | |
| `pair/{pairToken}` | 双向 | 1 | 配对交换临时凭证(短期) |

主控订阅:`result/+`、`status/+`、`health/+`、`location/+`、`activity/+`、`usage/+`、`pong/+`、`device/offline/+`
被控订阅:`cmd/{deviceId}`、`reminder/{deviceId}`、`push/{deviceId}`、`ping/{deviceId}`、`controller/offline/+`

> `{deviceId}` 为被控端唯一标识(配对时由服务器分配,持久化),`+` 为单层通配符。

### 4.4 EMQX Cloud REST API(主控端在线查询)

| 接口 | 用途 |
| --- | --- |
| `GET /subscriptions?_page=1&_limit=100` | 列出部署下所有订阅(clientid/topic/qos) |
| `GET /clients/{clientid}/subscriptions` | 查指定被控端的订阅 |
| `GET /clients` | 列出所有在线客户端 |

鉴权 HTTP Basic,用 `appid:app_secret`(EMQX 控制台分配)。REST 端口默认 8443(HTTPS,与 8883 共用证书)。主控端 Dashboard 启动调 `listOnlineDevices()`,每设备 60s 调 `isOnline(id)` 复核,与心跳 pong 交叉验证,降低误判。

---

## 五、设备遥测系统

被控端周期采集并回传遥测数据。主控端入库并供 UI 展示,远程 MySQL 做长期归档。

### 5.1 基础状态回报(`status/{deviceId}`)

```json
{
  "online": true,
  "battery": 82,
  "charging": true,
  "network": "wifi",            // wifi / cellular / none
  "networkStrength": -52,      // dBm
  "screenOn": false,
  "foregroundPkg": "com.tencent.mm",
  "deviceId": "device-a001",
  "timestamp": 1718000000000
}
```

- 触发:状态变化(电量±5%/网络切换/屏幕开关)立即上报 + 兜底每 5 分钟一次
- QoS 1,避免漏报

### 5.2 电量

包含在 5.1 STATUS 中。主控端 UI 在电量 < 20% 时高亮提醒,可触发"低电量自动锁屏"任务。

### 5.3 GPS 位置(`location/{deviceId}`)

```json
{
  "latitude": 31.2304,
  "longitude": 121.4737,
  "accuracy": 20.0,
  "speed": 0.5,
  "provider": "gps",            // gps / network
  "fenceEvent": null,          // enter / leave / null
  "timestamp": 1718000000000
}
```

- 采集:`LocationManager` GPS + Network 双 provider
- 上报周期:默认 15 分钟(可远程配置),QoS 0
- 电子围栏:主控端下发圆形围栏(lat/lng/radius)至被控端 Room `fence` 表;进入/离开触发 `fenceEvent` 并 QoS 1 上报
- 低功耗:屏幕关闭时延长至 30 分钟;定位失败回退 network provider

### 5.4 App Activity(应用行为,`activity/{deviceId}`)

```json
{
  "event": "APP_FOREGROUND",   // APP_FOREGROUND / APP_BACKGROUND / APP_BLOCKED
  "package": "com.tencent.mm",
  "appName": "微信",
  "userId": "user-a001",       // 多用户场景
  "durationMs": 0,            // 前台时为 0,后台时填本次前台时长
  "timestamp": 1718000000000
}
```

- 采集优先级:
  1. **Shizuku `dumpsys activity`** — 主路径,实时解析 `mResumedActivity`,精度高
  2. **AccessibilityService `TYPE_WINDOW_STATE_CHANGED`** — 备用,无需 root,精度中等
  3. **UsageStatsManager `queryEvents`** — 兜底,延迟约 1-2 秒
- 上报:前台切换事件 QoS 1,确保不漏
- 入库:`app_activity_log`(每日使用时长由主控端聚合 `durationMs` 得到)

### 5.5 设备健康(`health/{deviceId}`)

```json
{
  "mqtt": true,
  "service": true,
  "shizuku": true,
  "root": false,
  "accessibility": true,
  "deviceAdmin": true,
  "batteryWhitelist": true,
  "usageStats": true,
  "notificationListener": true,
  "android": "15",
  "appVersion": "1.2.3",
  "lastBootAt": 1718000000000,
  "timestamp": 1718000000000
}
```

- 触发:启动时全量上报 + 每 30 分钟增量上报
- 主控端 UI 展示能力雷达:

```text
设备在线
✓ MQTT
✓ Shizuku
✓ 后台白名单
✓ 服务运行
✓ 电量(82%)
✓ 位置(精度 20m)
✗ Root
```

---

## 六、调度系统

主控端 cron 调度,被控端只接受命令(除应用时长限制采样由被控端做本地兜底)。

### 6.1 软件定时使用(应用时间管理)

三种模式可组合:

| 模式 | 触发 | 被控端动作 |
| --- | --- | --- |
| 时间窗口禁用/放开 | cron 成对任务(SUSPEND / UNSUSPEND) | `pm disable-user` / `pm enable`(Shizuku)或无障碍切回 |
| 累计使用时长限制 | 被控端周期采样 `UsageStatsManager`,达到阈值 | suspend 该 App + 提醒 |
| 最后 10 分钟提醒 | 主控端在窗口结束前 10 分钟 cron 触发 | 下发 REMINDER,被控端发本地通知 |

```kotlin
// 主控端窗口型任务(成对)
fun scheduleWindow(rule: WindowRule) {
    scheduleCron(rule.startCron, Command(APP_TIME, "SUSPEND", mapOf("pkg" to rule.pkg)))
    scheduleCron(rule.endCron,   Command(APP_TIME, "UNSUSPEND", mapOf("pkg" to rule.pkg)))
    scheduleAt(cronPrevMinutes(rule.endCron, 10),
        WsMessage(uuid(), REMINDER, payloadOf(rule.pkg, "还剩 10 分钟")))
}
```

### 6.2 任务双存

- DataStore:任务列表快照(快速读取,UI 用)
- Room:全量任务 + 执行历史(可追溯)
- MySQL:跨设备长期归档

### 6.3 多设备下发粒度

| 粒度 | 实现 |
| --- | --- |
| 单设备 | `publish("cmd/$deviceId")` |
| 多设备(显式) | 遍历 `device_ids` 逐个 publish |
| 全广播 | 查所有 `enabled=1 AND status=online` 逐个 publish |

> 不用 MQTT 通配 publish(主控端是 publish 方),应用层循环便于每台回报对账。

---

## 七、数据库设计

### 7.1 本地 Room(两端各一份)

主控端 `device` / `task` / `execution_log` / `app_usage_local`(近期 7-30 天) / `device_status`(在线快照)
被控端 `local_results` / `fence`(围栏缓存) / `local_app_usage`(本地累加) / `pending_messages`(断线缓存)

### 7.2 远程 MySQL DDL

```sql
-- 设备台账
CREATE TABLE device (
  device_id    VARCHAR(64)  PRIMARY KEY,
  name         VARCHAR(128),
  appid        VARCHAR(64)  NOT NULL,
  first_seen   BIGINT       NOT NULL,
  last_seen    BIGINT       NOT NULL,
  status       VARCHAR(16)  NOT NULL    -- online/offline
);

-- 设备实时状态(在 device 基础上扩展遥测)
CREATE TABLE device_status (
  device_id        VARCHAR(64)  PRIMARY KEY,
  online           BOOLEAN      NOT NULL,
  battery          INT,
  charging         BOOLEAN,
  network          VARCHAR(16),
  network_strength INT,
  screen_on        BOOLEAN,
  foreground_pkg   VARCHAR(128),
  shizuku          BOOLEAN,
  root             BOOLEAN,
  accessibility    BOOLEAN,
  device_admin     BOOLEAN,
  android_version  VARCHAR(16),
  app_version      VARCHAR(32),
  last_seen        BIGINT       NOT NULL,
  FOREIGN KEY (device_id) REFERENCES device(device_id)
);

-- 用户(多用户场景)
CREATE TABLE user (
  user_id     VARCHAR(64)  PRIMARY KEY,
  device_id   VARCHAR(64)  NOT NULL,
  name        VARCHAR(128),
  avatar      VARCHAR(256),
  first_seen  BIGINT       NOT NULL,
  FOREIGN KEY (device_id) REFERENCES device(device_id),
  INDEX idx_dev_user (device_id, user_id)
);

-- 任务
CREATE TABLE task (
  task_id      BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id    VARCHAR(64)  NOT NULL,
  rule_type    VARCHAR(32)  NOT NULL,    -- WINDOW / LIMIT / ONESHOT
  cron_expr    VARCHAR(64),
  command_json TEXT         NOT NULL,
  enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at   BIGINT       NOT NULL,
  FOREIGN KEY (device_id) REFERENCES device(device_id)
);

-- 命令执行日志(msg_id 幂等,防 QoS 1 重发导致重复入库)
CREATE TABLE execution_log (
  id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
  task_id      BIGINT       NULL,
  device_id    VARCHAR(64)  NOT NULL,
  msg_id       VARCHAR(64)  NOT NULL,
  success      BOOLEAN      NOT NULL,
  output       MEDIUMTEXT,
  duration_ms  INT          NOT NULL,
  executed_at  BIGINT       NOT NULL,
  UNIQUE KEY uq_msg_id (msg_id),                          -- 幂等键
  INDEX idx_dev_time (device_id, executed_at)
);

-- 应用清单
CREATE TABLE app (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  pkg         VARCHAR(128) NOT NULL,
  label       VARCHAR(128),
  is_blocked  BOOLEAN      NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_dev_pkg (device_id, pkg)
);

-- 应用每日使用时长(每用户每应用每日聚合,UNIQUE 复合键保证 upsert 不重复)
CREATE TABLE app_usage_daily (
  id              BIGINT   PRIMARY KEY AUTO_INCREMENT,
  device_id       VARCHAR(64)  NOT NULL,
  user_id         VARCHAR(64)  NOT NULL,
  pkg             VARCHAR(128) NOT NULL,
  usage_minutes   INT          NOT NULL,
  date            DATE         NOT NULL,
  uploaded_at     BIGINT       NOT NULL,
  UNIQUE KEY uq_dev_user_pkg_date (device_id, user_id, pkg, date),   -- 同日同 pkg 同 user 只 upsert
  INDEX idx_user_date (user_id, date),
  INDEX idx_pkg_date (pkg, date)
);

-- 位置历史(高频写入,接受重复,QoS 0 不要求幂等)
CREATE TABLE location_history (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  user_id     VARCHAR(64),
  lat         DOUBLE       NOT NULL,
  lng         DOUBLE       NOT NULL,
  accuracy    FLOAT,
  speed       FLOAT,
  provider    VARCHAR(16)  NOT NULL,
  fence_event VARCHAR(32),
  reported_at BIGINT       NOT NULL,
  INDEX idx_dev_time (device_id, reported_at)
);

-- 应用行为日志(用 device_id + pkg + occurred_at 三元组幂等,5 秒内同事件视为重复)
CREATE TABLE app_activity_log (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  user_id     VARCHAR(64),
  event       VARCHAR(32)  NOT NULL,
  pkg         VARCHAR(128) NOT NULL,
  app_name    VARCHAR(128),
  duration_ms BIGINT,
  occurred_at BIGINT       NOT NULL,
  UNIQUE KEY uq_dev_pkg_event_time (device_id, pkg, event, occurred_at),  -- 幂等键
  INDEX idx_dev_pkg_time (device_id, pkg, occurred_at),
  INDEX idx_user_time (user_id, occurred_at)
);

-- 通知日志(用 device_id + pkg + posted_at 三元组幂等,同时间戳同通知视为重复)
CREATE TABLE notification_log (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  user_id     VARCHAR(64),
  pkg         VARCHAR(128) NOT NULL,
  title       VARCHAR(256),
  text        TEXT,
  posted_at   BIGINT       NOT NULL,
  UNIQUE KEY uq_dev_pkg_posted (device_id, pkg, posted_at),  -- 幂等键
  INDEX idx_dev_pkg_time (device_id, pkg, posted_at),
  INDEX idx_user_time (user_id, posted_at)
);
```

### 7.2.1 幂等与防重复处理(关键)

MQTT QoS 1 + cleanSession=false 保证消息不丢,但带来**重复消费**风险:主控端处理消息时若崩溃/超时未回 ACK,EMQX 会重发同一消息。重复入库会导致日志表被刷爆、统计错误。处理策略:

```kotlin
class ControllerMessageHandler(
    private val room: RoomDatabase,
    private val mysql: RemoteSyncRepository,
) : MqttCallback {
    // 本地 LRU 缓存近期已处理 msg_id,重复的直接 ACK 不处理
    private val processedIds = Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                size > 5000  // 缓存上限 5000 条
        }
    )

    override fun messageArrived(topic: String, message: MqttMessage) {
        val wsMsg = Json.decodeFromString<WsMessage>(String(message.payload))
        // 1) 签名校验
        if (!verifySignature(wsMsg)) { message.isAck = true; return }
        // 2) 重发检测(本地 LRU 缓存)
        if (!processedIds.add(wsMsg.id)) {
            // 已处理过,直接 ACK 不重复入库
            message.isAck = true
            return
        }
        // 3) 写 Room(upsert,UNIQUE KEY 拦截重复)
        runCatching {
            room.withTransaction {
                when (wsMsg.type) {
                    COMMAND_RESULT -> executionLogDao.upsert(ExecutionLog(...).copy(msgId = wsMsg.id))
                    ACTIVITY       -> activityLogDao.upsert(...)
                    USAGE          -> appUsageDao.upsertDaily(...)
                    // location_history QoS 0,允许重复,直接 insert
                    LOCATION       -> locationDao.insert(...)
                    ...
                }
            }
        }
        // 4) ACK 给 EMQX(让 EMQX 不再重发)
        message.isAck = true
        // 5) 异步投递 JDBC MySQL(失败入 dead-letter,不影响 ACK)
        syncExecutor.submit { runCatching { mysql.upsert(...) }.onFailure { deadLetterQueue.add(...) } }
    }
}
```

> 三道防线:
> - **L1 本地 LRU 缓存**:重复 msg_id 在内存层拦截,99% 重发在此挡掉
> - **L2 Room UNIQUE KEY**:LRU 没拦住(主控端重启、缓存丢失)的,数据库 UNIQUE KEY upsert 拦截
> - **L3 EMQX ACK**:成功处理后立即 ACK,从源头停止重发
> - 失败场景:写入失败不 ACK,EMQX 重发,下次再尝试,直到成功

### 7.3 同步策略

- **写**:MQTT `messageArrived` 收到 result/usage/location/activity → 写本地 Room → 投递 JDBC 队列 upsert MySQL,失败 3 次入 dead-letter 表
- **读**:UI 默认读本地 Room;远程历史页可切换查 MySQL
- **连接池**:HikariCP `maxPoolSize=2`,短事务,每条 INSERT 短连接
- **上传周期**:
  - `app_usage_daily`:整点 cron 上传(`0 * * * *`)
  - `location_history`:实时 QoS 0,15 分钟采样
  - `app_activity_log`:事件触发即时上报
  - `notification_log`:被控端按白名单过滤后即时上报

---

## 八、安全设计

### 8.1 MQTT 通道

- **TLS 8883**:EMQX Cloud Serverless 强制 TLS,密码不明文在线缆传输
- **Device ID 认证**:username = `${appid}@${deviceId}`,配对时由服务器签发,单设备单 username
- **ACL**:EMQX 控制台为每个 username 限定可 publish/subscribe 的 topic 前缀(防止越权)
- **cleanSession=false** + QoS 1:持久会话,断线期间消息不丢

### 8.2 消息签名

每条 `WsMessage` 带 `signature = HMAC-SHA256(payload + timestamp + deviceId, sessionKey)`。`sessionKey` 由配对时服务器随机下发,与 `deviceId` 绑定,存 EncryptedFile。

防:
- **伪造设备**:无 `sessionKey` 签不出有效 signature,EMQX 也可 ACL 拒绝
- **重放攻击**:`timestamp` + 5 分钟窗口校验,过期拒绝
- **非授权控制**:ACL 限定每个 clientid 只能 publish 自己的 `result/+` `status/+` 等,不能伪造他人

主控端收到消息先验签,验签失败丢弃并告警。

### 8.3 QR 配对

> 设计原则:**QR 码不包含长期凭证**(不放明文 username/password/secret),避免 QR 泄露导致设备控制权限泄露。

#### 8.3.1 配对流程

```text
1. 主控端在服务器 Web 控制台生成配对码:
   - server 生成 pair_token (短期,默认 10 分钟过期)
   - QR 码内容仅: { "v":1, "server":"https://api.xxx.com", "pairToken":"pt_xxx", "deviceId":"device-a001" }

2. 被控端扫码 → 用 pairToken 调 server:
   POST https://api.xxx.com/pair
   { "pairToken":"pt_xxx", "deviceId":"device-a001", "deviceName":"son-tablet",
     "pubKey":"<Ed25519 公钥,本机生成>" }

3. server 校验 pairToken → 下发临时凭证:
   {
     "broker": { "host":"...", "port":8883, "appid":"o8cc1111",
                 "username":"o8cc1111@device-a001",
                 "password":"<临时 MQTT 密码,7 天有效>" },
     "r2": { "endpoint":"https://696e933486bc331658bce6378aaceaea.r2.cloudflarestorage.com",
             "bucket":"slss-boby", "region":"auto",
             "accessKey":"<R2 AccessKey>",
             "accessSecret":"<R2 AccessSecret>",
             "publicRead": true },
     "sessionKey":"<HMAC 密钥,长期>",
     "expiresAt":1718606400000
   }
   server 同时把 deviceId/pubKey/sessionKey 写入 EMQX ACL 与本地 DB

4. 被控端 EncryptedFile 落盘 → 用临时凭证连 MQTT → 上线后向主控端 publish PAIR_COMPLETE
   主控端确认后可远程下发"换发长期凭证"指令(用 sessionKey 签名)

5. 临时凭证 7 天到期前,被控端用 sessionKey 签名调 server /renew 续签
```

#### 8.3.2 加密落盘

凭证通过 `androidx.security.crypto.EncryptedFile`(Android Keystore 包裹的 AES-GCM)存:

```kotlin
class ConfigStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val file = EncryptedFile.Builder(
        context, File(context.filesDir, "app_config.enc"),
        masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM
    ).build()
    fun save(c: AppConfig) = file.openFileOutput().use { it.write(Json.encodeToString(c).toByteArray()) }
    fun load(): AppConfig? = runCatching {
        file.openFileInput().bufferedReader().use { Json.decodeFromString(it.readText()) }
    }.getOrNull()
}
```

`android:allowBackup="false"`,凭证不进备份。

---

## 九、权限要求

### 9.1 Android Manifest 权限

```xml
<!-- 网络 / MQTT TLS -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 前台服务(Android 14+ 还需细分 type 权限) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- 前台通知(Android 13+ 需运行时申请) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 电池优化白名单 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- 开机自启 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- 定位 -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- 应用使用时长 -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />

<!-- 锁屏后启动 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- 前台服务类型权限(Android 14+) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

### 9.2 系统级授权(需用户在设置中开启)

| 服务 | 权限 | 获取方式 |
| --- | --- | --- |
| AccessibilityService | `BIND_ACCESSIBILITY_SERVICE` | 设置 → 无障碍 |
| DeviceAdmin | `BIND_DEVICE_ADMIN` | 跳 `ACTION_ADD_DEVICE_ADMIN` 引导 |
| UsageStatsManager | `PACKAGE_USAGE_STATS` | 跳 `ACTION_USAGE_ACCESS_SETTINGS` |
| LocationManager | `ACCESS_FINE_LOCATION` 等 | 运行时申请 + 后台定位引导 |
| NotificationListenerService | `BIND_NOTIFICATION_LISTENER_SERVICE` | 设置 → 通知访问 |

### 9.3 Shizuku 授权

用户在 Shizuku App 中授权本应用(普通权限即可,无需 root)。应用启动检测 Shizuku 状态,失败时通知主控端,UI 高亮"需要授权"。

---

## 十、部署方式

### 10.1 后端服务器(可选,用于配对与续签)

- 语言:Kotlin + Ktor / Spring Boot
- 数据库:MySQL(同 7.2)
- 职责:pairToken 签发、临时 MQTT 凭证签发、ACL 写入 EMQX REST、长期凭证续签
- 部署:Docker,HTTPS 反向代理,放行主控端与被控端出口 IP

### 10.2 EMQX Cloud Serverless

- 实例:`o8cc1111.ala.cn-hangzhou.emqxsl.cn`
- 端口:8883 (TLS) / 8084 (WSS) / 8443 (REST)
- ACL:为每个 `${appid}@${deviceId}` 配置 topic 前缀 ACL
- 凭证管理:在控制台 Application 模块创建,不直接复用 appid/app_secret 给设备(改用配对签发的临时凭证)

#### 10.2.1 实际账户限制与容量评估

> 账户实际限制以控制台显示为准(免费层默认值,可能与官方文档不同,以实测数据为准):

| 资源 | 实测限制 | 单位 | 说明 |
| --- | --- | --- | --- |
| 总连接数 | **30** | 个 | 含主控 + 全部被控,24×7 全在线最多 29 台被控 |
| 存储 | **500** | MB | 离线消息 + retain 消息 + 持久会话累积 |
| 请求数 | **36000** | 次/小时 | 约 10 req/s,平均峰值约 36000/3600 |
| 下次重置 | 每小时整点 | | 请求数按小时滚动 |

#### 10.2.2 本方案容量测算(基于 30 连接限制)

| 资源 | 测算 | 是否够用 | 备注 |
| --- | --- | --- | --- |
| **连接数** | 1 主控 + 29 被控 24×7 = 30 连接 | ✅ 顶满 | 不可超 30,超过会被拒绝新连接 |
| **session 分钟数** | 29 被控 + 1 主控 全在线 = 129.6 万分钟/月 | ⚠️ 超免费 100 万 | 超出部分约 ¥2.4/月(¥8/百万分钟),约 23 台 24×7 = 100 万刚好免费 |
| **存储** | 主控离线时,被控所有 QoS 1 上报堆积;每条 ~200B | ⚠️ 需调优 | 主控离线 1 天 ≈ 43 MB,1 周 ≈ 300 MB,接近上限 |
| **请求数(总量)** | 30 台 × 5 条/分钟 = 9000/小时 | ✅ 余量充足 | 远低于 36000 |
| **请求数(峰值)** | 整点 USAGE 集中触发,30 台 × 1 条 = 30 req/s | ⚠️ 错峰处理 | 见下"上报错峰策略" |

#### 10.2.3 缓解策略(必须实现)

1. **STATUS 改 QoS 0**:状态回报只需最新值,断线即丢无碍,降低存储压力
2. **LOCATION 已是 QoS 0**:高频定位消息不持久化
3. **message TTL**:EMQX 控制台配置消息保留期 7 天,过期自动清理
4. **主控端尽量在线**:若主控离线 > 24h,Broker 缓存压力陡增,可触发告警
5. **USAGE 上报错峰**:30 台设备整点同时上报会瞬间打 30 req/s。改为按 `deviceId` hash 到整点内不同分钟:
   ```kotlin
   // 被控端整点上报错峰:hash(deviceId) % 60 = 偏移分钟
   val offsetMin = abs(deviceId.hashCode()) % 60
   scheduleCron("$offsetMin * * * *", USAGE_UPLOAD)
   ```
6. **设备数硬上限**:方案层加 `MAX_DEVICES = 23` 配置项,超过拒绝新配对,避免连接数撑爆 30
7. **主控端重连策略**:Paho `maxReconnectDelay = 30s`,断线后快速恢复消费,降低离线堆积

#### 10.2.4 容量超限时的扩展路径

| 限制 | 接近上限时 | 解决方案 |
| --- | --- | --- |
| 连接数 > 30 | 设备数增长 | 升级 Dedicated Flex($234/月起,1000+ 连接) / 自建 EMQX 开源版 |
| 存储 > 500 MB | 主控长期离线 | 升级 / 主控改双活 / 降低持久化级别(部分 topic 改 QoS 0) |
| 请求数 > 36000/小时 | 设备 × 频率超限 | 降低非关键 topic 上报频率 / 错峰更细化 |

#### 10.2.5 极限场景

- **截屏消息(> 1 MB)**:Serverless 单消息 1 MB 上限,截屏 PNG 通常 2-4 MB。**截屏必须走 HTTP 旁路**(详见 10.4 Cloudflare R2):
  - 被控端用 Shizuku `screencap` 写本地 → 上传 R2 → MQTT 只回传 URL
  - 主控端收到 URL 后 HTTP 拉取图片展示
- **文件传输(> 1 MB)**:同理走 R2 旁路,MQTT 只传 URL + 校验信息

### 10.3 MySQL

- 推荐:阿里云 RDS MySQL 8.x
- 网络:放行主控端出口 IP(被控端不直连)
- 初始化:执行 7.2 的 DDL 脚本

### 10.4 Cloudflare R2(大文件 / 截屏 HTTP 旁路)

EMQX Serverless 单消息 **1 MB 上限**,截屏 PNG(1080p 约 2-4 MB)、APK 安装包、录屏视频、文件传输必须走 HTTP 旁路。R2 因**永久免费 egress + S3 兼容**成为最佳选择。

#### 10.4.1 端点

| 项 | 值 |
| --- | --- |
| Endpoint | `https://696e933486bc331658bce6378aaceaea.r2.cloudflarestorage.com` |
| Bucket | `slss-boby` |
| 协议 | S3 API(用 AWS S3 SDK 直连) |
| Region | `auto`(R2 全球边缘) |

#### 10.4.2 免费额度(永久)

| 资源 | 免费额度 | 本方案需求 | 是否够用 |
| --- | --- | --- | --- |
| 存储 | 10 GB / 月 | 截屏 30 设备 × 10 张/天 × 2 MB × 30 天 ≈ 18 GB(保留 7 天 = 4.2 GB) | ✅ 配合生命周期清理 7 天后删除 |
| Class A 写(PutObject 等) | 100 万 / 月 | 30 设备 × 10 次/天 × 30 = 9000 | ✅ 远低于上限 |
| Class B 读(GetObject 等) | 1000 万 / 月 | 主控按需拉取,估算 5 万/月 | ✅ 余量充足 |
| Egress | **永久免费** | 主控拉取截屏,每张 2 MB | ✅ 这是 R2 最大优势 |

> 关键:R2 **egress 永久免费**,主控端任意频率拉截屏都不产生流量费用。对比阿里云 OSS 流量费 ¥0.5/GB,30 设备每天 10 张截屏 = 600 MB/天 = 18 GB/月 = ¥9/月,R2 直接省掉。

#### 10.4.3 上传/下载流程

被控端(截屏为例):

```kotlin
class ScreenshotUploader(
    private val s3: S3Client,             // R2 endpoint + AccessKey/Secret
    private val bucket: String = "slss-boby",
) {
    suspend fun upload(deviceId: String, png: ByteArray): String {
        val key = "screenshots/$deviceId/${System.currentTimeMillis()}.png"
        s3.putObject(
            { it.bucket(bucket).key(key).build() },
            RequestBody.fromBytes(png)
        )
        // 返回可访问的 URL(若 bucket 设了 public,直接拼 URL;否则用 presigned URL)
        return "https://696e933486bc331658bce6378aaceaea.r2.cloudflarestorage.com/slss-boby/$key"
    }
}
```

主控端拉取:HTTP GET URL 即可(若 bucket 公开);私密场景用 presigned URL(被控端上传时生成有效期 1 小时的 URL 一起 publish 到 MQTT)。

#### 10.4.4 凭证管理

- R2 `AccessKey` / `AccessSecret` 通过 QR 配对时由服务器下发(同 MQTT 凭证一起,走 EncryptedFile 加密落盘)
- 不直接复用 EMQX 凭证,R2 在 Cloudflare 控制台单独创建 API Token
- bucket 建议设为 **public read**(截屏 URL 直接访问,简化主控端逻辑)+ **private write**(只有凭证能上传)

#### 10.4.5 R2 配置项

| 配置 | 推荐值 | 原因 |
| --- | --- | --- |
| 公共访问 | 开启 read | 主控端直接 GET URL,无需 presign |
| CORS | 允许主控端域名 | 主控若在浏览器/Web 控制台预览,需 CORS |
| 生命周期规则 | 7 天后自动 Delete | 防存储无限增长,7 天足够排查历史 |
| 存储类 | Standard | 截屏频繁读,不走 Infrequent Access(避免 retrieval 费用) |
| 加密 | 服务端 SSE-S3 | 透明加密,客户端无感 |

#### 10.4.6 适用场景清单

| 场景 | MQTT 传什么 | R2 传什么 |
| --- | --- | --- |
| 截屏 | screenshot URL + sha256 | PNG 文件 |
| 录屏 | video URL + duration | MP4 文件 |
| 文件传输 | file_url + meta(大小/校验) | 文件本身 |
| APK 自更新 | patch_url + checksum | APK / 差分包 |
| 应用清单上报 | snapshot URL(包列表太大时) | JSON 大对象 |

> 原则:**小于 100 KB** 走 MQTT 直接传(快);**大于 100 KB** 一律走 R2,MQTT 只传 URL。截屏、APK、录屏都在后者范围。

### 10.5 被控端 Agent APK 分发

- 渠道:Play Store / 自建更新服务器(详见第十一章)
- 首次启动:扫 QR 码配对 → 引导用户开启 Shizuku / 无障碍 / DeviceAdmin / 电池白名单
- 配置加载:从 EncryptedFile 读取凭证,初始化 MQTT Client

### 10.6 主控端 APK 分发

- 渠道:Play Store / 自建更新服务器
- 首次启动:扫服务器 QR(含 `server_url` + `pairToken` + 主控端 `controllerId`)→ 与后端交换长期凭证

---

## 十一、软件更新机制

### 11.1 双通道更新

| 通道 | 适用 | 实现 |
| --- | --- | --- |
| Play App Update | 上架 Play Store 的版本 | Google Play 的 `AppUpdateManager`,支持 immediate(全屏强制)/ flexible(后台下载)两种模式 |
| 自建更新服务器 | 自分发 APK(企业内部) | HTTPS 检查版本 → 差分包下载 → 应用 |

### 11.2 自建更新服务器协议

```http
GET https://api.xxx.com/update/check?deviceId=d-a001&currentVersion=1.2.3
```

响应:

```json
{
  "latestVersion": "1.3.0",
  "forceUpdate": false,
  "fullApkUrl": "https://.../app-1.3.0.apk",
  "patchUrl": "https://.../app-1.2.3-to-1.3.0.patch",
  "checksum": "sha256:...",
  "releaseNote": "1. 新增 Shizuku 桥接\n2. 修复 ..."
}
```

被控端:
1. 优先用差分包(基于 `bsdiff`,体积小,需配套历史 APK 缓存)
2. 失败回退全量 APK
3. `sha256` 校验通过后通过 Shizuku 调用 `pm install` 静默安装(需 Shizuku 可用),否则弹安装确认

### 11.3 主控端远程触发更新

主控端可下发 `UPDATE_NOTIFY` 命令,被控端收到后立即检查并应用更新,适合紧急修复。

### 11.4 更新通道抽象

```kotlin
interface UpdateChannel {
    suspend fun check(): UpdateInfo?
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File
    suspend fun install(apk: File): InstallResult
}

class PlayAppUpdateChannel(...) : UpdateChannel { /* AppUpdateManager */
class SelfHostUpdateChannel(...) : UpdateChannel { /* 自建差分包 */
```

启动时按分发渠道选实现(通过 BuildConfig 标识 Play / SelfHost),两条通道并存。

---

## 十二、目录结构

```text
/workspace
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── README.md
├── shared/                              ✅ 已建脚手架
│   └── src/main/kotlin/com/adbcontrol/shared/
│       ├── Protocol.kt                  ✅ WsMessage / MessageType(含遥测/配对/更新类型)
│       └── model/
│           ├── Command.kt               ✅ Command / CommandCategory
│           ├── ExecutionResult.kt       ✅ 执行回报模型
│           ├── AppConfig.kt             ⏳ 配对凭证 / BrokerConfig / SessionKey
│           ├── Telemetry.kt              ⏳ Status/Health/Location/Activity 载荷
│           └── Update.kt                 ⏳ UpdateInfo / UpdateChannel 抽象
├── controller/                          ⏳ 待开发
│   └── src/main/kotlin/com/adbcontrol/controller/
│       ├── ControllerApp.kt
│       ├── di/
│       ├── data/                        (Room: device/task/execution_log/device_status)
│       ├── net/                         (MQTT Client + EMQX REST)
│       ├── sync/                        (JDBC MySQL 同步)
│       ├── config/                      (QR 配对 + EncryptedFile)
│       ├── schedule/                    (cron + Worker)
│       ├── update/                      (PlayAppUpdateChannel / SelfHostUpdateChannel)
│       └── ui/                          (Compose screens)
└── controlled/                          ⏳ 待开发
    └── src/main/kotlin/com/adbcontrol/controlled/
        ├── ControlledApp.kt
        ├── di/
        ├── data/                        (Room: local_results/fence/pending_messages)
        ├── net/                         (MQTT Client + 订阅 + 验签)
        ├── config/                      (QR 配对 + EncryptedFile + SessionKey)
        ├── storage/                     (R2StorageClient:截屏/文件上传,HTTP 旁路)
        ├── executor/                    (ShizukuExecutor / RootExecutor / AccessibilityExecutor / NormalExecutor)
        ├── telemetry/                   (StatusReporter / LocationReporter / ActivityReporter / HealthReporter)
        ├── service/
        │   ├── ControlledService.kt      (常驻前台 Service + 保活)
        │   ├── BootReceiver.kt           (开机自启)
        │   ├── HeartbeatGuardWorker.kt   (WorkManager 周期兜底)
        │   └── UsageStatsWorker.kt       (整点上传)
        ├── accessibility/
        │   └── AdbControlAccessibilityService.kt  (窗口监听 / 手势 / 截屏 / UI 拦截)
        ├── admin/
        │   └── AdbControlDeviceAdminReceiver.kt   (锁屏 / 防卸载)
        ├── notification/
        │   └── AdbControlNotificationListener.kt  (通知监听 + ADB 冗余)
        └── update/                      (本地 UpdateChannel 实现)
```

---

## 十三、开发计划

1. **shared**:补 cron 工具、消息编解码、`Command`/`Telemetry`/`AppConfig`/`Update` 模型
2. **controlled**:
   1. 常驻 Foreground Service + 保活(11 层兜底)
   2. MQTT Client(含验签、自动重连、断线缓存)
   3. QR 配对 + EncryptedFile + SessionKey
   4. Command Executor(Shizuku → Root → Accessibility → Normal)
   5. 遥测引擎(Status / Location / Activity / Health)
   6. 五大服务集成(Accessibility / DeviceAdmin / UsageStats / Location / Notification)
3. **controller**:
   1. Hilt 骨架 → Room/DataStore → MQTT Client → EMQX REST
   2. 多设备管理(能力雷达)
   3. cron 调度 + 任务双存
   4. JDBC MySQL 同步(七张表)
   5. Compose UI(Dashboard / 任务 / 设备 / 遥测 / 远程历史 / 设置)
4. **软件更新机制**:Play App Update + 自建差分包双通道
5. **后端服务器**:pairToken / 临时凭证签发 / ACL 写入 EMQX(可选,无后端可用静态 QR + 手填凭证 fallback)
6. **联调**:端到端跑通 COMMAND 往返 + 一个 cron 任务 + Shizuku 截屏 + 遥测上报
7. **打磨**:鉴权、断线重连补发、UI 体验、DeviceOwner provisioning 脚本、厂商后台适配

---

## 十四、当前进度

> 端到端运行流程见 [docs/RUN.md](docs/RUN.md)。**2026-08-22:三端已全部部署上线并完成真实设备联调**(见下方"线上部署实录")。

- [x] Gradle 多模块脚手架(version catalog + wrapper)
- [x] shared 协议与数据模型(`WsMessage` 含遥测/配对/更新类型;`Command` / `ExecutionResult` `@Serializable`)
- [x] shared cron 工具与编解码、`AppConfig` / `Telemetry` / `Update` 模型
- [x] shared 安全(`HmacSigner` 常量时间比较 + 重放窗口 + key 长度校验;`MqttTopics` 单一来源)
- [x] controlled:常驻 Foreground Service + 11 层保活(通知/电池白名单/BootReceiver/HeartbeatGuardWorker)
- [x] controlled:Command Executor(Shizuku 主/Root 增强/Accessibility 兼容/Normal 兜底 + 注入防护白名单)
- [x] controlled:遥测引擎(Status/Location/Activity/Usage/Health 错峰上报)
- [x] controlled:五大服务集成(Accessibility/DeviceAdmin/UsageStats/Location/Notification)
- [x] controlled:QR 配对 + EncryptedFile + SessionKey 验签(缺失签名即拒绝)
- [x] controlled:软件更新(Play App Update + 自建差分包通道)
- [x] controlled:小米 MIUI 适配(`MiuiAdapter` USB 安全调试/应用锁/神隐/通知渠道/锁屏广播 + `OemBatterySettings` 自启/神隐跳转 + `OemAccessibilityGuard` 7 天自动关检测)
- [x] controller:多设备管理 + 能力雷达(Flow + Compose)
- [x] controller:cron 调度 + 任务双存(AlarmManager 对账 + 稳定 requestCode)
- [x] controller:JDBC MySQL 同步(七张表,PreparedStatement 参数化 + 指数退避 + dead letter)
- [x] controller:Compose UI(玻璃拟态深色主题 + 容量计四条进度条)
- [x] controller:R2 截图拉取 + EMQX REST 容量监控
- [x] 后端:pairToken / 临时凭证 / ACL(配额前置校验 + `compute` 原子化 + `putIfAbsent` 防重)
- [x] 后端:Ktor + HikariCP + TLS + StatusPages(不泄露内部错误)+ CORS
- [x] 跨端 LWT 载荷一致性 + 验签绕过修复(裸 deviceId 拦截在 codec 前)

### 14.1 线上部署实录(2026-08-22)

**真实环境:**

| 组件 | 部署位置 | 地址 |
| --- | --- | --- |
| 后端 | Fly.io(sin 区) | https://adbcontrol-backend.fly.dev |
| Web 管理端 | Cloudflare Workers 静态资产(wrangler.jsonc) | https://baby.slss.top |
| MySQL | SQLPub | mysql6.sqlpub.com:3311/slss12 |
| MQTT | EMQX Cloud Serverless(免费层) | o8cc1111.ala.cn-hangzhou.emqxsl.cn:8883 |
| 真机 | 小米 14(Android 14,国行无 GMS) | deviceId 形如 dev_xxx |

**CI/CD 已跑通:** 后端 push main → GitHub Actions(fly-deploy.yml)→ Fly 远程构建部署;Web push main → Cloudflare 侧自动构建;git 通道被墙时可走 GitHub API 提交(实测可用)。另有 set-secrets.yml 手动工作流,本地无 flyctl 时经 Actions 注入 Fly secrets。

**EMQX 认证机制(重要变更,替代原 8.1 的 ACL 设想):**

- EMQX Cloud Serverless **不提供 ACL、不公开认证用户管理 API 文档**,JWT 认证为专有版专属
- 实测发现部署 API 的内置数据库用户端点可用:`/api/v5/authentication/password_based:built_in_database/users`(POST 建号 201 / PUT 改密 204 / DELETE 删号 204)
- **配对时后端自动为设备注册独立 MQTT 账号**(username = deviceId,随机密码);renew 同步改密;吊销自动删号——均已线上实测
- `ingestor` 账号(后端遥测消费)在控制台手工创建一次,经 `ADB_EMQX_INGEST_USERNAME/PASSWORD` 注入
- 设备隔离防线:clientId(`device-{deviceId}`)+ 独立 topic + 每设备独立 HMAC sessionKey;REST 基址统一补 `/api/v5` 前缀

**本轮修复清单(线上验证通过):**

- 后端:任务 API `TaskRequest` DTO(原 `Map<String,Any>` 反序列化必 400)+ PUT 部分更新语义;3 处 query 参数误读路由参数;管理接口响应体序列化 500(`toJsonElement` 转换器 + `GeneratedPairToken`/`DeviceRow` 补 `@Serializable`);遥测 ingestor 首连失败 60s 周期重试;DB 环境变量名兼容 `ADB_MYSQL_*`/`ADB_DB_*`(曾致后端永远拿空密码连库);管理员改为**首次访问前端初始化**(`/api/setup` 仅空表时开放);泄露的 MySQL 真实密码从模板/fly.toml 移除并轮换
- Web:任务开关改全量载荷(原只传 enabled 会清空任务);会话恢复竞态(硬刷新被弹回登录);移除后端不支持的 `take_screenshot` 假功能
- 被控端:扫码器 ML Kit→**ZXing 内嵌**(国行无 GMS,ML Kit 模块下载永远卡住);配对报文补 `serverUrl` 必填字段;Android 14 FGS location 类型动态裁剪(原配对前定位未授权即崩溃循环);配对后服务重载配置拉起 MQTT;权限自检真实检测 + 每项「去授权」跳转对应系统设置;无障碍服务触摸探索模式移除(原开启即"屏幕失控")
- 构建:中文路径需 junction 至 ASCII 路径 + `android.overridePathCheck`;`org.gradle.jvmargs=-Xmx3g`;阿里云 Maven 镜像(`~/.gradle/init.gradle.kts`)

**已知遗留(未修,按优先级):**

- [ ] 被控端:凭证 7 天到期无自动续期(renew 已可用但无触发点)
- [ ] 被控端:静默安装前缺 APK 签名/包名校验;截屏只写本地不回传(R2 上传链路未接线);`AppTimeController` 本地限额未接线
- [ ] 后端:`notification_log` 表零写入(通知事件入错表);XFF 可伪造 + 登录限流可被用来锁死账号;sessionKey/MQTT 密码明文存 MySQL;`/update/report` 无界内存
- [ ] EMQX 无 ACL(Serverless 限制),越权防护完全依赖 HMAC 验签
- [ ] LWT retained 消息上线后不清除,新订阅者会收到过期"离线"
- [ ] Compose UI 与 docs/ui 高保真原型逐屏对照补齐

---

## 十五、UI 设计规范

> 高保真原型见 [docs/ui/](docs/ui/):
> - [01-dashboard.html](docs/ui/01-dashboard.html) — 主控端 Dashboard + 设备列表
> - [02-device-detail.html](docs/ui/02-device-detail.html) — 主控端 设备详情 + 任务编辑
> - [03-remote-control.html](docs/ui/03-remote-control.html) — 主控端 截屏查看 + 远程控制
> - [04-controlled-setup.html](docs/ui/04-controlled-setup.html) — 被控端 配对 + 权限自检
>
> 视觉参考:[docs/ui/assets/](docs/ui/assets/)(app icon / dashboard hero / 品牌主视觉)

### 15.1 设计方向

**玻璃拟态(Glassmorphism)· 深色主题**

- **氛围**:深午夜蓝渐变背景,叠加青/品红/紫三色径向光晕 + 噪点纹理(避免纯色背景)
- **核心元素**:磨砂玻璃卡片(`backdrop-filter: blur(20px) saturate(160%)` + 半透明白边 + 内/外阴影)
- **强对比**:深底白字 + 单一强调色渐变(青→品红)
- **取舍**:避免 frontend-design skill 警告的"紫色渐变 + 白底"AI 模板,采用深色玻璃 + 青/品红双色,跳出 AI 味

### 15.2 设计令牌(Design Tokens)

Compose 实现时建立 `app/design/theme/` 包,集中管理:

```kotlin
object AppColors {
    // 背景层
    val bgBase = Color(0xFF060812)
    val bgDeep = Color(0xFF02030A)
    // 玻璃面
    val glassBg = Color.White.copy(alpha = 0.045f)
    val glassBgStrong = Color.White.copy(alpha = 0.075f)
    val glassBorder = Color.White.copy(alpha = 0.08f)
    val glassBorderStrong = Color.White.copy(alpha = 0.14f)
    val glassHl = Color.White.copy(alpha = 0.22f)
    // 强调色
    val cyan = Color(0xFF4FD1E0)
    val magenta = Color(0xFFFF5FAD)
    val emerald = Color(0xFF4ADE80)
    val amber = Color(0xFFFBBF24)
    val rose = Color(0xFFFF6B6B)
    val slate = Color(0xFF64748B)
    // 文字
    val textPrimary = Color(0xFFE8ECF4)
    val textSecondary = Color(0xFFC7CEDD)
    val textTertiary = Color(0xFF9AA3B8)
    val textDisabled = Color(0xFF64748B)
}

object AppFonts {
    val display = FontFamily.FontFallback("Bricolage Grotesque", "Manrope")
    val body    = FontFamily.FontFallback("Manrope")
    val mono    = FontFamily.FontFallback("JetBrains Mono", "monospace")
}

object AppRadii {
    val sm = 10.dp; val md = 16.dp; val lg = 24.dp; val xl = 32.dp
    val pill = 999.dp
}

object AppShadows {
    val soft = listOf(
        ambientShadow(Color.Black.copy(alpha = 0.35f), blur = 32.dp, spread = 8.dp),
        innerHighlight(glassHl)
    )
    val hover = listOf(
        ambientShadow(Color.Black.copy(alpha = 0.55f), blur = 60.dp, spread = 20.dp),
        outline(glassBorderStrong)
    )
}
```

### 15.3 颜色语义

| 颜色 | Hex | 用途 |
| --- | --- | --- |
| **青 cyan** | `#4FD1E0` | 主强调色,所有交互态/链接/活跃 tab/cron 表达式/雷达点 |
| **品红 magenta** | `#FF5FAD` | 副强调色,与青色组合渐变用于按钮/状态点 |
| **翠绿 emerald** | `#4ADE80` | 在线 / 成功 / 通过 / 已授权 |
| **琥珀 amber** | `#FBBF24` | 警告 / 需关注 / 待授权 / 容量 70%+ |
| **玫红 rose** | `#FF6B6B` | 错误 / 离线 LWT / 容量 90%+ / 危险操作(锁屏/重启/关机) |
| **板岩 slate** | `#64748B` | 离线 / disabled / 次要元数据 |

### 15.4 字体规范

| 字体 | 用途 | Compose 实现 |
| --- | --- | --- |
| **Bricolage Grotesque** | 大标题/数字/品牌字(避免老套 Inter) | `FontFamily` 引入,字重 600 |
| **Manrope** | 正文/UI 文字(圆润但非默认字体) | 字重 300-700 |
| **JetBrains Mono** | 设备 ID / cron / 坐标 / shell 输出 / 时间戳 | 等宽,字重 400-500 |

> 三字组合可避开"系统默认 + Inter"的 AI 模板感,显得有选择。

### 15.5 间距系统

8 倍数:`4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 dp`

### 15.6 组件库(Compose)

#### 15.6.1 玻璃卡片 `GlassCard`

```kotlin
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val bg = if (strong) AppColors.glassBgStrong else AppColors.glassBg
    val border = if (strong) AppColors.glassBorderStrong else AppColors.glassBorder
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadii.lg))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(AppRadii.lg))
            .shadow(ambient = AppShadows.soft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}
```

> Compose 在 API 30+ 通过 `Modifier.blur` 实现近似 backdrop-filter;旧系统降级为半透明填充(失去模糊但保留层次感)。

#### 15.6.2 状态点 `StatusDot`

```kotlin
@Composable
fun StatusDot(state: StatusState, size: Dp = 6.dp) {
    val color = when (state) {
        ONLINE -> AppColors.emerald
        WARN   -> AppColors.amber
        OFFLINE-> AppColors.slate
        ERROR  -> AppColors.rose
    }
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(color)
            .then(if (state == ONLINE) Modifier.shadow(glow = color.copy(alpha = 0.8f), blur = 8.dp) else Modifier)
    )
}
```

#### 15.6.3 主按钮 `PrimaryButton`(青→品红渐变)

```kotlin
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val bg = Brush.linearGradient(listOf(AppColors.cyan, AppColors.magenta))
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) bg else AppColors.glassBgStrong)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(text, color = if (enabled) AppColors.bgDeep else AppColors.textDisabled,
             fontFamily = AppFonts.display, fontWeight = FontWeight.SemiBold)
    }
}
```

#### 15.6.4 能力雷达 `CapabilityRadar`

六边形雷达,SVG 由 Compose `Canvas` 绘制:
- 六轴:MQTT / SHIZUKU / ACCESS / ADMIN / USAGE / NOTI
- 数据多边形:青→品红渐变填充 + 描边
- 顶点:小圆点

#### 15.6.5 容量计 `MeterBar`

```kotlin
@Composable
fun MeterBar(label: String, value: Float, max: Float, threshold: Float = 0.7f) {
    val pct = value / max
    val color = when {
        pct > 0.9f -> Brush.horizontalGradient(listOf(AppColors.amber, AppColors.rose))
        pct > threshold -> Brush.horizontalGradient(listOf(AppColors.amber, AppColors.rose))
        else -> Brush.horizontalGradient(listOf(AppColors.cyan, AppColors.magenta))
    }
    Column {
        Row(Modifier.fillMaxWidth(), SpaceBetween) {
            Text(label, style = body2, color = textTertiary)
            Text("$value / $max", style = mono, color = textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(White.copy(alpha = 0.06f))
        ) {
            Box(
                Modifier.fillMaxWidth(pct).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}
```

### 15.7 屏幕清单与对应文件

| 屏幕 | 文件 | 关键组件 |
| --- | --- | --- |
| 主控 Dashboard | [01-dashboard.html](docs/ui/01-dashboard.html) | hero + 统计四卡 + 设备网格 + 任务流时间线 + 容量计 |
| 主控 设备详情 | [02-device-detail.html](docs/ui/02-device-detail.html) | 设备 hero + 遥测面板 + 能力雷达大图 + 应用使用排行 + 位置卡 |
| 主控 任务编辑 | (同 02) | 规则类型切换 + cron 5 字段构建器 + 包名输入 + 执行路径偏好开关 + 任务列表 |
| 主控 远程控制 | [03-remote-control.html](docs/ui/03-remote-control.html) | 设备屏幕模拟框 + 按键九宫格 + 手势区 + shell + 链路状态 + 截屏缩略图 |
| 被控端 配对 | [04-controlled-setup.html](docs/ui/04-controlled-setup.html) | 4 步 stepper + QR 扫描 + 凭证预览(掩码)+ 自检列表(MUST/OPT badge) |

### 15.8 交互细节约定

- **在线/离线**:状态点带发光阴影,离线点不发光
- **点击标记**:屏幕上的 tap-marker 用品红环 + 中心点,带 `pulse` 动画(2s 无限)
- **LIVE 标识**:顶部 LIVE pill 用翠绿背景 + 闪烁小点
- **进度条**:`> 70%` 切换为琥珀→玫红渐变,提示接近容量上限
- **不可清除通知**(被控端):`setOngoing(true)`,IMPORTANCE_LOW,默认无声音
- **危险操作**(锁屏/重启/关机):玫红色边框 + hover 时背景变浅红
- **MUST/OPT badge**:必须项标 magenta `MUST`,可选项标 slate `OPT`

### 15.9 响应式断点

- `≥ 1100px`:Desktop 三栏 / Hero 左右双列 / 设备网格 2 列
- `< 1100px`:单栏 / Hero 单列 / 设备网格 1 列 / 雷达 mini 隐藏
- 移动端被控端固定 420px phone 框,内部不响应式

### 15.10 动效原则

- 卡片 hover:`translateY(-2 ~ -3px)` + 阴影加深
- 按钮 hover:渐变阴影外发光(青色光晕)
- 状态点:在线点 1.4s `pulse` 闪烁
- 任务流:时间线连接线渐变向下淡出
- 截屏切换:旧图淡出 200ms,新图淡入 + 缩放

### 15.11 与方案对齐的关键 UI 元素

| 方案要素 | UI 体现 |
| --- | --- |
| EMQX 容量 30 连接 / 500MB / 36k/h | 侧栏容量计四条进度条 + 警报条 |
| 错峰上报 `deviceId.hashCode() % 60` | 任务列表标注 `USAGE · 错峰 +12min` |
| HMAC-SHA256 签名 | 远控面板链路状态显示"签名校验 ✓" |
| QR 配对不直接含凭证 | 配对页只显示 `pairToken` 输入框 |
| Shizuku 优先 / 无障碍兜底 | 设备能力雷达 + 任务编辑开关"优先 Shizuku" / "允许无障碍兜底" |
| R2 截屏旁路 | 工具栏标注"截屏 2.1MB · R2" |
| 三层幂等(LRU + UNIQUE KEY + ACK) | 不直接体现,但任务流时间线显示重试与去重后状态 |
| 截屏 > 1MB 必走 HTTP 旁路 | 质量切换 LOW / HD / RAW 三档(LOW 走 MQTT 缩略图,HD/RAW 走 R2) |
| 11 层保活 | 被控端启动 Agent 按钮 disabled 文案"需完成所有 MUST 项" |
| foregroundServiceType | 状态卡显示 `connectedDevice|dataSync` |
| MUST / OPT 权限等级 | 自检列表每项带 badge |
