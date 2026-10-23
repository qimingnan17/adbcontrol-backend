package com.adbcontrol.backend.v2

import com.adbcontrol.backend.BackendConstants
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.UpdateService
import com.adbcontrol.shared.ProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
private data class V2Error(val message: String)

@Serializable
private data class DecisionResponse(
    val request: ExtensionRequestRow,
    /** SENT = 已发布到设备;QUEUED = 设备离线,已进离线队列;FAILED = 下发失败(审批状态已落库) */
    val delivery: String,
    val commandId: String? = null,
)

@Serializable
private data class GeofencePutResponse(val items: List<GeofenceDto>, val delivery: String, val commandId: String? = null)

@Serializable
private data class OkResponse(val ok: Boolean = true)

/** 当前登录管理员用户名(审计 issued_by / decided_by)。 */
internal fun ApplicationCall.adminName(): String = principal<UserIdPrincipal>()?.name ?: "admin"

internal fun DeviceCommandBridge.DispatchResult.deliveryLabel(): Pair<String, String?> = when (this) {
    is DeviceCommandBridge.DispatchResult.Ok -> (if (emqxStatus in 200..299) "SENT" else "FAILED") to commandId
    is DeviceCommandBridge.DispatchResult.Queued -> "QUEUED" to commandId
    else -> "FAILED" to null
}

/**
 * v2.0 REST 路由(契约见 docs/v2-protocol.md)。
 * 除 GET /api/meta 外全部需要管理员会话(auth-session)。
 */
