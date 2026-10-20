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
    val protocolVersion: Int = com.adbcontrol.shared.ProtocolVersion.CURRENT,
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
    /**
     * APK 的 SHA-256(64 位小写 hex)。v2:可空 —— 旧清单行未登记哈希时为 null,
     * 被控端此时照常安装但记 warning;非空时下载后必须校验,不一致删包并上报
     * [UpdateErrorCodes.HASH_MISMATCH]。
     */
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    val forceUpdate: Boolean = false,
    val protocolVersion: Int = com.adbcontrol.shared.ProtocolVersion.CURRENT,
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
    val protocolVersion: Int = com.adbcontrol.shared.ProtocolVersion.CURRENT,
)

/** OTA 失败上报的错误码(写入 [UpdateResultReport.errorMsg])。 */
object UpdateErrorCodes {
    /** 下载完成后 SHA-256 与清单不一致:已删除安装包,未安装。 */
    const val HASH_MISMATCH = "UPDATE_HASH_MISMATCH"
}
