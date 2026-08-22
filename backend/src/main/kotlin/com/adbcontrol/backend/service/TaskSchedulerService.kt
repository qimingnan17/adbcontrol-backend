package com.adbcontrol.backend.service

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后端 cron 调度器:补位"定时任务只存不跑"的缺口。
 *
 * 每 [POLL_MS] 轮询一次 task 表的启用任务:
 * - 用 cron-utils(UNIX 5 字段)判断自上次轮询窗口内是否应当发火;
 * - type=notify 的任务经 [DeviceCommandBridge.dispatchReminder] 走 reminder/{deviceId};
 *   其余类型经 [DeviceCommandBridge.dispatch] 走 cmd/{deviceId};
 * - command_json 内 deviceId 为空(广播任务)逐台下发到当前数据库登记设备;
 *   taskId 透传进 ReminderPayload,签收回报入库后可在任务详情归集。
 *
 * 幂等:进程内 lastFire 记录每任务每个发火时刻只发一次;重启后在同一轮询窗口内
 * 可能补发一次(最多多一条通知),与 README"接受偶发重复、QoS 1 下游幂等"一致。
 */
class TaskSchedulerService(
    private val db: DatabaseService,
    private val bridge: DeviceCommandBridge,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "task-scheduler").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** taskId -> 最近一次发火的目标 timestamp(去重) */
    @Volatile private var lastFire: Map<Int, Long> = emptyMap()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { logger.error("tick failed", it) } },
            INITIAL_DELAY_MS, POLL_MS, TimeUnit.MILLISECONDS)
        logger.info("task scheduler started, poll={}ms", POLL_MS)
    }

    override fun close() {
        running.set(false)
        runCatching { executor.shutdownNow() }
        scope.cancel()
    }

    /** 单个轮询周期:扫描启用任务,命中窗口的逐台下发。 */
    private fun tick() {
        val now = System.currentTimeMillis()
        val nowZoned = ZonedDateTime.now()
        // 窗口取轮询周期 ~2 倍,容忍一轮超时丢 tick
        val windowStart = nowZoned.minusNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS * 2))
        val tasks = db.listTasks()
        for (task in tasks) {
            val enabled = task["enabled"] as? Boolean ?: false
            if (!enabled) continue
            val expr = (task["cronExpr"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val taskId = (task["id"] as? Number)?.toInt() ?: continue

            val fireAt = shouldFire(expr, windowStart, nowZoned)
            if (fireAt == null || lastFire[taskId] == fireAt) continue

            val commandJson = (task["commandJson"] as? String) ?: continue
            val parsed = runCatching { json.parseToJsonElement(commandJson) as? JsonObject }.getOrNull() ?: continue
            val type = parsed["type"]?.jsonPrimitive?.content ?: continue
            val args = (parsed["args"] as? JsonObject)
                ?.entries?.mapNotNull { (k, v) -> runCatching { k to v.jsonPrimitive.content }.getOrNull() }
                ?.toMap() ?: emptyMap()

            val deviceId = (task["deviceId"] as? String)?.takeIf { it.isNotBlank() }
            val targets = if (deviceId != null) listOf(deviceId)
            else db.listDevicesWithStatus().map { it.deviceId }
            if (targets.isEmpty()) continue

            // 先用 fireAt 占位再放发,避免长下发期间下次 tick 重发同一时刻
            lastFire = lastFire + (taskId to fireAt)
            scope.launch {
                for (devId in targets) {
                    val r = if (type == "notify") {
                        bridge.dispatchReminder(devId, args, taskId = taskId.toLong())
                    } else {
                        bridge.dispatch(devId, type, args)
                    }
                    when (r) {
                        is DeviceCommandBridge.DispatchResult.Ok ->
                            logger.info("task {} fired -> {} ({})", taskId, devId, type)
                        else -> logger.warn("task {} fire failed -> {}: {}", taskId, devId, r)
                    }
                }
            }
        }
    }

    /**
     * 判断 expr 在 (windowStart, now) 内是否应当发火;返回发火时刻 epochMilli,否则 null。
     * 用 ExecutionTime.nextExecution(windowStart) 求窗口内首个发火点,若 ≤ now 即命中。
     */
    companion object {
        private const val POLL_MS: Long = 30_000
        private const val INITIAL_DELAY_MS: Long = 10_000
        private val CRON_PARSER = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

        /**
         * 判断 expr 在 (windowStart, now] 内是否应当发火;返回发火时刻 epochMilli,否则 null。
         * 用 ExecutionTime.nextExecution(windowStart) 求窗口内首个发火点,≤ now 即命中。
         * 纯函数(伴生对象级),供 TaskSchedulerServiceTest 直接驱动。
         */
        internal fun shouldFire(expr: String, windowStart: ZonedDateTime, now: ZonedDateTime): Long? {
            val next = runCatching {
                ExecutionTime.forCron(CRON_PARSER.parse(expr)).nextExecution(windowStart).orElse(null)
            }.getOrNull() ?: return null
            return if (!next.isAfter(now)) next.toInstant().toEpochMilli() else null
        }
    }
}
