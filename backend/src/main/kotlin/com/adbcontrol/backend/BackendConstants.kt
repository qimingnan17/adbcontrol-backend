package com.adbcontrol.backend

/**
 * 后端全局常量。
 */
object BackendConstants {
    const val VERSION = "0.1.0"

    /** 临时 MQTT 凭证有效期:7 天(README 8.3)。 */
    const val CREDENTIAL_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 设备数硬上限(README 10.2.3 缓解策略第 6 条),超过拒绝新配对。 */
    const val MAX_DEVICES = 23

    /** HMAC sessionKey 字节数。 */
    const val SESSION_KEY_BYTES = 32

    /** MQTT 临时密码字节数。 */
    const val MQTT_PASSWORD_BYTES = 32
}
