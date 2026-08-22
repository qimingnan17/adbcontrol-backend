package com.adbcontrol.shared.model

import kotlinx.serialization.Serializable

/**
 * 主控端 → 被控端 REMINDER 载荷(经 reminder/{deviceId} 下发)。
 *
 * 被控端收到后在任务栏弹出一条通知(独立于前台常驻通知),
 * [buttons] 中的每个条目渲染为一个通知操作按钮,文字由主控端自定义。
 * [expectAck]=true 时,用户点击任意按钮(或通知本体)即视为"签收",
 * 被控端发布 REMINDER_RESULT 回报,载荷为 [ReminderAck]。
 */
@Serializable
data class ReminderPayload(
    val title: String,
    val text: String = "",
    /** 通知操作按钮文字列表,最多 2 个;空列表表示无按钮(纯展示通知) */
    val buttons: List<String> = emptyList(),
    /** 是否要求受控端点击后回报签收状态 */
    val expectAck: Boolean = false,
    /** 关联任务 ID(定时任务场景);手动下发为 null */
    val taskId: Long? = null,
)

/**
 * 受控端 → 主控端 REMINDER_RESULT 载荷(发布到 result/{deviceId})。
 *
 * [refId] 为原 REMINDER 消息的 envelope id,主控端据此幂等去重。
 */
@Serializable
data class ReminderAck(
    val refId: String,
    val taskId: Long? = null,
    /** 被点击的按钮文字;点击通知本体(非按钮)固定为 OPEN_TOKEN */
    val buttonText: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 点击通知本体时上报的 buttonText 占位值 */
        const val OPEN_TOKEN = "(open)"
    }
}
