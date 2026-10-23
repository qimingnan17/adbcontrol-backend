# AdbControl v2.0 协议与 API 契约（后端实现版）

> 本文是 `adbcontrol-backend` 在 v2.0 中**实际实现**的契约。跨仓库约定的原始版本是 `v2-contract.md`，
> 与它的差异都列在文末「与跨仓库契约的差异」一节。
>
> 约定：
> - 所有时间戳都是 **epoch 毫秒**（`Long`）。
> - `date` 参数格式为 `YYYY-MM-DD`，按**业务时区**切日（`ADB_SCHEDULER_ZONE`，默认 `Asia/Shanghai`），缺省为业务时区的今天。
> - 除 `GET /api/meta` 外，下文所有 `/api/*` 接口都需要管理员会话（`ADB_SESSION` Cookie，账号密码登录获得）。未登录返回 `401 {"message": "..."}`。
> - 错误响应统一为 `{"message": "人类可读的原因"}`，HTTP 状态码表示错误类别（400 参数错误、404 不存在、409 状态冲突、500 存储不可用）。
> - v2 新增的列表接口**直接返回 JSON 数组**（不再包一层 `{"items": [...]}`）；v1 既有接口保持原状。

---

## 1. 协议版本

### 1.1 MQTT 信封

所有 topic（`cmd/` `reminder/` `push/` `result/` `status/` `health/` `location/` `activity/` `usage/` `request/`）共用同一个信封：

```json
{
  "id": "web-3f2a9c...",
  "type": "COMMAND",
  "payload": "<载荷 JSON 字符串>",
  "timestamp": 1792425600000,
  "signature": "<Base64(HMAC-SHA256)>",
  "protocolVersion": 2
}
```

- 发送方写 `protocolVersion: 2`（shared `ProtocolVersion.CURRENT`）。
- 接收方：字段缺失视为 `1`（v2.0 之前的端）。旧版本**只记日志并兼容处理**（后端每台设备只记一次 info 日志），只有 JSON 无法解析时才丢弃。读取方式：`ProtocolVersion.of(rawJson)`。
- **签名不变**：`signature = Base64(HMAC-SHA256(payload + ":" + id + ":" + timestamp, sessionKey))`，`protocolVersion` 不参与签名，所以 v1 / v2 两端可以互相验签。被控端仍校验 ±5 分钟重放窗口。
- shared 模块的协议模型（`WsMessage`、`Command`、`ExecutionResult`、`StatusReport`、`BatterySnapshot`、`LocationReport`、`ActivityReport`、`UsageReport`、`HealthReport`、`ReminderPayload`、`ReminderAck`、`UpdateCheckRequest`、`UpdateCheckResponse`、`UpdateResultReport` 以及 v2 新载荷）都带 `protocolVersion: Int = 2`。shared 新增 `MqttEnvelope` 作为信封的统一定义。

v2 新增的信封 `type`（shared `MessageType`）：

| type | 方向 | topic | payload |
|---|---|---|---|
| `EXTENSION_REQUEST` | 被控 → 后端 | `request/{deviceId}` | `ExtensionRequestPayload` |
| `APP_TIME_EXTEND` | 后端 → 被控 | `cmd/{deviceId}` | `AppTimeExtendPayload` |
| `APP_TIME_EXTEND_DENIED` | 后端 → 被控 | `cmd/{deviceId}` | `AppTimeExtendDeniedPayload` |
| `GEOFENCE_SET` | 后端 → 被控 | `cmd/{deviceId}` | `GeofenceSetPayload` |

### 1.2 `GET /api/meta`（公开，无需登录）

```json
{ "protocolVersion": 2, "serverVersion": "2.0.0" }
```

---

## 2. OTA 完整性

### 2.1 数据

`app_version_manifest.sha256` 改为 `CHAR(64) NULL`（小写 hex）。旧库里的空串 / 长度不是 64 的值在迁移时置为 `NULL`，读出时也按 `NULL` 处理。

### 2.2 `GET /update/check`

响应新增 / 调整：

