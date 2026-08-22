package com.adbcontrol.backend.route

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把运行时构造的 Map/List/基本类型递归转为 JsonElement。
 *
 * kotlinx.serialization 无法为 Any 混合值类型的 Map(如 Map<String, Any>)推导
 * serializer,直接 respond 会 500(Application.kt 里 /api health 就因此显式声明过
 * Map<String, String>)。服务层聚合查询结果仍以 Map 返回,路由层统一经此转换后
 * 再 respond,字段名与前端契约保持不变。
 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
