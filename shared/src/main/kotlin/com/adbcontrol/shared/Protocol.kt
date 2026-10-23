package com.adbcontrol.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 协议版本(v2.0 起)。
 *
 * - 发送方:所有 MQTT 信封 / shared 协议模型带 `protocolVersion`(当前 = [CURRENT])。
 * - 接收方:字段缺失视为 [LEGACY](= 1,v2.0 之前的端);旧版本只记日志并兼容处理,
 *   只有 JSON 无法解析时才拒绝。
 * - protocolVersion **不参与** HMAC 签名(签名串仍是 payload:id:timestamp),
 *   这样 v1 / v2 两端可以互相验签。
 */
object ProtocolVersion {
    const val CURRENT: Int = 2
    const val LEGACY: Int = 1

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 从原始 JSON 文本读取 protocolVersion:字段缺失或不是整数 → [LEGACY];
     * 整段不是 JSON 对象 → null(调用方应拒绝该消息)。
     */
    fun of(rawJson: String): Int? {
        val obj = runCatching { lenient.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        return of(obj)
    }

    fun of(obj: JsonObject): Int =
        runCatching { obj["protocolVersion"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: LEGACY

    fun isLegacy(version: Int): Boolean = version < CURRENT
}

/**
 * MQTT wire 信封(cmd/ reminder/ push/ result/ status/ ... 全部 topic 共用)。
 * 字段与被控端 MessageCodec 的 MqttEnvelope 一致;v2 新增 [protocolVersion]。
 *
 * 注意:解码时缺失 protocolVersion 会落到默认值 [ProtocolVersion.CURRENT],
 * 需要区分 v1 / v2 时请用 [ProtocolVersion.of] 读原始 JSON。
 */
@Serializable
data class MqttEnvelope(
    val id: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val signature: String? = null,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

/**
 * WebSocket 消息协议。主控端 <-> 被控端之间的所有通信都封装为 [WsMessage]，
 * 通过 type 字段区分种类，使用 JSON 序列化传输。
 */
@Serializable
data class WsMessage(
    val id: String,                 // 消息唯一ID（UUID），用于配对请求/回报
    val type: MessageType,
    val payload: String,            // 序列化后的载荷 JSON
    val timestamp: Long = System.currentTimeMillis(),
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

@Serializable
enum class MessageType {
    PING,                           // 心跳
    PONG,
    COMMAND,                        // 主控端 -> 被控端：执行一条命令
    COMMAND_RESULT,                 // 被控端 -> 主控端：命令回报（可选）
    REMINDER,                       // 主控端 -> 被控端：触发提醒
    REMINDER_RESULT,                // 被控端 -> 主控端：提醒回报（可选）
    PUSH_DATA,                      // 主控端 -> 被控端：数据推送
    ACK,                            // 通用确认
    ERROR,                          // 通用错误

    // ---- v2.0 ----
    EXTENSION_REQUEST,              // 被控端 -> 主控端(request/{deviceId}):申请延时
    APP_TIME_EXTEND,                // 主控端 -> 被控端(cmd/{deviceId}):批准延时
    APP_TIME_EXTEND_DENIED,         // 主控端 -> 被控端(cmd/{deviceId}):拒绝延时
    GEOFENCE_SET,                   // 主控端 -> 被控端(cmd/{deviceId}):全量下发电子围栏
}
