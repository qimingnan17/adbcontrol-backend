package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

/**
 * 后端自持的配置/协议模型,字段与 :shared 模块镜像(保持线上 JSON 兼容),
 * 但不依赖 :shared,避免引入 Android 依赖。设计参见 README 第四章 / 第十章。
 */

@Serializable
data class BrokerConfig(
    val host: String,
    val port: Int = 8883,
    val useTls: Boolean = true,
    val appid: String,
    val username: String,
    val password: String,
    val cleanSession: Boolean = false,
    val keepAliveSec: Int = 60,
    /** MQTT over WebSocket(wss/ws)。自部署 EMQX 走 Cloudflare Tunnel 场景,见 :shared 镜像说明。 */
    val useWs: Boolean = false,
    /** WebSocket 路径,EMQX 默认 /mqtt */
    val wsPath: String = "/mqtt",
)

@Serializable
data class R2Config(
    val endpoint: String,
    val bucket: String,
    val region: String = "auto",
    val accessKey: String,
    val accessSecret: String,
    val publicRead: Boolean = true,
)

@Serializable
data class DbConfig(
    val type: String = "mysql",
    val host: String,
    val port: Int = 3306,
    val name: String,
    val user: String,
    val password: String,
    /** 远程 MySQL TLS 是否校验服务端证书(默认 false 保持历史兼容)。 */
    val sslVerify: Boolean = false,
)

/**
 * EMQX Serverless 实测限制(README 10.2.1)。主控端 Dashboard 容量计直接读这些常量。
 */
object EmqxLimits {
    const val MAX_CONNECTIONS = 30
    const val MAX_STORAGE_MB = 500
    const val MAX_REQUESTS_PER_HOUR = 36000
    const val FREE_DEVICES_24X7 = 23
    const val MAX_SUBSCRIPTIONS_PER_CLIENT = 10
    const val MAX_MESSAGE_BYTES = 1_000_000
}
