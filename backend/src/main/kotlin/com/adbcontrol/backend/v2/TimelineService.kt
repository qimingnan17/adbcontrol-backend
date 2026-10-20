package com.adbcontrol.backend.v2

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** 业务时区:与调度器一致,环境变量 ADB_SCHEDULER_ZONE 覆盖,缺省 Asia/Shanghai。 */
object BusinessZone {
    val zone: ZoneId
        get() = runCatching { ZoneId.of(System.getenv("ADB_SCHEDULER_ZONE") ?: "Asia/Shanghai") }
            .getOrElse { ZoneId.of("Asia/Shanghai") }

    /** yyyy-MM-dd → [当天 00:00, 次日 00:00) 的 epochMilli;格式非法返回 null。 */
    fun dayRange(date: String, zone: ZoneId = this.zone): Pair<Long, Long>? {
        val d = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { return null }
        val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    fun today(zone: ZoneId = this.zone): LocalDate = LocalDate.now(zone)
}

/**
 * 设备时间线(docs/v2-protocol.md §5)。由已归档的遥测拼出:
 *
 * | type | 数据源 |
 * |---|---|
 * | APP_FOREGROUND | app_activity_log(event=APP_FOREGROUND) |
 * | FENCE_ENTER / FENCE_EXIT | location_history.fence_event |
 * | REMINDER_ACK | task_ack |
 * | EXTENSION_REQUEST | extension_request |
 * | SCREEN_ON / SCREEN_OFF / CHARGING_START / CHARGING_STOP / ONLINE / OFFLINE / TASK_RUN | device_event(v2 新增归档,v2 部署之前的历史没有) |
 */
class TimelineService(private val repo: V2Repository) {

    fun timeline(deviceId: String, from: Long, to: Long): List<TimelineItem> = merge(
        repo.listDeviceEvents(deviceId, from, to),
        repo.listForegroundActivity(deviceId, from, to),
        repo.listFenceEvents(deviceId, from, to).mapNotNull { (ts, raw) -> fenceItem(ts, raw) },
        repo.listReminderAcks(deviceId, from, to),
        repo.listExtensionRequests(null, deviceId, from, to, limit = 1000).map { extensionItem(it) },
    )

    companion object {
        val TYPES = listOf(
            "SCREEN_ON", "SCREEN_OFF", "APP_FOREGROUND", "CHARGING_START", "CHARGING_STOP",
            "FENCE_ENTER", "FENCE_EXIT", "TASK_RUN", "REMINDER_ACK", "EXTENSION_REQUEST", "ONLINE", "OFFLINE",
        )

        /** 多源合并,按时间升序;同一时刻按类型名稳定排序。 */
        fun merge(vararg sources: List<TimelineItem>): List<TimelineItem> =
            sources.flatMap { it }.sortedWith(compareBy<TimelineItem>({ it.ts }, { it.type }))

        /**
         * 被控端 LocationReport.fenceEvent 是自由字符串(如 "ENTER:学校" / "exit 家" / "进入学校")。
         * 识别出进入/离开,其余返回 null(不进时间线)。
         */
        fun classifyFence(raw: String): String? {
            val s = raw.trim().lowercase()
            return when {
                s.startsWith("enter") || s.contains("fence_enter") || s.contains("进入") -> "FENCE_ENTER"
                s.startsWith("exit") || s.startsWith("leave") || s.contains("fence_exit") || s.contains("离开") -> "FENCE_EXIT"
                else -> null
            }
        }

        /** 从 fenceEvent 里取围栏名("ENTER:学校" → "学校");取不到返回原串。 */
        private fun fenceName(raw: String): String =
            raw.substringAfter(':', "").trim().ifEmpty {
                raw.replace(Regex("(?i)^(fence_)?(enter|exit|leave)\\s*"), "").replace("进入", "").replace("离开", "").trim()
            }.ifEmpty { raw }

        fun fenceItem(ts: Long, raw: String): TimelineItem? {
            val type = classifyFence(raw) ?: return null
            val name = fenceName(raw)
            val title = if (type == "FENCE_ENTER") "进入围栏 $name" else "离开围栏 $name"
            return TimelineItem(ts, type, title, raw)
        }

        fun extensionItem(r: ExtensionRequestRow): TimelineItem = TimelineItem(
            ts = r.createdAt,
            type = "EXTENSION_REQUEST",
            title = "申请延时 ${r.appLabel ?: r.packageName} ${r.minutes} 分钟",
            detail = buildString {
                append(r.status)
                r.approvedMinutes?.let { append(" · 批准 ").append(it).append(" 分钟") }
                r.reason?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            },
        )
    }
}
