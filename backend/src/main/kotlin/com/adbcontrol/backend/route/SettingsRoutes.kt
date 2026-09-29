package com.adbcontrol.backend.route

import com.adbcontrol.backend.security.NetworkSecurity
import com.adbcontrol.backend.service.SettingsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.system.exitProcess

@Serializable
private data class TestR2Request(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val accessSecret: String,
)

@Serializable
private data class TestEmqxRequest(
    val restEndpoint: String,
    val appId: String,
    val appSecret: String,
)

private suspend fun ApplicationCall.ensureTailscaleOrLocal(): Boolean {
    if (!NetworkSecurity.isLocalOrTailscale(this)) {
        respond(
            HttpStatusCode.Forbidden,
            mapOf(
                "ok" to false,
                "code" to "FORBIDDEN_TAILSCALE_ONLY",
                "message" to "安全限制：此管理配置接口仅允许在 Tailscale 内网或本地访问，公网已被严格阻断。"
            )
        )
        return false
    }
    return true
}

fun Route.settingsRoutes(settingsService: SettingsService) {
    // 网络模式检查：诊断探针无需强制会话，方便前端未登录或初始化时也能判断网络环境
    get("/api/admin/settings/network") {
        val isTailscale = NetworkSecurity.isLocalOrTailscale(call)
        val clientIp = NetworkSecurity.getClientIp(call)
        call.respond(
            mapOf(
                "isTailscale" to isTailscale,
                "clientIp" to clientIp,
                "tip" to if (isTailscale) "Tailscale / Localhost 安全内网" else "Cloudflare / 公网访问（配置功能受限）"
            )
        )
    }

    authenticate("auth-session") {
        route("/api/admin/settings") {

            // 读取脱敏配置
            get("/secrets") {
                if (!call.ensureTailscaleOrLocal()) return@get
                call.respond(settingsService.readMaskedSecrets())
            }

            // 保存配置
            post("/secrets") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val body = runCatching { call.receive<Map<String, String>>() }.getOrNull()
                if (body == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "message" to "请求体格式错误"))
                    return@post
                }
                val ok = settingsService.saveSecrets(body)
                if (ok) {
                    call.respond(mapOf("ok" to true, "message" to "配置已成功保存到 secrets.properties"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("ok" to false, "message" to "保存配置失败"))
                }
            }

            // 测试 R2 连接
            post("/test-r2") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<TestR2Request>() }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "message" to "请求参数格式错误"))
                    return@post
                }
                val (ok, msg) = settingsService.testR2(req.endpoint, req.bucket, req.accessKey, req.accessSecret)
                call.respond(mapOf("ok" to ok, "message" to msg))
            }

            // 测试 EMQX REST API 连接
            post("/test-emqx") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<TestEmqxRequest>() }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "message" to "请求参数格式错误"))
                    return@post
                }
                val (ok, msg) = settingsService.testEmqx(req.restEndpoint, req.appId, req.appSecret)
                call.respond(mapOf("ok" to ok, "message" to msg))
            }

            // 重启后端服务生效配置
            post("/restart") {
                if (!call.ensureTailscaleOrLocal()) return@post
                call.respond(mapOf("ok" to true, "message" to "后端守护进程正在重启，约 5 秒内恢复上线..."))
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    exitProcess(0)
                }
            }
        }
    }
}
