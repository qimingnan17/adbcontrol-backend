package com.adbcontrol.shared.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 签名工具。设计参见 README 第八章 8.2。
 *
 * 防御目标:
 * - 伪造设备:无 sessionKey 无法生成合法签名
 * - 重放攻击:WsMessage.id + timestamp 在 5 分钟外被拒
 * - 越权控制:服务器 ACL 限制每个 deviceId 只能 publish 自己的 topic
 *
 * 用法:
 * - 发送方:val sig = HmacSigner.sign(payloadJson + ":" + msg.id + ":" + msg.timestamp, sessionKey)
 * - 接收方:HmacSigner.verify(payloadJson + ":" + msg.id + ":" + msg.timestamp, sessionKey, sig)
 */
object HmacSigner {

    private const val ALGORITHM = "HmacSHA256"

    /** 时间窗口(毫秒),超过则视为重放攻击 */
    const val REPLAY_WINDOW_MS = 5 * 60 * 1000L

    /** 签名,返回 Base64 字符串。sessionKeyBase64 非法时抛 IllegalArgumentException。 */
    fun sign(data: String, sessionKeyBase64: String): String {
        val key = decodeBase64(sessionKeyBase64)
        require(key.size >= 16) { "HMAC key too short: ${key.size} bytes" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return encodeBase64(raw)
    }

    /** 验签。任一参数非法或签名不匹配均返回 false(不抛异常,与 sign 行为对称防御)。 */
    fun verify(data: String, sessionKeyBase64: String, signatureBase64: String): Boolean {
        return try {
            val expected = sign(data, sessionKeyBase64)
            constantTimeEq(expected, signatureBase64)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查时间戳是否在重放窗口内。
     * @param msgTimestamp 消息自带的时间戳(毫秒)
     * @param now 当前时间(毫秒),默认 System.currentTimeMillis()
     */
    fun isWithinReplayWindow(msgTimestamp: Long, now: Long = System.currentTimeMillis()): Boolean {
        val delta = Math.abs(now - msgTimestamp)
        return delta <= REPLAY_WINDOW_MS
    }

    /**
     * 构建待签字符串。
     * @param payloadJson 序列化后的 WsMessage.payload
     * @param msgId WsMessage.id
     * @param timestamp WsMessage.timestamp
     */
    fun buildSigningData(payloadJson: String, msgId: String, timestamp: Long): String {
        return "$payloadJson:$msgId:$timestamp"
    }

    // ---------- 内部工具 ----------

    private fun decodeBase64(s: String): ByteArray {
        return java.util.Base64.getDecoder().decode(s)
    }

    private fun encodeBase64(b: ByteArray): String {
        return java.util.Base64.getEncoder().encodeToString(b)
    }

    /** 常数时间比较,防侧信道。长度不等仍遍历较长串做 dummy 比较再返回,避免长度或前缀泄露。 */
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
