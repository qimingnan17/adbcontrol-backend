package com.adbcontrol.shared

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.cronutils.model.time.ExecutionTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * cron 表达式解析测试(README 第六章调度系统)。
 *
 * shared 模块声明 cron-utils 依赖但未直接使用它(controller 模块才是主要消费者);
 * 这里覆盖主控端会用到的典型 UNIX 5 字段表达式,
 * 验证 22:00 每日触发 / 整点错峰 offset / 不合法表达式拒绝。
 */
class CronUtilsParseTest {

    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    @Test
    fun `parses daily 22_00 cron and yields a future next execution`() {
        val cron = parser.parse("0 22 * * *")
        val exec = ExecutionTime.forCron(cron)
        val now = ZonedDateTime.now()
        val next = exec.nextExecution(now)
        assertNotNull(next.orElse(null), "0 22 * * * 应返回下一次发火时刻")
        assertTrue(next.get().isAfter(now), "下次发火应在未来")
    }

    @Test
    fun `parses every minute cron for usage staggering`() {
        // README 10.2.3 USAGE 上报错峰:hash(deviceId) % 60 = 偏移分钟
        // 偏移分钟可以为 0..59,正则化为 "N * * * *"
        for (minute in 0..59) {
            val expr = "$minute * * * *"
            val cron = parser.parse(expr)
            val next = ExecutionTime.forCron(cron).nextExecution(ZonedDateTime.now())
            assertNotNull(next.orElse(null), "表达式 $expr 应能解析并返回下次时刻")
        }
    }

    @Test
    fun `invalid cron expression should throw`() {
        // 6 字段非 UNIX 表达式应抛 IllegalArgumentException
        var threw = false
        try {
            parser.parse("0 22 * * * 7")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "非法 UNIX cron 表达式应抛 IllegalArgumentException")
    }
}
