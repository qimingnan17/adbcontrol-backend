package com.adbcontrol.backend.security

import io.ktor.server.application.*
import io.ktor.server.request.*

object NetworkSecurity {
    /**
     * 判断当前调用是否来自 Tailscale 或本地内网。
     * 若流量经过 Cloudflare 隧道 (CF-Connecting-IP / CF-Ray / CF-Visitor)，坚决视为公网请求，返回 false。
     */
    fun isLocalOrTailscale(call: ApplicationCall): Boolean {
        // 1. 如果请求带有 Cloudflare 隧道特征头，坚决视为公网流量
        if (call.request.header("CF-Connecting-IP") != null ||
            call.request.header("CF-Ray") != null ||
            call.request.header("CF-Visitor") != null) {
            return false
        }

        // 2. 取出实际发起端 IP
        val ip = call.request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: call.request.header("X-Real-IP")?.trim()
            ?: call.request.local.remoteAddress

        return isTailscaleOrLocalIp(ip)
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
}
