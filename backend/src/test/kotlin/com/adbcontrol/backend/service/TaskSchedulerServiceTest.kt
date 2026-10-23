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

    // ---------------- v2:跨零点 cron 窗口 / 时间窗口 ----------------

    @Test
    fun `midnight cron fires when poll window spans midnight`() {
        // 轮询窗口 (23:59:30, 00:00:10] 跨日,每天 00:00 的任务必须命中次日 00:00
        val windowStart = ZonedDateTime.of(2026, 10, 20, 23, 59, 30, 0, zone)
        val now = ZonedDateTime.of(2026, 10, 21, 0, 0, 10, 0, zone)
        val fireAt = shouldFire("0 0 * * *", windowStart, now)
        assertNotNull(fireAt)
        assertEquals(ZonedDateTime.of(2026, 10, 21, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), fireAt)
    }

    @Test
    fun `day of week cron does not fire on other days`() {
        // 2026-10-20 是周二;"0 8 * * 1"(周一 08:00)不应在周二触发
        val now = ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0, zone)
        assertNull(shouldFire("0 8 * * 1", now.minusMinutes(1), now))
        assertNotNull(shouldFire("0 8 * * 2", now.minusMinutes(1), now))
    }

    @Test
    fun `cron validation`() {
        kotlin.test.assertTrue(TaskSchedulerService.isValidCron("*/5 * * * *"))
        kotlin.test.assertFalse(TaskSchedulerService.isValidCron("0 0 * *"))
    }

    @Test
    fun `HH mm parsing`() {
        assertEquals(java.time.LocalTime.of(7, 5), TaskSchedulerService.parseHhMm("07:05"))
        assertEquals(java.time.LocalTime.of(7, 5), TaskSchedulerService.parseHhMm("7:05"))
        assertNull(TaskSchedulerService.parseHhMm("24:00"))
        assertNull(TaskSchedulerService.parseHhMm("12:60"))
        assertNull(TaskSchedulerService.parseHhMm(null))
    }

    @Test
    fun `same day window is half open`() {
        val s = java.time.LocalTime.of(8, 0)
        val e = java.time.LocalTime.of(17, 0)
        kotlin.test.assertTrue(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(8, 0)))
        kotlin.test.assertTrue(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(16, 59)))
        kotlin.test.assertFalse(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(17, 0)))
        kotlin.test.assertFalse(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(7, 59)))
    }

    @Test
    fun `cross midnight window covers late night and early morning`() {
        val s = java.time.LocalTime.of(22, 0)
        val e = java.time.LocalTime.of(7, 0)
        kotlin.test.assertTrue(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(23, 30)))
        kotlin.test.assertTrue(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.MIDNIGHT))
        kotlin.test.assertTrue(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(6, 59)))
        kotlin.test.assertFalse(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(7, 0)))
        kotlin.test.assertFalse(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(12, 0)))
        kotlin.test.assertFalse(TaskSchedulerService.isWithinWindow(s, e, java.time.LocalTime.of(21, 59)))
    }

    @Test
    fun `window length handles midnight and empty windows`() {
        assertEquals(540, TaskSchedulerService.windowLengthMinutes(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(7, 0)))
        assertEquals(60, TaskSchedulerService.windowLengthMinutes(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(9, 0)))
        assertEquals(0, TaskSchedulerService.windowLengthMinutes(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 0)))
        kotlin.test.assertFalse(TaskSchedulerService.isWithinWindow(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 0)))
    }
}
