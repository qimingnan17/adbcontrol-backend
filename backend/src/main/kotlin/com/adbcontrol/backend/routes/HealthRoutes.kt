package com.adbcontrol.backend.routes

import com.adbcontrol.backend.BackendConstants
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
private data class Health(val status: String, val version: String)

/**
 * GET /health → 200 + JSON {"status":"UP","version":"0.1.0"}
 * 用于容器/反向代理健康探测与就绪检查。
 */
fun Routing.healthRoutes() {
    get("/health") {
        call.respond(Health(status = "UP", version = BackendConstants.VERSION))
    }
}
