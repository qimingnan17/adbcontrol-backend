package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.backend.model.UpdateCheckResponse
import com.adbcontrol.backend.model.UpdatePriority
import com.adbcontrol.backend.model.UpdateResultReport
import com.adbcontrol.backend.model.VersionManifest
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 软件更新服务器(README 11.2)。
 *
 * - GET /update/check:查最新 manifest,返回 [UpdateCheckResponse](fullApkUrl + 可选 patchUrl + sha256 + priority)。
 * - POST /update/report:接收 [UpdateResultReport] 入库(开发期内存)。
 *
 * versions manifest 当前存内存(预置示例版本);TODO 持久化到 DB。
 * 差分包生成(bsdiff)先 stub:上传新 APK 时基于历史版本生成 patch 的逻辑留 TODO。
 */
class UpdateService(private val config: BackendConfig) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val versions = CopyOnWriteArrayList<VersionManifest>()
    private val reports = CopyOnWriteArrayList<UpdateResultReport>()

    init {
        // 预置示例版本(便于联调)。真实版本由管理流程上传(见 addVersion / TODO bsdiff)。
        versions += VersionManifest(
            versionCode = 10000,
            versionName = "1.0.0",
            channel = "stable",
            fullApkUrl = "${config.r2Endpoint}/${config.r2Bucket}/releases/controlled-1.0.0.apk",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 12_000_000,
            releaseNotes = "基线版本",
        )
        versions += VersionManifest(
            versionCode = 10100,
            versionName = "1.1.0",
            channel = "stable",
            fullApkUrl = "${config.r2Endpoint}/${config.r2Bucket}/releases/controlled-1.1.0.apk",
            patchUrl = "${config.r2Endpoint}/${config.r2Bucket}/releases/controlled-1.0.0-to-1.1.0.patch",
            patchFromVersionCode = 10000,
            patchToVersionCode = 10100,
            sha256 = "1111111111111111111111111111111111111111111111111111111111111111",
            sizeBytes = 1_500_000,
            releaseNotes = "1. 新增 Shizuku 桥接\n2. 修复若干问题",
            priority = UpdatePriority.NORMAL,
        )
        logger.info("Seeded {} version manifest(s)", versions.size)
    }

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
        logger.info(
            "Update report: deviceId={} version={} success={} error={} durationMs={}",
            report.deviceId, report.versionCode, report.success, report.errorMsg, report.durationMs
        )
        // TODO: 入库到 versions/分发统计表(需先建表)
    }

    /**
     * 新增版本 manifest(管理流程调用)。差分包生成(bsdiff)为 TODO stub。
     */
    fun addVersion(manifest: VersionManifest) {
        versions += manifest
        logger.info("Added version manifest: code={} name={} channel={}", manifest.versionCode, manifest.versionName, manifest.channel)
        // TODO: bsdiff 生成从历史版本到新版本的 patch 并上传 R2,填充 patchUrl/patchFrom/patchTo
    }
}
