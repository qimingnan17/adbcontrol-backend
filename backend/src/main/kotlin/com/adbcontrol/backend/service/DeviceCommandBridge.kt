package com.adbcontrol.backend.service

import com.adbcontrol.backend.v2.DevicePresence
import com.adbcontrol.backend.v2.GeofenceDto
import com.adbcontrol.backend.v2.OfflineQueuePolicy
import com.adbcontrol.backend.v2.PendingCommandRow
import com.adbcontrol.backend.v2.V2Repository
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.ProtocolVersion
import com.adbcontrol.shared.model.AppTimeExtendDeniedPayload
import com.adbcontrol.shared.model.AppTimeExtendPayload
import com.adbcontrol.shared.model.Geofence
import com.adbcontrol.shared.model.GeofenceSetPayload
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.ReminderPayload
import com.adbcontrol.shared.net.MqttTopics
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Web 管理端 -> 被控端的命令下发桥。
 *
 * 修复审查发现的「Web 命令链路三层全断」问题:
 * 1. topic:改为被控端真正订阅的 cmd/{deviceId}(见 shared [MqttTopics]),
 *    不再发到 adb/dev/{id}/cmd/in;
 * 2. wire 格式:封装为被控端 MessageCodec 可解码的 envelope
 *    {id, type:"COMMAND", payload:Command JSON 字符串, timestamp, signature};
 * 3. 签名:使用配对该设备时签发的 sessionKey 做
 *    HMAC-SHA256(payload + ":" + id + ":" + timestamp),Base64 输出。
 *    规则必须与被控端 MessageCodec.decode 的校验严格一致,否则消息会被静默丢弃。
 *
 * Web 侧的 type/args 命名(DeviceDetail.vue)→ shared [Command] 的映射见 [mapCommand]:
 * - shell           → FILE/shell,   params={cmd: args.command}
 * - launch_app      → APP/start,    params={pkg: args.packageName}
 * - stop_app        → APP/forceStop,params={pkg: args.packageName}
 * - app_time_limit  → APP_TIME/setLimit,params={pkg, minutes}(minutes<=0 即清除该包限制)
 * - app_time_clear  → APP_TIME/clearLimit,params={pkg}
 * - app_time_window → APP_TIME/setWindow,params={pkg, start:"HH:mm", end:"HH:mm"}(支持跨零点)
 * - app_time_window_clear → APP_TIME/clearWindow,params={pkg}
 *
 * 任务栏通知(可自定义按钮+签收)不走 Command:[dispatchReminder] 封装 REMINDER 信封
 * 发到 reminder/{deviceId},载荷为 [ReminderPayload]。
 *
 * v2.0:
 * - 信封带 protocolVersion=2(不参与签名);
 * - 离线命令队列:设备离线([repo] 可用时)命令落 pending_command,设备上线由 [deliverPending]
 *   重新签名投递,过期的标 EXPIRED 永不发送(见 [OfflineQueuePolicy]);
 * - 命令审计:每次下发写 command_audit,回报到达后由 ingestor 更新 result/channel;
 * - 新命令信封类型:APP_TIME_EXTEND / APP_TIME_EXTEND_DENIED / GEOFENCE_SET。
 */
