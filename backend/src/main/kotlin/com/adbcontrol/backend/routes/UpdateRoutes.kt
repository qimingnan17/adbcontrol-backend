package com.adbcontrol.backend.routes

import com.adbcontrol.backend.model.UpdateCheckResponse
import com.adbcontrol.backend.model.UpdateResultReport
import com.adbcontrol.backend.model.VersionManifest
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.UpdateService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

@Serializable
private data class Ack(val status: String, val message: String = "")

/**
 * 软件更新服务器(README 11.2)。
 * - GET /update/check?deviceId=&currentVersionCode=&channel=:返回 UpdateCheckResponse。
 * - POST /update/report:接收 UpdateResultReport 入库(内存留存)。
 * - POST /api/updates/publish(CI/Web 发布入口):X-Admin-Token 鉴权,发布新版本
 *   并向全部已配对设备广播 update_available 推送。
 */
fun Route.updateRoutes(service: UpdateService, commandBridge: DeviceCommandBridge) {
    get("/update/check") {
        val deviceId = call.request.queryParameters["deviceId"]
        val currentVersionCode = call.request.queryParameters["currentVersionCode"]?.toIntOrNull()
        val channel = call.request.queryParameters["channel"] ?: "stable"

        if (deviceId.isNullOrBlank() || currentVersionCode == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                Ack("error", "missing or invalid deviceId/currentVersionCode"),
            )
            return@get
        }
        // GitHub 直链改写为后端中转链接:被控端(尤其国内网络)直连/公共代理下载大文件
        // 常被中间设备掐断(HEAD 能通、传输中断),OTA 反复失败。旧版 App 对非 GitHub
        // 域名直接走普通 HTTP 下载,天然兼容 —— 无需升级即可受益。
        call.respond(call.withProxiedApkUrls(service, service.check(deviceId, currentVersionCode, channel)))
    }

    post("/update/report") {
        val report = call.receive<UpdateResultReport>()
        service.report(report)
        call.respond(Ack("ok"))
    }

    post("/api/admin/upgrade") {
        if (!service.verifyPublishToken(call.request.headers["X-Admin-Token"])) {
            call.respond(HttpStatusCode.Unauthorized, Ack("error", "invalid or missing X-Admin-Token"))
            return@post
        }
        val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")
        if (isWindows) {
            runCatching {
                ProcessBuilder("schtasks", "/run", "/tn", "AdbControlAutoUpdate").start()
            }.onFailure {
                call.respond(HttpStatusCode.InternalServerError, Ack("error", "failed to trigger task: ${it.message}"))
                return@post
            }
        }
        call.respond(Ack("ok", "upgrade scheduled task triggered"))
    }

    post("/api/updates/publish") {
        // 令牌校验放在 body 解析前,避免未授权请求消耗解析资源
        if (!service.verifyPublishToken(call.request.headers["X-Admin-Token"])) {
            call.respond(HttpStatusCode.Unauthorized, Ack("error", "invalid or missing X-Admin-Token (or ADB_PM_TOKEN not configured)"))
            return@post
        }
        val manifest = runCatching { call.receive<VersionManifest>() }.getOrElse {
            // receive 失败时给一条更友好的诊断(原始 body 截断)
            val raw = runCatching { call.receiveText() }.getOrDefault("")
            return@post call.respond(
                HttpStatusCode.BadRequest,
                Ack("error", "malformed VersionManifest JSON: ${raw.take(200)}"),
            )
        }
        val err = service.publish(manifest)
        if (err != null) {
            call.respond(HttpStatusCode.BadRequest, Ack("error", err))
            return@post
        }

        // 广播更新通知到所有有配对会话的设备(best-effort,失败不影响发布结果)
        val payload = buildJsonObject {
            put("event", "update_available")
            put("versionCode", manifest.versionCode)
            put("versionName", manifest.versionName)
            put("releaseNotes", manifest.releaseNotes)
            put("timestamp", System.currentTimeMillis())
        }
        val notified = runCatching { commandBridge.broadcastPush(payload.toString()) }
            .onFailure { call.application.environment.log.warn("broadcastPush failed: {}", it.message) }
            .getOrDefault(0)

        call.respond(buildJsonObject {
            put("ok", true)
            put("versionCode", manifest.versionCode)
            put("notified", notified)
        })
    }
}

/** 把响应中的 GitHub 直链(full/patch)改写为 `{本机}/update/apk?url=` 中转链接。 */
private fun ApplicationCall.withProxiedApkUrls(service: UpdateService, resp: UpdateCheckResponse): UpdateCheckResponse =
    resp.copy(
        fullApkUrl = resp.fullApkUrl?.let { proxiedIfGitHub(service, it) },
        patchUrl = resp.patchUrl?.let { proxiedIfGitHub(service, it) },
    )

private fun ApplicationCall.proxiedIfGitHub(service: UpdateService, url: String): String {
    val host = runCatching { url.toHttpUrlOrNull()?.host?.lowercase() }
        .getOrNull() ?: return url
    if (host !in APK_PROXY_ALLOWED_HOSTS) return url
    return "${selfOriginPrefix(service)}/update/apk?url=" + URLEncoder.encode(url, "UTF-8")
}

/**
 * 本服务对外可达的前缀(协议+主机[+端口]):
 * - 手动读 Host 头与 X-Forwarded-Proto(fly.dev 等反代会带上);
 * - Host 头按规范只在非默认端口时带端口,原样使用即可:
 *   fly.dev → https://adbcontrol-backend.fly.dev,本地开发 → http://localhost:8080。
 * - HTTP/1.1 起 Host 为必带头,缺失视为非法请求直接报错兜底。
 */
private fun ApplicationCall.selfOriginPrefix(service: UpdateService): String {
    // 优先用配置的 server.url:设备直连源站时 Host 头可被攻击者注入,让设备拿到
    // 指向第三方的 /update/apk 前缀(下载后虽有 manifest sha256 兜底,仍属加固点)。
    // 未配置(仍是 example.com 占位)时回退请求头推导。
    val configured = service.configuredServerUrl.trim().trimEnd('/')
    if (configured.isNotBlank() && !configured.contains("example.com")) return configured
    val scheme = request.headers["X-Forwarded-Proto"]
        ?.substringBefore(",")?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http"
    val host = request.headers[HttpHeaders.Host]
        ?.trim()?.takeIf { it.isNotBlank() }
        ?: error("missing Host header")
    return "$scheme://$host"
}