```json
{
  "hasUpdate": true,
  "latestVersionCode": 21,
  "fullApkUrl": "https://.../update/apk?url=...",
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "protocolVersion": 2,
  "...": "其余字段同 v1"
}
```

- `sha256` 可为 `null`（旧清单未登记）。被控端：非空时下载后必须校验，不一致 → 删除安装包、`POST /update/report` 上报 `errorMsg = "UPDATE_HASH_MISMATCH"`（shared `UpdateErrorCodes.HASH_MISMATCH`）、不安装；为 `null` 时照常安装但记 warning。

### 2.3 哈希来源

1. CI 发布 `POST /api/updates/publish`（`X-Admin-Token`）时可带 `sha256`（64 位 hex，大小写不限，入库统一小写；格式不对返回 400）。
2. 不带 `sha256` 时：`fullApkUrl` 是 GitHub Release 资产（`github.com` / `*.githubusercontent.com` / `codeload.github.com`）则允许发布，后端**后台下载一次并计算** SHA-256 回填；其它来源必须带 `sha256`，否则 400。旧行缺哈希时，第一次 `/update/check` 命中它也会触发后台计算（本次响应仍为 `null`，计算完成后的下一次 check 即带上）。
3. 管理员手工登记：

`PUT /api/updates/{versionCode}/sha256`

```json
{ "sha256": "<64 hex>", "channel": "stable" }
```

响应 `{"ok": true}`；版本不存在 404；格式错误 400。

---

## 3. 申请延时

### 3.1 被控端发起（MQTT）

发布到 `request/{deviceId}`，QoS 1，信封 `type = "EXTENSION_REQUEST"`，payload：

```json
{
  "requestId": "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
  "packageName": "com.tencent.tmgp.sgame",
  "appLabel": "王者荣耀",
  "minutes": 15,
  "reason": "还差一局",
  "ts": 1792425600000,
  "protocolVersion": 2
}
```

- `requestId` 是幂等键（`extension_request.request_id UNIQUE`），QoS 1 重发只入库一次。
- `minutes` 必须 > 0，上限 1440（超出截断）。`ts` 晚于服务器当前时间时以服务器时间入库（防设备时钟超前导致永不过期）。
- EMQX ACL：v2 给被控端账号新增 `publish request/{deviceId}`。后端启动约 15 秒后会对所有已配对设备重写一次 ACL（幂等），无需重新配对。ingestor 账号需新增 `subscribe request/+`（见文末「部署须知」）。

### 3.2 存储

`extension_request(id, request_id UNIQUE, device_id, package_name, app_label, minutes, reason, status, created_at, decided_at, decided_by, approved_minutes)`，`status ∈ PENDING | APPROVED | DENIED | EXPIRED`。

`PENDING` 超过 **30 分钟**自动 `EXPIRED`（后台每分钟扫描一次；列表查询前、审批前也会先过期一次），`decided_by = "system"`。

### 3.3 SSE

沿用 `GET /api/events` 同一条流。新建、批准、拒绝、过期都会推送：

```
event: extension_request
data: {"type":"extension_request","deviceId":"dev-1","status":"PENDING","msgId":"<requestId>","ts":...,"data":{<ExtensionRequestRow>}}
```

`data` 即下文的 `ExtensionRequestRow`。

### 3.4 REST

`ExtensionRequestRow`：

```json
{
  "id": 12,
  "requestId": "1b4e28ba-...",
  "deviceId": "dev-1",
  "packageName": "com.tencent.tmgp.sgame",
  "appLabel": "王者荣耀",
  "minutes": 15,
  "reason": "还差一局",
  "status": "PENDING",
  "createdAt": 1792425600000,
  "decidedAt": null,
  "decidedBy": null,
  "approvedMinutes": null
}
```

- `GET /api/extension-requests?status=PENDING&deviceId=` → `ExtensionRequestRow[]`（按 `createdAt` 倒序，最多 200 条；`status` 非法 400）。
- `POST /api/extension-requests/{requestId}/approve`，body 可选 `{"minutes": 20}`（缺省 = 申请的分钟数，范围 1..1440）。
- `POST /api/extension-requests/{requestId}/deny`，无 body。

