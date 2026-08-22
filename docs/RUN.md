# AdbControlApp 端到端运行流程

> 本文档描述如何从构建产物出发,启动后端、安装两端 APK、完成配对并验证端到端链路。
> 适用环境:Linux/macOS 开发机 + 小米 Android 设备(MIUI/HyperOS,Android 11+)。

---

## 0. 前置条件

| 项 | 要求 |
| --- | --- |
| Android 设备 | Android 11+(API 30)以上,已开启「USB 调试」或「无线调试」 |
| ADB | 开发机已安装 `adb` 并能 `adb devices` 列出设备 |
| JDK | 17+(构建用) |
| 后端凭证 | `/workspace/secrets.properties` 已按 template 填入 EMQX app_secret / R2 密钥 |
| Shizuku | 设备已安装 Shizuku App 并启动(无线调试或 PC adb 启动) |

---

## 1. 构建产物位置

全项目清理构建后,产物路径如下:

```text
/workspace
├── controlled/build/outputs/apk/debug/controlled-debug.apk   # 被控端 Agent
├── controller/build/outputs/apk/debug/controller-debug.apk  # 主控端
└── backend/build/install/backend/bin/backend                  # 后端可执行(Linux/macOS)
```

重新构建(沙箱 6GB 内存已调优 gradle.properties):

```bash
cd /workspace && ./gradlew clean assembleDebug :backend:installDist
```

---

## 2. 后端启动

### 2.1 准备凭证

首次运行需从模板复制并填入占位符:

```bash
cp secrets.properties.template secrets.properties
# 编辑 secrets.properties,填入:
#   emqx.app_secret  = <EMQX 控制台 Application 模块的 secret>
#   r2.access_key    = <Cloudflare R2 API Token 的 AccessKey>
#   r2.access_secret = <Cloudflare R2 API Token 的 AccessSecret>
```

> MySQL 凭证与 EMQX/R2 已知值已在 template 中预填,无需改动。
> `secrets.properties` 不入 git(见 .gitignore)。也可用同名环境变量覆盖(优先级 env > file)。

### 2.2 启动后端

```bash
cd /workspace
./backend/build/install/backend/bin/backend
```

默认监听 `0.0.0.0:8080`(可用 `PORT` / `HOST` 环境变量覆盖)。

### 2.3 健康检查

```bash
curl -s http://localhost:8080/health
# 期望: {"status":"UP","version":"0.1.0"}
```

### 2.4 验证配对接口(使用预置 demo token)

> 说明:`pt_demo_001` **现在只在显式设置 `ADB_SEED_DEMO_TOKEN=true` 时播种**,
> 不再默认注入(原实现按 serverUrl 域名隐式开启,曾被默认 example.com
> 推广到生产上成为后门)。日常开发：启动前
> `export ADB_SEED_DEMO_TOKEN=true`,或者直接去 Web 控制台「令牌管理」点新增,
> 拿到一个一次性的真实 pairToken 再做后面的步骤。

```bash
curl -s -X POST http://localhost:8080/pair \
  -H 'Content-Type: application/json' \
  -d '{"pairToken":"pt_demo_001","serverUrl":"https://api.adbcontrol.example.com","deviceId":"device-a001","deviceName":"demo-device"}'
# 期望: 返回 broker + r2 + sessionKey + expiresAt 的 JSON
# 再次调用同一 token 应返回 TOKEN_USED 错误(防重放)
```

> 数据库不可达时后端仍可启动,配对功能不受影响(DDL/upsert 为 best-effort)。

---

## 3. 小米设备 APK 安装

### 3.1 连接设备

```bash
# USB 连接(需开启 USB 调试)
adb devices

# 或无线调试(Android 11+,需设备与开发机同网段)
adb pair <设备IP>:<配对端口>     # 设备「无线调试」页面显示的配对码
adb connect <设备IP>:<连接端口>
```

### 3.2 安装被控端(controlled)

```bash
adb install -r /workspace/controlled/build/outputs/apk/debug/controlled-debug.apk
# 包名: com.adbcontrol.controlled
```

### 3.3 安装主控端(controller)

```bash
adb install -r /workspace/controller/build/outputs/apk/debug/controller-debug.apk
# 包名: com.adbcontrol.controller
```

> `-r` 允许覆盖安装,保留已有数据。若签名冲突需先卸载旧版。

### 3.4 启动应用

```bash
# 被控端
adb shell am start -n com.adbcontrol.controlled/.ui.MainActivity

# 主控端
adb shell am start -n com.adbcontrol.controller/.MainActivity
```

