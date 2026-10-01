package com.adbcontrol.backend.routes

import com.adbcontrol.backend.model.DeviceFileListResponse
import com.adbcontrol.backend.model.DeviceFileResponse
import com.adbcontrol.backend.model.FileAvailableNotice
import com.adbcontrol.backend.model.SettingsOperationResponse
import com.adbcontrol.backend.model.toDto
import com.adbcontrol.backend.plugin.UserSession
import com.adbcontrol.backend.security.NetworkSecurity
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.DeviceFileStore
import com.adbcontrol.backend.service.PairingService
import com.adbcontrol.backend.service.SettingsService
import com.adbcontrol.backend.service.DatabaseService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream

private val fileJson = Json { encodeDefaults = true; explicitNulls = false }

/** 下行文件下载票据有效期:10 分钟。 */
private const val DOWN_TICKET_TTL_MS = 10L * 60 * 1000

/** Content-Length 缺失时的读取上限保护(与 DeviceFileStore.MAX_FILE_BYTES 一致)。 */
private class FileTooLargeException : RuntimeException()

private fun ApplicationCall.isAdminSession(): Boolean {
    val sess = sessions.get<UserSession>() ?: return false
    return sess.role == "admin"
}

private fun ApplicationCall.adminUsername(): String =
    sessions.get<UserSession>()?.username ?: "admin"

/** 读取设备鉴权头(缺任一项返回 null)。 */
private data class DeviceHeaders(val deviceId: String, val expiresAt: Long, val signature: String)

private fun ApplicationCall.deviceHeaders(): DeviceHeaders? {
    val deviceId = request.headers["X-Device-Id"]?.trim().orEmpty()
    val expiresAt = request.headers["X-Device-Expires"]?.trim()?.toLongOrNull() ?: 0L
    val signature = request.headers["X-Device-Signature"]?.trim().orEmpty()
    if (deviceId.isEmpty() || expiresAt == 0L || signature.isEmpty()) return null
    return DeviceHeaders(deviceId, expiresAt, signature)
}

/**
 * 校验设备 HMAC 鉴权头并返回 deviceId;失败时已自行应答并返回 null。
 *
 * 待签字符串见 [DeviceFileStore.signingData];签名算法与 MQTT 信封完全一致
 * (HMAC-SHA256 + 配对签发的 sessionKey),因此手机端无需新增任何长期密钥。
 */
private suspend fun ApplicationCall.requireDevice(
    direction: String,
    pairingService: PairingService,
): String? {
    val hdrs = deviceHeaders() ?: run {
        respond(
            HttpStatusCode.Unauthorized,
            SettingsOperationResponse(
                ok = false,
                message = "缺少设备鉴权头（X-Device-Id / X-Device-Expires / X-Device-Signature）"
            )
        )
        return null
    }
    val sessionKey = pairingService.sessionKeyFor(hdrs.deviceId) ?: run {
        respond(HttpStatusCode.Forbidden, SettingsOperationResponse(ok = false, message = "设备未配对或配对会话已失效"))
        return null
    }
    if (!DeviceFileStore.verifyDeviceSignature(
            hdrs.deviceId, direction, hdrs.expiresAt, hdrs.signature, sessionKey,
        )
    ) {
        respond(HttpStatusCode.Unauthorized, SettingsOperationResponse(ok = false, message = "设备签名校验失败"))
        return null
    }
    return hdrs.deviceId
}

/** 读取原始请求体并施加大小上限;超限/读失败时已自行应答并返回 null。 */
private suspend fun ApplicationCall.readBodyCapped(max: Long): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > max) {
        respond(
            HttpStatusCode.PayloadTooLarge,
            SettingsOperationResponse(ok = false, message = "文件超过上限 ${max / (1024 * 1024)}MB")
        )
        return null
    }
    return try {
        receiveStream().use { readFully(it, max) }
    } catch (e: FileTooLargeException) {
        respond(
            HttpStatusCode.PayloadTooLarge,
            SettingsOperationResponse(ok = false, message = "文件超过上限 ${max / (1024 * 1024)}MB")
        )
        null
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "读取上传内容失败: ${e.message}"))
        null
    }
}

