package com.adbcontrol.backend.routes

import com.adbcontrol.backend.model.UpdateResultReport
import com.adbcontrol.backend.service.UpdateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class Ack(val status: String, val message: String = "")

/**
 * 软件更新服务器(README 11.2)。
 * - GET /update/check?deviceId=&currentVersionCode=&channel=:返回 UpdateCheckResponse。
 * - POST /update/report:接收 UpdateResultReport 入库。
 */
fun Routing.updateRoutes(service: UpdateService) {
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
}