审批响应：

```json
{ "request": { <ExtensionRequestRow> }, "delivery": "SENT", "commandId": "ext-..." }
```

`delivery ∈ SENT | QUEUED | FAILED`：`QUEUED` = 设备离线，命令已进离线队列（延时类命令保留 **2 小时**）；`FAILED` = 下发失败（如设备没有配对会话），但审批结果已落库。
申请不存在 404；已不是 `PENDING`（含刚好过期）409 `{"message":"申请当前状态为 EXPIRED,不能再审批"}`。

### 3.5 下发给被控端

批准：`cmd/{deviceId}`，`type = "APP_TIME_EXTEND"`

```json
{ "requestId": "1b4e28ba-...", "packageName": "com.tencent.tmgp.sgame", "minutes": 20, "protocolVersion": 2 }
```

拒绝：`cmd/{deviceId}`，`type = "APP_TIME_EXTEND_DENIED"`

```json
{ "requestId": "1b4e28ba-...", "packageName": "com.tencent.tmgp.sgame", "protocolVersion": 2 }
```

签名规则同其它命令。

---

## 4. 准实时使用时长

被控端每 15 分钟向 `usage/{deviceId}` 额外发布一次当日累计，`UsageReport` 带 `"partial": true`（`date` 为设备本地日期 `YYYY-MM-DD`）。

后端 upsert 键为 `(device_id, user_id, pkg, date)`（即契约里的 `(device_id, date, package_name)`，外加既有的多用户维度）：

- `partial: true` → `usage_minutes = GREATEST(旧值, 新值)`（乱序到达的旧快照不会把值改小）；
- `partial: false` / 缺省（常规整点上报）→ 以设备为准覆盖写（与 v1 相同）。

---

## 5. 时间线、轨迹、电子围栏

### 5.1 `GET /api/devices/{id}/timeline?date=YYYY-MM-DD`

```json
[ { "ts": 1792450000000, "type": "APP_FOREGROUND", "title": "打开 微信", "detail": "com.tencent.mm" } ]
```

按 `ts` 升序。`type` 与数据来源：

| type | 来源 | 说明 |
|---|---|---|
| `APP_FOREGROUND` | `app_activity_log`（event = APP_FOREGROUND） | 历史数据可用 |
| `FENCE_ENTER` / `FENCE_EXIT` | `location_history.fence_event` | 从自由字符串识别：以 `enter`/`exit`/`leave` 开头、含 `fence_enter`/`fence_exit`、或含「进入」/「离开」；无法识别的不进时间线。建议被控端上报 `ENTER:<围栏名>` / `EXIT:<围栏名>` |
| `REMINDER_ACK` | `task_ack` | 历史数据可用 |
| `EXTENSION_REQUEST` | `extension_request` | v2 起 |
| `SCREEN_ON` / `SCREEN_OFF` | `device_event`（v2 新表） | ingestor 对比相邻两条 `status` 的 `screenOn` 跳变写入 |
| `CHARGING_START` / `CHARGING_STOP` | `device_event` | 同上，对比 `charging`，`detail` = `电量 NN%` |
| `ONLINE` / `OFFLINE` | `device_event` | 离线 → 在线跳变（status/health 到达）/ LWT `device/offline/{id}` |
| `TASK_RUN` | `device_event` | 调度器发火时每台目标设备一条，`detail` = 已下发 / 已进入离线队列 / 下发失败 |

**限制**：`device_event` 只从 v2 部署之后开始归档，所以 `SCREEN_*` / `CHARGING_*` / `ONLINE` / `OFFLINE` / `TASK_RUN` 没有 v2 之前的历史；后端进程重启后每台设备的第一条 `status` 只作为基线，不产生 `SCREEN_*` / `CHARGING_*` 事件。

### 5.2 `GET /api/devices/{id}/locations?date=YYYY-MM-DD`

```json
[ { "ts": 1792450000000, "lat": 31.2304, "lng": 121.4737, "accuracy": 12.5, "speed": 0.0 } ]
```

