package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.*
import com.adbcontrol.backend.plugin.UserSession
import com.adbcontrol.backend.security.LoginRateLimiter
import com.adbcontrol.backend.security.NetworkSecurity
import com.adbcontrol.backend.security.PasswordHasher
import com.adbcontrol.backend.service.CloudflareService
import com.adbcontrol.backend.service.DatabaseService
import com.adbcontrol.backend.service.SettingsService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.util.UUID

/** 改密请求体(仅已登录管理员改自己)。 */
@Serializable
private data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
)

/** 首次初始化管理员请求体(仅当 admin_user 为空时可用)。 */
@Serializable
private data class SetupRequest(
    val username: String,
    val password: String,
)

private fun ApplicationCall.clientIp(): String =
    // 只有直连对端是可信代理(本机 cloudflared / 内网 / Tailscale)时才采信转发头;
    // 公网直连的请求一律用对端地址,否则伪造 XFF 即可轮换限流键无限爆破
    NetworkSecurity.trustedClientIp(this)

private fun ApplicationCall.clientOrigin(): String {
    val proto = request.header("X-Forwarded-Proto")
        ?: request.header("CF-Visitor")?.let { if (it.contains("https")) "https" else "http" }
        ?: if (request.local.scheme.isNotBlank()) request.local.scheme else "http"
    val host = request.header("X-Forwarded-Host")
        ?: request.header("Host")
        ?: "localhost:8080"
    return "$proto://$host"
}

/** 归一化 URL 的 scheme+host[+非默认端口],同源比较用;非法返回 null。 */
private fun originKey(url: String): String? = runCatching {
    val uri = java.net.URI(url.trim())
    val scheme = uri.scheme?.lowercase() ?: return@runCatching null
    val host = uri.host?.lowercase() ?: return@runCatching null
    var port = uri.port
    if (port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) port = -1
    "$scheme://$host" + if (port > 0) ":$port" else ""
}.getOrNull()

private data class OidcStateInfo(
    val origin: String,
    val redirectUri: String,
    val createdAt: Long = System.currentTimeMillis()
)

private val oidcStateCache = java.util.concurrent.ConcurrentHashMap<String, OidcStateInfo>()

private fun cleanExpiredOidcStates() {
    val now = System.currentTimeMillis()
    oidcStateCache.entries.removeIf { now - it.value.createdAt > 600_000 }
}

