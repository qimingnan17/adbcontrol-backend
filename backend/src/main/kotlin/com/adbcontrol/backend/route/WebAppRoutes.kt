package com.adbcontrol.backend.route

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/**
 * 静态 Web 前端单页应用 (SPA) 托管。
 * 优先检查外部目录 (C:\adbcontrol\web 或 ./web)，若无则回退至 Classpath 内置资源 (static/)。
 */
fun Route.webAppRoutes() {
    val externalDir = listOf(File("C:/adbcontrol/web"), File("web"), File("../web")).firstOrNull { File(it, "index.html").exists() }

    fun getContentType(name: String): ContentType = when {
        name.endsWith(".html") -> ContentType.Text.Html
        name.endsWith(".js") -> ContentType.Application.JavaScript
        name.endsWith(".css") -> ContentType.Text.CSS
        name.endsWith(".json") -> ContentType.Application.Json
        name.endsWith(".png") -> ContentType.Image.PNG
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> ContentType.Image.JPEG
        name.endsWith(".svg") -> ContentType.Image.SVG
        name.endsWith(".ico") -> ContentType("image", "x-icon")
        name.endsWith(".woff") -> ContentType("font", "woff")
        name.endsWith(".woff2") -> ContentType("font", "woff2")
        name.endsWith(".ttf") -> ContentType("font", "ttf")
        else -> ContentType.Application.OctetStream
    }

    get("/{path...}") {
        val relPath = call.parameters.getAll("path")?.joinToString("/")?.trim('/') ?: ""
        if (relPath.contains("..")) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }

        // 1. 外部目录托管
        if (externalDir != null) {
            val file = if (relPath.isEmpty()) File(externalDir, "index.html") else File(externalDir, relPath)
            if (file.exists() && file.isFile) {
                call.respondBytes(file.readBytes(), getContentType(file.name))
                return@get
            }
            if (!relPath.contains(".") || relPath.endsWith(".html")) {
                val indexFile = File(externalDir, "index.html")
                if (indexFile.exists()) {
                    call.respondBytes(indexFile.readBytes(), ContentType.Text.Html)
                    return@get
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
            call.respondBytes(bytes, getContentType(name))
            return@get
        }

        // SPA 路径回退 (如 /login, /dashboard, /devices, /settings 均回退至 index.html)
        if (!relPath.contains(".") || relPath.endsWith(".html")) {
            val indexStream = cl.getResourceAsStream("static/index.html")
            if (indexStream != null) {
                val bytes = indexStream.use { it.readBytes() }
                call.respondBytes(bytes, ContentType.Text.Html)
                return@get
            }
        }

        call.respond(HttpStatusCode.NotFound)
    }
}
