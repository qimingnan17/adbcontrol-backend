package com.adbcontrol.backend.security

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 加密 / 签名工具。HMAC-SHA256 实现与 :shared 的 HmacSigner 行为一致(README 8.2),
 * 后端独立维护以避免引入 :shared 的 Android 依赖。
 */
object CryptoUtil {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val secureRandom = SecureRandom()

    /** 生成随机字节并 Base64 编码(用于 MQTT 临时密码 / sessionKey)。 */
    fun randomBase64(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength).also { secureRandom.nextBytes(it) }
        return base64Encode(bytes)
    }

    /** HMAC-SHA256 签名,返回 Base64 字符串。 */
    fun sign(data: String, sessionKeyBase64: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(base64Decode(sessionKeyBase64), HMAC_ALGORITHM))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return base64Encode(raw)
    }

    /** 验签(常数时间比较,防侧信道)。 */
    fun verify(data: String, sessionKeyBase64: String, signatureBase64: String): Boolean = try {
        constantTimeEq(sign(data, sessionKeyBase64), signatureBase64)
    } catch (e: Exception) {
        false
    }

    /**
     * SHA-256 摘要的十六进制小写表示。
     *
     * 主要用途:R2 的 S3 Secret Access Key = SHA-256 **hex** digest(API token value),
     * 见 Cloudflare R2 官方文档的 "Get S3 API credentials from an API token"。
     */
    fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun base64Decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    private fun base64Encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    private fun constantTimeEq(a: String, b: String): Boolean {
        val maxLen = maxOf(a.length, b.length)
        var diff = if (a.length != b.length) 1 else 0
        for (i in 0 until maxLen) {
            val ca = if (i < a.length) a[i].code else 0
            val cb = if (i < b.length) b[i].code else 0
            diff = diff or (ca xor cb)
        }
        return diff == 0
    }
}