fun Route.authRoutes(db: DatabaseService, cfService: CloudflareService, settingsService: SettingsService) {
    /**
     * 首次访问初始化流程:前端据此在登录页切换"初始化管理员"模式。
     * 已初始化后此接口只回 true,不再暴露任何创建入口。
     */
    get("/api/setup-status") {
        call.respond(mapOf("initialized" to db.isAdminInitialized()))
    }

    post("/api/setup") {
        val remoteIp = call.clientIp()
        if (LoginRateLimiter.isLocked("setup:ip:$remoteIp")) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "初始化失败次数过多，请 5 分钟后再试"))
            return@post
        }
        // 竞态兜底:任何时点管理员已存在即拒绝,不依赖客户端状态
        if (db.isAdminInitialized()) {
            call.respond(HttpStatusCode.Conflict, mapOf("message" to "系统已初始化，请直接登录"))
            return@post
        }
        val req = runCatching { call.receive<SetupRequest>() }.getOrNull()
        if (req == null) {
            LoginRateLimiter.fail("setup:ip:$remoteIp")
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请求体格式错误"))
            return@post
        }
        val username = req.username.trim()
        if (!Regex("^[A-Za-z0-9_.-]{3,32}$").matches(username)) {
            LoginRateLimiter.fail("setup:ip:$remoteIp")
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "用户名需为 3-32 位字母/数字/_.- 组合"))
            return@post
        }
        if (req.password.length < 8) {
            LoginRateLimiter.fail("setup:ip:$remoteIp")
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "密码至少 8 位"))
            return@post
        }
        val admin = db.createInitialAdmin(username, req.password)
        if (admin == null) {
            // 并发竞争被 UNIQUE 拦下或 DB 异常:一律按已初始化处理
            call.respond(HttpStatusCode.Conflict, mapOf("message" to "系统已初始化，请直接登录"))
            return@post
        }
        LoginRateLimiter.clear("setup:ip:$remoteIp")
        db.updateAdminLastLogin(admin.id)
        call.sessions.set(UserSession(admin.id, admin.username, admin.role, System.currentTimeMillis()))
        call.respond(LoginResponse(ok = true, user = admin.copy(passwordHash = "")))
    }

    post("/api/login") {
        val remoteIp = call.clientIp()
        if (LoginRateLimiter.isLocked("ip:$remoteIp")) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "登录失败次数过多，请 5 分钟后再试"))
            return@post
        }
        val req = runCatching { call.receive<LoginRequest>() }.getOrNull()
        if (req == null || req.username.isBlank() || req.password.isBlank()) {
            LoginRateLimiter.fail("ip:$remoteIp")
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "用户名和密码不能为空"))
            return@post
        }
        // 防 DoS:按 ip:user 联合维度限流,避免外部攻击者通过暴破恶意永久锁死合法管理员账号
        val userIpKey = "user-ip:${req.username.trim()}:$remoteIp"
        if (LoginRateLimiter.isLocked(userIpKey)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "该账号在此 IP 尝试失败过多已被锁定，请 5 分钟后再试"))
            return@post
        }
        val admin = db.findAdminByUsername(req.username.trim())
        if (admin == null || !PasswordHasher.verify(req.password, admin.passwordHash)) {
            LoginRateLimiter.fail("ip:$remoteIp")
            LoginRateLimiter.fail(userIpKey)
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "用户名或密码错误"))
            return@post
        }
        db.updateAdminLastLogin(admin.id)
        LoginRateLimiter.clear("ip:$remoteIp")
        LoginRateLimiter.clear(userIpKey)
        call.sessions.set(UserSession(admin.id, admin.username, admin.role, System.currentTimeMillis()))
        call.respond(LoginResponse(ok = true, user = admin.copy(passwordHash = "")))
    }

    /**
     * 探测是否存在 Cloudflare Zero Trust (Access) SSO 身份认证请求头。
     * 若存在，前端可提示用户"检测到 Cloudflare 账户，一键免密登录"。
     */
    get("/api/auth/cf-access") {
        val cfEmail = call.request.header("Cf-Access-Authenticated-User-Email")?.trim()
        // 头可伪造:仅在请求确定经本机 cloudflared 转发时才回显管理员信息,
        // 否则公网直连者可借此探测主管理员用户名/角色/最近登录时间。
        if (!cfEmail.isNullOrBlank() && com.adbcontrol.backend.security.NetworkSecurity.isLoopbackPeer(call)) {
            val primaryAdmin = db.getPrimaryAdmin()
            call.respond(CfAccessStatusResponse(hasAccessHeader = true, email = cfEmail, user = primaryAdmin?.copy(passwordHash = "")))
        } else {
            call.respond(CfAccessStatusResponse(hasAccessHeader = false))
        }
    }

    /**
     * Cloudflare Zero Trust (Access) 免密登录：
     * 基于 Cloudflare 隧道注入的 Cf-Access-Authenticated-User-Email 头校验身份。
     * 该头本身可被任意客户端伪造,因此只信 TCP 直连对端为本机回环的请求
     * (cloudflared 与后端同机部署,转发请求一律来自 127.0.0.1);
     * 公网/内网直连后端端口的伪造请求直接拒绝。若 cloudflared 独立部署在其它机器,
     * 此通道将不可用(fail-closed),管理员仍可用密码 / OIDC / API Token 登录。
     */
    post("/api/auth/cf-access-login") {
        val remoteIp = call.clientIp()
        if (LoginRateLimiter.isLocked("cfaccess:ip:$remoteIp")) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "尝试次数过多，请 5 分钟后再试"))
            return@post
        }
        val cfEmail = call.request.header("Cf-Access-Authenticated-User-Email")?.trim()
        if (cfEmail.isNullOrBlank()) {
            LoginRateLimiter.fail("cfaccess:ip:$remoteIp")
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未检测到 Cloudflare Zero Trust (Access) 认证头"))
            return@post
        }
        if (!com.adbcontrol.backend.security.NetworkSecurity.isLoopbackPeer(call)) {
            LoginRateLimiter.fail("cfaccess:ip:$remoteIp")
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Access 认证头仅接受经 Cloudflare 隧道(本机回环)转发的请求"))
            return@post
        }
        var admin = db.getPrimaryAdmin()
        if (admin == null) {
            val username = cfEmail.substringBefore("@").filter { it.isLetterOrDigit() || it in "_.-" }.take(32)
                .ifEmpty { "cf_admin" }
            admin = db.createInitialAdmin(username, UUID.randomUUID().toString())
        }
        if (admin == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "无法绑定管理员身份"))
            return@post
        }
        db.updateAdminLastLogin(admin.id)
        call.sessions.set(UserSession(admin.id, admin.username, admin.role, System.currentTimeMillis()))
        call.respond(LoginResponse(ok = true, user = admin.copy(passwordHash = "")))
    }

    /**
     * Cloudflare API Token 授权登录：
     * 用户提供 Cloudflare API Token，校验有效性后直接发放管理员会话，并自动将 Token 保存至系统配置。
     */
    post("/api/auth/cf-token-login") {
        val remoteIp = call.clientIp()
        if (LoginRateLimiter.isLocked("cftoken:ip:$remoteIp")) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "Token 登录失败过多，请 5 分钟后再试"))
            return@post
        }
        // 安全约束:任何"有效"的 Cloudflare API Token(攻击者自己注册 CF 账号也能签发)
        // 都能换取管理员会话,属于高危通道。因此仅允许受信通道使用:
        // 本机 cloudflared 转发(回环)或 Tailscale/内网直连;公网直连一律拒绝。
        if (!com.adbcontrol.backend.security.NetworkSecurity.isLoopbackPeer(call) &&
            !com.adbcontrol.backend.security.NetworkSecurity.isLocalOrTailscale(call)
        ) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Token 登录仅支持经 Cloudflare 隧道或 Tailscale 内网访问"))
            return@post
        }
        val req = runCatching { call.receive<CfTokenLoginRequest>() }.getOrNull()
        val token = req?.apiToken?.trim()
        if (token.isNullOrBlank()) {
            LoginRateLimiter.fail("cftoken:ip:$remoteIp")
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Cloudflare API Token 不能为空"))
            return@post
        }
        val verifyInfo = cfService.verifyToken(token)
        if (!verifyInfo.valid) {
            LoginRateLimiter.fail("cftoken:ip:$remoteIp")
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to (verifyInfo.message ?: "Token 校验失败")))
            return@post
        }
        var admin = db.getPrimaryAdmin()
        if (admin == null) {
            admin = db.createInitialAdmin("admin", UUID.randomUUID().toString())
        }
        if (admin == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "系统初始化失败"))
            return@post
        }
        LoginRateLimiter.clear("cftoken:ip:$remoteIp")
        db.updateAdminLastLogin(admin.id)
        // 自动保存 token 到 secrets.properties 便于后台使用
        runCatching {
            settingsService.saveSecrets(mapOf("cf.api_token" to token))
        }
        call.sessions.set(UserSession(admin.id, admin.username, admin.role, System.currentTimeMillis()))
        call.respond(LoginResponse(ok = true, user = admin.copy(passwordHash = "")))
    }

    /**
     * 获取 Cloudflare Zero Trust (Access) OIDC 授权跳转地址。
     * 前端点击「Cloudflare 官方账号登录」时调用，拉起官方登录页。
     */
    get("/api/auth/cf-oidc/authorize-url") {
        val teamDomain = settingsService.getRawProperty("cf.team_domain")
        val clientId = settingsService.getRawProperty("cf.oidc_client_id")
        if (teamDomain.isBlank() || clientId.isBlank()) {
            call.respond(
                CfOidcAuthUrlResponse(
                    configured = false,
                    message = "尚未配置 Cloudflare OIDC 单点登录。请先在「系统设置」->「Cloudflare 资源中心」中配置团队域名与 OIDC 客户端 ID，或使用 API Token / 账号密码登录。"
                )
            )
            return@get
        }

        // 开放重定向防护:显式 origin 必须与配置的 server.url 或当前请求来源同源,
        // 否则(如 origin=https://evil.com)认证成功后会被 302 到攻击者站点。
        val originParam = call.request.queryParameters["origin"]?.trim()
        val fallbackOrigin = call.clientOrigin().trimEnd('/')
        val origin = if (!originParam.isNullOrBlank() &&
            originKey(originParam) != null &&
            (originKey(originParam) == originKey(settingsService.getRawProperty("server.url")) ||
                originKey(originParam) == originKey(fallbackOrigin))
        ) originParam.trimEnd('/') else fallbackOrigin

        val customRedirect = settingsService.getRawProperty("cf.oidc_redirect_uri")
        val redirectUri = if (customRedirect.isNotBlank()) customRedirect else "$origin/api/auth/cf-oidc/callback"

        cleanExpiredOidcStates()
        val state = UUID.randomUUID().toString()
        oidcStateCache[state] = OidcStateInfo(origin = origin, redirectUri = redirectUri)

        val domain = teamDomain.removePrefix("https://").removePrefix("http://").trimEnd('/').substringBefore("/cdn-cgi")
        val authUrl = "https://$domain/cdn-cgi/access/sso/oidc/$clientId/authorization?response_type=code&client_id=$clientId&redirect_uri=${redirectUri.encodeURLParameter()}&scope=openid%20profile%20email&state=$state"

        call.respond(CfOidcAuthUrlResponse(configured = true, authUrl = authUrl))
    }

    /**
     * Cloudflare Zero Trust (Access) OIDC 认证回调端点。
     * 官方授权成功后跳转至此，置换身份并建立管理员会话，随后跳回前端。
     */
    get("/api/auth/cf-oidc/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]
        val errorDesc = call.request.queryParameters["error_description"]

        val stateInfo = if (!state.isNullOrBlank()) oidcStateCache.remove(state) else null
        val origin = stateInfo?.origin ?: settingsService.getRawProperty("server.url").ifBlank { call.clientOrigin() }.trimEnd('/')

        if (!error.isNullOrBlank() || code.isNullOrBlank()) {
            val msg = errorDesc ?: error ?: "授权已被取消或中断"
            call.respondRedirect("$origin/login?cf_error=${msg.encodeURLParameter()}")
            return@get
        }

        if (stateInfo == null) {
            call.respondRedirect("$origin/login?cf_error=${"授权请求已失效或过期，请重新登录".encodeURLParameter()}")
            return@get
        }

        val teamDomain = settingsService.getRawProperty("cf.team_domain")
        val clientId = settingsService.getRawProperty("cf.oidc_client_id")
        val clientSecret = settingsService.getRawProperty("cf.oidc_client_secret")

        val (ok, userInfo) = cfService.exchangeOidcCode(
            teamDomain = teamDomain,
            clientId = clientId,
            clientSecret = clientSecret,
            code = code,
            redirectUri = stateInfo.redirectUri
        )

        if (!ok || userInfo == null || userInfo.email.isBlank()) {
            call.respondRedirect("$origin/login?cf_error=${"Cloudflare 身份验证置换失败，请检查 Client Secret 是否有效".encodeURLParameter()}")
            return@get
        }

        var admin = db.getPrimaryAdmin()
        if (admin == null) {
            val username = userInfo.email.substringBefore("@").filter { it.isLetterOrDigit() || it in "_.-" }.take(32)
                .ifEmpty { "cf_admin" }
            admin = db.createInitialAdmin(username, UUID.randomUUID().toString())
        }

        if (admin == null) {
            call.respondRedirect("$origin/login?cf_error=${"无法绑定或初始化管理员账号".encodeURLParameter()}")
            return@get
        }

        db.updateAdminLastLogin(admin.id)
        call.sessions.set(UserSession(admin.id, admin.username, admin.role, System.currentTimeMillis()))
        call.respondRedirect("$origin/dashboard?cf_sso=success")
    }

    authenticate("auth-session") {
        get("/api/me") {
            val sess = call.sessions.get<UserSession>()!!
            val admin = db.findAdminByUsername(sess.username)
            if (admin == null) {
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "用户不存在"))
            } else {
                call.respond(mapOf("user" to admin.copy(passwordHash = "")))
            }
        }
        post("/api/logout") {
            call.sessions.clear<UserSession>()
            call.respond(mapOf("ok" to true))
        }

        // 仅允许"登录态管理员改自己",pin 到 username 维度上参与限流。
        post("/api/change-password") {
            val sess = call.sessions.get<UserSession>()!!
            val username = sess.username
            if (LoginRateLimiter.isLocked("changepwd:$username")) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf("message" to "原密码错误次数过多,请 5 分钟后再试")
                )
                return@post
            }
            val req = runCatching { call.receive<ChangePasswordRequest>() }.getOrNull()
            if (req == null || req.oldPassword.isBlank() || req.newPassword.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "oldPassword / newPassword 不能为空"))
                return@post
            }
            if (req.newPassword.length < 8) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "新密码至少 8 位"))
                return@post
            }
            if (req.newPassword == req.oldPassword) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "新密码不能与原密码相同"))
                return@post
            }
            val admin = db.findAdminByUsername(username)
            if (admin == null) {
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "当前账号不存在"))
                return@post
            }
            if (!PasswordHasher.verify(req.oldPassword, admin.passwordHash)) {
                LoginRateLimiter.fail("changepwd:$username")
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "原密码不正确"))
                return@post
            }
            val ok = db.updateAdminPassword(admin.id, PasswordHasher.hash(req.newPassword))
            if (!ok) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "密码更新失败,请稍后重试"))
                return@post
            }
            LoginRateLimiter.clear("changepwd:$username")
            // 改密后吊销当前会话强制重新登录:cookie 里的 iat 已早于新的
            // password_changed_at,即使不清也会被 validate 拒绝,这里显式清更直接
            call.sessions.clear<UserSession>()
            call.respond(mapOf("ok" to true, "message" to "密码已更新，请使用新密码重新登录"))
        }
    }
}
