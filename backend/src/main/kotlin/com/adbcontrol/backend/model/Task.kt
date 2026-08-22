package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

/**
 * 任务创建/编辑请求体(替换旧的无类型 Map<String, Any>,kotlinx.serialization
 * 对 Any 值没有 serializer,运行时反序列化必失败)。
 *
 * 所有业务字段可空:POST 缺省走默认值;PUT 缺省保留库里原值(部分更新语义,
 * 避免前端只传 {enabled} 时把 deviceId/cronExpr/commandJson 清空)。
 * 字段名与前端 buildPayload() 约定一致(camelCase)。
 */
@Serializable
data class TaskRequest(
    val id: Long? = null,
    val deviceId: String? = null,
    val ruleType: String? = null,
    val cronExpr: String? = null,
    val commandJson: String? = null,
    val enabled: Boolean? = null,
)
