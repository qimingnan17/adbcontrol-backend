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
    fun `app_time_limit maps to APP_TIME setLimit`() {
        val m = newBridge().mapCommand("app_time_limit", mapOf("packageName" to "com.douyin.android", "minutes" to "60"))
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(m)
        assertEquals(CommandCategory.APP_TIME, mapped.command.category)
        assertEquals("setLimit", mapped.command.action)
        assertEquals("60", mapped.command.params["minutes"])
        assertEquals("com.douyin.android", mapped.command.params["pkg"])
    }

    @Test
    fun `app_time_window maps start_end params`() {
        val m = newBridge().mapCommand(
            "app_time_window",
            mapOf("packageName" to "com.tencent.mm", "startTime" to "22:00", "endTime" to "07:00"),
        )
        val mapped = assertIs<DeviceCommandBridge.MapResult.Mapped>(m)
        assertEquals("setWindow", mapped.command.action)
        assertEquals("22:00", mapped.command.params["start"])
        assertEquals("07:00", mapped.command.params["end"])
    }

    @Test
    fun `notify payload keeps custom button texts and ack flag`() {
        val b = newBridge().buildReminderPayload(
            mapOf(
                "title" to "该睡觉了",
                "text" to "晚安",
                "buttons" to """["收到","立刻睡觉"]""",
                "expectAck" to "true",
            ),
            taskId = 42L,
        )
        val ready = assertIs<DeviceCommandBridge.ReminderBuild.Ready>(b)
        assertEquals("该睡觉了", ready.payload.title)
        assertEquals(listOf("收到", "立刻睡觉"), ready.payload.buttons)
        assertTrue(ready.payload.expectAck)
        assertEquals(42L, ready.payload.taskId)
    }

    @Test
    fun `notify without title rejected as MissingField`() {
        val b = newBridge().buildReminderPayload(mapOf("text" to "only text"))
        val missing = assertIs<DeviceCommandBridge.ReminderBuild.MissingField>(b)
        assertEquals("title", missing.field)
    }

    @Test
    fun `notify buttons are trimmed and capped at 2`() {
        val b = newBridge().buildReminderPayload(
            mapOf("title" to "t", "buttons" to """[" a "," b ","c"]"""),
        )
        val ready = assertIs<DeviceCommandBridge.ReminderBuild.Ready>(b)
        assertEquals(listOf("a", "b"), ready.payload.buttons)
    }

    @Test
    fun `reminder envelope verifies like command envelope`() {
        val bridge = newBridge()
        val sessionKey = sampleSessionKey()
        val ready = assertIs<DeviceCommandBridge.ReminderBuild.Ready>(
            bridge.buildReminderPayload(mapOf("title" to "测试"))
        )
        val env = bridge.buildReminderEnvelope(ready.payload, sessionKey, id = "rem-test1", timestamp = System.currentTimeMillis())
        assertEquals("REMINDER", env.type)
        assertNotNull(env.signature)
        val data = HmacSigner.buildSigningData(env.payload, env.id, env.timestamp)
        assertTrue(HmacSigner.verify(data, sessionKey, env.signature!!))
        assertTrue(HmacSigner.isWithinReplayWindow(env.timestamp))
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
