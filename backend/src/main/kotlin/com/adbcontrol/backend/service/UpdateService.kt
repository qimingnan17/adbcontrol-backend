package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.backend.model.UpdateCheckResponse
import com.adbcontrol.backend.model.UpdatePriority
import com.adbcontrol.backend.model.UpdateResultReport
import com.adbcontrol.backend.model.VersionManifest
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 软件更新服务器(README 11.2)。
 *
 * - GET /update/check:查最新 manifest,返回 [UpdateCheckResponse](fullApkUrl + 可选 patchUrl + sha256 + priority)。
 * - POST /update/report:接收 [UpdateResultReport](内存留存,便于排查)。
 * - POST /api/updates/publish(CI/Web):发布新版本 → 入库 app_version_manifest + 刷新内存缓存。
 *
 * 持久化:版本清单落 MySQL app_version_manifest,启动时加载,重启不丢已发布版本。
 * 安全约束:不预置任何示例版本 —— 硬编码的假 APK URL + 无效 sha256 会让被控端下载到
 * 不存在的包。没有任何真实清单时一律返回 hasUpdate=false。
 */
class UpdateService(
    private val config: BackendConfig,
    private val databaseService: DatabaseService,
    private val settingsService: SettingsService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 配置的对外服务地址(生成设备侧下载链接时优先于请求 Host 头,防 Host 注入)。 */
    val configuredServerUrl: String get() = config.serverUrl

    private val versions = CopyOnWriteArrayList<VersionManifest>()
    private val reports = CopyOnWriteArrayList<UpdateResultReport>()

    init {
        // 启动时从 DB 恢复版本清单(DB 不可达则空表启动,publish 时再重试入库)
        val restored = runCatching { databaseService.listVersionManifests() }.getOrElse { emptyList() }
        restored.forEach { row -> runCatching { versions += rowToManifest(row) } }
        if (restored.isNotEmpty()) {
            logger.info("Restored {} version manifest(s) from DB", restored.size)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rowToManifest(row: Map<String, Any?>): VersionManifest = VersionManifest(
        versionCode = (row["versionCode"] as Number).toInt(),
        versionName = row["versionName"] as String,
        channel = row["channel"] as String,
        fullApkUrl = row["fullApkUrl"] as String,
        patchUrl = row["patchUrl"] as? String,
        patchFromVersionCode = (row["patchFromVersionCode"] as Number).toInt(),
        patchToVersionCode = (row["patchToVersionCode"] as Number).toInt(),
        sha256 = (row["sha256"] as? String)?.let { normalizeSha256(it) },
        sizeBytes = (row["sizeBytes"] as Number).toLong(),
        releaseNotes = row["releaseNotes"] as? String ?: "",
        priority = runCatching { UpdatePriority.valueOf(row["priority"] as String) }
            .getOrDefault(UpdatePriority.NORMAL),
    )

    fun check(
        deviceId: String,
        currentVersionCode: Int,
        channel: String,
    ): UpdateCheckResponse {
        val latest = versions
            .filter { it.channel == channel && it.versionCode > currentVersionCode }
            .maxByOrNull { it.versionCode }

        if (latest == null) {
            return UpdateCheckResponse(hasUpdate = false)
        }

        // 是否存在从当前版本直连到 latest 的差分包
        val patch = versions.firstOrNull {
            it.patchUrl != null &&
                it.patchFromVersionCode == currentVersionCode &&
                it.patchToVersionCode == latest.versionCode
        }

        return UpdateCheckResponse(
            hasUpdate = true,
            latestVersionCode = latest.versionCode,
            latestVersionName = latest.versionName,
            releaseNotes = latest.releaseNotes,
            priority = latest.priority,
            fullApkUrl = latest.fullApkUrl,
            patchUrl = patch?.patchUrl,
            patchFromVersionCode = patch?.patchFromVersionCode ?: 0,
            patchToVersionCode = patch?.patchToVersionCode ?: 0,
            sha256 = latest.sha256?.let { normalizeSha256(it) } ?: run {
                // 旧行没有哈希:若是 GitHub 资产,后台补算(本次响应仍为 null,下次 check 即可带上)
                ensureSha256Async(latest)
                null
            },
            sizeBytes = latest.sizeBytes,
            forceUpdate = latest.priority == UpdatePriority.CRITICAL,
        )
    }

    // ---------------------------------------------------------------- v2: OTA 完整性

    /** 正在计算哈希的清单 key(versionCode/channel),防同一资产并发重复下载。 */
    private val hashing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hashExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ota-sha256").apply { isDaemon = true }
    }
    private val hashClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 管理员手工登记某版本的 sha256。返回 null 表示成功,否则为错误信息。 */
    fun setSha256(versionCode: Int, channel: String, sha256: String, repo: com.adbcontrol.backend.v2.V2Repository): String? {
        val sha = normalizeSha256(sha256) ?: return "sha256 必须是 64 位十六进制"
        val m = versions.firstOrNull { it.versionCode == versionCode && it.channel == channel } ?: return "版本不存在"
        if (!repo.updateManifestSha256(versionCode, channel, sha)) return "写入失败(数据库不可达)"
        versions.removeAll { it.versionCode == versionCode && it.channel == channel }
        versions += m.copy(sha256 = sha)
        return null
    }

    /** GitHub Release 资产缺哈希时后台下载并计算 SHA-256,回填 DB + 内存。 */
    fun ensureSha256Async(m: VersionManifest) {
        if (m.sha256 != null || !isGitHubAsset(m.fullApkUrl)) return
        val key = "${m.versionCode}/${m.channel}"
        if (!hashing.add(key)) return
        hashExecutor.execute {
            try {
                val sha = computeRemoteSha256(m.fullApkUrl) ?: return@execute
                databaseService.withConnection("ota.updateSha256", false) { c ->
                    c.prepareStatement("UPDATE app_version_manifest SET sha256 = ? WHERE version_code = ? AND channel = ? AND full_apk_url = ?").use { ps ->
                        ps.setString(1, sha); ps.setInt(2, m.versionCode); ps.setString(3, m.channel); ps.setString(4, m.fullApkUrl)
                        ps.executeUpdate() > 0
                    }
                }
                val current = versions.firstOrNull { it.versionCode == m.versionCode && it.channel == m.channel }
                if (current != null && current.fullApkUrl == m.fullApkUrl) {
                    versions.remove(current)
                    versions += current.copy(sha256 = sha)
                }
                logger.info("OTA sha256 computed for v{}/{}: {}", m.versionCode, m.channel, sha)
            } catch (t: Throwable) {
                logger.warn("OTA sha256 compute failed for v{}/{}: {}", m.versionCode, m.channel, t.message)
            } finally {
                hashing.remove(key)
            }
        }
    }

    private fun computeRemoteSha256(url: String): String? {
        val req = okhttp3.Request.Builder().url(url).get().build()
        hashClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.warn("OTA sha256 fetch {} -> HTTP {}", url, resp.code)
                return null
            }
            val body = resp.body ?: return null
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun report(report: UpdateResultReport) {
        reports += report
        // 只保留最近 500 条,防内存无限增长
        while (reports.size > 500) reports.removeAt(0)
        logger.info(
            "Update report: deviceId={} version={} success={} error={} durationMs={}",
            report.deviceId, report.versionCode, report.success, report.errorMsg, report.durationMs
        )
    }

    fun listReports(): List<UpdateResultReport> = reports.toList()

    companion object {
        private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

        /** 规范化为 64 位小写 hex;不合法返回 null。 */
        fun normalizeSha256(raw: String): String? = raw.trim().takeIf { SHA256_RE.matches(it) }?.lowercase()

        fun isGitHubAsset(url: String): Boolean = runCatching {
            java.net.URI(url).host?.lowercase() in setOf(
                "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
                "raw.githubusercontent.com", "codeload.github.com",
            )
        }.getOrDefault(false)
    }

    /**
     * CI/Web 发布入口:校验 → 入库(失败中止,避免重启丢版本)→ 更新内存缓存。
     * 返回 null 表示成功,否则为用户可读错误信息。
     */
    fun publish(manifest: VersionManifest): String? {
        if (manifest.versionCode <= 0) return "versionCode 必须 > 0"
        if (!manifest.fullApkUrl.startsWith("http://") && !manifest.fullApkUrl.startsWith("https://")) {
            return "fullApkUrl 必须是 http(s) URL"
        }
        val rawSha = manifest.sha256?.trim().orEmpty()
        val sha = if (rawSha.isEmpty()) null else normalizeSha256(rawSha) ?: return "sha256 必须是 64 位十六进制"
        if (sha == null && !isGitHubAsset(manifest.fullApkUrl)) {
            return "sha256 不能为空(仅 GitHub Release 资产可由后端自动计算)"
        }
        val normalized = manifest.copy(sha256 = sha)
        if (!databaseService.upsertVersionManifest(normalized)) return "清单入库失败(数据库不可达)"

        versions.removeAll { it.versionCode == manifest.versionCode && it.channel == manifest.channel }
        versions += normalized
        if (sha == null) ensureSha256Async(normalized)
        logger.info("Published version: code={} name={} channel={} url={}",
            manifest.versionCode, manifest.versionName, manifest.channel, manifest.fullApkUrl)
        return null
    }

    /**
     * 校验 CI 发布令牌(X-Admin-Token 头)。常数时间比较防时序侧信道;
     * 未配置 ADB_PM_TOKEN 时一律拒绝(发布通道关闭)。
     */
    fun verifyPublishToken(token: String?): Boolean {
        val expected = config.pmToken
        if (expected.isBlank() || token.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * 校验 CI 部署触发令牌(ci.upgrade_token),仅放行 /api/admin/upgrade。
     *
     * 与 [verifyPublishToken] 分开的理由:pmToken 能向全部已配对设备推送 OTA 安装包,
     * 攻击者拿到它即可远程下发任意 APK;而部署触发只会让主机拉取 GitHub 上最新构建。
     * 两者影响面差一个量级,不能共用同一把钥匙。
     *
     * 每次都实时读文件而非用启动时的 config:Web 面板轮换令牌后应立即生效,不必重启后端。
     */
    fun verifyUpgradeToken(token: String?): Boolean {
        val expected = runCatching { settingsService.getRawProperty("ci.upgrade_token") }
            .getOrDefault("")
            .ifBlank { config.ciUpgradeToken }
        if (expected.isBlank() || token.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }
}