---

## 4. Shizuku 启动(被控端主桥接)

被控端默认依赖 Shizuku 执行 ADB 等价命令(无 root)。设备上需先启动 Shizuku:

### 4.1 方式 A:无线调试自启动(Android 11+,免 PC)

1. 设备「设置 → 开发者选项 → 无线调试」开启
2. 在 Shizuku App 内点击「通过无线调试启动」,扫码配对
3. Shizuku 服务常驻,重启设备后需重新启动一次

### 4.2 方式 B:PC adb 启动(老系统)

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

### 4.3 授权被控端

在 Shizuku App 中授权 `com.adbcontrol.controlled` 访问权限(普通权限,无需 root)。

---

## 5. 配对流程(端到端)

1. **后端签发 pairToken**:由 Web 管理控制台「令牌管理」生成(开发期也可
   启动时加 `ADB_SEED_DEMO_TOKEN=true` 播种预置的 `pt_demo_001`,默认 10 分钟过期)
2. **被控端扫码/输入**:在被控端 MainActivity 配对页输入 pairToken + serverUrl
3. **被控端调 /pair**:后端校验 token → 签发临时 MQTT 凭证(7 天)+ 长期 sessionKey
4. **被控端加密落盘**:用 EncryptedFile(AES-GCM)保存凭证
5. **被控端连 MQTT**:用临时凭证连 EMQX 8883(TLS),publish `PAIR_COMPLETE`
6. **主控端确认**:主控端订阅 `pair/+`,收到 PAIR_COMPLETE 后可下发命令

---

## 6. 端到端验证清单

| 检查项 | 命令/操作 | 期望 |
| --- | --- | --- |
| 后端健康 | `curl localhost:8080/health` | `{"status":"UP"}` |
| 配对接口 | `curl -X POST localhost:8080/pair ...` | 返回 broker/r2/sessionKey |
| 被控端安装 | `adb shell pm list packages \| grep adbcontrol` | 列出两个包 |
| 被控端前台 | `adb shell dumpsys activity \| grep mResumed` | controlled Activity 在前台 |
| Shizuku 可用 | 被控端 UI 自检列表 | Shizuku 项显示「已授权」 |
| MQTT 连接 | 被控端 UI 链路状态 | 显示已连 broker |
| 命令往返 | 主控端下发 COMMAND | 被控端执行 + 回执 COMMAND_RESULT |

---

## 7. 常用 adb 命令速查

```bash
# 查看已安装应用
adb shell pm list packages | grep adbcontrol

# 卸载
adb uninstall com.adbcontrol.controlled
adb uninstall com.adbcontrol.controller

# 清数据(不卸载)
adb shell pm clear com.adbcontrol.controlled

# 查看前台 Activity
adb shell dumpsys activity activities | grep mResumedActivity

# 查看服务运行状态
adb shell dumpsys activity services com.adbcontrol.controlled

# 查看日志(过滤本应用)
adb logcat | grep -E 'adbcontrol|Shizuku|Mqtt'

# 拉取 APK 到本机
adb pull /data/app/.../base.apk controlled-installed.apk

# 截屏(调试用)
adb shell screencap -p /sdcard/test.png && adb pull /sdcard/test.png
```

---

## 8. 故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 后端启动失败 | secrets.properties 缺占位符 | 检查 template,确保所有 `REPLACE_ME_*` 已填 |
| 后端启动但 DB 报错 | MySQL 不可达 | best-effort 不阻断启动;如需归档功能需修复 DB 连接 |
| APK 安装失败 INSTALL_FAILED | 签名冲突 / 存储不足 | 先 `adb uninstall` 旧版;清理设备空间 |
| 被控端 Shizuku 不可用 | 未启动 / 未授权 | 按 4.x 启动 Shizuku 并授权 |
| MQTT 连不上 | 8883 TLS / 凭证过期 | 检查凭证有效期,必要时调 `/renew` 续签 |
| 命令无回执 | 主题订阅错误 / 验签失败 | 看 logcat 验签日志;确认 topic 命名 `cmd/{deviceId}` |

---

## 9. 沙箱构建注意事项

沙箱内存仅 6GB,`gradle.properties` 已调优:

```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseParallelGC -XX:-UseContainerSupport
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configuration-cache=true
```

> `-XX:-UseContainerSupport` 解决容器内 cgroup 检测导致 lint 初始化失败的问题。
> 并行关闭 + worker=1 避免多进程内存峰值。构建速度换稳定性。
