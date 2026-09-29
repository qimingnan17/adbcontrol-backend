package com.adbcontrol.backend.plugin

import com.adbcontrol.backend.service.DatabaseService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.sessions.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class UserSession(val adminId: Int, val username: String, val role: String)

/**
 * 使用"带签名的会话 Cookie + 内存 session store"两层方案:
 * 1) SessionTransportTransformerMessageAuthentication 只做签名,避免 IV/AES 长度参数炸启动;
 * 2) 会话对象本身是明文但很小(adminId/username/role),用 HMAC 签名后塞在 HttpOnly Cookie 里,
 *    防篡改但不防读——要高保密可以后续再补一层 SessionTransportTransformerEncrypt(等 Ktor 3.x 稳定 API 再换)。
 */
private fun hmacSha256Key(seed: String): ByteArray {
    // 32 bytes — 刚好是 SessionTransportTransformerMessageAuthentication 的推荐密钥长度
    return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
}

fun Application.configureSecurity(db: DatabaseService) {
    val env = environment
    val config = env.config
    install(Sessions) {
        val seed = config.propertyOrNull("session.secret")?.getString()
            ?: run {
                val tmp = ByteArray(48).also { SecureRandom().nextBytes(it) }
                org.apache.commons.codec.binary.Hex.encodeHexString(tmp)
            }
        cookie<UserSession>("ADB_SESSION") {
            // 同源托管与 Tailscale HTTP 部署场景下,SameSite 默认 Lax,secure 默认 false;
            // 允许在 http://100.x.x.x 等内网环境下正常存储和携带会话 Cookie。
            val sameSite = config.propertyOrNull("session.same_site")?.getString()?.takeIf { it.isNotBlank() } ?: "Lax"
            val secure = if (sameSite.equals("None", ignoreCase = true)) {
                true
            } else {
                config.propertyOrNull("session.secure")?.getString()?.toBoolean() ?: false
            }
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secure
            cookie.extensions["SameSite"] = sameSite
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 14
            transform(SessionTransportTransformerMessageAuthentication(hmacSha256Key(seed)))
        }
    }
    install(Authentication) {
        session<UserSession>("auth-session") {
            validate { session ->
                db.findAdminByUsername(session.username)?.let {
                    UserIdPrincipal(session.username)
                }
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录或会话已过期"))
            }
        }
    }
}
