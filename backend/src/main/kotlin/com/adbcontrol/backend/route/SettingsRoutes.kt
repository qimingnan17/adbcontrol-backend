package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.*
import com.adbcontrol.backend.security.NetworkSecurity
import com.adbcontrol.backend.service.CloudflareService
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
            SettingsOperationResponse(
                ok = false,
                code = "FORBIDDEN_TAILSCALE_ONLY",
                message = "安全限制：此管理配置接口仅允许在 Tailscale 内网或本地访问，公网已被严格阻断。"
            )
        )
        return false
    }
    return true
}

fun Route.settingsRoutes(settingsService: SettingsService, cfService: CloudflareService) {
    // 网络模式检查：诊断探针无需强制会话，方便前端未登录或初始化时也能判断网络环境
    get("/api/admin/settings/network") {
        val isTailscale = NetworkSecurity.isLocalOrTailscale(call)
        val clientIp = NetworkSecurity.getClientIp(call)
        call.respond(
            NetworkStatusResponse(
                isTailscale = isTailscale,
                clientIp = clientIp,
                tip = if (isTailscale) "Tailscale / Localhost 安全内网" else "Cloudflare / 公网访问（配置功能受限）"
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
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "请求体格式错误")
                    )
                    return@post
                }
                val ok = settingsService.saveSecrets(body)
                if (ok) {
                    call.respond(
                        SettingsOperationResponse(ok = true, message = "配置已成功保存到 secrets.properties")
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        SettingsOperationResponse(ok = false, message = "保存配置失败")
                    )
                }
            }

            // 测试 R2 连接
            post("/test-r2") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<TestR2Request>() }.getOrNull()
                if (req == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "请求参数格式错误")
                    )
                    return@post
                }
                val (ok, msg) = settingsService.testR2(req.endpoint, req.bucket, req.accessKey, req.accessSecret)
                call.respond(SettingsOperationResponse(ok = ok, message = msg))
            }

            // 测试 EMQX REST API 连接
            post("/test-emqx") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<TestEmqxRequest>() }.getOrNull()
                if (req == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "请求参数格式错误")
                    )
                    return@post
                }
                val (ok, msg) = settingsService.testEmqx(req.restEndpoint, req.appId, req.appSecret)
                call.respond(SettingsOperationResponse(ok = ok, message = msg))
            }

            // 重启后端服务生效配置
            post("/restart") {
                if (!call.ensureTailscaleOrLocal()) return@post
                call.respond(
                    SettingsOperationResponse(ok = true, message = "后端守护进程正在重启，约 5 秒内恢复上线...")
                )
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    exitProcess(0)
                }
            }

            // 同步 Cloudflare 资源：获取账户、域名、隧道、R2 桶、D1 数据库
            post("/cloudflare/sync") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfTokenLoginRequest>() }.getOrNull()
                var token = req?.apiToken?.trim() ?: ""
                if (token.isEmpty()) {
                    // 尝试从持久化配置中读取
                    val file = settingsService.getSecretsFile()
                    val p = java.util.Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
                    token = p.getProperty("cf.api_token", "")
                }
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfAllResources(valid = false, message = "未提供 Cloudflare API Token，请先在下方输入或保存 Token")
                    )
                    return@post
                }
                val resources = cfService.fetchAllResources(token)
                if (resources.valid && token.isNotEmpty() && !token.contains("******")) {
                    val updates = mutableMapOf("cf.api_token" to token)
                    if (resources.accessOrg != null && resources.accessOrg.authDomain.isNotBlank() && settingsService.getRawProperty("cf.team_domain").isBlank()) {
                        updates["cf.team_domain"] = resources.accessOrg.authDomain
                    }
                    settingsService.saveSecrets(updates)
                }
                call.respond(resources)
            }

            // 一键应用 Cloudflare R2 存储桶配置到系统
            post("/cloudflare/apply-r2") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyR2Request>() }.getOrNull()
                if (req == null || req.endpoint.isBlank() || req.bucket.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "R2 Endpoint 与 Bucket 不能为空")
                    )
                    return@post
                }
                val updates = mutableMapOf(
                    "r2.endpoint" to req.endpoint.trim(),
                    "r2.bucket" to req.bucket.trim()
                )
                if (!req.accessKey.isNullOrBlank()) updates["r2.access_key"] = req.accessKey.trim()
                if (!req.accessSecret.isNullOrBlank()) updates["r2.access_secret"] = req.accessSecret.trim()
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已成功应用 Cloudflare R2 存储配置"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存 R2 配置失败"))
                }
            }

            // 一键将 Cloudflare 隧道域名应用为系统服务地址
            post("/cloudflare/apply-tunnel") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyTunnelRequest>() }.getOrNull()
                if (req == null || req.serverUrl.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "服务地址不能为空")
                    )
                    return@post
                }
                val ok = settingsService.saveSecrets(mapOf("server.url" to req.serverUrl.trim()))
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已将 Cloudflare 隧道域名设为服务地址"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存服务地址失败"))
                }
            }

            // 保存 Cloudflare Zero Trust (OIDC) 授权登录配置
            post("/cloudflare/apply-oidc") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyOidcRequest>() }.getOrNull()
                if (req == null || req.teamDomain.isBlank() || req.clientId.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "Team Domain 与 Client ID 不能为空")
                    )
                    return@post
                }
                val updates = mutableMapOf(
                    "cf.team_domain" to req.teamDomain.trim(),
                    "cf.oidc_client_id" to req.clientId.trim()
                )
                if (!req.clientSecret.isNullOrBlank() && !req.clientSecret.contains("******")) {
                    updates["cf.oidc_client_secret"] = req.clientSecret.trim()
                }
                if (!req.redirectUri.isNullOrBlank()) {
                    updates["cf.oidc_redirect_uri"] = req.redirectUri.trim()
                }
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已成功保存 Cloudflare Zero Trust (OIDC) 授权配置"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存 OIDC 配置失败"))
                }
            }
        }
    }
}
