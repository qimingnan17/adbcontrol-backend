package com.adbcontrol.shared.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HmacSigner 签名/验签一致性测试(README 第八章 8.2 安全设计)。
 *
 * 验证目标:
 * - sign + verify 在相同输入下应一致返回 true
 * - 篡改 payload / 篡改签名 / 错误密钥 应返回 false
 * - 常数时间比较不应被长度差绕过
 */
class HmacSignerTest {

    private val randomKey: String =
        Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    @Test
    fun `sign and verify roundtrip returns true`() {
        val data = "{\"cmd\":\"SUSPEND\"}:msg-001:1718000000000"
        val sig = HmacSigner.sign(data, randomKey)
        assertTrue(HmacSigner.verify(data, randomKey, sig), "验签应通过")
    }

    @Test
    fun `tampered payload should fail verification`() {
        val original = "payload:msg-002:1718000001000"
        val tampered = "payload-TAMPERED:msg-002:1718000001000"
        val sig = HmacSigner.sign(original, randomKey)
        assertFalse(HmacSigner.verify(tampered, randomKey, sig), "篡改 payload 后验签应失败")
    }

    @Test
    fun `wrong session key should fail verification`() {
        val data = "payload:msg-003:1718000002000"
        val sig = HmacSigner.sign(data, randomKey)
        val otherKey =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        assertFalse(HmacSigner.verify(data, otherKey, sig), "错误密钥验签应失败")
    }

    @Test
    fun `tampered signature should fail verification`() {
        val data = "payload:msg-004:1718000003000"
        val sig = HmacSigner.sign(data, randomKey)
        val tamperedSig = sig.dropLast(1) + (if (sig.last() == 'A') 'B' else 'A')
        assertFalse(HmacSigner.verify(data, randomKey, tamperedSig), "篡改签名后验签应失败")
    }

    @Test
    fun `signatures for same input are deterministic`() {
        val data = "payload:msg-005:1718000004000"
        val s1 = HmacSigner.sign(data, randomKey)
        val s2 = HmacSigner.sign(data, randomKey)
        assertEquals(s1, s2, "相同输入签名应一致")
    }

    @Test
    fun `isWithinReplayWindow accepts timestamps within 5 minutes`() {
        val now = 1_718_000_000_000L
        assertTrue(HmacSigner.isWithinReplayWindow(now - 60_000, now), "1 分钟内应通过")
        assertTrue(HmacSigner.isWithinReplayWindow(now + 240_000, now), "未来 4 分钟应通过")
        assertTrue(HmacSigner.isWithinReplayWindow(now, now), "相同时间应通过")
    }

    @Test
    fun `isWithinReplayWindow rejects timestamps beyond 5 minutes`() {
        val now = 1_718_000_000_000L
        assertFalse(HmacSigner.isWithinReplayWindow(now - 5 * 60_000L - 1, now), "5 分 1 毫秒前应拒绝")
        assertFalse(HmacSigner.isWithinReplayWindow(now + 5 * 60_000L + 1, now), "未来 5 分 1 毫秒应拒绝")
    }

    @Test
    fun `buildSigningData joins payload id and timestamp with colon`() {
        val data = HmacSigner.buildSigningData("{\"x\":1}", "abc", 123L)
        assertEquals("{\"x\":1}:abc:123", data)
    }
}
