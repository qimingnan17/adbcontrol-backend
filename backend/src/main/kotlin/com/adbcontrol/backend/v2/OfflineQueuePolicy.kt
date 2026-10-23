package com.adbcontrol.backend.v2

import java.util.concurrent.ConcurrentHashMap

/**
 * 离线命令队列的过期策略(纯逻辑,便于单元测试)。docs/v2-protocol.md §8。
 *
 * - 默认有效期 24h;
 * - 危险类型(重启 / shell / 卸载)默认 10 分钟 —— 家长几小时前点的"重启"在孩子
 *   下次开机时突然执行,比"没执行"更糟;
 * - 调用方可显式指定 ttl,收敛到 [MIN_TTL_MS, MAX_TTL_MS]。
 */
object OfflineQueuePolicy {
    const val DEFAULT_TTL_MS: Long = 24L * 60 * 60 * 1000
    const val DANGEROUS_TTL_MS: Long = 10L * 60 * 1000
    const val MIN_TTL_MS: Long = 60L * 1000
    const val MAX_TTL_MS: Long = DEFAULT_TTL_MS

    /** Web 命令 type(DeviceCommandBridge.mapCommand 的入参)中视为危险的类型。 */
    val DANGEROUS_TYPES: Set<String> = setOf(
        "shell", "reboot", "reboot_device", "uninstall", "uninstall_app",
    )

    fun isDangerous(type: String): Boolean = type.lowercase() in DANGEROUS_TYPES

    fun ttlFor(type: String, overrideMs: Long? = null): Long =
        overrideMs?.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
            ?: if (isDangerous(type)) DANGEROUS_TTL_MS else DEFAULT_TTL_MS

    fun expiresAt(type: String, now: Long, overrideMs: Long? = null): Long = now + ttlFor(type, overrideMs)

    fun isExpired(row: PendingCommandRow, now: Long): Boolean = now >= row.expiresAt

    /** 把 PENDING 行分成 (可投递, 已过期)。非 PENDING 行忽略。保持入队顺序。 */
    fun partition(rows: List<PendingCommandRow>, now: Long): Pair<List<PendingCommandRow>, List<PendingCommandRow>> {
        val pending = rows.filter { it.status == "PENDING" }
        return pending.partition { !isExpired(it, now) }
    }
}

/**
 * 设备在线状态(进程内)。ingestor 收到 status/health 置在线、收到 LWT 置离线;
 * 命令桥据此决定直发还是入离线队列。进程刚启动时状态未知(null),由调用方回落到 DB。
 */
class DevicePresence {
    private val online = ConcurrentHashMap<String, Boolean>()

    fun get(deviceId: String): Boolean? = online[deviceId]

    /** 设置状态,返回之前的值(null = 本进程首次见到该设备)。 */
    fun set(deviceId: String, isOnline: Boolean): Boolean? = online.put(deviceId, isOnline)

    fun forget(deviceId: String) { online.remove(deviceId) }
}