来自 `location_history`，按时间升序，最多 10000 点。`accuracy` / `speed` 可为 `null`。

### 5.3 电子围栏

`GeofenceDto`：`{ "id": 3, "name": "学校", "lat": 31.23, "lng": 121.47, "radiusMeters": 300 }`

- `GET /api/devices/{id}/geofences` → `GeofenceDto[]`
- `PUT /api/devices/{id}/geofences`，body 为 `GeofenceDto[]`（**全量替换**，`id` 忽略，空数组 = 清空；最多 50 个；`radiusMeters` 10..100000；坐标合法）。响应：

```json
{ "items": [ <GeofenceDto，带新 id> ], "delivery": "SENT", "commandId": "geo-..." }
```

保存后立即向设备下发 `cmd/{deviceId}`，`type = "GEOFENCE_SET"`：

```json
{ "geofences": [ { "id": 3, "name": "学校", "lat": 31.23, "lng": 121.47, "radiusMeters": 300.0 } ], "protocolVersion": 2 }
```

设备离线时进离线队列（24h）；设备没有配对会话时围栏仍会保存，`delivery = "FAILED"`。

---

## 6. 使用趋势

`GET /api/devices/{id}/usage/trend?days=7|30`（缺省 7；其它值 400）

```json
[ { "date": "2026-10-20", "packageName": "com.tencent.mm", "appLabel": "微信", "minutes": 95 } ]
```

区间为业务时区的「今天往前 days 天（含今天）」；同一天同一包名多用户求和；按日期升序、同日按分钟倒序。没有数据的日期不返回（前端补零）。

---

## 7. 策略模板

### 7.1 结构

```json
{
  "id": 1,
  "name": "上学日",
  "payload": {
    "limits":  [ { "packageName": "com.tencent.tmgp.sgame", "minutes": 60 } ],
    "windows": [ { "packageName": "com.ss.android.ugc.aweme", "startTime": "22:00", "endTime": "07:00" } ],
    "cronExpr": "0 0 * * *",
    "applyNow": true
  },
  "createdAt": 1792425600000,
  "updatedAt": 1792425600000
}
```

- `payload` 以 JSON 对象收发，库里存于 `policy_template.payload_json`。
- `limits[].minutes` 0..1440（0 = 清除该包限制，与 `app_time_limit` 语义一致）。
- `windows[]` 的 `startTime` / `endTime` 为 `HH:mm`，**支持跨零点**（`22:00` → `07:00` 表示 22:00–次日 07:00）；起止相同视为非法。
- `cronExpr`（缺省 `0 0 * * *`）：apply 生成的定时任务的执行时间，用于定期重申规则。
- `applyNow`（缺省 `true`）：apply 时是否立即下发一次。
- `limits` 与 `windows` 不能同时为空。

### 7.2 接口

- `GET /api/policy-templates` → 模板数组
- `GET /api/policy-templates/{id}`
- `POST /api/policy-templates`，body `{name, payload}` → 201 + 模板
- `PUT /api/policy-templates/{id}`，body `{name, payload}` → 模板
- `DELETE /api/policy-templates/{id}` → `{"ok": true}`（已生成的任务**不会**随之删除）
- `POST /api/policy-templates/{id}/apply`，body `{"deviceIds": ["dev-1", "dev-2"]}` →

```json
[ { "deviceId": "dev-1", "taskIds": [41, 42], "dispatched": 2, "queued": 0, "errors": [] } ]
```

每条规则为每台设备建一个 `task`：`rule_type = "policy_template:{id}"`，`cron_expr = payload.cronExpr`，`command_json = {"type":"app_time_limit"|"app_time_window","args":{...},"templateId":id}`（`args` 字段与 `POST /api/devices/{id}/commands` 相同：`packageName`、`minutes` / `startTime`、`endTime`）。

---

## 8. 离线命令队列

