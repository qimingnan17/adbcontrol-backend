package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.LoginRequest
import com.adbcontrol.backend.model.LoginResponse
import com.adbcontrol.backend.plugin.UserSession
import com.adbcontrol.backend.security.LoginRateLimiter
import com.adbcontrol.backend.security.PasswordHasher
import com.adbcontrol.backend.service.DatabaseService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/** 改密请求体(仅已登录管理员改自己)。 */
@Serializable
private data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
)

fun Route.authRoutes(db: DatabaseService) {
    post("/api/login") {
        val remoteIp = call.request.header("X-Forwarded-For")?.split(",")?.first()?.trim() ?: call.request.local.remoteAddress
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
        if (LoginRateLimiter.isLocked("user:${req.username}")) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "该账号已被临时锁定"))
            return@post
        }
        val admin = db.findAdminByUsername(req.username.trim())
        if (admin == null || !PasswordHasher.verify(req.password, admin.passwordHash)) {
            LoginRateLimiter.fail("ip:$remoteIp")
            LoginRateLimiter.fail("user:${req.username}")
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "用户名或密码错误"))
            return@post
        }
        db.updateAdminLastLogin(admin.id)
        LoginRateLimiter.clear("ip:$remoteIp")
        LoginRateLimiter.clear("user:${req.username}")
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
