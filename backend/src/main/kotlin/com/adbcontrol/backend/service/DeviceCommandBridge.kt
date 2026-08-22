package com.adbcontrol.backend.service

import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.net.MqttTopics
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.Serializable
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
 * - shell       → FILE/shell,   params={cmd: args.command}
 * - launch_app  → APP/start,    params={pkg: args.packageName}
 * - stop_app    → APP/forceStop,params={pkg: args.packageName}
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

    /** Web type/args → shared Command(纯函数,便于单元测试)。 */
    internal fun mapCommand(type: String, args: Map<String, String>): MapResult = when (type) {
        "shell" -> {
            val cmd = args["command"]?.trim()
            if (cmd.isNullOrEmpty()) MapResult.MissingField("command")
            else MapResult.Mapped(Command(CommandCategory.FILE, "shell", mapOf("cmd" to cmd)))
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
        else -> MapResult.Unsupported(type)
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
}