package com.adbcontrol.shared.model

import kotlinx.serialization.Serializable

/**
 * 一条具体要执行的命令。对应"应用管理 / 截屏录屏 / 输入控制 / 文件传输 / Shell / 系统控制 / 应用时间管理"。
 */
@Serializable
data class Command(
    val category: CommandCategory,
    val action: String,            // 具体动作名，如 "tap" "install" "setBrightness"
    val params: Map<String, String> = emptyMap(),
    /** 是否要求被控端回传执行结果（回报可选） */
    val expectResult: Boolean = true,
)

@Serializable
enum class CommandCategory {
    APP,            // 应用管理 + 截屏/录屏
    INPUT,          // 点击/滑动/按键
    FILE,           // 文件传输 + Shell
    SYSTEM,         // 亮度/音量/锁屏等
    APP_TIME,       // 应用时间管理
}
