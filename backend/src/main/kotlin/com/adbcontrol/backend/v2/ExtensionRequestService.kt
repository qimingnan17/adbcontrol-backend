package com.adbcontrol.backend.v2

import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.RealtimeEventService
import com.adbcontrol.shared.model.ExtensionRequestPayload
import com.adbcontrol.shared.model.ExtensionRequestStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 申请延时(docs/v2-protocol.md §3)。
 *
 * - [ingest]:ingestor 收到 request/{deviceId} EXTENSION_REQUEST 后入库并推 SSE;
 * - [approve] / [deny]:管理员审批,下发 APP_TIME_EXTEND / APP_TIME_EXTEND_DENIED;
 * - 维护定时器(每分钟):PENDING 超 30 分钟 → EXPIRED(推 SSE),顺带过期离线命令队列。
 */
class ExtensionRequestService(
    private val repo: V2Repository,
    private val bridge: DeviceCommandBridge,
    private val realtime: RealtimeEventService?,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "v2-maintenance").apply { isDaemon = true }
    }

    sealed class Decision {
        data class Done(val row: ExtensionRequestRow, val dispatch: DeviceCommandBridge.DispatchResult) : Decision()
        data object NotFound : Decision()
        data class NotPending(val status: String) : Decision()
        data class Invalid(val message: String) : Decision()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({
            runCatching { maintenanceTick() }.onFailure { logger.warn("v2 maintenance tick failed: {}", it.message) }
        }, 30, 60, TimeUnit.SECONDS)
    }

    override fun close() {
        running.set(false)
        runCatching { executor.shutdownNow() }
    }

    /** 被控端申请入库。返回入库后的行;重复 requestId(QoS 1 重发)返回 null。 */
    fun ingest(deviceId: String, p: ExtensionRequestPayload): ExtensionRequestRow? {
        if (p.requestId.isBlank() || p.packageName.isBlank() || p.minutes <= 0) {
            logger.warn("drop invalid EXTENSION_REQUEST from {}: requestId={} pkg={} minutes={}", deviceId, p.requestId, p.packageName, p.minutes)
            return null
        }
        val now = clock()
        // 设备时钟可能漂移:created_at 用设备 ts,但不允许落在未来,否则永远不会过期
        val createdAt = p.ts.takeIf { it in 1..now } ?: now
        val row = ExtensionRequestRow(
            requestId = p.requestId.take(64),
            deviceId = deviceId,
            packageName = p.packageName.take(128),
            appLabel = p.appLabel.takeIf { it.isNotBlank() }?.take(128),
            minutes = p.minutes.coerceAtMost(MAX_MINUTES),
            reason = p.reason?.take(512),
            status = ExtensionRequestStatus.PENDING.name,
            createdAt = createdAt,
        )
        if (!repo.insertExtensionRequest(row)) return null
        val stored = repo.getExtensionRequest(row.requestId) ?: row
        realtime?.emit(RealtimeEventService.Event.ExtensionRequestEvent(deviceId, stored))
        logger.info("extension request {} from {} pkg={} minutes={}", row.requestId, deviceId, row.packageName, row.minutes)
        return stored
    }

    suspend fun approve(requestId: String, minutes: Int?, decidedBy: String): Decision {
        if (minutes != null && (minutes <= 0 || minutes > MAX_MINUTES)) return Decision.Invalid("minutes 必须在 1..$MAX_MINUTES")
        val row = repo.getExtensionRequest(requestId) ?: return Decision.NotFound
        if (row.status != ExtensionRequestStatus.PENDING.name) return Decision.NotPending(row.status)
        if (isStale(row)) {
            expireOne(row)
            return Decision.NotPending(ExtensionRequestStatus.EXPIRED.name)
        }
        val granted = minutes ?: row.minutes
        val now = clock()
        if (!repo.decideExtensionRequest(requestId, ExtensionRequestStatus.APPROVED.name, decidedBy, granted, now)) {
            return Decision.NotPending(repo.getExtensionRequest(requestId)?.status ?: "UNKNOWN")
        }
        val r = bridge.sendAppTimeExtend(row.deviceId, requestId, row.packageName, granted, decidedBy)
        val updated = row.copy(status = ExtensionRequestStatus.APPROVED.name, decidedAt = now, decidedBy = decidedBy, approvedMinutes = granted)
        realtime?.emit(RealtimeEventService.Event.ExtensionRequestEvent(row.deviceId, updated))
        return Decision.Done(updated, r)
    }

    suspend fun deny(requestId: String, decidedBy: String): Decision {
        val row = repo.getExtensionRequest(requestId) ?: return Decision.NotFound
        if (row.status != ExtensionRequestStatus.PENDING.name) return Decision.NotPending(row.status)
        val now = clock()
        if (!repo.decideExtensionRequest(requestId, ExtensionRequestStatus.DENIED.name, decidedBy, null, now)) {
            return Decision.NotPending(repo.getExtensionRequest(requestId)?.status ?: "UNKNOWN")
        }
        val r = bridge.sendAppTimeExtendDenied(row.deviceId, requestId, row.packageName, decidedBy)
        val updated = row.copy(status = ExtensionRequestStatus.DENIED.name, decidedAt = now, decidedBy = decidedBy)
        realtime?.emit(RealtimeEventService.Event.ExtensionRequestEvent(row.deviceId, updated))
        return Decision.Done(updated, r)
    }

    fun list(status: String?, deviceId: String?): List<ExtensionRequestRow> {
        // 查询前先把超时的过期掉,列表里不会出现"已超时但仍 PENDING"的行
        expireStale()
        return repo.listExtensionRequests(status, deviceId, null, null)
    }

    fun isStale(row: ExtensionRequestRow): Boolean = clock() - row.createdAt >= EXPIRY_MS

    private fun expireOne(row: ExtensionRequestRow) {
        val now = clock()
        if (repo.decideExtensionRequest(row.requestId, ExtensionRequestStatus.EXPIRED.name, "system", null, now)) {
            realtime?.emit(RealtimeEventService.Event.ExtensionRequestEvent(
                row.deviceId, row.copy(status = ExtensionRequestStatus.EXPIRED.name, decidedAt = now, decidedBy = "system"),
            ))
        }
    }

    fun expireStale(): Int {
        val now = clock()
        val expired = repo.expireExtensionRequests(now - EXPIRY_MS, now)
        expired.forEach { realtime?.emit(RealtimeEventService.Event.ExtensionRequestEvent(it.deviceId, it)) }
        return expired.size
    }

    private fun maintenanceTick() {
        val e = expireStale()
        val q = bridge.expireStalePending(clock())
        if (e > 0 || q > 0) logger.info("v2 maintenance: expired {} extension request(s), {} queued command(s)", e, q)
    }

    companion object {
        /** PENDING 申请 30 分钟未审批自动过期。 */
        const val EXPIRY_MS: Long = 30L * 60 * 1000
        /** 单次申请/批准分钟数上限(防误操作 99999)。 */
        const val MAX_MINUTES: Int = 24 * 60
    }
}
