package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.*
import com.adbcontrol.backend.plugin.UserSession
import com.adbcontrol.backend.security.LoginRateLimiter
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
    request.header("Fly-Client-IP")
        ?: request.header("CF-Connecting-IP")
        ?: request.header("X-Forwarded-For")?.split(",")?.first()?.trim()
        ?: request.local.remoteAddress

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
        call.sessions.set(UserSession(admin.id, admin.username, admin.role))
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
        call.sessions.set(UserSession(admin.id, admin.username, admin.role))
        call.respond(LoginResponse(ok = true, user = admin.copy(passwordHash = "")))
    }

    /**
     * 探测是否存在 Cloudflare Zero Trust (Access) SSO 身份认证请求头。
     * 若存在，前端可提示用户"检测到 Cloudflare 账户，一键免密登录"。
     */
    get("/api/auth/cf-access") {
        val cfEmail = call.request.header("Cf-Access-Authenticated-User-Email")?.trim()
        if (!cfEmail.isNullOrBlank()) {
            val primaryAdmin = db.getPrimaryAdmin()
            call.respond(CfAccessStatusResponse(hasAccessHeader = true, email = cfEmail, user = primaryAdmin?.copy(passwordHash = "")))
        } else {
            call.respond(CfAccessStatusResponse(hasAccessHeader = false))
        }
    }

    /**
     * Cloudflare Zero Trust (Access) 免密登录：
     * 基于 Cloudflare 隧道注入的 Cf-Access-Authenticated-User-Email 头校验身份。
     */
    post("/api/auth/cf-access-login") {
        val cfEmail = call.request.header("Cf-Access-Authenticated-User-Email")?.trim()
        if (cfEmail.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未检测到 Cloudflare Zero Trust (Access) 认证头"))
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
        call.sessions.set(UserSession(admin.id, admin.username, admin.role))
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
        call.sessions.set(UserSession(admin.id, admin.username, admin.role))
        call.respond(LoginResponse(ok = true, user = admin.copy(passwordHash = "")))
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
            call.respond(mapOf("ok" to true))
        }
    }
}
