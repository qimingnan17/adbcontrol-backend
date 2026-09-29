package com.adbcontrol.backend.route

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 静态 Web 前端单页应用 (SPA) 托管。
 * 优先检查外部目录 (C:\adbcontrol\web 或 ./web)，若无则回退至 Classpath 内置资源 (static/)。
 */
fun Route.webAppRoutes() {
    val externalDir = listOf(File("C:/adbcontrol/web"), File("web"), File("../web")).firstOrNull { File(it, "index.html").exists() }

    fun getContentType(name: String): ContentType = when {
        name.endsWith(".html") -> ContentType.Text.Html.withCharset(StandardCharsets.UTF_8)
        name.endsWith(".js") -> ContentType.Application.JavaScript.withCharset(StandardCharsets.UTF_8)
        name.endsWith(".css") -> ContentType.Text.CSS.withCharset(StandardCharsets.UTF_8)
        name.endsWith(".json") -> ContentType.Application.Json.withCharset(StandardCharsets.UTF_8)
        name.endsWith(".png") -> ContentType.Image.PNG
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> ContentType.Image.JPEG
        name.endsWith(".svg") -> ContentType.Image.SVG
        name.endsWith(".ico") -> ContentType("image", "x-icon")
        name.endsWith(".woff") -> ContentType("font", "woff")
        name.endsWith(".woff2") -> ContentType("font", "woff2")
        name.endsWith(".ttf") -> ContentType("font", "ttf")
        else -> ContentType.Application.OctetStream
    }

    route("/{path...}") {
        handle {
            if (call.request.local.method != HttpMethod.Get && call.request.local.method != HttpMethod.Head) {
                return@handle
            }

            val relPath = call.parameters.getAll("path")?.joinToString("/")?.trim('/') ?: ""
            if (relPath.contains("..")) {
                call.respond(HttpStatusCode.Forbidden)
                return@handle
            }

            fun setCacheHeaders(name: String) {
                if (name.endsWith(".html")) {
                    call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Expires", "0")
                } else if (relPath.startsWith("assets/")) {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                }
            }

            // 1. 外部目录托管
            if (externalDir != null) {
                val file = if (relPath.isEmpty()) File(externalDir, "index.html") else File(externalDir, relPath)
                if (file.exists() && file.isFile) {
                    setCacheHeaders(file.name)
                    call.respondBytes(file.readBytes(), getContentType(file.name))
                    return@handle
                }
                if (!relPath.contains(".") || relPath.endsWith(".html")) {
                    val indexFile = File(externalDir, "index.html")
                    if (indexFile.exists()) {
                        setCacheHeaders("index.html")
                        call.respondBytes(indexFile.readBytes(), ContentType.Text.Html.withCharset(StandardCharsets.UTF_8))
                        return@handle
                    }
                }
            }

            // 2. Classpath 内置资源托管
            val resourcePath = if (relPath.isEmpty()) "static/index.html" else "static/$relPath"
            val cl = Thread.currentThread().contextClassLoader
            val stream = cl.getResourceAsStream(resourcePath)
            if (stream != null) {
                val bytes = stream.use { it.readBytes() }
                val name = resourcePath.substringAfterLast('/')
                setCacheHeaders(name)
                call.respondBytes(bytes, getContentType(name))
                return@handle
            }

            // SPA 路径回退 (如 /login, /dashboard, /devices, /settings 均回退至 index.html)
            if (!relPath.contains(".") || relPath.endsWith(".html")) {
                val indexStream = cl.getResourceAsStream("static/index.html")
                if (indexStream != null) {
                    val bytes = indexStream.use { it.readBytes() }
                    setCacheHeaders("index.html")
                    call.respondBytes(bytes, ContentType.Text.Html.withCharset(StandardCharsets.UTF_8))
                    return@handle
                }
            }

            call.respond(HttpStatusCode.NotFound)
        }
    }
}
