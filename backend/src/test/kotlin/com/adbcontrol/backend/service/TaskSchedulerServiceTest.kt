package com.adbcontrol.backend.service

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TaskSchedulerService.shouldFire(伴生对象纯函数)的边界测试。
 * 覆盖"窗口内命中/未来发火跳过/每分钟粒度/非法表达式容错/已过点不补发"。
 */
class TaskSchedulerServiceTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun shouldFire(expr: String, windowStart: ZonedDateTime, now: ZonedDateTime) =
        TaskSchedulerService.shouldFire(expr, windowStart, now)

    @Test
    fun `fires when cron time inside window`() {
        // 每天 10:00 触发,窗口 (09:30, 10:00] → 命中 10:00 这一针
        val now = ZonedDateTime.of(2026, 8, 22, 10, 0, 0, 0, zone)
        val windowStart = now.minusMinutes(1)
        val fireAt = shouldFire("0 10 * * *", windowStart, now)
        assertNotNull(fireAt)
        assertEquals(now.toInstant().toEpochMilli(), fireAt)
    }

    @Test
    fun `does not fire when next fire is in the future`() {
        val now = ZonedDateTime.of(2026, 8, 22, 9, 59, 59, 0, zone)
        val windowStart = now.minusMinutes(1)
        // 下一次发火是 10:00,尚在未来
        val fireAt = shouldFire("0 10 * * *", windowStart, now)
        assertNull(fireAt)
    }

    @Test
    fun `every minute cron fires on the most recent minute boundary`() {
        val now = ZonedDateTime.of(2026, 8, 22, 10, 5, 30, 0, zone)
        val windowStart = now.minusMinutes(1)
        val fireAt = shouldFire("* * * * *", windowStart, now)
        assertNotNull(fireAt)
        // 应当是 10:05:00(最近的整分),换算回本地时间校验
        val fireZoned = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(fireAt!!), zone)
        assertEquals(10, fireZoned.hour)
        assertEquals(5, fireZoned.minute)
        assertEquals(0, fireZoned.second)
    }

    @Test
    fun `invalid cron expression returns null instead of throwing`() {
        val now = ZonedDateTime.now(zone)
        assertNull(shouldFire("not-a-cron", now.minusMinutes(1), now))
    }

    @Test
    fun `old fire outside window (backwards) does not fire again`() {
        // 10:00 的 cron,窗口从 10:01 开始 → 已错过,不再补发
        val now = ZonedDateTime.of(2026, 8, 22, 10, 1, 0, 0, zone)
        val windowStart = ZonedDateTime.of(2026, 8, 22, 10, 1, 0, 0, zone)
        val fireAt = shouldFire("0 10 * * *", windowStart, now)
        // 10:01 之后的下一次是明天 10:00,不在窗口(<=now)内
        assertNull(fireAt)
    }
}
