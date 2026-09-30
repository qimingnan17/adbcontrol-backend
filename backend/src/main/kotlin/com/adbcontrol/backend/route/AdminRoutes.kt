package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.TaskRequest
import com.adbcontrol.backend.service.DatabaseService
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.PairingService
import com.adbcontrol.backend.service.TaskSchedulerService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

        // 删除设备:级联清理 DB(device/status/usage/task/log 等全部关联表)
        // + 吊销配对令牌/会话/EMQX 账号。DB 删除失败返回 500,不静默假成功。
        delete("/api/devices/{deviceId}") {
            val id = call.parameters["deviceId"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                mapOf("message" to "missing deviceId")
            )
            val existedInDb = db.deviceExists(id)
            val existedInPairing = pairing.knowsDevice(id)
            if (!existedInDb && !existedInPairing) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "设备不存在"))
                return@delete
            }
            val dbDeleted = db.deleteDevice(id)
            // 令牌/会话/EMQX 清理 best-effort,不阻塞响应;失败仅影响残留凭证,不影响列表展示
            pairing.removeDevice(id)
            if (!dbDeleted && existedInDb) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "设备数据删除失败(数据库不可达或写入失败),请稍后重试")
                )
            } else {
                call.respond(mapOf("ok" to true))
            }
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
            val b = runCatching { call.receive<TaskRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请求体格式错误"))
            validateTaskRequest(b)
                ?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to it)) }
            val id = db.upsertTask(b)
                ?: return@post call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "task create failed"))
            call.respond(mapOf("id" to id))
        }

        put("/api/tasks/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "invalid task id"))
            val b = runCatching { call.receive<TaskRequest>() }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请求体格式错误"))
            validateTaskRequest(b)
                ?.let { return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to it)) }
            db.upsertTask(b.copy(id = id))
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("message" to "task not found"))
            call.respond(mapOf("ok" to true))
        }

        delete("/api/tasks/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "invalid task id"))
            val n = db.deleteTask(id)
            call.respond(mapOf("deleted" to n))
        }

        // 任务通知签收列表(挂在任务详情);?taskId 缺省表示全部(含手动下发)
        get("/api/task-acks") {
            val taskId = call.request.queryParameters["taskId"]?.toLongOrNull()
            call.respond(buildJsonObject {
                put("items", db.listTaskAcks(taskId).toJsonElement())
            })
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
            val name = body?.get("deviceName")?.jsonPrimitive?.contentOrNull?.trim()
            // 设备名称是设备的主标识:与已有设备或待使用令牌重名时拒绝(409),前端提示改名
            if (!name.isNullOrBlank() && (pairing.isPendingTokenNameTaken(name) || db.deviceNameExists(name))) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("message" to "设备名称「$name」已被占用，请换一个名称")
                )
                return@post
            }
            // ttl 收敛到 [1 分钟, 24 小时],防止生成永不过期的配对 token
            val ttl = (body?.get("ttlMs")?.jsonPrimitive?.longOrNull ?: 10 * 60_000L)
                .coerceIn(60_000L, 24 * 3_600_000L)
            val preferredUrl = body?.get("serverUrl")?.jsonPrimitive?.contentOrNull?.trim()
                ?: com.adbcontrol.backend.security.NetworkSecurity.clientOrigin(call)
            val token = pairing.generatePairToken(name, ttl, preferredUrl)
            call.respond(token)
        }

        // OTA 版本清单(Web 查询;CI 发布走 /api/updates/publish 的 token 通道)
        get("/api/updates") {
            call.respond(buildJsonObject {
                put("items", db.listVersionManifests().toJsonElement())
            })
        }

        delete("/api/pairing-tokens/{idOrPrefix}") {
            val removed = pairing.revokePairingToken(call.parameters["idOrPrefix"]!!)
            if (removed) call.respond(mapOf("ok" to true))
            else call.respond(HttpStatusCode.NotFound, mapOf("message" to "未找到匹配的令牌或设备"))
        }
    }
}

/**
 * 创建/更新任务前的参数预校验。返回错误文案,合法返回 null。
 * 没有这层校验时:非法 cron 经调度器容错静默永不执行、坏 commandJson 会让
 * 调度器每轮扫描在该任务上抛异常(已修复为逐任务隔离,但任务本身仍不可用)。
 */
private fun validateTaskRequest(b: TaskRequest): String? {
    if (b.enabled != true) return null // 未启用的任务不预执行,宽松放行
    val cron = b.cronExpr?.trim()
    if (cron.isNullOrEmpty()) return "启用任务必须提供 cronExpr"
    if (!TaskSchedulerService.isValidCron(cron)) return "cronExpr 不是合法的 UNIX cron 表达式(5 字段)"
    val cmdJson = b.commandJson?.trim()
    if (cmdJson.isNullOrEmpty()) return "启用任务必须提供 commandJson"
    val parsed = runCatching { Json.parseToJsonElement(cmdJson).jsonObject }.getOrNull()
        ?: return "commandJson 不是合法的 JSON 对象"
    val type = runCatching { parsed["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    if (type.isNullOrBlank()) return "commandJson 必须包含字符串字段 type"
    return null
}