- 下发前判断设备在线状态：进程内状态（status/health 到达 = 在线，LWT = 离线）优先，未知时用 `device_status.online`，都未知按在线处理。
- 离线时命令落 `pending_command`，**未签名**保存载荷；设备上线（离线 → 在线跳变，或本进程首次收到其上行）时按入队顺序用当前 `sessionKey` + **当前时间**重新签名投递（命令 id 不变）。
- 有效期（`expires_at`）：默认 **24 小时**；危险类型 `shell` / `reboot` / `uninstall`（及 `reboot_device` / `uninstall_app`）默认 **10 分钟**；`APP_TIME_EXTEND(_DENIED)` 2 小时。`POST /api/devices/{id}/commands` 可传 `ttlSeconds` 覆盖（收敛到 60 秒..24 小时）。
- 过期未投递的标记 `EXPIRED`，**永不发送**（上线投递时先剔除；后台每分钟全表扫描一次）。
- 状态：`PENDING` → `SENT` / `EXPIRED`。投递采用条件更新抢占，同一命令只投一次；发布失败退回 `PENDING` 等下次上线。
- 适用范围：Web 命令、通知（`notify`）、调度器任务、`APP_TIME_EXTEND(_DENIED)`、`GEOFENCE_SET`。`PUSH_DATA`（OTA 广播、文件就绪通知）不入队。
- DB 不可用时退化为 v1 行为（直接发布）。

接口：

- `POST /api/devices/{id}/commands`：在线 `200 {"commandId","status","emqx","queued":false}`；离线 **`202 {"commandId","queued":true,"expiresAt"}`**。
- `GET /api/devices/{id}/pending-commands?limit=50` → `PendingCommandRow[]`（最近的队列记录，含 SENT / EXPIRED；`payloadJson` 截断到 500 字符）：

```json
[ { "id": 7, "commandId": "web-...", "deviceId": "dev-1", "commandType": "shell", "topic": "cmd/dev-1",
    "envelopeType": "COMMAND", "payloadJson": "{...}", "status": "PENDING",
    "createdAt": 1792425600000, "expiresAt": 1792426200000, "sentAt": null } ]
```

---

## 9. 审计日志

`command_audit(id, command_id UNIQUE, device_id, type, payload_summary, issued_by, issued_at, channel, result, finished_at)`

- 每次下发写一行：`type` = Web 命令 type（如 `shell`、`notify`、`app_time_limit`）或 v2 信封类型（`APP_TIME_EXTEND`、`GEOFENCE_SET`…）；`issued_by` = 管理员用户名 / `scheduler:task#<id>`；`payload_summary` = 参数摘要（每个值截断 80 字符，总长 ≤ 400）。
- `result`：`SENT` / `QUEUED` / `PUBLISH_FAILED` / `EXPIRED`，收到命令回报后更新为 `SUCCESS` / `FAILED`，同时写 `channel` 与 `finished_at`。
- `channel`：被控端在 `ExecutionResult` 里回报的执行通道 `Shizuku` / `Root` / `Accessibility` / `DeviceAdmin` / `Normal`（shared `ExecutorChannels`）；v1 被控端不回报时为 `null`。
- 关联方式：命令回报的 `ExecutionResult.commandId`（缺失时用回报信封 id）= 下发信封 id = `command_audit.command_id`。
- 设备删除时审计记录**保留**。

`GET /api/audit?deviceId=&from=&to=&limit=200`

- `from` / `to`：epoch 毫秒，或 `YYYY-MM-DD`（`to` 取当天结束）；区间 `[from, to)`；`limit` 1..1000。

```json
[ { "id": 1, "commandId": "web-...", "deviceId": "dev-1", "type": "shell", "payloadSummary": "shell command=getprop",
    "issuedBy": "admin", "issuedAt": 1792425600000, "channel": "Shizuku", "result": "SUCCESS", "finishedAt": 1792425601200 } ]
```

---

## 10. SSE 事件汇总（`GET /api/events`）

