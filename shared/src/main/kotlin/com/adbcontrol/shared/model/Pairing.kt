package com.adbcontrol.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * QR 配对载荷与服务器响应。
 * 设计参见 README 第八章"配置导入"。
 *
 * 安全要点:QR 内只含 pairToken,不直接含 MQTT/R2 长期凭证。
 * 配对成功后服务器签发 [PairingResponse] 临时凭证(7 天有效),
 * 加密落盘到 EncryptedFile。
 */

/** QR 二维码载荷 */
@Serializable
data class PairTokenPayload(
    /** 一次性配对令牌,服务端签发,使用后失效 */
    val pairToken: String,
    /** 后端服务器 URL,如 https://api.adbcontrol.example.com */
    val serverUrl: String,
    /** 设备 ID,主控端预先登记 */
    val deviceId: String,
    /** 设备友好名(可选,主控端预填) */
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
    val code: String,                          // TOKEN_INVALID / TOKEN_USED / DEVICE_LIMIT / SERVER_ERROR
    val message: String,
)

/**
 * 续期请求。被控端临时凭证接近过期时,向后端续期。
 * 用当前 sessionKey 签名证明身份。
 */
@Serializable
data class RenewRequest(
    val deviceId: String,
    val pairToken: String,                     // 已用过的 token 作为身份证明(后端记录已使用)
)

@Serializable
data class RenewResponse(
    val broker: BrokerConfig,
    val expiresAt: Long,
)
