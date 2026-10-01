package com.adbcontrol.backend.service

import com.adbcontrol.backend.model.DeviceFileRow
import com.adbcontrol.backend.security.CryptoUtil
import com.adbcontrol.shared.security.HmacSigner
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件中转存储层(手机 ↔ 后端 ↔ Web)。
 *
 * 设计取舍:
 * - **为什么落本地磁盘而不是 R2**:后端目前没有 S3 客户端(见 build.gradle.kts),
 *   而 R2 凭据是下发给手机端直传的。中转层用后端本地磁盘做暂存,零新增依赖、
 *   零额外密钥,弱网下也不受对象存储延迟影响;需要长期归档时再挂 R2 迁移任务即可。
 * - **为什么保留内存索引**:本项目其它模块在 MySQL 不可达时会降级运行(内存用户/令牌)。
 *   文件元数据同样要有降级路径,否则 DB 抖动会让"已上传的文件"凭空消失、
 *   download-by-id 全部 404。内存索引与 DB 是"合并读",任一可用即能工作。
 *
 * 目录:环境变量 `ADB_FILES_DIR` > Windows 默认 `C:\adbcontrol\files` > 其它平台 `data/files`。
 */
class DeviceFileStore(private val db: DatabaseService) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** 磁盘根目录(不存在则创建)。 */
    val root: File = run {
        val fromEnv = System.getenv("ADB_FILES_DIR")?.trim().orEmpty()
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val path = when {
            fromEnv.isNotEmpty() -> fromEnv
            isWindows -> "C:\\adbcontrol\\files"
            else -> "data/files"
        }
        File(path).apply { mkdirs() }
    }

    /** 内存索引:DB 不可达时的降级来源,也是刚写入记录的读缓存。 */
    private val memoryIndex = ConcurrentHashMap<String, DeviceFileRow>()

    /**
     * 落盘并登记一条文件记录。
     *
     * @param uploader 上传者标识:设备上行时为设备 id,Web 下行时为管理员用户名。
     */
    fun save(
        deviceId: String,
        direction: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        uploader: String,
    ): DeviceFileRow {
        val id = UUID.randomUUID().toString().replace("-", "").take(32)
        val safeName = sanitizeFileName(fileName)
        val relative = "${sanitizeSegment(deviceId)}/$id${extensionOf(safeName)}"
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)

        val row = DeviceFileRow(
            id = id,
            deviceId = deviceId,
            direction = direction,
            fileName = safeName,
            contentType = contentType.ifBlank { "application/octet-stream" },
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            storagePath = relative,
            status = "ready",
            uploader = uploader,
            createdAt = System.currentTimeMillis(),
            deliveredAt = 0L,
        )
        memoryIndex[id] = row
        if (!db.insertDeviceFile(row)) {
            logger.warn("device_file row persisted to memory only (DB unavailable): id={}", id)
        }
        return row
    }

    /** 按 id 查记录:内存优先,未命中再落 DB 并回填缓存。 */
    fun find(id: String): DeviceFileRow? {
        memoryIndex[id]?.let { return it }
        val fromDb = db.findDeviceFile(id) ?: return null
        memoryIndex[id] = fromDb
        return fromDb
    }

    /** 列表:DB 与内存索引合并去重(任一可用即有结果),按创建时间倒序。 */
    fun list(deviceId: String?, limit: Int): List<DeviceFileRow> {
        val fromDb = db.listDeviceFiles(deviceId, limit)
        val fromMemory = memoryIndex.values.filter { deviceId.isNullOrBlank() || it.deviceId == deviceId }
        val merged = LinkedHashMap<String, DeviceFileRow>()
        // 内存索引里的记录更新(状态可能已被 delivered 更新),优先放内存版本
        fromMemory.forEach { merged[it.id] = it }
        fromDb.forEach { merged.putIfAbsent(it.id, it) }
        return merged.values
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 500))
    }

    /** 标记已投递给设备(内存 + DB 同步)。 */
    fun markDelivered(id: String, ts: Long) {
        memoryIndex.computeIfPresent(id) { _, row -> row.copy(status = "delivered", deliveredAt = ts) }
        db.markDeviceFileDelivered(id, ts)
    }

    /** 删除记录与磁盘文件。返回记录是否原本存在。 */
    fun delete(id: String): Boolean {
        val row = find(id) ?: return false
        memoryIndex.remove(id)
        db.deleteDeviceFile(id)
        runCatching {
            val f = File(root, row.storagePath)
            if (f.exists()) f.delete()
        }.onFailure { logger.warn("delete file blob failed for id={}: {}", id, it.message) }
        return true
    }

    /** 读取文件字节;文件已丢失返回 null。 */
    fun readBytes(row: DeviceFileRow): ByteArray? {
        val f = File(root, row.storagePath)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    companion object {
        /** 单文件上限:64MB。EMQX 单消息上限 1MB,文件必须走 HTTP 旁路,故不受其约束。 */
        const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024

        const val DIRECTION_UP = "up"
        const val DIRECTION_DOWN = "down"

        /**
         * 设备侧 HTTP 鉴权的待签字符串(与被控端 Android 端必须完全一致):
         *   `file:{deviceId}:{direction}:{expiresAt}`
         *
         * direction 见 [DIRECTION_UP] / [DIRECTION_DOWN];expiresAt 为毫秒时间戳。
         * 签名算法复用 shared [HmacSigner](HMAC-SHA256,Base64),sessionKey 来自配对签发。
         */
        fun signingData(deviceId: String, direction: String, expiresAt: Long): String =
            "file:$deviceId:$direction:$expiresAt"

        /** 校验设备签名与有效期。 */
        fun verifyDeviceSignature(
            deviceId: String,
            direction: String,
            expiresAt: Long,
            signature: String,
            sessionKey: String,
        ): Boolean {
            if (signature.isBlank()) return false
            // 复用协议既有的 ±5 分钟重放窗口:过期时刻偏离当前时间过多一律拒绝
            if (!HmacSigner.isWithinReplayWindow(expiresAt)) return false
            return HmacSigner.verify(signingData(deviceId, direction, expiresAt), sessionKey, signature)
        }

        /** 生成设备签名(后端下发下行文件通知时使用)。 */
        fun signForDevice(deviceId: String, direction: String, expiresAt: Long, sessionKey: String): String =
            HmacSigner.sign(signingData(deviceId, direction, expiresAt), sessionKey)

        /** 清洗文件名:去掉路径分隔符与控制字符,防目录穿越,保留可读性。 */
        fun sanitizeFileName(raw: String?): String {
            val cleaned = (raw ?: "")
                .replace('\\', '_')
                .replace('/', '_')
                .replace(Regex("[\\p{Cntrl}]"), "")
                .trim()
                .trimStart('.')
            val safe = if (cleaned.isEmpty()) "file" else cleaned
            return safe.take(180)
        }

        /** 目录段清洗(deviceId 落到路径上,必须防穿越)。 */
        fun sanitizeSegment(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "unknown" }

        /** 取扩展名(含点),仅允许字母数字,最长 10 字符。 */
        fun extensionOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.length - 1) return ""
            val ext = fileName.substring(dot + 1)
            return if (ext.length in 1..10 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
        }

        /** 复用 [CryptoUtil] 的实现,避免同一套摘要逻辑在两处各写一遍。 */
        fun sha256Hex(bytes: ByteArray): String = CryptoUtil.sha256Hex(bytes)
    }
}
