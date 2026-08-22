package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.TaskRequest
import com.adbcontrol.backend.service.DatabaseService
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.PairingService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun Route.adminRoutes(db: DatabaseService, pairing: PairingService, commandBridge: DeviceCommandBridge) {
    authenticate("auth-session") {
        get("/api/devices") {
            call.respond(mapOf("items" to db.listDevicesWithStatus()))
        }

        get("/api/devices/{deviceId}") {
            val id = call.parameters["deviceId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("message" to "missing deviceId")
            )
            call.respond(buildJsonObject {
                put("overview", (db.getDeviceOverview(id) ?: emptyMap<String, Any?>()).toJsonElement())
            })
        }

        get("/api/devices/{deviceId}/commands") {
            val id = call.parameters["deviceId"]!!
            // limit 是 query 参数,不是路由参数
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(buildJsonObject {
                put("items", db.listRecentCommands(id, limit).toJsonElement())
            })
        }

        post("/api/devices/{deviceId}/commands") {
            val id = call.parameters["deviceId"]!!
            // 按 JSON 对象解析,交由 DeviceCommandBridge 映射成 signed envelope。
            // 旧实现把裸 {'commandId','type',...} JSON 直发 adb/dev/{id}/cmd/in,
            // 被控端订阅的 cmd/{deviceId} 收不到、MessageCodec 也无签名可验 → 全链路静默失效。
            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            val cmdType = body?.get("type")?.jsonPrimitive?.contentOrNull
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("message" to "missing type")
                )
            val args = (body["args"] as? JsonObject)
                ?.entries?.mapNotNull { (k, v) ->
                    runCatching { k to v.jsonPrimitive.content }.getOrNull()
                }?.toMap()
                ?: emptyMap()
            when (val r = commandBridge.dispatch(id, cmdType, args)) {
                is DeviceCommandBridge.DispatchResult.Ok ->
                    call.respond(buildJsonObject {
                        put("commandId", r.commandId)
                        put("status", r.emqxStatus)
                        put("emqx", r.emqxBody.toJsonElement())
                    })
                is DeviceCommandBridge.DispatchResult.UnsupportedType ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "unsupported command type: ${r.type}")
                    )
                is DeviceCommandBridge.DispatchResult.MissingArg ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "missing args.${r.field}")
                    )
                DeviceCommandBridge.DispatchResult.SessionMissing ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("message" to "设备未建立配对会话(sessionKey 不在后端),请重新配对")
                    )
            }
        }

        get("/api/tasks") {
            // deviceId 是 query 参数(?deviceId=xxx),不是路由参数
            val deviceId = call.request.queryParameters["deviceId"]
            call.respond(buildJsonObject {
                put("items", db.listTasks(deviceId).toJsonElement())
            })
        }

        post("/api/tasks") {
            val b = call.receive<TaskRequest>()
            val id = db.upsertTask(b)
                ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "task create failed"))
            call.respond(mapOf("id" to id))
        }

        put("/api/tasks/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "invalid task id"))
            val b = call.receive<TaskRequest>()
            db.upsertTask(b.copy(id = id))
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("message" to "task not found"))
            call.respond(mapOf("ok" to true))
        }

        delete("/api/tasks/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "invalid task id"))
            val n = db.deleteTask(id)
            call.respond(mapOf("deleted" to n))
        }

        get("/api/pairing-tokens") {
            // includeUsed 是 query 参数,不是路由参数
            val all = call.request.queryParameters["includeUsed"] == "1"
            call.respond(buildJsonObject {
                put("items", pairing.listPairingTokens(all).toJsonElement())
            })
        }

        post("/api/pairing-tokens") {
            // Map<String, Any> 无法被 kotlinx.serialization 反序列化,必须按 JsonObject 解析
            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            val name = body?.get("deviceName")?.jsonPrimitive?.contentOrNull
            // ttl 收敛到 [1 分钟, 24 小时],防止生成永不过期的配对 token
            val ttl = (body?.get("ttlMs")?.jsonPrimitive?.longOrNull ?: 10 * 60_000L)
                .coerceIn(60_000L, 24 * 3_600_000L)
            val token = pairing.generatePairToken(name, ttl)
            call.respond(token)
        }

        delete("/api/pairing-tokens/{idOrPrefix}") {
            pairing.revokePairingToken(call.parameters["idOrPrefix"]!!)
            call.respond(mapOf("ok" to true))
        }
    }
}
