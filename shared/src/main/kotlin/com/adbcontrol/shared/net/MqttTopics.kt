package com.adbcontrol.shared.net

/**
 * MQTT topic 命名单一来源(shared 模块)。主控端 / 被控端均引用此常量,
 * 避免任一端用裸字符串字面量导致 topic 名漂移后订阅 / 发布失联。
 *
 * 设计参见 README 第四章 4.3。
 *
 * - 主控 → 被控:cmd / reminder / push / ping
 * - 被控 → 主控:result / status / health / location / activity / usage / pong
 * - LWT:device/offline/{deviceId}(被控掉线)、controller/offline/{controllerId}(主控掉线)
 *
 * `{deviceId}` 为被控端唯一标识,`+` 为单层通配符。
 */
object MqttTopics {
    // 主控 → 被控
    const val CMD_PREFIX = "cmd/"
    const val REMINDER_PREFIX = "reminder/"
    const val PUSH_PREFIX = "push/"
    const val PING_PREFIX = "ping/"

    // 被控 → 主控
    const val RESULT_PREFIX = "result/"
    const val PONG_PREFIX = "pong/"
    const val STATUS_PREFIX = "status/"
    const val HEALTH_PREFIX = "health/"
    const val LOCATION_PREFIX = "location/"
    const val ACTIVITY_PREFIX = "activity/"
    const val USAGE_PREFIX = "usage/"

    // LWT
    const val DEVICE_OFFLINE_PREFIX = "device/offline/"
    const val CONTROLLER_OFFLINE_PREFIX = "controller/offline/"

    fun cmd(deviceId: String) = "$CMD_PREFIX$deviceId"
    fun reminder(deviceId: String) = "$REMINDER_PREFIX$deviceId"
    fun push(deviceId: String) = "$PUSH_PREFIX$deviceId"
    fun ping(deviceId: String) = "$PING_PREFIX$deviceId"
    fun result(deviceId: String) = "$RESULT_PREFIX$deviceId"
    fun pong(deviceId: String) = "$PONG_PREFIX$deviceId"
    fun status(deviceId: String) = "$STATUS_PREFIX$deviceId"
    fun health(deviceId: String) = "$HEALTH_PREFIX$deviceId"
    fun location(deviceId: String) = "$LOCATION_PREFIX$deviceId"
    fun activity(deviceId: String) = "$ACTIVITY_PREFIX$deviceId"
    fun usage(deviceId: String) = "$USAGE_PREFIX$deviceId"
    fun deviceOffline(deviceId: String) = "$DEVICE_OFFLINE_PREFIX$deviceId"
    fun controllerOffline(controllerId: String) = "$CONTROLLER_OFFLINE_PREFIX$controllerId"

    /** 主控端订阅集合(均用单层通配 `+`)。QoS 见 [subscriptionQos]。 */
    val subscriptions: List<String> = listOf(
        "${RESULT_PREFIX}+",
        "${STATUS_PREFIX}+",
        "${HEALTH_PREFIX}+",
        "${LOCATION_PREFIX}+",
        "${ACTIVITY_PREFIX}+",
        "${USAGE_PREFIX}+",
        "${PONG_PREFIX}+",
        "$DEVICE_OFFLINE_PREFIX+",
    )

    /**
     * 与 [subscriptions] 一一对应的 QoS。被控端发布侧 pong/result 等多用 QoS 0,
     * 主控端订阅侧 QoS 不应高于发布侧,否则占用 EMQX Serverless 存储配额(500MB)。
     */
    val subscriptionQos: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0, 1)

    /** 被控端订阅集合(主控 → 被控方向)。 */
    val controlledSubscriptions: List<String> = listOf(
        "${CMD_PREFIX}${'$'}{deviceId}",
        "${REMINDER_PREFIX}${'$'}{deviceId}",
        "${PUSH_PREFIX}${'$'}{deviceId}",
        "${PING_PREFIX}${'$'}{deviceId}",
        "$CONTROLLER_OFFLINE_PREFIX+",
    )

    /** 被控端订阅对应 QoS。 */
    val controlledSubscriptionQos: List<Int> = listOf(1, 1, 1, 0, 1)

    /**
     * 从入站 topic 反解出 deviceId。规则:取 topic 最后一个 `/` 之后的部分。
     * 例如 `status/device-a001` → `device-a001`;`device/offline/device-a001` → `device-a001`。
     */
    fun parseDeviceId(topic: String): String? {
        val idx = topic.lastIndexOf('/')
        return if (idx >= 0 && idx < topic.length - 1) topic.substring(idx + 1) else null
    }

    /** 推断入站 topic 的语义类别(供消息路由分发用)。 */
    fun classify(topic: String): IncomingCategory? {
        return when {
            topic.startsWith(RESULT_PREFIX) -> IncomingCategory.RESULT
            topic.startsWith(PONG_PREFIX) -> IncomingCategory.PONG
            topic.startsWith(STATUS_PREFIX) -> IncomingCategory.STATUS
            topic.startsWith(HEALTH_PREFIX) -> IncomingCategory.HEALTH
            topic.startsWith(LOCATION_PREFIX) -> IncomingCategory.LOCATION
            topic.startsWith(ACTIVITY_PREFIX) -> IncomingCategory.ACTIVITY
            topic.startsWith(USAGE_PREFIX) -> IncomingCategory.USAGE
            topic.startsWith(DEVICE_OFFLINE_PREFIX) -> IncomingCategory.DEVICE_OFFLINE
            topic.startsWith(CONTROLLER_OFFLINE_PREFIX) -> IncomingCategory.CONTROLLER_OFFLINE
            else -> null
        }
    }
}

enum class IncomingCategory {
    RESULT, PONG, STATUS, HEALTH, LOCATION, ACTIVITY, USAGE, DEVICE_OFFLINE, CONTROLLER_OFFLINE
}

/** 从 topic 提取 deviceId 的便捷扩展。 */
fun topicDeviceId(topic: String): String = MqttTopics.parseDeviceId(topic) ?: "unknown"
