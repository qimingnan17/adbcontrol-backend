package com.adbcontrol.shared.model

import com.adbcontrol.shared.ProtocolVersion
import kotlinx.serialization.Serializable

/**
 * v2.0 申请延时(使用时长延长)协议。完整契约见 docs/v2-protocol.md §3。
 *
 * 1. 被控端 → 后端:发布到 `request/{deviceId}`(QoS 1),信封 type = `EXTENSION_REQUEST`,
 *    payload = [ExtensionRequestPayload]。
 * 2. 后端 → 被控端:批准时信封 type = `APP_TIME_EXTEND`,payload = [AppTimeExtendPayload];
 *    拒绝时 type = `APP_TIME_EXTEND_DENIED`,payload = [AppTimeExtendDeniedPayload]。
 *    均发到 `cmd/{deviceId}`,签名规则与其它命令一致。
 */
@Serializable
data class ExtensionRequestPayload(
    /** 被控端生成的 UUID,后端以此幂等去重 */
    val requestId: String,
    val packageName: String,
    val appLabel: String = "",
    /** 申请延长的分钟数(>0) */
    val minutes: Int,
    val reason: String? = null,
    /** 申请时刻 epochMilli */
    val ts: Long,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

@Serializable
data class AppTimeExtendPayload(
    val requestId: String,
    val packageName: String,
    /** 实际批准的分钟数(家长可改小/改大) */
    val minutes: Int,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

@Serializable
data class AppTimeExtendDeniedPayload(
    val requestId: String,
    val packageName: String,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

/** 申请状态(extension_request.status)。 */
@Serializable
enum class ExtensionRequestStatus { PENDING, APPROVED, DENIED, EXPIRED }

/**
 * 电子围栏(v2.0,docs/v2-protocol.md §5)。
 * 后端 PUT /api/devices/{id}/geofences 后经 `cmd/{deviceId}` 下发信封
 * type = `GEOFENCE_SET`,payload = [GeofenceSetPayload](全量替换,空列表 = 清空)。
 */
@Serializable
data class Geofence(
    val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
)

@Serializable
data class GeofenceSetPayload(
    val geofences: List<Geofence>,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

/** 命令回报 [ExecutionResult.channel] 的约定取值。 */
object ExecutorChannels {
    const val SHIZUKU = "Shizuku"
    const val ROOT = "Root"
    const val ACCESSIBILITY = "Accessibility"
    const val DEVICE_ADMIN = "DeviceAdmin"
    const val NORMAL = "Normal"
    val ALL = listOf(SHIZUKU, ROOT, ACCESSIBILITY, DEVICE_ADMIN, NORMAL)
}