fun Route.v2Routes(
    repo: V2Repository,
    bridge: DeviceCommandBridge,
    extensions: ExtensionRequestService,
    timeline: TimelineService,
    templates: PolicyTemplateService,
    updateService: UpdateService,
) {
    // §1 协议版本(公开:被控端/主控端握手前探测用)
    get("/api/meta") {
        call.respond(MetaResponse(protocolVersion = ProtocolVersion.CURRENT, serverVersion = BackendConstants.VERSION))
    }

    authenticate("auth-session") {
        // ---------------------------------------------------------------- §3 申请延时
        get("/api/extension-requests") {
            val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in setOf("PENDING", "APPROVED", "DENIED", "EXPIRED")) {
                return@get call.respond(HttpStatusCode.BadRequest, V2Error("status 必须是 PENDING/APPROVED/DENIED/EXPIRED"))
            }
            val deviceId = call.request.queryParameters["deviceId"]?.takeIf { it.isNotBlank() }
            call.respond(extensions.list(status, deviceId))
        }

        post("/api/extension-requests/{requestId}/approve") {
            val requestId = call.parameters["requestId"]!!
            val body = runCatching { call.receive<ApproveBody>() }.getOrElse { ApproveBody() }
            call.respondDecision(extensions.approve(requestId, body.minutes, call.adminName()))
        }

        post("/api/extension-requests/{requestId}/deny") {
            val requestId = call.parameters["requestId"]!!
            call.respondDecision(extensions.deny(requestId, call.adminName()))
        }

        // ---------------------------------------------------------------- §5 时间线 / 轨迹 / 围栏
        get("/api/devices/{id}/timeline") {
            val id = call.parameters["id"]!!
            val range = call.dayRangeParam() ?: return@get call.respond(HttpStatusCode.BadRequest, V2Error("date 必须是 YYYY-MM-DD"))
            call.respond(timeline.timeline(id, range.first, range.second))
        }

        get("/api/devices/{id}/locations") {
            val id = call.parameters["id"]!!
            val range = call.dayRangeParam() ?: return@get call.respond(HttpStatusCode.BadRequest, V2Error("date 必须是 YYYY-MM-DD"))
            call.respond(repo.listLocations(id, range.first, range.second))
        }

        get("/api/devices/{id}/geofences") {
            call.respond(repo.listGeofences(call.parameters["id"]!!))
        }

        put("/api/devices/{id}/geofences") {
            val id = call.parameters["id"]!!
            val fences = runCatching { call.receive<List<GeofenceDto>>() }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, V2Error("请求体必须是 [{name, lat, lng, radiusMeters}] 数组"))
            validateGeofences(fences)?.let { return@put call.respond(HttpStatusCode.BadRequest, V2Error(it)) }
            val saved = repo.replaceGeofences(id, fences.map { it.copy(name = it.name.trim()) }, System.currentTimeMillis())
                ?: return@put call.respond(HttpStatusCode.InternalServerError, V2Error("围栏保存失败(数据库不可达)"))
            val r = bridge.sendGeofenceSet(id, saved, call.adminName())
            if (r is DeviceCommandBridge.DispatchResult.SessionMissing) {
                // 已保存,但设备无配对会话无法下发:仍 200,delivery=FAILED 让前端提示重新配对
                return@put call.respond(GeofencePutResponse(saved, "FAILED"))
            }
            val (delivery, cmdId) = r.deliveryLabel()
            call.respond(GeofencePutResponse(saved, delivery, cmdId))
        }

        // ---------------------------------------------------------------- §6 使用趋势
        get("/api/devices/{id}/usage/trend") {
            val id = call.parameters["id"]!!
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            if (days != 7 && days != 30) return@get call.respond(HttpStatusCode.BadRequest, V2Error("days 只能是 7 或 30"))
            val today = BusinessZone.today()
            val from = today.minusDays((days - 1).toLong())
            call.respond(repo.usageTrend(id, from.toString(), today.toString()))
        }

        // ---------------------------------------------------------------- §7 策略模板
        get("/api/policy-templates") { call.respond(templates.list()) }

        get("/api/policy-templates/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, V2Error("invalid id"))
            val t = templates.get(id) ?: return@get call.respond(HttpStatusCode.NotFound, V2Error("模板不存在"))
            call.respond(t)
        }

        post("/api/policy-templates") {
            val dto = runCatching { call.receive<PolicyTemplateDto>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, V2Error("请求体格式错误,应为 {name, payload}"))
            call.respondTemplateResult(templates.create(dto), created = true)
        }

        put("/api/policy-templates/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, V2Error("invalid id"))
            val dto = runCatching { call.receive<PolicyTemplateDto>() }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, V2Error("请求体格式错误,应为 {name, payload}"))
            call.respondTemplateResult(templates.update(id, dto), created = false)
        }

        delete("/api/policy-templates/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, V2Error("invalid id"))
            if (templates.delete(id)) call.respond(OkResponse()) else call.respond(HttpStatusCode.NotFound, V2Error("模板不存在"))
        }

        post("/api/policy-templates/{id}/apply") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, V2Error("invalid id"))
            val body = runCatching { call.receive<ApplyTemplateBody>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, V2Error("请求体应为 {deviceIds: [...]}"))
            when (val r = templates.apply(id, body.deviceIds, call.adminName())) {
                is PolicyTemplateService.Result.Ok -> call.respond(r.value)
                is PolicyTemplateService.Result.Invalid -> call.respond(HttpStatusCode.BadRequest, V2Error(r.message))
                PolicyTemplateService.Result.NotFound -> call.respond(HttpStatusCode.NotFound, V2Error("模板不存在"))
                PolicyTemplateService.Result.StorageError -> call.respond(HttpStatusCode.InternalServerError, V2Error("数据库不可达"))
            }
        }

        // ---------------------------------------------------------------- §8 离线队列(只读)
        get("/api/devices/{id}/pending-commands") {
            val id = call.parameters["id"]!!
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
            call.respond(repo.listRecentPendingCommands(id, limit).map { it.copy(payloadJson = it.payloadJson.take(500)) })
        }

        // ---------------------------------------------------------------- §9 审计
        get("/api/audit") {
            val q = call.request.queryParameters
            val deviceId = q["deviceId"]?.takeIf { it.isNotBlank() }
            val from = q["from"]?.let { parseTimeParam(it) ?: return@get call.respond(HttpStatusCode.BadRequest, V2Error("from 必须是 epoch 毫秒或 YYYY-MM-DD")) }
            val to = q["to"]?.let { parseTimeParam(it, endOfDay = true) ?: return@get call.respond(HttpStatusCode.BadRequest, V2Error("to 必须是 epoch 毫秒或 YYYY-MM-DD")) }
            val limit = (q["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)
            call.respond(repo.listAudit(deviceId, from, to, limit))
        }

        // ---------------------------------------------------------------- §2 OTA:管理员登记 sha256
        put("/api/updates/{versionCode}/sha256") {
            val versionCode = call.parameters["versionCode"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, V2Error("invalid versionCode"))
            val body = runCatching { call.receive<SetSha256Body>() }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, V2Error("请求体应为 {sha256, channel?}"))
            val err = updateService.setSha256(versionCode, body.channel, body.sha256, repo)
            if (err == null) call.respond(OkResponse())
            else call.respond(if (err == "版本不存在") HttpStatusCode.NotFound else HttpStatusCode.BadRequest, V2Error(err))
        }
    }
}

