package com.adbcontrol.backend.routes

import com.adbcontrol.backend.model.PairTokenPayload
import com.adbcontrol.backend.model.RenewRequest
import com.adbcontrol.backend.service.PairingService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post

/**
 * 配对 / 续期端点(README 8.3)。
 * - POST /pair:校验 pairToken,签发临时 MQTT 凭证 + sessionKey,返回 PairingResponse。
 * - POST /renew:校验身份,签发新 MQTT 密码,返回 RenewResponse。
 */
fun Routing.pairingRoutes(service: PairingService) {
    post("/pair") {
        val payload = call.receive<PairTokenPayload>()
        when (val r = service.pair(payload)) {
            is PairingService.PairResult.Ok -> call.respond(r.response)
            is PairingService.PairResult.Fail -> call.respond(statusFor(r.error.code), r.error)
        }
    }

    post("/renew") {
        val req = call.receive<RenewRequest>()
        when (val r = service.renew(req)) {
            is PairingService.RenewResult.Ok -> call.respond(r.response)
            is PairingService.RenewResult.Fail -> call.respond(statusFor(r.error.code), r.error)
        }
    }
}

private fun statusFor(code: String): HttpStatusCode = when (code) {
    "TOKEN_INVALID", "TOKEN_EXPIRED" -> HttpStatusCode.BadRequest
    "TOKEN_USED" -> HttpStatusCode.Gone
    "DEVICE_LIMIT" -> HttpStatusCode.TooManyRequests
    "DEVICE_NOT_FOUND" -> HttpStatusCode.NotFound
    else -> HttpStatusCode.InternalServerError
}
