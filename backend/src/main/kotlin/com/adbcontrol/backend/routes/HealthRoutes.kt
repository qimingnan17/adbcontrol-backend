package com.adbcontrol.backend.routes

import com.adbcontrol.backend.BackendConstants
import com.adbcontrol.backend.service.DatabaseService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
private data class Health(val status: String, val version: String, val db: Boolean)

/**
 * GET /health → 200 + JSON {"status":"UP","version":"0.1.0","db":true}
 * 用于容器/反向代理健康探测与就绪检查。数据库不可达时回退 503 DEGRADED。
 */
fun Routing.healthRoutes(db: DatabaseService? = null) {
    get("/health") {
        val dbOk = db?.isHealthy() ?: true
        val status = if (dbOk) "UP" else "DEGRADED"
        val code = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(code, Health(status = status, version = BackendConstants.VERSION, db = dbOk))
    }
}
