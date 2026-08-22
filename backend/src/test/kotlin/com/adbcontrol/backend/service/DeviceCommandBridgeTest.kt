package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.Base64

/**
 * 验证 DeviceCommandBridge 生成的命令信封能通过被控端 MessageCodec.decode 的全套校验:
 * - type 是 "COMMAND"
 * - payload 可反序列化为 shared Command,并映射自 Web UI 的 type/args
 * - signature = HMAC-SHA256(payload:id:timestamp, sessionKey) 验签通过
 * - timestamp 落在 5 分钟重放窗口内
 */
class DeviceCommandBridgeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun newBridge(): DeviceCommandBridge {
        val config = BackendConfig.load()
        val db = DatabaseService(config)      // 缺 DB 连接时 best-effort,不会真正连库
        val emqx = EmqxProxyService(config)
        val pairing = PairingService(config, AclService(config), db, emqx)
        return DeviceCommandBridge(pairing, emqx)
    }

    private fun sampleSessionKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `shell maps to FILE shell with whitelist-safe cmd param`() {
        val m = newBridge().mapCommand("shell", mapOf("command" to "getprop ro.build.version.release"))
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(m)
        assertEquals(CommandCategory.FILE, mapped.command.category)
        assertEquals("shell", mapped.command.action)
        assertEquals("getprop ro.build.version.release", mapped.command.params["cmd"])
    }

    @Test
    fun `launch_app maps to APP start with pkg param`() {
        val m = newBridge().mapCommand("launch_app", mapOf("packageName" to "com.android.settings"))
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(m)
        assertEquals(CommandCategory.APP, mapped.command.category)
        assertEquals("start", mapped.command.action)
        assertEquals("com.android.settings", mapped.command.params["pkg"])
    }

    @Test
    fun `stop_app maps to APP forceStop with pkg param`() {
        val m = newBridge().mapCommand("stop_app", mapOf("packageName" to "com.example.bad"))
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(m)
        assertEquals(CommandCategory.APP, mapped.command.category)
        assertEquals("forceStop", mapped.command.action)
    }

    @Test
    fun `unknown type rejected`() {
        val m = newBridge().mapCommand("reboot_now", emptyMap())
        assertIs<DeviceCommandBridge.MapResult.Unsupported>(m)
    }

    @Test
    fun `shell without command arg rejected as MissingField`() {
        val m = newBridge().mapCommand("shell", emptyMap())
        val missing = assertIs<DeviceCommandBridge.MapResult.MissingField>(m)
        assertEquals("command", missing.field)
    }

    @Test
    fun `signed envelope passes controlled MessageCodec verification rules`() {
        val bridge = newBridge()
        val sessionKey = sampleSessionKey()
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(
            bridge.mapCommand("launch_app", mapOf("packageName" to "com.android.settings"))
        )
        val now = System.currentTimeMillis()
        val env = bridge.buildSignedEnvelope(mapped.command, sessionKey, id = "web-test123", timestamp = now)

        // 1) wire 字段
        assertEquals("COMMAND", env.type)
        assertEquals("web-test123", env.id)
        assertNotNull(env.signature)

        // 2) 签名与被控端完全同一条公式
        val signingData = HmacSigner.buildSigningData(env.payload, env.id, env.timestamp)
        assertTrue(HmacSigner.verify(signingData, sessionKey, env.signature!!))

        // 3) 重放窗口
        assertTrue(HmacSigner.isWithinReplayWindow(env.timestamp))

        // 4) payload 还原
        val decoded = json.decodeFromString(Command.serializer(), env.payload)
        assertEquals(CommandCategory.APP, decoded.category)
        assertEquals("start", decoded.action)
        assertEquals("com.android.settings", decoded.params["pkg"])
    }

    @Test
    fun `tampered signature must be rejected`() {
        val bridge = newBridge()
        val sessionKey = sampleSessionKey()
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(
            bridge.mapCommand("stop_app", mapOf("packageName" to "com.x"))
        )
        val env = bridge.buildSignedEnvelope(mapped.command, sessionKey)

        // 换一个 sessionKey 验签必须失败
        val wrongKey = Base64.getEncoder().encodeToString(ByteArray(32) { (200 - it).toByte() })
        val signingData = HmacSigner.buildSigningData(env.payload, env.id, env.timestamp)
        assertTrue(!HmacSigner.verify(signingData, wrongKey, env.signature!!))
    }
}
