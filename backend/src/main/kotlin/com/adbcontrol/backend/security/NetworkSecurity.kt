package com.adbcontrol.backend.security

import io.ktor.server.application.*
import io.ktor.server.request.*

object NetworkSecurity {
    /**
     * 判断当前调用是否来自 Tailscale 或本地内网。
     * 若流量经过 Cloudflare 隧道 (CF-Connecting-IP / CF-Ray / CF-Visitor)，坚决视为公网请求，返回 false。
     *
     * 安全基线：只信任 TCP socket 对端地址 (remoteAddress)。
     * X-Forwarded-For / X-Real-IP 是客户端可任意伪造的请求头——直连源站的公网攻击者
     * 伪造 "X-Forwarded-For: 100.64.x.x" 即可冒充 Tailscale 节点绕过管理守卫
     * (本地实测复现)，因此这些头只能用于日志/限流，绝不参与信任判定。
     */
    fun isLocalOrTailscale(call: ApplicationCall): Boolean {
        // 1. 如果请求带有 Cloudflare 隧道特征头，坚决视为公网流量
        if (call.request.header("CF-Connecting-IP") != null ||
            call.request.header("CF-Ray") != null ||
            call.request.header("CF-Visitor") != null) {
            return false
        }

        // 2. 直接以 socket 对端地址判定（不读取任何转发头）
        return isTailscaleOrLocalIp(call.request.local.remoteAddress)
    }

    /**
     * 判断请求是否确定经由本机 cloudflared 代理转发（CF Access / CF Token 登录端点专用）。
     * Cf-Access-Authenticated-User-Email 等头在直连源站时可被任意伪造，唯一不可伪造的
     * 信号是：请求同时带有 CF 注入头 + socket 对端为本机/内网（cloudflared 与后端同机部署）。
     */
    fun isViaLocalCloudflared(call: ApplicationCall): Boolean {
        return call.request.header("CF-Connecting-IP") != null &&
            isTailscaleOrLocalIp(call.request.local.remoteAddress)
    }

    /**
     * 校验给定 IP 是否为 Tailscale CGNAT 地址段、本地回环或私有网段。
     */
    fun isTailscaleOrLocalIp(rawIp: String): Boolean {
        val clean = rawIp.removePrefix("/").trim()
        val ip = if (clean.contains(":")) {
            // IPv4 带端口 (1.2.3.4:5678) 或 IPv6
            if (clean.contains(".") && !clean.contains("::")) {
                clean.substringBefore(":")
            } else {
                clean
            }
        } else {
            clean
        }

        // 本地回路
        if (ip == "127.0.0.1" || ip == "::1" || ip == "localhost" || ip == "0:0:0:0:0:0:0:1") return true

        // Tailscale IPv4 地址段: 100.64.0.0/10 (100.64.0.0 ~ 100.127.255.255)
        if (ip.startsWith("100.")) {
            val parts = ip.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 64..127) {
                    return true
                }
            }
        }

        // Tailscale IPv6 ULA 前缀: fd7a:115c:a1e0::/48
        if (ip.startsWith("fd7a:115c:a1e0", ignoreCase = true)) {
            return true
        }

        // 局域网私有网段
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }

        return false
    }

    fun getClientIp(call: ApplicationCall): String {
        return call.request.header("CF-Connecting-IP")
            ?: call.request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: call.request.header("X-Real-IP")?.trim()
            ?: call.request.local.remoteAddress
    }

    /**
     * 可信客户端 IP(用于限流键 / 鉴权判定):
     * 只有当 TCP 直连对端是可信代理(本地回环 / 内网 / Tailscale)时,
     * 才采信 X-Forwarded-For、CF-Connecting-IP 等转发头;
     * 公网直连的请求一律忽略转发头,直接用对端地址 —— 否则攻击者可伪造
     * XFF 轮换身份绕过登录限流,甚至伪装成内网地址。
     */
    fun trustedClientIp(call: ApplicationCall): String {
        val peer = call.request.local.remoteAddress
        if (isTailscaleOrLocalIp(peer)) {
            return getClientIp(call)
        }
        return peer
    }

    /** TCP 直连对端是否为本机回环(cloudflared 等本地反代进程)。 */
    fun isLoopbackPeer(call: ApplicationCall): Boolean {
        val peer = call.request.local.remoteAddress.trim('[', ']')
        return peer == "127.0.0.1" || peer == "::1" || peer.equals("localhost", true) ||
            peer == "0:0:0:0:0:0:0:1"
    }

    /** 推导客户端访问该请求所使用的 Origin 基础前缀 (scheme://host[:port])。 */
    fun clientOrigin(call: ApplicationCall): String {
        val proto = call.request.header("X-Forwarded-Proto")
            ?: call.request.header("CF-Visitor")?.let { if (it.contains("https")) "https" else "http" }
            ?: if (call.request.local.scheme.isNotBlank()) call.request.local.scheme else "http"
        val host = call.request.header("X-Forwarded-Host")
            ?: call.request.header("Host")
            ?: "localhost:8080"
        return "$proto://$host"
    }
}
