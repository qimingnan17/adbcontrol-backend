package com.adbcontrol.backend.routes

import com.adbcontrol.backend.model.UpdateResultReport
import com.adbcontrol.backend.model.VersionManifest
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.UpdateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class Ack(val status: String, val message: String = "")

/**
 * 软件更新服务器(README 11.2)。
 * - GET /update/check?deviceId=&currentVersionCode=&channel=:返回 UpdateCheckResponse。
 * - POST /update/report:接收 UpdateResultReport 入库(内存留存)。
 * - POST /api/updates/publish(CI/Web 发布入口):X-Admin-Token 鉴权,发布新版本
 *   并向全部已配对设备广播 update_available 推送。
 */
fun Routing.updateRoutes(service: UpdateService, commandBridge: DeviceCommandBridge) {
    get("/update/check") {
        val deviceId = call.request.queryParameters["deviceId"]
        val currentVersionCode = call.request.queryParameters["currentVersionCode"]?.toIntOrNull()
        val channel = call.request.queryParameters["channel"] ?: "stable"

        if (deviceId.isNullOrBlank() || currentVersionCode == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                Ack("error", "missing or invalid deviceId/currentVersionCode"),
            )
            return@get
        }
        call.respond(service.check(deviceId, currentVersionCode, channel))
    }

    post("/update/report") {
        val report = call.receive<UpdateResultReport>()
        service.report(report)
        call.respond(Ack("ok"))
    }

    post("/api/updates/publish") {
        // 令牌校验放在 body 解析前,避免未授权请求消耗解析资源
        if (!service.verifyPublishToken(call.request.headers["X-Admin-Token"])) {
            call.respond(HttpStatusCode.Unauthorized, Ack("error", "invalid or missing X-Admin-Token (or ADB_PM_TOKEN not configured)"))
            return@post
        }
        val manifest = runCatching { call.receive<VersionManifest>() }.getOrElse {
            // receive 失败时给一条更友好的诊断(原始 body 截断)
            val raw = runCatching { call.receiveText() }.getOrDefault("")
            return@post call.respond(
                HttpStatusCode.BadRequest,
                Ack("error", "malformed VersionManifest JSON: ${raw.take(200)}"),
            )
        }
        val err = service.publish(manifest)
        if (err != null) {
            call.respond(HttpStatusCode.BadRequest, Ack("error", err))
            return@post
        }

        // 广播更新通知到所有有配对会话的设备(best-effort,失败不影响发布结果)
        val payload = buildJsonObject {
            put("event", "update_available")
            put("versionCode", manifest.versionCode)
            put("versionName", manifest.versionName)
            put("releaseNotes", manifest.releaseNotes)
            put("timestamp", System.currentTimeMillis())
        }
        val notified = runCatching { commandBridge.broadcastPush(payload.toString()) }
            .onFailure { call.application.environment.log.warn("broadcastPush failed: {}", it.message) }
            .getOrDefault(0)

        call.respond(buildJsonObject {
            put("ok", true)
            put("versionCode", manifest.versionCode)
            put("notified", notified)
        })
    }
}
