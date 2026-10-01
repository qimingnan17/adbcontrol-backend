package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

/**
 * 文件中转条目(手机 ↔ 后端 ↔ Web)。
 *
 * - `direction = "up"`  手机上传给后端,Web 控制台可列表 / 下载
 * - `direction = "down"` Web 控制台上传,后端 MQTT 通知手机来拉取
 *
 * 注意:`storagePath` 属于服务端内部实现细节,不对外暴露(见 [DeviceFileDto] 不含该字段)。
 */
data class DeviceFileRow(
    val id: String,
    val deviceId: String,
    val direction: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storagePath: String,
    val status: String,
    val uploader: String,
    val createdAt: Long,
    val deliveredAt: Long,
)

/** 对外返回的文件元数据。 */
@Serializable
data class DeviceFileDto(
    val id: String,
    val deviceId: String,
    val direction: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val status: String,
    val uploader: String,
    val createdAt: Long,
    val deliveredAt: Long,
)

fun DeviceFileRow.toDto(): DeviceFileDto = DeviceFileDto(
    id = id,
    deviceId = deviceId,
    direction = direction,
    fileName = fileName,
    contentType = contentType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    status = status,
    uploader = uploader,
    createdAt = createdAt,
    deliveredAt = deliveredAt,
)

@Serializable
data class DeviceFileListResponse(
    val files: List<DeviceFileDto> = emptyList(),
)

@Serializable
data class DeviceFileResponse(
    val ok: Boolean,
    val file: DeviceFileDto? = null,
    val message: String? = null,
)

/**
 * 后端经 `push/{deviceId}` 下发给手机的文件可用通知载荷。
 *
 * 手机侧上传/down 下载的鉴权都是**无状态 HMAC**,不需要先向后端要票据:
 *   canonical = "file:{deviceId}:{up|down}:{expiresAt}"
 *   signature = Base64(HMAC-SHA256(sessionKey, canonical))
 * 其中 canonical 的拼法见 [com.adbcontrol.backend.service.DeviceFileStore.signingData],
 * 与 shared HmacSigner 同口径。上行时手机自行用 `up` 方向签好再发；
 * 下行时后端把签好的 expiresAt / signature 放进本载荷,手机直接回传即可。
 */
@Serializable
data class FileAvailableNotice(
    val event: String = "file_available",
    val fileId: String,
    val deviceId: String,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String,
    val sha256: String,
    val downloadUrl: String,
    val expiresAt: Long,
    val signature: String,
)