| event | v | 说明 |
|---|---|---|
| `device_status` | 1 | 电量 / 网络 / lastSeen |
| `device_offline` | 1 | LWT |
| `device_online` | **2** | 离线 → 在线跳变 |
| `command_result` | 1 | 命令回报 |
| `reminder_ack` | 1 | 通知签收 |
| `extension_request` | **2** | 申请延时新建 / 批准 / 拒绝 / 过期，`data` = `ExtensionRequestRow` |

---

## 11. 数据库迁移

沿用仓库既有方式（`DatabaseService.runSchema`，启动时后台执行、best-effort）：

- 新表在 `backend/src/main/resources/db/schema.sql` 末尾以 `CREATE TABLE IF NOT EXISTS` 定义：`extension_request`、`device_event`、`geofence`、`policy_template`、`pending_command`、`command_audit`。
- 老表变更在 `runSchema` 的增量迁移列表中：
  - `ALTER TABLE app_version_manifest MODIFY COLUMN sha256 CHAR(64) NULL`
  - `UPDATE app_version_manifest SET sha256 = NULL WHERE sha256 = '' OR CHAR_LENGTH(sha256) <> 64`
  - `UPDATE app_version_manifest SET sha256 = LOWER(sha256) WHERE sha256 IS NOT NULL`
- 全部幂等，重复执行无副作用；无需手工执行 SQL。回滚到 v1 时新表可保留（v1 不读）。

## 12. 部署须知

1. **EMQX ingestor 账号**（`ADB_EMQX_INGEST_USERNAME`）需要新增订阅权限 `request/+`，否则收不到申请延时。
2. 被控端账号的 `publish request/{deviceId}` 由后端在启动后自动重写 ACL；若 EMQX 部署不支持授权 API（如 Serverless 无 ACL），无需处理。
3. Web 前端需要处理 `POST /api/devices/{id}/commands` 的 **202 Queued** 响应与新的 SSE 事件。

## 13. 与跨仓库契约（v2-contract.md）的差异

| 项 | 契约 | 实现 | 原因 |
|---|---|---|---|
| §2 `sha256` 列 | 新增列 | 列已存在（`VARCHAR(128) NOT NULL`），改为 `CHAR(64) NULL` | 旧表早已有该列 |
| §3 `extension_request` | 列表如契约 | 额外列 `approved_minutes` | 记录家长实际批准的分钟数（可改） |
| §3 SSE | "with the row" | 行放在事件的 `data` 字段，`status` / `msgId(=requestId)` 也在顶层 | 沿用既有 `EventDto` 扁平结构 |
| §3 审批响应 | 未定义 | `{request, delivery, commandId}` | — |
| §4 upsert 键 | `(device_id, date, package_name)` | `(device_id, user_id, pkg, date)`；仅 `partial=true` 取 max | 表本来就有多用户维度；整点上报仍以设备为准 |
| §5 时间线 | 来自已归档遥测 | 新增 `device_event` 表归档 `SCREEN_*`/`CHARGING_*`/`ONLINE`/`OFFLINE`/`TASK_RUN` | 这些类型此前没有任何归档；v2 之前无历史 |
| §5 PUT geofences 响应 | 未定义 | `{items, delivery, commandId}` | — |
| §5 GEOFENCE_SET 载荷 | "the list" | `{"geofences": [...]}` | 便于以后加字段 |
| §7 payload_json | 未定义 | 见 §7.1 | — |
| §8 投递 | 设备 ONLINE 时 | 离线→在线跳变或进程首次收到上行时；另有每分钟过期扫描 | — |
| §8 新增 | — | `ttlSeconds` 覆盖、`GET /api/devices/{id}/pending-commands`、离线时 202 | — |
| §9 `command_audit` | 列表如契约 | 额外列 `command_id`（UNIQUE） | 用于关联命令回报 |
| §9 `result` 取值 | 未定义 | `SENT/QUEUED/PUBLISH_FAILED/EXPIRED/SUCCESS/FAILED` | — |
| 列表响应 | 未定义包装 | v2 新接口直接返回数组 | 与契约里 timeline 等示例一致 |
| 新增 | — | `PUT /api/updates/{versionCode}/sha256`、SSE `device_online` | 契约要求"Admin can set it" |
