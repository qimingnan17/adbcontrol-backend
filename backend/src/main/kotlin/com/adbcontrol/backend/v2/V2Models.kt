package com.adbcontrol.backend.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * v2.0 REST 模型(字段名即对外 JSON 契约,见 docs/v2-protocol.md)。
 */

@Serializable
data class MetaResponse(val protocolVersion: Int, val serverVersion: String)

@Serializable
data class ExtensionRequestRow(
    val id: Long = 0,
    val requestId: String,
    val deviceId: String,
    val packageName: String,
    val appLabel: String? = null,
    val minutes: Int,
    val reason: String? = null,
    val status: String,
    val createdAt: Long,
    val decidedAt: Long? = null,
    val decidedBy: String? = null,
    /** 批准时实际下发的分钟数(契约外的附加字段,DENIED/PENDING 为 null) */
    val approvedMinutes: Int? = null,
)

@Serializable
data class ApproveBody(val minutes: Int? = null)

@Serializable
data class TimelineItem(val ts: Long, val type: String, val title: String, val detail: String? = null)

@Serializable
data class LocationPoint(val ts: Long, val lat: Double, val lng: Double, val accuracy: Float? = null, val speed: Float? = null)

@Serializable
data class GeofenceDto(
    val id: Long? = null,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
)

@Serializable
data class UsageTrendItem(val date: String, val packageName: String, val appLabel: String? = null, val minutes: Int)

@Serializable
data class PolicyTemplateDto(
    val id: Long? = null,
    val name: String,
    /** 结构见 [PolicyTemplatePayload];以 JSON 对象收发,库里存字符串 */
    val payload: JsonObject,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/** policy_template.payload_json 的结构。 */
@Serializable
data class PolicyTemplatePayload(
    val limits: List<PolicyLimit> = emptyList(),
    val windows: List<PolicyWindow> = emptyList(),
    /** 生成任务的 cron(UNIX 5 字段);缺省每天 00:00 重申一次,防止设备侧规则丢失 */
    val cronExpr: String = "0 0 * * *",
    /** apply 时是否立即下发一次(设备离线则进离线队列) */
    val applyNow: Boolean = true,
)

@Serializable
data class PolicyLimit(val packageName: String, val minutes: Int)

@Serializable
data class PolicyWindow(val packageName: String, val startTime: String, val endTime: String)

@Serializable
data class ApplyTemplateBody(val deviceIds: List<String>)

@Serializable
data class ApplyTemplateResult(val deviceId: String, val taskIds: List<Long>, val dispatched: Int, val queued: Int, val errors: List<String> = emptyList())

@Serializable
data class AuditRow(
    val id: Long,
    val commandId: String,
    val deviceId: String,
    val type: String,
    val payloadSummary: String? = null,
    val issuedBy: String,
    val issuedAt: Long,
    val channel: String? = null,
    val result: String,
    val finishedAt: Long? = null,
)

@Serializable
data class PendingCommandRow(
    val id: Long = 0,
    val commandId: String,
    val deviceId: String,
    val commandType: String,
    val topic: String,
    val envelopeType: String,
    val payloadJson: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val sentAt: Long? = null,
)

@Serializable
data class SetSha256Body(val sha256: String, val channel: String = "stable")
