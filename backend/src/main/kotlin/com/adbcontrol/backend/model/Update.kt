package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

/**
 * 软件更新协议(README 第十一章)。双通道:Play App Update + 自建更新服务器(差分包)。
 */

@Serializable
data class UpdateCheckRequest(
    val deviceId: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val channel: String = "stable",
)

@Serializable
data class UpdateCheckResponse(
    val hasUpdate: Boolean,
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val releaseNotes: String = "",
    val priority: UpdatePriority = UpdatePriority.NORMAL,
    val fullApkUrl: String? = null,
    val patchUrl: String? = null,
    val patchFromVersionCode: Int = 0,
    val patchToVersionCode: Int = 0,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val forceUpdate: Boolean = false,
)

enum class UpdatePriority { NORMAL, HIGH, CRITICAL }

@Serializable
data class UpdateResultReport(
    val deviceId: String,
    val versionCode: Int,
    val success: Boolean,
    val errorMsg: String? = null,
    val durationMs: Long = 0,
    val timestamp: Long,
)

/**
 * 版本 manifest(后端维护,内存态;TODO 持久化到 DB)。
 * [fromVersionCode]=0 表示这是一个全量包,无可升级差分。
 */
@Serializable
data class VersionManifest(
    val versionCode: Int,
    val versionName: String,
    val channel: String,
    val fullApkUrl: String,
    val patchUrl: String? = null,
    val patchFromVersionCode: Int = 0,
    val patchToVersionCode: Int = 0,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
    val priority: UpdatePriority = UpdatePriority.NORMAL,
)
