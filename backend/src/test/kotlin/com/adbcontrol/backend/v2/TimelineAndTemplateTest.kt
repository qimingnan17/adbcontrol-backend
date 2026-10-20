package com.adbcontrol.backend.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TimelineAndTemplateTest {

    @Test
    fun `fence events are classified`() {
        assertEquals("FENCE_ENTER", TimelineService.classifyFence("ENTER:学校"))
        assertEquals("FENCE_EXIT", TimelineService.classifyFence("exit:家"))
        assertEquals("FENCE_ENTER", TimelineService.classifyFence("进入学校"))
        assertEquals("FENCE_EXIT", TimelineService.classifyFence("离开 家"))
        assertNull(TimelineService.classifyFence("dwell"))
        assertEquals("进入围栏 学校", TimelineService.fenceItem(1L, "ENTER:学校")!!.title)
    }

    @Test
    fun `merge sorts by time`() {
        val a = listOf(TimelineItem(3, "SCREEN_ON", "a"), TimelineItem(1, "ONLINE", "b"))
        val b = listOf(TimelineItem(2, "APP_FOREGROUND", "c"))
        assertEquals(listOf(1L, 2L, 3L), TimelineService.merge(a, b).map { it.ts })
    }

    @Test
    fun `day range follows business zone`() {
        val (from, to) = BusinessZone.dayRange("2026-10-20", ZoneId.of("Asia/Shanghai"))!!
        assertEquals(1792425600000L, from)          // 2026-10-20T00:00+08:00
        assertEquals(24L * 3600 * 1000, to - from)
        assertNull(BusinessZone.dayRange("2026-13-01"))
    }

    private fun dto(payload: String) = PolicyTemplateDto(name = "上学日", payload = Json.parseToJsonElement(payload).jsonObject)

    @Test
    fun `template validation accepts cross midnight windows`() {
        val r = PolicyTemplateService.validate(dto(
            """{"limits":[{"packageName":"com.game","minutes":60}],"windows":[{"packageName":"com.video","startTime":"22:00","endTime":"07:00"}]}"""
        ))
        val ok = assertIs<PolicyTemplateService.Result.Ok<PolicyTemplatePayload>>(r)
        assertEquals("0 0 * * *", ok.value.cronExpr)
        val cmds = PolicyTemplateService.commandsOf(ok.value)
        assertEquals(listOf("app_time_limit", "app_time_window"), cmds.map { it.first })
        assertEquals("22:00", cmds[1].second["startTime"])
    }

    @Test
    fun `template validation rejects bad input`() {
        assertIs<PolicyTemplateService.Result.Invalid>(PolicyTemplateService.validate(dto("""{}""")))
        assertIs<PolicyTemplateService.Result.Invalid>(PolicyTemplateService.validate(dto(
            """{"windows":[{"packageName":"a","startTime":"25:00","endTime":"07:00"}]}""")))
        assertIs<PolicyTemplateService.Result.Invalid>(PolicyTemplateService.validate(dto(
            """{"windows":[{"packageName":"a","startTime":"08:00","endTime":"08:00"}]}""")))
        assertIs<PolicyTemplateService.Result.Invalid>(PolicyTemplateService.validate(dto(
            """{"limits":[{"packageName":"a","minutes":10}],"cronExpr":"bad"}""")))
    }

    @Test
    fun `geofence validation`() {
        assertNull(validateGeofences(listOf(GeofenceDto(name = "家", lat = 31.2, lng = 121.5, radiusMeters = 200.0))))
        assertEquals("第 1 个围栏坐标非法", validateGeofences(listOf(GeofenceDto(name = "x", lat = 91.0, lng = 0.0, radiusMeters = 200.0))))
    }
}
