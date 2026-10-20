package com.adbcontrol.backend.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineQueuePolicyTest {

    private fun row(id: String, type: String, createdAt: Long, expiresAt: Long, status: String = "PENDING") =
        PendingCommandRow(
            commandId = id, deviceId = "d", commandType = type, topic = "cmd/d", envelopeType = "COMMAND",
            payloadJson = "{}", status = status, createdAt = createdAt, expiresAt = expiresAt,
        )

    @Test
    fun `default expiry is 24h`() {
        assertEquals(24L * 3600 * 1000, OfflineQueuePolicy.ttlFor("launch_app"))
        assertEquals(1000L + 24L * 3600 * 1000, OfflineQueuePolicy.expiresAt("notify", 1000L))
    }

    @Test
    fun `dangerous types default to 10 minutes`() {
        for (t in listOf("shell", "reboot", "uninstall", "SHELL")) {
            assertTrue(OfflineQueuePolicy.isDangerous(t), t)
            assertEquals(10L * 60 * 1000, OfflineQueuePolicy.ttlFor(t), t)
        }
        assertFalse(OfflineQueuePolicy.isDangerous("app_time_limit"))
    }

    @Test
    fun `explicit ttl override is clamped`() {
        assertEquals(OfflineQueuePolicy.MIN_TTL_MS, OfflineQueuePolicy.ttlFor("shell", 1L))
        assertEquals(OfflineQueuePolicy.MAX_TTL_MS, OfflineQueuePolicy.ttlFor("notify", 7L * 24 * 3600 * 1000))
        assertEquals(30L * 60 * 1000, OfflineQueuePolicy.ttlFor("shell", 30L * 60 * 1000))
    }

    @Test
    fun `expired commands are never delivered`() {
        val queuedAt = 0L
        val shell = row("a", "shell", queuedAt, OfflineQueuePolicy.expiresAt("shell", queuedAt))
        val notify = row("b", "notify", queuedAt, OfflineQueuePolicy.expiresAt("notify", queuedAt))
        // 设备 11 分钟后上线:shell 已过期,notify 仍可投
        val now = 11L * 60 * 1000
        val (deliver, expired) = OfflineQueuePolicy.partition(listOf(shell, notify), now)
        assertEquals(listOf("b"), deliver.map { it.commandId })
        assertEquals(listOf("a"), expired.map { it.commandId })
    }

    @Test
    fun `expiry boundary is inclusive`() {
        val r = row("a", "notify", 0L, 1000L)
        assertFalse(OfflineQueuePolicy.isExpired(r, 999L))
        assertTrue(OfflineQueuePolicy.isExpired(r, 1000L))
    }

    @Test
    fun `non pending rows are ignored and order is kept`() {
        val rows = listOf(
            row("1", "notify", 0, 10_000),
            row("2", "notify", 0, 10_000, status = "SENT"),
            row("3", "notify", 0, 10_000),
            row("4", "notify", 0, 10_000, status = "EXPIRED"),
        )
        val (deliver, expired) = OfflineQueuePolicy.partition(rows, 5_000)
        assertEquals(listOf("1", "3"), deliver.map { it.commandId })
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `presence returns previous state`() {
        val p = DevicePresence()
        assertEquals(null, p.set("d", true))
        assertEquals(true, p.set("d", false))
        assertEquals(false, p.get("d"))
    }
}
