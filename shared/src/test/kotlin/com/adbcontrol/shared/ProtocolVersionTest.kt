package com.adbcontrol.shared

import com.adbcontrol.shared.model.ExecutionResult
import com.adbcontrol.shared.model.ExtensionRequestPayload
import com.adbcontrol.shared.model.UpdateCheckResponse
import com.adbcontrol.shared.model.UsageReport
import com.adbcontrol.shared.net.IncomingCategory
import com.adbcontrol.shared.net.MqttTopics
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolVersionTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `missing protocolVersion is treated as legacy v1`() {
        assertEquals(1, ProtocolVersion.of("""{"id":"a","type":"COMMAND","payload":"{}","timestamp":1}"""))
        assertEquals(2, ProtocolVersion.of("""{"id":"a","protocolVersion":2}"""))
        assertTrue(ProtocolVersion.isLegacy(1))
        assertFalse(ProtocolVersion.isLegacy(2))
    }

    @Test
    fun `unparseable json yields null so receiver can reject`() {
        assertNull(ProtocolVersion.of("not json"))
        assertNull(ProtocolVersion.of("[1,2]"))
    }

    @Test
    fun `envelope encodes protocolVersion 2 by default`() {
        val out = json.encodeToString(MqttEnvelope.serializer(), MqttEnvelope("i", "COMMAND", "{}", 1L))
        assertTrue(out.contains("\"protocolVersion\":2"))
    }

    @Test
    fun `v1 execution result decodes with null channel`() {
        val r = json.decodeFromString(ExecutionResult.serializer(), """{"commandId":"c","success":true}""")
        assertNull(r.channel)
        val v2 = json.decodeFromString(ExecutionResult.serializer(), """{"commandId":"c","success":true,"channel":"Shizuku"}""")
        assertEquals("Shizuku", v2.channel)
    }

    @Test
    fun `usage report partial defaults to false`() {
        val r = json.decodeFromString(UsageReport.serializer(),
            """{"deviceId":"d","userId":"0","date":"2026-10-20","items":[],"timestamp":1}""")
        assertFalse(r.partial)
    }

    @Test
    fun `update check response sha256 is nullable`() {
        val r = json.decodeFromString(UpdateCheckResponse.serializer(), """{"hasUpdate":false}""")
        assertNull(r.sha256)
    }

    @Test
    fun `extension request round trips`() {
        val p = ExtensionRequestPayload("u-1", "com.a", "A", 15, null, 100L)
        assertEquals(p, json.decodeFromString(ExtensionRequestPayload.serializer(), json.encodeToString(ExtensionRequestPayload.serializer(), p)))
    }

    @Test
    fun `request topic is classified`() {
        assertEquals("request/dev1", MqttTopics.request("dev1"))
        assertEquals(IncomingCategory.REQUEST, MqttTopics.classify("request/dev1"))
        assertEquals(MqttTopics.subscriptions.size, MqttTopics.subscriptionQos.size)
    }
}