private suspend fun ApplicationCall.respondDecision(d: ExtensionRequestService.Decision) {
    when (d) {
        is ExtensionRequestService.Decision.Done -> {
            val (delivery, cmdId) = d.dispatch.deliveryLabel()
            respond(DecisionResponse(d.row, delivery, cmdId))
        }
        ExtensionRequestService.Decision.NotFound -> respond(HttpStatusCode.NotFound, V2Error("申请不存在"))
        is ExtensionRequestService.Decision.NotPending -> respond(HttpStatusCode.Conflict, V2Error("申请当前状态为 ${d.status},不能再审批"))
        is ExtensionRequestService.Decision.Invalid -> respond(HttpStatusCode.BadRequest, V2Error(d.message))
    }
}

private suspend fun ApplicationCall.respondTemplateResult(r: PolicyTemplateService.Result<PolicyTemplateDto>, created: Boolean) {
    when (r) {
        is PolicyTemplateService.Result.Ok -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, r.value)
        is PolicyTemplateService.Result.Invalid -> respond(HttpStatusCode.BadRequest, V2Error(r.message))
        PolicyTemplateService.Result.NotFound -> respond(HttpStatusCode.NotFound, V2Error("模板不存在"))
        PolicyTemplateService.Result.StorageError -> respond(HttpStatusCode.InternalServerError, V2Error("数据库不可达"))
    }
}

/** ?date=YYYY-MM-DD(缺省 = 业务时区今天)→ 当天毫秒区间。 */
private fun ApplicationCall.dayRangeParam(): Pair<Long, Long>? {
    val date = request.queryParameters["date"]?.trim()?.takeIf { it.isNotEmpty() } ?: BusinessZone.today().toString()
    return BusinessZone.dayRange(date)
}

/** epoch 毫秒,或 YYYY-MM-DD(业务时区;endOfDay=true 时取次日 00:00 作为开区间上界)。 */
internal fun parseTimeParam(raw: String, endOfDay: Boolean = false): Long? {
    raw.trim().toLongOrNull()?.let { return it }
    val range = BusinessZone.dayRange(raw.trim()) ?: return null
    return if (endOfDay) range.second else range.first
}

internal fun validateGeofences(fences: List<GeofenceDto>): String? {
    if (fences.size > 50) return "围栏最多 50 个"
    fences.forEachIndexed { i, f ->
        if (f.name.isBlank()) return "第 ${i + 1} 个围栏 name 不能为空"
        if (f.lat !in -90.0..90.0 || f.lng !in -180.0..180.0) return "第 ${i + 1} 个围栏坐标非法"
        if (f.radiusMeters !in 10.0..100_000.0) return "第 ${i + 1} 个围栏 radiusMeters 必须在 10..100000"
    }
    return null
}
