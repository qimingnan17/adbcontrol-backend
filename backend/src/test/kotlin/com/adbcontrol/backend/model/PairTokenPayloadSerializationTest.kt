package com.adbcontrol.backend.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PairTokenPayload JSON 序列化/反序列化测试(README 第八章 8.3 QR 配对)。
 *
 * 验证目标:
 * - QR 内的载荷只含 pairToken / serverUrl / deviceId / deviceName?,不含长期凭证
 * - 反序列化兼容省略 deviceName 时为 null
 * - 与 backend Application 中 Json { ignoreUnknownKeys=true, encodeDefaults=true, explicitNulls=false } 配置兼容
 */
class PairTokenPayloadSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val original = PairTokenPayload(
            pairToken = "pt_demo_001",
            serverUrl = "https://api.adbcontrol.example.com",
            deviceId = "device-a001",
            deviceName = "测试设备",
        )
        val encoded = json.encodeToString(PairTokenPayload.serializer(), original)
        val decoded = json.decodeFromString(PairTokenPayload.serializer(), encoded)

        assertEquals(original, decoded, "roundtrip 应保持对象相等")
        assertEquals("pt_demo_001", decoded.pairToken)
        assertEquals("https://api.adbcontrol.example.com", decoded.serverUrl)
        assertEquals("device-a001", decoded.deviceId)
        assertEquals("测试设备", decoded.deviceName)
    }

    @Test
    fun `decoded payload without deviceName yields null`() {
        val raw = """{"pairToken":"pt_x","serverUrl":"https://srv","deviceId":"d1"}"""
        val decoded = json.decodeFromString(PairTokenPayload.serializer(), raw)
        assertEquals("pt_x", decoded.pairToken)
        assertEquals("d1", decoded.deviceId)
        assertNull(decoded.deviceName, "省略 deviceName 字段时应为 null")
    }

    @Test
    fun `payload decoded from QR-style json matches expected fields`() {
        val qrStyle = """
            {
              "pairToken": "pt_demo_001",
              "serverUrl": "http://localhost:8080",
              "deviceId": "device-a001",
              "deviceName": "测试设备"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(PairTokenPayload.serializer(), qrStyle)

        assertEquals("pt_demo_001", decoded.pairToken)
        assertEquals("http://localhost:8080", decoded.serverUrl)
        assertEquals("device-a001", decoded.deviceId)
        assertEquals("测试设备", decoded.deviceName)
    }

    @Test
    fun `payload does not contain long-term credentials`() {
        val original = PairTokenPayload(
            pairToken = "pt_demo_001",
            serverUrl = "http://localhost:8080",
            deviceId = "device-a001",
            deviceName = null,
        )
        val encoded = json.encodeToString(PairTokenPayload.serializer(), original)
        // 安全要点:QR 载荷绝不应包含长期凭证字段
        assertTrue(!encoded.contains("sessionKey", ignoreCase = true), "QR 载荷不应包含 sessionKey")
        assertTrue(!encoded.contains("password", ignoreCase = true), "QR 载荷不应包含 password")
        assertTrue(!encoded.contains("accessSecret", ignoreCase = true), "QR 载荷不应包含 R2 accessSecret")
    }

    @Test
    fun `two payloads with same token but different device differ`() {
        val a = PairTokenPayload("pt", "http://x", "d1", null)
        val b = PairTokenPayload("pt", "http://x", "d2", null)
        assertNotEquals(a, b, "不同 deviceId 的载荷应不相等")
    }

    @Test
    fun `encoded payload with null deviceName omits the field`() {
        val original = PairTokenPayload("pt", "http://x", "d1", null)
        val encoded = json.encodeToString(PairTokenPayload.serializer(), original)
        // explicitNulls=false 应让 null 字段不出现在 JSON 中
        assertTrue(!encoded.contains("deviceName"), "explicitNulls=false 下 null 字段应省略")
    }
}
