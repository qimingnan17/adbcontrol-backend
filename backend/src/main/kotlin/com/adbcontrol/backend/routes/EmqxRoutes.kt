package com.adbcontrol.backend.routes

import com.adbcontrol.backend.service.EmqxProxyService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * EMQX REST 代理(README 4.4)。供主控端调用,避免跨域与泄漏 app_secret。
 * - GET /emqx/devices:代理 EMQX REST /clients,返回在线设备列表。
 * - GET /emqx/subscriptions?clientId=:代理 EMQX REST /clients/{clientId}/subscriptions。
 *
 * 透传 EMQX 原始 JSON,成功与否都以 EMQX 的状态码回传。
 *
 * 注意:扩展接收者必须是 [Route] 而非 Routing —— 声明为 `fun Routing.xxx` 时,
 * 在 `routing { authenticate { ... } }` 里调用会被 Kotlin 解析到外层 Routing 接收者,
 * 路由会注册到 authenticate 块之外,鉴权静默失效(实测踩坑)。
 */
fun Route.emqxRoutes(service: EmqxProxyService) {
    get("/emqx/devices") {
        val result = service.listClients(call.request.queryParameters.formUrlEncode())
        call.respondEmqx(result)
    }

    get("/emqx/subscriptions") {
        val clientId = call.request.queryParameters["clientId"]
        val result = service.listSubscriptions(clientId, call.request.queryParameters.formUrlEncode())
        call.respondEmqx(result)
    }
}

private suspend fun ApplicationCall.respondEmqx(result: EmqxProxyService.EmqxResponse) {
    val status = HttpStatusCode.fromValue(result.status)
    respond(status, TextContent(result.body, ContentType.Application.Json, status))
}
