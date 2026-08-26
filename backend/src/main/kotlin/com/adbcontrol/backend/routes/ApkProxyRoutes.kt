package com.adbcontrol.backend.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Serializable
private data class ProxyError(val error: String)

/**
 * 允许中转的 GitHub 域名白名单(SSRF 防护:只放行 Release 资产相关主机)。
 * UpdateRoutes 把 check 响应里的 GitHub 直链改写成 `/update/apk?url=` 时也用这份白名单;
 * 同时它天然拦下了公共加速代理(ghfast 等)回源我们中转链接造成的递归流量。
 */
internal val APK_PROXY_ALLOWED_HOSTS = setOf(
    "github.com",
    "raw.githubusercontent.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "codeload.github.com",
)

/** 透传给客户端的响应头(Content-Length 单独经 respondBytesWriter 传,避免与分块冲突)。 */
private val APK_PROXY_PASSTHROUGH_HEADERS = listOf(
    HttpHeaders.ContentRange,
    HttpHeaders.AcceptRanges,
)

/**
 * 中转并发闸:fly 免费机 256MB,大文件流式转发并发一多就被 cgroup OOM 杀掉整个进程
 * (2026-08-25 实测:82MB APK 中转期间 anon-rss 冲到 154MB 被 kill)。
 * 超限的请求立即 503 —— 客户端下载器有竞速换源机制,会转投其他源或稍后重试。
 */
private val apkProxyPermits = Semaphore(2)

private val logger = LoggerFactory.getLogger("ApkProxy")

/**
 * 上游(GitHub)专用客户端:
 * - 跟随跳转(github.com → objects/release-assets.githubusercontent.com);
 * - 读超时放宽到 120s(APK 数十 MB,境外→境内链路偶发慢速段);
 * - 不设总超时(callTimeout=0),流式转发耗时取决于两端速度。
 */
private val apkProxyClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()
}

/**
 * APK 下载中转(GET/HEAD /update/apk?url=<GitHub Release 链接>)。
 *
 * 背景:被控端在国内网络直连 GitHub Releases 或经公共加速代理下载 APK 时,
 * HEAD 探测可通过、但大文件持续传输常被中间设备掐断(EOFException /
 * StreamResetException),OTA 反复失败。而境外部署的后端拉 GitHub 稳定,
 * 且设备→后端的链路已被 /update/check 长期验证 —— 因此由后端流式中转字节。
 *
 * 安全约束(防开放代理/SSRF):
 * - 只允许 https;
 * - host 必须在 [APK_PROXY_ALLOWED_HOSTS] 白名单内;
 * - 不透传客户端请求头(仅 Range),不携带任何内部凭证。
 */
fun Route.updateApkProxyRoutes() {
    suspend fun ApplicationCall.proxy(headOnly: Boolean) {
        val raw = request.queryParameters["url"]
        if (raw.isNullOrBlank()) {
            respond(HttpStatusCode.BadRequest, ProxyError("missing ?url="))
            return
        }
        val parsed = raw.toHttpUrlOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.host.lowercase() !in APK_PROXY_ALLOWED_HOSTS) {
            logger.warn("rejected apk proxy target: {}", raw.take(300))
            respond(HttpStatusCode.BadRequest, ProxyError("url host not allowed"))
            return
        }

        if (!apkProxyPermits.tryAcquire()) {
            logger.warn("apk proxy busy, reject {}", parsed.host)
            respond(HttpStatusCode.ServiceUnavailable, ProxyError("proxy busy, retry later"))
            return
        }
        try {
            forwardApk(parsed, headOnly)
        } finally {
            apkProxyPermits.release()
        }
    }

    get("/update/apk") { call.proxy(headOnly = false) }
    head("/update/apk") { call.proxy(headOnly = true) }
}

/** 上游拉取并流式回写。调用方持有并发许可。 */
private suspend fun ApplicationCall.forwardApk(parsed: HttpUrl, headOnly: Boolean) {
    val builder = Request.Builder().url(parsed)
    if (headOnly) builder.head() else builder.get()
    // 断点续传:原样转发 Range,让客户端拿到 206 分段
    request.headers[HttpHeaders.Range]?.let { builder.header(HttpHeaders.Range, it) }

    logger.info("apk proxy {} {}", if (headOnly) "HEAD" else "GET", parsed.host)
    val upstream = runCatching { apkProxyClient.newCall(builder.build()).execute() }.getOrElse {
        logger.warn("apk proxy upstream connect failed: {}", it.message)
        respond(HttpStatusCode.BadGateway, ProxyError("upstream unreachable"))
        return
    }

    upstream.use { up ->
        if (!up.isSuccessful) {
            logger.warn("apk proxy upstream HTTP {}", up.code)
            respond(HttpStatusCode.BadGateway, ProxyError("upstream HTTP ${up.code}"))
            return
        }

        response.header(HttpHeaders.CacheControl, "no-store")
        response.header(
            HttpHeaders.ContentType,
            up.header(HttpHeaders.ContentType) ?: "application/vnd.android.package-archive",
        )
        APK_PROXY_PASSTHROUGH_HEADERS.forEach { h -> up.header(h)?.let { response.header(h, it) } }

        // HEAD 探测(App 端竞速靠它):只回头部。
        // 注意不能手动塞 Content-Length 头再 respond(OK)——空响应体配非 0 长度
        // 属协议冲突,会被网关判 502。用 OutgoingContent 声明长度由引擎生成头。
        if (headOnly) {
            respond(object : OutgoingContent.NoContent() {
                override val status =
                    if (up.code == 206) HttpStatusCode.PartialContent else HttpStatusCode.OK
                override val contentLength = up.header(HttpHeaders.ContentLength)?.toLongOrNull()
            })
            return
        }

        val body = up.body
        if (body == null) {
            respond(HttpStatusCode.BadGateway, ProxyError("empty upstream body"))
            return
        }

        var copied = 0L
        try {
            respondBytesWriter(
                status = HttpStatusCode.fromValue(up.code),
                contentLength = up.header(HttpHeaders.ContentLength)?.toLongOrNull(),
            ) {
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                withContext(Dispatchers.IO) {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        writeFully(buf, 0, n)
                        copied += n
                    }
                    flush()
                }
            }
            logger.info("apk proxy done: {} bytes for {}", copied, parsed.host)
        } catch (e: Exception) {
            // 客户端中途断开属正常(如 App 换源重试),记 warning 后原样上抛交 StatusPages 处理
            logger.warn("apk proxy aborted at {} bytes for {}: {}", copied, parsed.host, e.message)
            throw e
        }
    }
}
