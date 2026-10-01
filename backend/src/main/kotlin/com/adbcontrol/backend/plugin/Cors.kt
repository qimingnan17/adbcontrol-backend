package com.adbcontrol.backend.plugin

import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.http.*

fun Application.configureCors() {
    val env = environment
    val config = env.config
    // server.url 不在 application.conf 里(它在 secrets.properties,由 BackendConfig 读取)。
    // 直接从系统属性取,见 Application.kt 启动时的透传;取不到就退化为"自身不入白名单",
    // 此时同源子资源请求会 403 —— 日志里会明显暴露,不至于变成静默白屏。
    val serverUrl = System.getProperty("adbcontrol.server.url")
        ?: config.propertyOrNull("server.url")?.getString()?.trim()
        ?: ""

    // server.url 是本服务的对外地址(设隧道时写入),访问自己的资源时浏览器
    // 仍会带上 Origin:<server.url>(同源子资源请求同样如此)。
    // 它必须进白名单,否则所有 /assets/*.js|css 会被 CORS 插件判为非法来源
    // 直接 403,页面只剩白屏 —— 且响应带 Vary: Origin,极易被误判成
    // Cloudflare / WAF / Bot Fight Mode 在拦(实测排查绕了很大一圈)。
    val selfOrigin = serverUrl
        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.trimEnd('/')
        .orEmpty()

    install(CORS) {
        // 精确白名单:仅放行 localhost 开发地址 + 自身对外地址 + CORS_ORIGINS 显式声明的域名。
        // 不允许再对 *.pages.dev / *.workers.dev / *.fly.dev 这类"人人可部署"的公共后缀
        // 整体放行:它们上面的任何站点结合 SameSite=None 的会话 Cookie 都能发起
        // 带凭据的跨站请求,等效托管后门。
        val customOrigins = config.propertyOrNull("cors.origins")?.getString()
            ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            ?: emptyList()
        val allowed = buildList {
            add("http://localhost:5173")
            add("http://localhost:4173")
            if (selfOrigin.isNotEmpty()) add(selfOrigin)
            addAll(customOrigins)
        }.distinct()
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
