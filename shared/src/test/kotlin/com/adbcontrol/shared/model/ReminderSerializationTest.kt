package com.adbcontrol.shared.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ReminderPayload / ReminderAck 序列化对拍测试。
 * 受控端 MessageCodec 与后端 DeviceCommandBridge 必须对同一字段名/类型编解码,
 * 防止两端任一字段漂移导致任务栏通知按钮或签收回报静默失效。
 */
class ReminderSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `payload with custom buttons round-trips`() {
        val p = ReminderPayload(
            title = "该写作业了",
            text = "请完成作业",
            buttons = listOf("收到", "现在就写"),
            expectAck = true,
            taskId = 7L,
        )
        val out = json.encodeToString(ReminderPayload.serializer(), p)
        val back = json.decodeFromString(ReminderPayload.serializer(), out)
        assertEquals(p, back)
        // 按钮数组必须以 JSON 数组形式存在,不会被压成字符串
        assertTrue(out.contains("\"buttons\":[\"收到\",\"现在就写\"]"))
        assertTrue(out.contains("\"taskId\":7"))
    }

    @Test
    fun `ack round-trips and open-token survives`() {
        val ack = ReminderAck(refId = "rem-abc123", taskId = 42L, buttonText = ReminderAck.OPEN_TOKEN)
        val back = json.decodeFromString(ReminderAck.serializer(), json.encodeToString(ReminderAck.serializer(), ack))
        assertEquals(ack, back)
        assertEquals("(open)", back.buttonText)
    }

    @Test
    fun `payload defaults are backward compatible (no buttons, no ack)`() {
        val back = json.decodeFromString(
            ReminderPayload.serializer(),
            """{"title":"t"}"""
        )
        assertEquals("t", back.title)
        assertEquals(emptyList(), back.buttons)
        assertFalse(back.expectAck)
    }
}
