package com.adbcontrol.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 遥测载荷。被控端周期性或事件触发上报,主控端入库 + UI 展示。
 * 设计参见 README 第五章"设备遥测系统"。
 */

// ---------- 5.1 状态回报(status/{deviceId}) ----------
@Serializable
data class StatusReport(
    val deviceId: String,
    val online: Boolean = true,
    val battery: Int = 0,                      // 0-100
    val charging: Boolean = false,
    val network: NetworkType,
    // Bug #1:对齐 README §5.1 协议字段名 networkStrength(对外 JSON 协议)
    @SerialName("networkStrength")
    val signalDbm: Int = 0,
    val screenOn: Boolean = false,
    // Bug #1:对齐 README §5.1 协议字段名 foregroundPkg
    @SerialName("foregroundPkg")
    val foregroundPackage: String? = null,     // 当前前台 App 包名
    val timestamp: Long,
) {
    enum class NetworkType { WIFI, CELLULAR_2G, CELLULAR_3G, CELLULAR_4G, CELLULAR_5G, NONE }
}

// ---------- 5.2 电量(集成于 StatusReport,本类供历史归档用) ----------
@Serializable
data class BatterySnapshot(
    val deviceId: String,
    val level: Int,
    val charging: Boolean,
    val timestamp: Long,
)

// ---------- 5.3 位置(location/{deviceId}) ----------
@Serializable
data class LocationReport(
    val deviceId: String,
    // Bug #2:对齐 README §5.3 协议字段名 latitude/longitude
    @SerialName("latitude")
    val lat: Double,
    @SerialName("longitude")
    val lng: Double,
    val accuracy: Float,                       // 米
    val speed: Float = 0f,                     // m/s
    val provider: String,                      // "gps" / "network"
    /** 触发围栏事件名,无则 null */
    val fenceEvent: String? = null,
    val timestamp: Long,
)

// ---------- 5.4 应用行为(activity/{deviceId}) ----------
@Serializable
data class ActivityReport(
    val deviceId: String,
    val userId: String? = null,                 // Android 多用户场景
    val event: ActivityEvent,
    val pkg: String,
    val appName: String? = null,
    /** 前台持续时间(毫秒),APP_BACKGROUND 时填 */
    val durationMs: Long = 0,
    val timestamp: Long,
) {
    enum class ActivityEvent {
        APP_FOREGROUND,                        // 应用切到前台
        APP_BACKGROUND,                       // 应用切到后台
        APP_BLOCKED,                           // 命中禁用名单,被拦截
        NOTIFICATION_POSTED,                   // 收到通知
        NOTIFICATION_REMOVED,
    }
}

// ---------- 使用时长聚合(usage/{deviceId},整点上报,错峰) ----------
@Serializable
data class UsageReport(
    val deviceId: String,
    val userId: String,
    val date: String,                          // yyyy-MM-dd(设备本地时区)
    /** 单条 = 一台设备一天一用户的多 App 时长 */
    val items: List<UsageItem>,
    val timestamp: Long,
)

@Serializable
data class UsageItem(
    val pkg: String,
    val appName: String? = null,
    /** 官方图标 URL(R2 公开读,icons/{pkg}.png);未配置 R2 时为 null */
    val iconUrl: String? = null,
    val usageMinutes: Int,
)

// ---------- 5.5 设备健康(health/{deviceId}) ----------
@Serializable
data class HealthReport(
    val deviceId: String,
    val mqtt: Boolean,                         // MQTT 已连接
    val service: Boolean,                      // ControlledService 运行中
    val shizuku: ShizukuState,
    val root: Boolean,
    val accessibility: Boolean,
    val deviceAdmin: Boolean,
    val usageStats: Boolean,
    val notificationListener: Boolean,
    val batteryWhitelist: Boolean,
    val androidVersion: String,
    val appVersion: String,
    val timestamp: Long,
) {
    enum class ShizukuState { CONNECTED, NOT_AUTHORIZED, NOT_INSTALLED, UNSUPPORTED }
}