private fun readFully(input: InputStream, max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw FileTooLargeException()
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 兜底的 Content-Type 解析(非法值不能让它把整个下载打成 500)。 */
private fun parseContentType(raw: String): ContentType =
    runCatching { ContentType.parse(raw) }.getOrDefault(ContentType.Application.OctetStream)

/** 拼 Content-Disposition,非 ASCII 文件名走 RFC 5987。 */
private fun contentDisposition(fileName: String): String {
    val ascii = fileName.filter { it.code in 32..126 && it != '"' && it != '\\' }.ifBlank { "download" }
    val encoded = java.net.URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

/**
 * 文件中转路由(手机 ↔ 后端 ↔ Web)。
 *
 * 两个方向都经后端中转,而不是让两端直连对象存储:
 * - 上行 `POST /api/files/device-upload`:手机 HMAC 鉴权后把字节流交给后端落盘,Web 可下载;
 * - 下行 `POST /api/files/send`:Web 管理员上传,后端落盘后经 `push/{deviceId}` 通知手机,
 *   手机凭通知里的临时签名拉取 `GET /api/files/{id}/download`。
 *
 * 鉴权:设备侧一律用配对 sessionKey 的 HMAC 签名(与 MQTT 信封同口径);Web 侧用管理员会话。
 * 本路由**不能**挂在 authenticate("auth-session") 内,否则设备请求会被会话中间件拦掉。
 */
fun Route.fileTransferRoutes(
    settingsService: SettingsService,
    databaseService: DatabaseService,
    pairingService: PairingService,
    commandBridge: DeviceCommandBridge,
) {
    val store = DeviceFileStore(databaseService)

    // ---------- 设备上行:手机 → 后端 ----------
    post("/api/files/device-upload") {
        val deviceId = call.requireDevice(DeviceFileStore.DIRECTION_UP, pairingService) ?: return@post
        val fileName = DeviceFileStore.sanitizeFileName(call.request.queryParameters["fileName"])
        val contentType = call.request.queryParameters["contentType"]?.trim()?.take(128)?.ifBlank { null }
            ?: "application/octet-stream"
        val bytes = call.readBodyCapped(DeviceFileStore.MAX_FILE_BYTES) ?: return@post
        val row = store.save(
            deviceId = deviceId,
            direction = DeviceFileStore.DIRECTION_UP,
            fileName = fileName,
            contentType = contentType,
            bytes = bytes,
            uploader = deviceId,
        )
        call.respond(
            DeviceFileResponse(
                ok = true,
                file = row.toDto(),
                message = "上传成功（${row.sizeBytes} 字节）",
            )
        )
    }

    // ---------- Web 下行:控制台 → 后端 → 手机 ----------
    post("/api/files/send") {
        if (!call.isAdminSession()) {
            call.respond(HttpStatusCode.Forbidden, SettingsOperationResponse(ok = false, message = "需要管理员会话"))
            return@post
        }
        val deviceId = call.request.queryParameters["deviceId"]?.trim().orEmpty()
        if (deviceId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "缺少 deviceId"))
            return@post
        }
        val sessionKey = pairingService.sessionKeyFor(deviceId)
        if (sessionKey == null) {
            call.respond(HttpStatusCode.NotFound, SettingsOperationResponse(ok = false, message = "设备未配对或会话已失效，无法下发"))
            return@post
        }
        val fileName = DeviceFileStore.sanitizeFileName(call.request.queryParameters["fileName"])
        val contentType = call.request.queryParameters["contentType"]?.trim()?.take(128)?.ifBlank { null }
            ?: "application/octet-stream"
        val bytes = call.readBodyCapped(DeviceFileStore.MAX_FILE_BYTES) ?: return@post

        val row = store.save(
            deviceId = deviceId,
            direction = DeviceFileStore.DIRECTION_DOWN,
            fileName = fileName,
            contentType = contentType,
            bytes = bytes,
            uploader = call.adminUsername(),
        )

        // 生成短期下载票据并复用 push/{deviceId} 通道通知设备来拉取
        val expiresAt = System.currentTimeMillis() + DOWN_TICKET_TTL_MS
        val signature = DeviceFileStore.signForDevice(
            deviceId, DeviceFileStore.DIRECTION_DOWN, expiresAt, sessionKey,
        )
        val baseUrl = settingsService.getRawProperty("server.url")
            .ifBlank { NetworkSecurity.clientOrigin(call) }
            .trimEnd('/')
        val notice = FileAvailableNotice(
            fileId = row.id,
            deviceId = deviceId,
            fileName = row.fileName,
            sizeBytes = row.sizeBytes,
            contentType = row.contentType,
            sha256 = row.sha256,
            downloadUrl = "$baseUrl/api/files/${row.id}/download",
            expiresAt = expiresAt,
            signature = signature,
        )
        val pushed = commandBridge.pushToDevice(
            deviceId, fileJson.encodeToString(FileAvailableNotice.serializer(), notice),
        )
        call.respond(
            DeviceFileResponse(
                ok = true,
                file = row.toDto(),
                message = if (pushed) "已上传并通知设备拉取" else "已上传，但通知设备失败（设备可能离线，可稍后重发）",
            )
        )
    }

    // ---------- Web:文件列表 ----------
    get("/api/files") {
        if (!call.isAdminSession()) {
            call.respond(HttpStatusCode.Forbidden, SettingsOperationResponse(ok = false, message = "需要管理员会话"))
            return@get
        }
        val deviceId = call.request.queryParameters["deviceId"]?.trim()?.ifBlank { null }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        call.respond(DeviceFileListResponse(store.list(deviceId, limit).map { it.toDto() }))
    }

    // ---------- 下载:Web 管理员 或 持有 down 票据的设备 ----------
    get("/api/files/{fileId}/download") {
        val fileId = call.parameters["fileId"]?.trim().orEmpty()
        if (fileId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "缺少 fileId"))
            return@get
        }
        val row = store.find(fileId) ?: run {
            call.respond(HttpStatusCode.NotFound, SettingsOperationResponse(ok = false, message = "文件不存在或已删除"))
            return@get
        }

        val isAdmin = call.isAdminSession()
        var authorized = isAdmin
        if (!authorized) {
            val hdrs = call.deviceHeaders()
            if (hdrs == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    SettingsOperationResponse(ok = false, message = "需要管理员会话或设备签名鉴权头")
                )
                return@get
            }
            // 设备只能拉取发给自己的文件,且只认 down 票据
            if (hdrs.deviceId != row.deviceId) {
                call.respond(HttpStatusCode.Forbidden, SettingsOperationResponse(ok = false, message = "无权访问该文件"))
                return@get
            }
            val sessionKey = pairingService.sessionKeyFor(hdrs.deviceId)
            authorized = sessionKey != null && DeviceFileStore.verifyDeviceSignature(
                hdrs.deviceId, DeviceFileStore.DIRECTION_DOWN, hdrs.expiresAt, hdrs.signature, sessionKey,
            )
            if (!authorized) {
                call.respond(HttpStatusCode.Unauthorized, SettingsOperationResponse(ok = false, message = "设备签名校验失败或票据已过期"))
                return@get
            }
        }

        val bytes = store.readBytes(row) ?: run {
            call.respond(HttpStatusCode.NotFound, SettingsOperationResponse(ok = false, message = "文件内容已丢失"))
            return@get
        }

        // 设备成功拉取下行文件即视为投递完成
        if (!isAdmin && row.direction == DeviceFileStore.DIRECTION_DOWN) {
            store.markDelivered(row.id, System.currentTimeMillis())
        }

        call.response.header(HttpHeaders.ContentDisposition, contentDisposition(row.fileName))
        call.response.header("X-File-Sha256", row.sha256)
        call.respondBytes(bytes, parseContentType(row.contentType))
    }

    // ---------- Web:删除 ----------
    delete("/api/files/{fileId}") {
        if (!call.isAdminSession()) {
            call.respond(HttpStatusCode.Forbidden, SettingsOperationResponse(ok = false, message = "需要管理员会话"))
            return@delete
        }
        val fileId = call.parameters["fileId"]?.trim().orEmpty()
        if (fileId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "缺少 fileId"))
            return@delete
        }
        val existed = store.delete(fileId)
        if (existed) {
            call.respond(SettingsOperationResponse(ok = true, message = "文件已删除"))
        } else {
            call.respond(HttpStatusCode.NotFound, SettingsOperationResponse(ok = false, message = "文件不存在"))
        }
    }
}
