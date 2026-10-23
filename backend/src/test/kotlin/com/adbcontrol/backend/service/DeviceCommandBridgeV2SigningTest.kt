package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.shared.ProtocolVersion
import com.adbcontrol.shared.model.AppTimeExtendPayload
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * v2 HMAC 签名回归:
 * - 统一出口 signOutgoing 的签名 = HMAC-SHA256(payload:id:timestamp, sessionKey),与被控端一致;
 * - protocolVersion 出现在 wire JSON 但不参与签名(v1 被控端可照常验签);
 * - 离线队列投递时用同一 commandId + 新 timestamp 重签,新旧签名不同但都能验过;
 * - 篡改 payload / id / timestamp 任一项验签失败。
 */
class DeviceCommandBridgeV2SigningTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun newBridge(): DeviceCommandBridge {
        val config = BackendConfig.load()
        val db = DatabaseService(config)
        val emqx = EmqxProxyService(config)
        val pairing = PairingService(config, AclService(config, emqx), db, emqx)
        return DeviceCommandBridge(pairing, emqx)
    }

    private val key: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })

    private fun extendOutgoing(id: String = "ext-0001") = DeviceCommandBridge.Outgoing(
        commandId = id,
        topic = "cmd/dev-1",
        envelopeType = "APP_TIME_EXTEND",
        payloadJson = json.encodeToString(AppTimeExtendPayload.serializer(), AppTimeExtendPayload("req-1", "com.game", 15)),
        auditType = "APP_TIME_EXTEND",
        summary = null,
    )

    @Test
    fun `signOutgoing signature matches shared HmacSigner formula`() {
        val bridge = newBridge()
        val out = extendOutgoing()
        val ts = 1_760_000_000_000L
        val env = bridge.signOutgoing(out, key, ts)
        val expected = HmacSigner.sign(HmacSigner.buildSigningData(out.payloadJson, out.commandId, ts), key)
        assertEquals(expected, env.signature)
        assertEquals("APP_TIME_EXTEND", env.type)
        assertEquals(out.commandId, env.id)
        assertTrue(HmacSigner.verify(HmacSigner.buildSigningData(env.payload, env.id, env.timestamp), key, env.signature!!))
    }

    @Test
    fun `protocolVersion is on the wire but not part of the signature`() {
        val bridge = newBridge()
        val env = bridge.signOutgoing(extendOutgoing(), key, 1_760_000_000_000L)
        val wire = json.encodeToString(DeviceCommandBridge.CommandEnvelope.serializer(), env)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals(ProtocolVersion.CURRENT, obj["protocolVersion"]!!.jsonPrimitive.int)
        // 把版本改成 1(模拟 v1 视角)签名依旧成立:签名串里没有 protocolVersion
        val v1 = env.copy(protocolVersion = 1)
        assertTrue(HmacSigner.verify(HmacSigner.buildSigningData(v1.payload, v1.id, v1.timestamp), key, v1.signature!!))
    }

    @Test
    fun `offline redelivery re-signs with fresh timestamp and same id`() {
        val bridge = newBridge()
        val out = extendOutgoing("web-queued01")
        val queuedAt = System.currentTimeMillis() - 3_600_000L    // 1 小时前入队
        val now = System.currentTimeMillis()
        val old = bridge.signOutgoing(out, key, queuedAt)
        val fresh = bridge.signOutgoing(out, key, now)
        assertEquals(old.id, fresh.id)
        assertNotEquals(old.signature, fresh.signature)
        // 旧签名已超出 5 分钟重放窗口,被控端会丢弃;重签后在窗口内
        assertFalse(HmacSigner.isWithinReplayWindow(old.timestamp, now))
        assertTrue(HmacSigner.isWithinReplayWindow(fresh.timestamp, now))
        assertTrue(HmacSigner.verify(HmacSigner.buildSigningData(fresh.payload, fresh.id, fresh.timestamp), key, fresh.signature!!))
    }

    @Test
    fun `tampering payload id or timestamp breaks the signature`() {
        val bridge = newBridge()
        val env = bridge.signOutgoing(extendOutgoing(), key, 1_760_000_000_000L)
        val sig = env.signature!!
        val tamperedPayload = env.payload.replace("15", "150")
        assertFalse(HmacSigner.verify(HmacSigner.buildSigningData(tamperedPayload, env.id, env.timestamp), key, sig))
        assertFalse(HmacSigner.verify(HmacSigner.buildSigningData(env.payload, "ext-other", env.timestamp), key, sig))
        assertFalse(HmacSigner.verify(HmacSigner.buildSigningData(env.payload, env.id, env.timestamp + 1), key, sig))
    }

    @Test
    fun `legacy builders still produce verifiable envelopes with protocolVersion 2`() {
        val bridge = newBridge()
        val mapped = bridge.mapCommand("launch_app", mapOf("packageName" to "com.a")) as DeviceCommandBridge.MapResult.Mapped
        val env = bridge.buildSignedEnvelope(mapped.command, key, id = "web-x", timestamp = 1L)
        assertEquals(ProtocolVersion.CURRENT, env.protocolVersion)
        assertTrue(HmacSigner.verify(HmacSigner.buildSigningData(env.payload, env.id, env.timestamp), key, env.signature!!))
    }

    @Test
    fun `audit summary truncates long args`() {
        val s = newBridge().summarize("shell", mapOf("command" to "x".repeat(1000)))
        assertTrue(s.startsWith("shell command="))
        assertTrue(s.length <= 400)
    }
}
