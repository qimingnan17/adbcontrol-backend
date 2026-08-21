package com.adbcontrol.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 软件更新协议。设计参见 README 第十一章。
 * 双通道:Play App Update(主) + 自建更新服务器(备,支持差分包 + 静默安装)。
 */

/** 客户端检查更新请求 */
@Serializable
data class UpdateCheckRequest(
    val deviceId: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val channel: String = "stable",            // stable / beta / internal
)

/** 服务器响应 */
@Serializable
data class UpdateCheckResponse(
    val hasUpdate: Boolean,
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val releaseNotes: String = "",
    /** 优先级:NORMAL / HIGH / CRITICAL,CRITICAL 强制更新 */
    val priority: UpdatePriority = UpdatePriority.NORMAL,
    /** 全量 APK 下载 URL(R2) */
    val fullApkUrl: String? = null,
    /** 差分包下载 URL(从 fromVersionCode 升到 toVersionCode),null 表示无差分 */
    val patchUrl: String? = null,
    val patchFromVersionCode: Int = 0,
    val patchToVersionCode: Int = 0,
    /** sha256 校验,下载后必须验 */
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val forceUpdate: Boolean = false,
) {
    enum class UpdatePriority { NORMAL, HIGH, CRITICAL }
}

/** 上报安装结果,便于服务器统计分发情况 */
@Serializable
data class UpdateResultReport(
    val deviceId: String,
    val versionCode: Int,
    val success: Boolean,
    val errorMsg: String? = null,
    val durationMs: Long = 0,
    val timestamp: Long,
)
