package com.adbcontrol.shared.model

import kotlinx.serialization.Serializable

/**
 * 命令执行回报。被控端执行后写入本地数据库，并按需回传给主控端。
 *
 * 跨端协议模型:主控端 / 被控端均通过 kotlinx.serialization 编解码此类型,
 * 避免任一端手写 JSON 字段名导致字段拼写漂移。
 */
@Serializable
data class ExecutionResult(
    val commandId: String,         // 对应 WsMessage.id
    val success: Boolean,
    val output: String = "",        // 命令输出 / 错误信息
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
)
