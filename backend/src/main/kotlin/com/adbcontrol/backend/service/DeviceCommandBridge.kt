package com.adbcontrol.backend.service

import com.adbcontrol.shared.MessageType
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
 */
class DeviceCommandBridge(
    private val pairingService: PairingService,
    private val emqx: EmqxProxyService,
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
    )

    sealed class DispatchResult {
        data class Ok(val commandId: String, val emqxStatus: Int, val emqxBody: String) : DispatchResult()
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

    suspend fun dispatch(deviceId: String, type: String, args: Map<String, String>): DispatchResult {
        // 任务栏通知走 REMINDER 通道而非 Command(按钮/签收语义不属于命令执行)
        if (type == "notify") return dispatchReminder(deviceId, args)

        val command = when (val m = mapCommand(type, args)) {
            is MapResult.MissingField -> return DispatchResult.MissingArg(m.field)
            is MapResult.Unsupported -> return DispatchResult.UnsupportedType(m.type)
            is MapResult.Mapped -> m.command
        }
        val sessionKey = pairingService.sessionKeyFor(deviceId) ?: run {
            logger.warn("dispatch rejected: no pairing session for deviceId={}", deviceId)
            return DispatchResult.SessionMissing
        }
        val envelope = buildSignedEnvelope(command, sessionKey)
        val wireJson = json.encodeToString(CommandEnvelope.serializer(), envelope)
        val resp = emqx.publish(MqttTopics.cmd(deviceId), wireJson, qos = 1)
        logger.info(
            "dispatch commandId={} deviceId={} type={} emqxStatus={}",
            envelope.id, deviceId, type, resp.status,
        )
        return DispatchResult.Ok(envelope.id, resp.status, resp.body)
    }

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
     * 下发任务栏通知(REMINDER)。按钮文字、是否要求签收受 [ReminderPayload] 控制。
     * 签收回报由受控端发 REMINDER_RESULT 到 result/{deviceId},经 ingestor 入库 task_ack。
     */
    suspend fun dispatchReminder(deviceId: String, args: Map<String, String>, taskId: Long? = null): DispatchResult {
        val payload = when (val m = buildReminderPayload(args, taskId)) {
            is ReminderBuild.MissingField -> return DispatchResult.MissingArg(m.field)
            is ReminderBuild.Ready -> m.payload
        }
        val sessionKey = pairingService.sessionKeyFor(deviceId) ?: run {
            logger.warn("dispatchReminder rejected: no pairing session for deviceId={}", deviceId)
            return DispatchResult.SessionMissing
        }
        val envelope = buildReminderEnvelope(payload, sessionKey)
        val wireJson = json.encodeToString(CommandEnvelope.serializer(), envelope)
        val resp = emqx.publish(MqttTopics.reminder(deviceId), wireJson, qos = 1)
        logger.info(
            "dispatchReminder reminderId={} deviceId={} taskId={} expectAck={} emqxStatus={}",
            envelope.id, deviceId, taskId, payload.expectAck, resp.status,
        )
        return DispatchResult.Ok(envelope.id, resp.status, resp.body)
    }
}
