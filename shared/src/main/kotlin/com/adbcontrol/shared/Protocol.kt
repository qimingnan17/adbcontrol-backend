package com.adbcontrol.shared

import kotlinx.serialization.Serializable

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
}
