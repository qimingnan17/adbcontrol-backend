package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

/**
 * QR 配对载荷与服务器响应(README 第八章 8.3)。
 * 安全要点:QR 内只含 pairToken,不直接含 MQTT/R2 长期凭证;
 * 配对成功后服务器签发 [PairingResponse] 临时凭证(7 天有效),加密落盘到 EncryptedFile。
 */

/** QR 二维码载荷 */
@Serializable
data class PairTokenPayload(
    val pairToken: String,
    val serverUrl: String,
    val deviceId: String,
    val deviceName: String? = null,
)

/** 服务器响应:配对成功后下发临时凭证 */
@Serializable
data class PairingResponse(
    val broker: BrokerConfig,
    val r2: R2Config? = null,
    /** HMAC 签名密钥(Base64),长期有效 */
    val sessionKey: String,
    /** 临时凭证过期时间(毫秒) */
    val expiresAt: Long,
)

/** 配对错误响应 */
@Serializable
data class PairingError(
    val code: String,
    val message: String,
)

/** 续期请求:被控端临时凭证接近过期时,用 sessionKey / pairToken 证明身份后向后端续期。 */
@Serializable
data class RenewRequest(
    val deviceId: String,
    val pairToken: String,
)

@Serializable
data class RenewResponse(
    val broker: BrokerConfig,
    val expiresAt: Long,
)
