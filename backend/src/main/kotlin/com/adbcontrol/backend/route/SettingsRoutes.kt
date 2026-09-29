package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.*
import com.adbcontrol.backend.plugin.UserSession
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
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
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
    // 1. 本地回环或 Tailscale CGNAT 私网直连
    if (NetworkSecurity.isLocalOrTailscale(this)) return true

    // 2. 具备合法管理员会话（支持通过 Cloudflare Zero Trust OIDC 单点登录或管理员登录的远程会话）
    val sess = sessions.get<UserSession>()
    if (sess != null && sess.role == "admin") {
        return true
    }

    respond(
        HttpStatusCode.Forbidden,
        SettingsOperationResponse(
            ok = false,
            code = "FORBIDDEN_AUTH_REQUIRED",
            message = "安全限制：此管理配置接口仅允许在 Tailscale 内网或具备管理员授权会话中访问，公网未登录已被严格阻断。"
        )
    )
    return false
}

fun Route.settingsRoutes(settingsService: SettingsService, cfService: CloudflareService) {
    // 网络模式检查：诊断探针无需强制会话，方便前端未登录或初始化时也能判断网络环境
    get("/api/admin/settings/network") {
        val isTailscale = NetworkSecurity.isLocalOrTailscale(call)
        val clientIp = NetworkSecurity.getClientIp(call)
        val sess = call.sessions.get<UserSession>()
        val isAuthed = (sess != null && sess.role == "admin")
        call.respond(
            NetworkStatusResponse(
                isTailscale = isTailscale || isAuthed,
                clientIp = clientIp,
                tip = when {
                    isTailscale -> "Tailscale / Localhost 安全内网"
                    isAuthed -> "Cloudflare SSO / 管理员授权访问"
                    else -> "公网访问（需登录管理员或内网配置）"
                }
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

            // 查询系统绑定的云端资产概览 (域名/隧道、R2 桶、D1 数据库)
            get("/cloudflare/bindings") {
                if (!call.ensureTailscaleOrLocal()) return@get
                call.respond(settingsService.getCloudBindings())
            }

            // 一键应用 Cloudflare D1 数据库配置到系统
            post("/cloudflare/apply-d1") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyD1Request>() }.getOrNull()
                if (req == null || req.databaseId.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "D1 Database ID 不能为空")
                    )
                    return@post
                }
                val updates = mutableMapOf(
                    "d1.database_id" to req.databaseId.trim(),
                    "d1.database_name" to req.databaseName.trim()
                )
                if (!req.accountId.isNullOrBlank()) {
                    updates["d1.account_id"] = req.accountId.trim()
                }
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已成功绑定 Cloudflare D1 数据库「${req.databaseName}」"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存 D1 数据库配置失败"))
                }
            }

            // 一键全量自动探测并绑定 Cloudflare 资产 (Tunnel 域名、R2 存储桶、D1 数据库)
            post("/cloudflare/auto-bind") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfAutoBindRequest>() }.getOrNull()
                var token = req?.apiToken?.trim() ?: ""
                if (token.isEmpty() || token.contains("******")) {
                    token = settingsService.getRawProperty("cf.api_token", "")
                }
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfAutoBindResponse(ok = false, message = "未检测到 Cloudflare API Token，请先填入 API Token 后再执行自动绑定")
                    )
                    return@post
                }

                val res = cfService.fetchAllResources(token)
                if (!res.valid) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfAutoBindResponse(ok = false, message = res.message ?: "Cloudflare API Token 鉴权或探测失败")
                    )
                    return@post
                }

                val updates = mutableMapOf<String, String>()
                var boundTunnel: String? = null
                var boundR2: String? = null
                var boundD1: String? = null

                // 1. 自动绑定 Tunnel / 域名
                val healthyTunnel = res.tunnels.firstOrNull { it.status == "healthy" } ?: res.tunnels.firstOrNull()
                if (healthyTunnel != null) {
                    val url = if (healthyTunnel.name.contains(".")) {
                        "https://${healthyTunnel.name}"
                    } else if (res.zones.isNotEmpty()) {
                        val zone = res.zones.first()
                        "https://${healthyTunnel.name}.${zone.name}"
                    } else {
                        "https://${healthyTunnel.name}.trycloudflare.com"
                    }
                    updates["server.url"] = url
                    boundTunnel = "${healthyTunnel.name} ($url)"
                }

                // 2. 自动绑定 R2 存储桶
                val r2 = res.r2Buckets.firstOrNull()
                if (r2 != null) {
                    updates["r2.bucket"] = r2.name
                    updates["r2.endpoint"] = r2.s3Endpoint
                    boundR2 = "${r2.name} (${r2.s3Endpoint})"
                }

                // 3. 自动绑定 D1 数据库
                val d1 = res.d1Databases.firstOrNull()
                if (d1 != null) {
                    updates["d1.database_id"] = d1.uuid
                    updates["d1.database_name"] = d1.name
                    if (d1.accountId.isNotBlank()) {
                        updates["d1.account_id"] = d1.accountId
                    }
                    boundD1 = "${d1.name} (${d1.uuid})"
                }

                // 4. 保存 API Token 及 Access Org 团队域名
                if (req?.apiToken != null && !req.apiToken.contains("******") && req.apiToken.isNotBlank()) {
                    updates["cf.api_token"] = req.apiToken.trim()
                }
                if (res.accessOrg != null && res.accessOrg.authDomain.isNotBlank() && settingsService.getRawProperty("cf.team_domain").isBlank()) {
                    updates["cf.team_domain"] = res.accessOrg.authDomain
                }

                if (updates.isEmpty()) {
                    call.respond(CfAutoBindResponse(ok = false, message = "未在 Cloudflare 账户下探测到可绑定的 Tunnel、R2 桶或 D1 数据库"))
                    return@post
                }

                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    val details = listOfNotNull(
                        boundTunnel?.let { "域名隧道: $it" },
                        boundR2?.let { "R2 存储桶: $it" },
                        boundD1?.let { "D1 数据库: $it" }
                    ).joinToString("；")
                    call.respond(
                        CfAutoBindResponse(
                            ok = true,
                            boundTunnel = boundTunnel,
                            boundR2 = boundR2,
                            boundD1 = boundD1,
                            message = if (details.isNotEmpty()) "自动绑定成功！已关联 $details" else "Cloudflare 配置已更新"
                        )
                    )
                } else {
                    call.respond(HttpStatusCode.InternalServerError, CfAutoBindResponse(ok = false, message = "保存自动绑定配置失败"))
                }
            }

            // 用户手动批量绑定资源 (域名隧道、R2 存储桶、D1 数据库)
            post("/cloudflare/manual-bind") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfManualBindRequest>() }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "请求体格式错误"))
                    return@post
                }
                val updates = mutableMapOf<String, String>()
                if (!req.serverUrl.isNullOrBlank()) updates["server.url"] = req.serverUrl.trim()
                if (!req.r2Endpoint.isNullOrBlank()) updates["r2.endpoint"] = req.r2Endpoint.trim()
                if (!req.r2Bucket.isNullOrBlank()) updates["r2.bucket"] = req.r2Bucket.trim()
                if (!req.r2AccessKey.isNullOrBlank()) updates["r2.access_key"] = req.r2AccessKey.trim()
                if (!req.r2AccessSecret.isNullOrBlank() && !req.r2AccessSecret.contains("******")) {
                    updates["r2.access_secret"] = req.r2AccessSecret.trim()
                }
                if (!req.d1DatabaseId.isNullOrBlank()) updates["d1.database_id"] = req.d1DatabaseId.trim()
                if (!req.d1DatabaseName.isNullOrBlank()) updates["d1.database_name"] = req.d1DatabaseName.trim()
                if (!req.d1AccountId.isNullOrBlank()) updates["d1.account_id"] = req.d1AccountId.trim()

                if (updates.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "未提交任何需要绑定的有效参数"))
                    return@post
                }
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "手动绑定云端资源成功！"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存绑定配置失败"))
                }
            }
        }
    }
}
