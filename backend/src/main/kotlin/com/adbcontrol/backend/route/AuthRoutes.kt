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
    }
}
