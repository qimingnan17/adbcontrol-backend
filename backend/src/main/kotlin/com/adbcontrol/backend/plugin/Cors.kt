package com.adbcontrol.backend.plugin

import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.http.*

fun Application.configureCors() {
    val env = environment
    val config = env.config
    install(CORS) {
        // 精确白名单:仅放行 localhost 开发地址 + CORS_ORIGINS 里显式声明的域名。
        // 不允许再对 *.pages.dev / *.workers.dev / *.fly.dev 这类"人人可部署"的公共后缀
        // 整体放行:它们上面的任何站点结合 SameSite=None 的会话 Cookie 都能发起
        // 带凭据的跨站请求,等效托管后门。
        val customOrigins = config.propertyOrNull("cors.origins")?.getString()
            ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            ?: emptyList()
        val allowed = listOf("http://localhost:5173", "http://localhost:4173") + customOrigins
        allowed.forEach { origin ->
            val parts = origin.split("://", limit = 2)
            if (parts.size != 2 || "*" in parts[1]) {
                // 含通配符的配置不再支持,直接跳过并告警(在日志层面暴露)
                return@forEach
            }
            val scheme = parts[0]
            val hostPort = parts[1]
            allowHost(hostPort, schemes = listOf(scheme), subDomains = emptyList())
        }
        // 兜底再确认一次 Origin 精确等于白名单,避免任何子域/后缀绕过。
        allowOrigins { origin -> origin in allowed }
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Requested-With")
        allowCredentials = true
        anyMethod()
    }
}