class DeviceCommandBridge(
    private val pairingService: PairingService,
    private val emqx: EmqxProxyService,
    private val repo: V2Repository? = null,
    private val presence: DevicePresence? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** wire 信封,字段与被控端 controlled/net/MessageCodec.kt 的 MqttEnvelope 完全一致。 */
    @Serializable
    data class CommandEnvelope(
        val id: String,
        val type: String,
        val payload: String,
        val timestamp: Long,
        val signature: String? = null,
        /** v2:协议版本(不参与签名)。v1 被控端 ignoreUnknownKeys 忽略此字段。 */
        val protocolVersion: Int = ProtocolVersion.CURRENT,
    )

    sealed class DispatchResult {
        data class Ok(val commandId: String, val emqxStatus: Int, val emqxBody: String) : DispatchResult()
        /** v2:设备离线,命令已进入离线队列,上线后投递;[expiresAt] 之前未上线则作废。 */
        data class Queued(val commandId: String, val expiresAt: Long) : DispatchResult()
        data class UnsupportedType(val type: String) : DispatchResult()
        data class MissingArg(val field: String) : DispatchResult()
        data object SessionMissing : DispatchResult()
    }

    internal sealed class MapResult {
        data class Mapped(val command: Command) : MapResult()
        data class MissingField(val field: String) : MapResult()
        data class Unsupported(val type: String) : MapResult()
    }

    /** 通知载荷构造结果(与 MapResult 并行,通知不走 Command 模型)。 */
    internal sealed class ReminderBuild {
        data class Ready(val payload: ReminderPayload) : ReminderBuild()
        data class MissingField(val field: String) : ReminderBuild()
    }

    /** Web type/args → shared Command(纯函数,便于单元测试)。 */
    internal fun mapCommand(type: String, args: Map<String, String>): MapResult = when (type) {
        "input_tap", "tap" -> {
            val x = args["x"]?.trim()
            val y = args["y"]?.trim()
            if (x.isNullOrEmpty() || y.isNullOrEmpty()) MapResult.MissingField("x/y")
            else MapResult.Mapped(Command(CommandCategory.INPUT, "tap", mapOf("x" to x, "y" to y)))
        }
        "input_swipe", "swipe" -> {
            val x1 = args["x1"]?.trim()
            val y1 = args["y1"]?.trim()
            val x2 = args["x2"]?.trim()
            val y2 = args["y2"]?.trim()
            val durationMs = args["durationMs"]?.trim() ?: "300"
            if (x1.isNullOrEmpty() || y1.isNullOrEmpty() || x2.isNullOrEmpty() || y2.isNullOrEmpty()) MapResult.MissingField("x1/y1/x2/y2")
            else MapResult.Mapped(Command(CommandCategory.INPUT, "swipe", mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to durationMs)))
        }
        "input_keyevent", "keyevent" -> {
            val code = args["code"]?.trim()
            if (code.isNullOrEmpty()) MapResult.MissingField("code")
            else MapResult.Mapped(Command(CommandCategory.INPUT, "keyevent", mapOf("code" to code)))
        }
        "input_text", "text" -> {
            val text = args["text"]
            if (text == null) MapResult.MissingField("text")
            else MapResult.Mapped(Command(CommandCategory.INPUT, "text", mapOf("text" to text)))
        }
        "clipboard_paste", "paste_text" -> {
            val text = args["text"]
            if (text == null) MapResult.MissingField("text")
            else MapResult.Mapped(Command(CommandCategory.INPUT, "pasteText", mapOf("text" to text)))
        }
        "shell" -> {
            val cmd = args["command"]?.trim()
            if (cmd.isNullOrEmpty()) MapResult.MissingField("command")
            else {
                // 兼容回退：如果客户端直接发了 input tap / swipe / keyevent / text，自动转为 INPUT 结构化模型
                val tapMatch = Regex("^input\\s+tap\\s+(\\d+)\\s+(\\d+)$").find(cmd)
                val swipeMatch = Regex("^input\\s+swipe\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)(?:\\s+(\\d+))?$").find(cmd)
                val keyMatch = Regex("^input\\s+keyevent\\s+([A-Z0-9_]+)$").find(cmd)
                val textMatch = Regex("^input\\s+text\\s+[\"']?(.*?)[\"']?$").find(cmd)
                when {
                    tapMatch != null -> MapResult.Mapped(Command(CommandCategory.INPUT, "tap", mapOf("x" to tapMatch.groupValues[1], "y" to tapMatch.groupValues[2])))
                    swipeMatch != null -> MapResult.Mapped(Command(CommandCategory.INPUT, "swipe", mapOf(
                        "x1" to swipeMatch.groupValues[1], "y1" to swipeMatch.groupValues[2],
                        "x2" to swipeMatch.groupValues[3], "y2" to swipeMatch.groupValues[4],
                        "durationMs" to (swipeMatch.groupValues[5].ifEmpty { "300" })
                    )))
                    keyMatch != null -> MapResult.Mapped(Command(CommandCategory.INPUT, "keyevent", mapOf("code" to keyMatch.groupValues[1])))
                    textMatch != null -> MapResult.Mapped(Command(CommandCategory.INPUT, "text", mapOf("text" to textMatch.groupValues[1])))
                    else -> MapResult.Mapped(Command(CommandCategory.FILE, "shell", mapOf("cmd" to cmd)))
                }
            }
        }
        "launch_app" -> {
            val pkg = args["packageName"]?.trim()
            if (pkg.isNullOrEmpty()) MapResult.MissingField("packageName")
            else MapResult.Mapped(Command(CommandCategory.APP, "start", mapOf("pkg" to pkg)))
        }
        "stop_app" -> {
            val pkg = args["packageName"]?.trim()
            if (pkg.isNullOrEmpty()) MapResult.MissingField("packageName")
            else MapResult.Mapped(Command(CommandCategory.APP, "forceStop", mapOf("pkg" to pkg)))
        }
        // 获取当前截图:被控端 Shizuku screencap / 无障碍 takeScreenshot 采集后,
        // 经 R2 上传回传 URL(无 R2 时降级 base64),见 controlled CommandHandler.handleScreencap
        "screencap" -> MapResult.Mapped(Command(CommandCategory.APP, "screencap", emptyMap()))
        "app_time_limit" -> {
            val pkg = args["packageName"]?.trim()
            if (pkg.isNullOrEmpty()) MapResult.MissingField("packageName")
            else {
                // minutes<=0 时受控端按"清除该包限制"处理
                val minutes = args["minutes"]?.trim()?.toIntOrNull() ?: 0
                MapResult.Mapped(
                    Command(CommandCategory.APP_TIME, "setLimit", mapOf("pkg" to pkg, "minutes" to minutes.toString()))
                )
            }
        }
        "app_time_clear" -> {
            val pkg = args["packageName"]?.trim()
            if (pkg.isNullOrEmpty()) MapResult.MissingField("packageName")
            else MapResult.Mapped(Command(CommandCategory.APP_TIME, "clearLimit", mapOf("pkg" to pkg)))
        }
        "app_time_window" -> {
            val pkg = args["packageName"]?.trim()
            if (pkg.isNullOrEmpty()) MapResult.MissingField("packageName")
            else {
                val start = args["startTime"]?.trim()
                val end = args["endTime"]?.trim()
                if (start.isNullOrEmpty() || end.isNullOrEmpty()) MapResult.MissingField("startTime/endTime")
                else MapResult.Mapped(
                    Command(CommandCategory.APP_TIME, "setWindow", mapOf("pkg" to pkg, "start" to start, "end" to end))
                )
            }
        }
        "app_time_window_clear" -> {
            val pkg = args["packageName"]?.trim()
            if (pkg.isNullOrEmpty()) MapResult.MissingField("packageName")
            else MapResult.Mapped(Command(CommandCategory.APP_TIME, "clearWindow", mapOf("pkg" to pkg)))
        }
        else -> MapResult.Unsupported(type)
    }

    /**
     * 从 Web args 构造 [ReminderPayload](纯函数,便于单元测试)。
     * args:title(必填)/ text / buttons(JSON 数组字符串,如 '["收到","完成"]') /
     * expectAck("true"/"false")。按钮文字由发布方自定义,最多取 2 个
     * (Android 通知操作按钮展示上限)。
     */
    internal fun buildReminderPayload(args: Map<String, String>, taskId: Long? = null): ReminderBuild {
        val title = args["title"]?.trim()
        if (title.isNullOrEmpty()) return ReminderBuild.MissingField("title")
        val buttons = args["buttons"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), raw)
            }.getOrNull()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)
        } ?: emptyList()
        return ReminderBuild.Ready(
            ReminderPayload(
                title = title,
                text = args["text"]?.trim() ?: "",
                buttons = buttons,
                expectAck = args["expectAck"] == "true",
                taskId = taskId,
            )
        )
    }

    /** 构造并签名一条 REMINDER 信封,发布 topic 为 reminder/{deviceId}。 */
    internal fun buildReminderEnvelope(
        payload: ReminderPayload,
        sessionKey: String,
        id: String = "rem-" + UUID.randomUUID().toString().replace("-", "").take(16),
        timestamp: Long = System.currentTimeMillis(),
    ): CommandEnvelope {
        val payloadJson = json.encodeToString(ReminderPayload.serializer(), payload)
        val signature = HmacSigner.sign(
            HmacSigner.buildSigningData(payloadJson, id, timestamp),
            sessionKey,
        )
        return CommandEnvelope(
            id = id,
            type = MessageType.REMINDER.name,
            payload = payloadJson,
            timestamp = timestamp,
            signature = signature,
        )
    }

    /** 构造并签名一条命令信封(纯函数,id/timestamp 可注入,便于单元测试对拍被控端解码)。 */
    internal fun buildSignedEnvelope(
        command: Command,
        sessionKey: String,
        id: String = "web-" + UUID.randomUUID().toString().replace("-", "").take(16),
        timestamp: Long = System.currentTimeMillis(),
    ): CommandEnvelope {
        val payload = json.encodeToString(Command.serializer(), command)
        val signature = HmacSigner.sign(
            HmacSigner.buildSigningData(payload, id, timestamp),
            sessionKey,
        )
        return CommandEnvelope(
            id = id,
            type = MessageType.COMMAND.name,
            payload = payload,
            timestamp = timestamp,
            signature = signature,
        )
    }

    /**
     * 待发送的一条下行消息(未签名)。离线时原样落 pending_command,投递时再签名。
     * [auditType] 写入 command_audit.type / pending_command.command_type(Web type 或 v2 信封类型),
     * 同时决定离线队列默认有效期(危险类型 10 分钟)。
     */
    internal data class Outgoing(
        val commandId: String,
        val topic: String,
        val envelopeType: String,
        val payloadJson: String,
        val auditType: String,
        val summary: String?,
    )

    /** 用 sessionKey 对一条下行消息签名,timestamp 取签名时刻(离线投递时即投递时刻)。 */
    internal fun signOutgoing(out: Outgoing, sessionKey: String, timestamp: Long = System.currentTimeMillis()): CommandEnvelope =
        CommandEnvelope(
            id = out.commandId,
            type = out.envelopeType,
            payload = out.payloadJson,
            timestamp = timestamp,
            signature = HmacSigner.sign(HmacSigner.buildSigningData(out.payloadJson, out.commandId, timestamp), sessionKey),
        )

    /** 设备是否确定离线:进程内状态优先,未知时回落 DB device_status.online;都未知按在线处理(直发)。 */
    private fun isKnownOffline(deviceId: String): Boolean {
        presence?.get(deviceId)?.let { return !it }
        return repo?.deviceOnline(deviceId) == false
    }

    /**
     * 统一下发出口:离线入队 / 在线直发 + 审计。
     * @param allowQueue false 时即便离线也直发(QoS 1 由 broker 尽力而为)。
     */
    private suspend fun send(
        deviceId: String,
        out: Outgoing,
        issuedBy: String,
        ttlOverrideMs: Long? = null,
        allowQueue: Boolean = true,
    ): DispatchResult {
        val sessionKey = pairingService.sessionKeyFor(deviceId) ?: run {
            logger.warn("send rejected: no pairing session for deviceId={} type={}", deviceId, out.auditType)
            return DispatchResult.SessionMissing
        }
        val now = System.currentTimeMillis()
        if (allowQueue && repo != null && isKnownOffline(deviceId)) {
            val expiresAt = OfflineQueuePolicy.expiresAt(out.auditType, now, ttlOverrideMs)
            val queued = repo.insertPendingCommand(
                PendingCommandRow(
                    commandId = out.commandId,
                    deviceId = deviceId,
                    commandType = out.auditType,
                    topic = out.topic,
                    envelopeType = out.envelopeType,
                    payloadJson = out.payloadJson,
                    status = "PENDING",
                    createdAt = now,
                    expiresAt = expiresAt,
                )
            )
            if (queued) {
                repo.insertAudit(out.commandId, deviceId, out.auditType, out.summary, issuedBy, now, "QUEUED")
                logger.info("queued commandId={} deviceId={} type={} expiresAt={}", out.commandId, deviceId, out.auditType, expiresAt)
                return DispatchResult.Queued(out.commandId, expiresAt)
            }
            logger.warn("enqueue failed (DB?), falling back to direct publish: commandId={}", out.commandId)
        }
        val envelope = signOutgoing(out, sessionKey, now)
        val resp = emqx.publish(out.topic, json.encodeToString(CommandEnvelope.serializer(), envelope), qos = 1)
        val ok = resp.status in 200..299
        repo?.insertAudit(out.commandId, deviceId, out.auditType, out.summary, issuedBy, now, if (ok) "SENT" else "PUBLISH_FAILED")
        logger.info(
            "send commandId={} deviceId={} type={} emqxStatus={}",
            out.commandId, deviceId, out.auditType, resp.status,
        )
        return DispatchResult.Ok(envelope.id, resp.status, resp.body)
    }

    suspend fun dispatch(
        deviceId: String,
        type: String,
        args: Map<String, String>,
        issuedBy: String = "system",
        ttlOverrideMs: Long? = null,
    ): DispatchResult {
        // 任务栏通知走 REMINDER 通道而非 Command(按钮/签收语义不属于命令执行)
        if (type == "notify") return dispatchReminder(deviceId, args, issuedBy = issuedBy, ttlOverrideMs = ttlOverrideMs)

        val command = when (val m = mapCommand(type, args)) {
            is MapResult.MissingField -> return DispatchResult.MissingArg(m.field)
            is MapResult.Unsupported -> return DispatchResult.UnsupportedType(m.type)
            is MapResult.Mapped -> m.command
        }
        val out = Outgoing(
            commandId = newId("web-"),
            topic = MqttTopics.cmd(deviceId),
            envelopeType = MessageType.COMMAND.name,
            payloadJson = json.encodeToString(Command.serializer(), command),
            auditType = type,
            summary = summarize(type, args),
        )
        return send(deviceId, out, issuedBy, ttlOverrideMs)
    }

    /** v2:批准延时 → APP_TIME_EXTEND。离线默认保留 2 小时(隔天再加时长已无意义)。 */
    suspend fun sendAppTimeExtend(deviceId: String, requestId: String, packageName: String, minutes: Int, issuedBy: String): DispatchResult {
        val payload = json.encodeToString(AppTimeExtendPayload.serializer(), AppTimeExtendPayload(requestId, packageName, minutes))
        val out = Outgoing(newId("ext-"), MqttTopics.cmd(deviceId), MessageType.APP_TIME_EXTEND.name, payload,
            MessageType.APP_TIME_EXTEND.name, "requestId=$requestId pkg=$packageName minutes=$minutes")
        return send(deviceId, out, issuedBy, ttlOverrideMs = EXTENSION_TTL_MS)
    }

    /** v2:拒绝延时 → APP_TIME_EXTEND_DENIED。 */
    suspend fun sendAppTimeExtendDenied(deviceId: String, requestId: String, packageName: String, issuedBy: String): DispatchResult {
        val payload = json.encodeToString(AppTimeExtendDeniedPayload.serializer(), AppTimeExtendDeniedPayload(requestId, packageName))
        val out = Outgoing(newId("ext-"), MqttTopics.cmd(deviceId), MessageType.APP_TIME_EXTEND_DENIED.name, payload,
            MessageType.APP_TIME_EXTEND_DENIED.name, "requestId=$requestId pkg=$packageName")
        return send(deviceId, out, issuedBy, ttlOverrideMs = EXTENSION_TTL_MS)
    }

    /** v2:全量下发电子围栏 → GEOFENCE_SET。 */
    suspend fun sendGeofenceSet(deviceId: String, fences: List<GeofenceDto>, issuedBy: String): DispatchResult {
        val payload = json.encodeToString(
            GeofenceSetPayload.serializer(),
            GeofenceSetPayload(fences.map { Geofence(it.id ?: 0, it.name, it.lat, it.lng, it.radiusMeters) }),
        )
        val out = Outgoing(newId("geo-"), MqttTopics.cmd(deviceId), MessageType.GEOFENCE_SET.name, payload,
            MessageType.GEOFENCE_SET.name, "${fences.size} geofence(s)")
        return send(deviceId, out, issuedBy)
    }

    /**
     * 设备上线:投递其离线队列。过期的标 EXPIRED(永不发送),其余按入队顺序用当前
     * sessionKey + 当前时间重新签名(被控端有 5 分钟重放窗口,不能用入队时的签名)。
     * 返回成功投递条数。
     */
    suspend fun deliverPending(deviceId: String): Int {
        val r = repo ?: return 0
        val rows = r.listPendingCommands(deviceId)
        if (rows.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val (deliverable, expired) = OfflineQueuePolicy.partition(rows, now)
        expired.forEach { row ->
            if (r.transitionPendingCommand(row.commandId, "EXPIRED", now)) {
                r.updateAuditResult(row.commandId, "EXPIRED", null, now)
            }
        }
        val sessionKey = pairingService.sessionKeyFor(deviceId) ?: return 0
        var sent = 0
        for (row in deliverable) {
            // 条件更新抢占:多个上线信号并发触发时同一命令只投一次
            if (!r.transitionPendingCommand(row.commandId, "SENT", now)) continue
            val out = Outgoing(row.commandId, row.topic, row.envelopeType, row.payloadJson, row.commandType, null)
            val envelope = signOutgoing(out, sessionKey)
            val resp = runCatching { emqx.publish(row.topic, json.encodeToString(CommandEnvelope.serializer(), envelope), qos = 1) }.getOrNull()
            val ok = resp != null && resp.status in 200..299
            if (ok) {
                sent++
                r.updateAuditResult(row.commandId, "SENT", null, null)
            } else {
                // 发布失败退回 PENDING,下次上线信号再投
                r.revertPendingCommand(row.commandId)
                logger.warn("deliverPending publish failed commandId={} status={}", row.commandId, resp?.status)
            }
        }
        if (sent > 0 || expired.isNotEmpty()) {
            logger.info("deliverPending deviceId={} sent={} expired={}", deviceId, sent, expired.size)
        }
        return sent
    }

    /** 维护任务:全表过期仍 PENDING 的命令标 EXPIRED。返回过期条数。 */
    fun expireStalePending(now: Long = System.currentTimeMillis()): Int {
        val r = repo ?: return 0
        var n = 0
        for (id in r.listExpiredPendingCommandIds(now)) {
            if (r.transitionPendingCommand(id, "EXPIRED", now)) {
                r.updateAuditResult(id, "EXPIRED", null, now)
                n++
            }
        }
        return n
    }

    private fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(16)

    /** 审计摘要:type + 参数(截断,避免把长文本整段写进审计表)。 */
    internal fun summarize(type: String, args: Map<String, String>): String =
        (type + " " + args.entries.joinToString(", ") { (k, v) -> "$k=${v.take(80)}" }).trim().take(400)

    /**
     * 向所有有配对会话的设备广播一条 PUSH_DATA(逐设备用各自 sessionKey 签名)。
     * OTA 版本发布后通知被控端立即检查更新。返回成功送达的设备数。
     */
    suspend fun broadcastPush(payloadJson: String): Int {
        var sent = 0
        for (deviceId in pairingService.allSessionDeviceIds()) {
            val sessionKey = pairingService.sessionKeyFor(deviceId) ?: continue
            val id = "push-" + UUID.randomUUID().toString().replace("-", "").take(16)
            val timestamp = System.currentTimeMillis()
            val envelope = CommandEnvelope(
                id = id,
                type = MessageType.PUSH_DATA.name,
                payload = payloadJson,
                timestamp = timestamp,
                signature = HmacSigner.sign(HmacSigner.buildSigningData(payloadJson, id, timestamp), sessionKey),
            )
            val resp = emqx.publish(MqttTopics.push(deviceId), json.encodeToString(CommandEnvelope.serializer(), envelope), qos = 1)
            if (resp.status in 200..299) sent++
            logger.info("broadcastPush deviceId={} status={}", deviceId, resp.status)
        }
        return sent
    }

    /**
     * 向单台设备推送一条 PUSH_DATA(用该设备自己的 sessionKey 签名)。
     *
     * 用于「某文件已就绪,请来拉取」这类点对点通知。与 [broadcastPush] 共用 push/{deviceId}
     * 通道而不是新增 topic —— 受控端已订阅 push/{deviceId},EMQX ACL 也已放行,
     * 新增 topic 反而要同步改两端订阅集合与 ACL 规则,链路更长更易漏。
     *
     * @return 是否成功发布(无配对会话或 EMQX 非 2xx 为 false)。
     */
    suspend fun pushToDevice(deviceId: String, payloadJson: String): Boolean {
        val sessionKey = pairingService.sessionKeyFor(deviceId) ?: run {
            logger.warn("pushToDevice rejected: no pairing session for deviceId={}", deviceId)
            return false
        }
        val id = "push-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val timestamp = System.currentTimeMillis()
        val envelope = CommandEnvelope(
            id = id,
            type = MessageType.PUSH_DATA.name,
            payload = payloadJson,
            timestamp = timestamp,
            signature = HmacSigner.sign(HmacSigner.buildSigningData(payloadJson, id, timestamp), sessionKey),
        )
        val resp = emqx.publish(
            MqttTopics.push(deviceId),
            json.encodeToString(CommandEnvelope.serializer(), envelope),
            qos = 1,
        )
        logger.info("pushToDevice deviceId={} status={}", deviceId, resp.status)
        return resp.status in 200..299
    }

    /**
     * 下发任务栏通知(REMINDER)。按钮文字、是否要求签收受 [ReminderPayload] 控制。
     * 签收回报由受控端发 REMINDER_RESULT 到 result/{deviceId},经 ingestor 入库 task_ack。
     */
    suspend fun dispatchReminder(
        deviceId: String,
        args: Map<String, String>,
        taskId: Long? = null,
        issuedBy: String = "system",
        ttlOverrideMs: Long? = null,
    ): DispatchResult {
        val payload = when (val m = buildReminderPayload(args, taskId)) {
            is ReminderBuild.MissingField -> return DispatchResult.MissingArg(m.field)
            is ReminderBuild.Ready -> m.payload
        }
        val out = Outgoing(
            commandId = newId("rem-"),
            topic = MqttTopics.reminder(deviceId),
            envelopeType = MessageType.REMINDER.name,
            payloadJson = json.encodeToString(ReminderPayload.serializer(), payload),
            auditType = "notify",
            summary = "notify title=${payload.title.take(80)}" + (taskId?.let { " taskId=$it" } ?: ""),
        )
        return send(deviceId, out, issuedBy, ttlOverrideMs)
    }

    companion object {
        /** 延时审批命令的离线有效期:2 小时。 */
        const val EXTENSION_TTL_MS: Long = 2L * 60 * 60 * 1000
    }
}
