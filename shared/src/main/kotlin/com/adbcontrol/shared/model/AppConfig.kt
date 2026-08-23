package com.adbcontrol.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 运行时配置。被控端 / 主控端通过 QR 配对或服务器签发获得,
 * 加密落盘到 EncryptedFile,启动时加载。
 *
 * 设计参见 README 第八章"配置导入"与第十章 R2 凭证。
 */
@Serializable
data class AppConfig(
    val deviceId: String,
    /** MQTT broker 配置(由配对服务器签发的临时凭证,7 天有效) */
    val broker: BrokerConfig,
    /** R2 对象存储配置(截屏 / 大文件 HTTP 旁路) */
    val r2: R2Config? = null,
    /** 远程 MySQL 配置(仅主控端使用;被控端不直连 MySQL) */
    val db: DbConfig? = null,
    /** HMAC 签名密钥(Base64),长期有效,用于消息验签防伪造/重放 */
    val sessionKey: String? = null,
    /** 临时凭证过期时间(毫秒),过期后由被控端向 backend 续期 */
    val expiresAt: Long = 0,
    /** 后端服务器 URL(OTA 更新检查 /update/check 与结果上报 /update/report 用) */
    val serverUrl: String = "",
)

@Serializable
data class BrokerConfig(
    val host: String,                          // o8cc1111.ala.cn-hangzhou.emqxsl.cn
    val port: Int = 8883,                      // TLS 8883,WSS 8084
    val useTls: Boolean = true,
    val appid: String,                         // o8cc1111,作为 username 前缀
    val username: String,                      // o8cc1111@device-a001
    val password: String,                      // 临时 MQTT 密码,7 天有效
    /** cleanSession=false 让 EMQX 缓存离线消息(QoS 1 不丢),配合 LWT 掉线感知 */
    val cleanSession: Boolean = false,
    /** keepAlive 秒,默认 60 */
    val keepAliveSec: Int = 60,
)

@Serializable
data class R2Config(
    val endpoint: String,                     // https://696e933486bc331658bce6378aaceaea.r2.cloudflarestorage.com
    val bucket: String,                        // slss-boby
    val region: String = "auto",               // R2 全球边缘
    val accessKey: String,
    val accessSecret: String,
    /** bucket 是否 public read,主控可直接 GET URL */
    val publicRead: Boolean = true,
)

@Serializable
data class DbConfig(
    val type: String = "mysql",                // 仅支持 mysql
    val host: String,                          // mysql6.sqlpub.com
    val port: Int = 3311,
    val name: String,                          // slss12
    val user: String,
    val password: String,
)

/**
 * EMQX Serverless 实际限制(实测自控制台)。
 * 主控端 Dashboard 容量计直接读这些常量。
 */
object EmqxLimits {
    const val MAX_CONNECTIONS = 30
    const val MAX_STORAGE_MB = 500
    const val MAX_REQUESTS_PER_HOUR = 36000
    /** 24×7 全在线不超免费 session 分钟数(100 万 / 月)的设备数 */
    const val FREE_DEVICES_24X7 = 23
    /** 单 client 订阅 topic 上限 */
    const val MAX_SUBSCRIPTIONS_PER_CLIENT = 10
    /** 单消息大小上限(字节) */
    const val MAX_MESSAGE_BYTES = 1_000_000
}
