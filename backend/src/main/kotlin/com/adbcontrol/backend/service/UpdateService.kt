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
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val versions = CopyOnWriteArrayList<VersionManifest>()
    private val reports = CopyOnWriteArrayList<UpdateResultReport>()

    init {
        // 启动时从 DB 恢复版本清单(DB 不可达则空表启动,publish 时再重试入库)
        val restored = runCatching { databaseService.listVersionManifests() }.getOrElse { emptyList() }
        restored.forEach { row -> versions += rowToManifest(row) }
        if (restored.isNotEmpty()) {
            logger.info("Restored {} version manifest(s) from DB", restored.size)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rowToManifest(row: Map<String, Any>): VersionManifest = VersionManifest(
        versionCode = (row["versionCode"] as Number).toInt(),
        versionName = row["versionName"] as String,
        channel = row["channel"] as String,
        fullApkUrl = row["fullApkUrl"] as String,
        patchUrl = row["patchUrl"] as? String,
        patchFromVersionCode = (row["patchFromVersionCode"] as Number).toInt(),
        patchToVersionCode = (row["patchToVersionCode"] as Number).toInt(),
        sha256 = row["sha256"] as String,
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
            sha256 = latest.sha256,
            sizeBytes = latest.sizeBytes,
            forceUpdate = latest.priority == UpdatePriority.CRITICAL,
        )
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

    /**
     * CI/Web 发布入口:校验 → 入库(失败中止,避免重启丢版本)→ 更新内存缓存。
     * 返回 null 表示成功,否则为用户可读错误信息。
     */
    fun publish(manifest: VersionManifest): String? {
        if (manifest.versionCode <= 0) return "versionCode 必须 > 0"
        if (!manifest.fullApkUrl.startsWith("http://") && !manifest.fullApkUrl.startsWith("https://")) {
            return "fullApkUrl 必须是 http(s) URL"
        }
        if (manifest.sha256.isBlank()) return "sha256 不能为空"
        if (!databaseService.upsertVersionManifest(manifest)) return "清单入库失败(数据库不可达)"

        versions.removeAll { it.versionCode == manifest.versionCode && it.channel == manifest.channel }
        versions += manifest
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
}
