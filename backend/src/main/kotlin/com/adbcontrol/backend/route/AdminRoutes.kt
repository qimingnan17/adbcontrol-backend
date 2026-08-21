package com.adbcontrol.backend.route

import com.adbcontrol.backend.service.DatabaseService
import com.adbcontrol.backend.service.EmqxProxyService
import com.adbcontrol.backend.service.PairingService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.adminRoutes(db: DatabaseService, pairing: PairingService, emqx: EmqxProxyService) {
    authenticate("auth-session") {
        get("/api/devices") {
            call.respond(mapOf("items" to db.listDevicesWithStatus()))
        }

        get("/api/devices/{deviceId}") {
            val id = call.parameters["deviceId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("message" to "missing deviceId")
            )
            call.respond(mapOf("overview" to (db.getDeviceOverview(id) ?: mapOf<String, Any?>())))
        }

        get("/api/devices/{deviceId}/commands") {
            val id = call.parameters["deviceId"]!!
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            call.respond(mapOf("items" to db.listRecentCommands(id, limit)))
        }

        post("/api/devices/{deviceId}/commands") {
            val id = call.parameters["deviceId"]!!
            val body = runCatching { call.receive<Map<String, Any>>() }.getOrNull()
            val cmdType = body?.get("type")?.toString()?.removeSurrounding("\"")
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("message" to "missing type")
                )
            val cmdId = "web-" + System.currentTimeMillis()
            val topic = "adb/dev/$id/cmd/in"
            val argsJson = body["args"]?.toString() ?: "{}"
            val payload =
                """{"commandId":"$cmdId","type":"$cmdType","args":$argsJson,"webSource":1,"ts":${System.currentTimeMillis()}}"""
            val resp = emqx.publish(topic, payload)
            call.respond(mapOf("commandId" to cmdId, "status" to resp.status, "emqx" to resp.body))
        }

        get("/api/tasks") {
            val deviceId = call.parameters["deviceId"]
            call.respond(mapOf("items" to db.listTasks(deviceId)))
        }

        post("/api/tasks") {
            val b = call.receive<Map<String, Any>>()
            val id = db.upsertTask(b)
            call.respond(mapOf("id" to id))
        }

        put("/api/tasks/{id}") {
            val b = call.receive<Map<String, Any>>()
            val id = call.parameters["id"]!!.toInt()
            db.upsertTask(b + ("id" to id))
            call.respond(mapOf("ok" to true))
        }

        delete("/api/tasks/{id}") {
            val id = call.parameters["id"]!!.toInt()
            val n = db.deleteTask(id)
            call.respond(mapOf("deleted" to n))
        }

        get("/api/pairing-tokens") {
            val all = call.parameters["includeUsed"] == "1"
            call.respond(mapOf("items" to pairing.listPairingTokens(all)))
        }

        post("/api/pairing-tokens") {
            val b = runCatching { call.receive<Map<String, Any>>() }.getOrNull() ?: emptyMap()
            val name = b["deviceName"]?.toString()
            val ttl = b["ttlMs"]?.toString()?.toLongOrNull() ?: 10 * 60_000L
            val token = pairing.generatePairToken(name, ttl)
            call.respond(token)
        }

        delete("/api/pairing-tokens/{idOrPrefix}") {
            pairing.revokePairingToken(call.parameters["idOrPrefix"]!!)
            call.respond(mapOf("ok" to true))
        }
    }
}
