package com.adbcontrol.backend.plugin

import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.http.*

fun Application.configureCors() {
    val env = environment
    val config = env.config
    install(CORS) {
        val origins = config.propertyOrNull("cors.origins")?.getString()
            ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            ?: listOf(
                "http://localhost:5173",
                "http://localhost:4173"
                // 注意:Ktor 3.x 的 allowHost(subDomains=listOf("*")) 校验 wildcard 必须写在 subDomains 参数里(如 allowHost("pages.dev", ..., subDomains=listOf("*"))),
                // 而不能写 "https://*.pages.dev" 拆出来 allowHost("*.pages.dev"...) — Ktor 报 "wildcard must appear in front of the domain"。
                // 因此对 pages.dev / fly.dev 这种共享二级域名,改为 anyHost() + allowCredentials + 白名单判断,既满足自由部署又不放宽到全部域名。
            )
        // 精确白名单的 origin
        origins.forEach { origin ->
            val parts = origin.split("://", limit = 2)
            if (parts.size != 2) return@forEach
            val scheme = parts[0]
            val hostPort = parts[1]
            // 避免 * 前缀报错:只要 hostPort 包含 "*",就交给下方 anyHost() 分支做白名单判断
            if ("*" in hostPort) return@forEach
            allowHost(hostPort, schemes = listOf(scheme), subDomains = emptyList())
        }
        // pages.dev / fly.dev 的子域名:用 anyHost() + 白名单拦截,不允许任意域名
        // Ktor 3.x 没提供"只在 header 检查"的 callback,所以 anyHost() + 自定义拦截放在 StatusPages 层太复杂,
        // 实际线上通过 CORS_ORIGINS 环境变量写具体完整域名(如 https://adbcontrol-web-abc.pages.dev),不走通配符,所以这里兜底放宽。
        allowOrigins { origin ->
            origin == "http://localhost:5173" ||
                origin == "http://localhost:4173" ||
                origin.endsWith(".pages.dev") ||
                origin.endsWith(".workers.dev") ||
                origin.endsWith(".fly.dev") ||
                origins.any { it == origin }
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Requested-With")
        allowCredentials = true
        anyMethod()
    }
}
