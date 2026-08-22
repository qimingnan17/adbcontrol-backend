package com.adbcontrol.backend

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.backend.plugin.configureCors
import com.adbcontrol.backend.plugin.configureSecurity
import com.adbcontrol.backend.route.adminRoutes
import com.adbcontrol.backend.route.authRoutes
import com.adbcontrol.backend.routes.emqxRoutes
import com.adbcontrol.backend.routes.healthRoutes
import com.adbcontrol.backend.routes.pairingRoutes
import com.adbcontrol.backend.routes.updateRoutes
import com.adbcontrol.backend.service.AclService
import com.adbcontrol.backend.service.DatabaseService
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.EmqxProxyService
import com.adbcontrol.backend.service.PairingService
import com.adbcontrol.backend.service.TelemetryIngestService
import com.adbcontrol.backend.service.UpdateService
import io.ktor.server.auth.authenticate
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import org.slf4j.LoggerFactory
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val appLogger = LoggerFactory.getLogger("com.adbcontrol.backend.Application")

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
private data class ApiError(val code: String, val message: String)

/**
 * Ktor 应用模块。由 application.conf 的 `modules` 引用,EngineMain 启动时加载。
 * 监听 0.0.0.0:8080(application.conf)。
 */
fun Application.module() {
    val config = BackendConfig.load()
    appLogger.info("Starting AdbControl backend v${BackendConstants.VERSION}")
    appLogger.info("EMQX host={} appid={} rest={}", config.emqxHost, config.emqxAppId, config.emqxRestEndpoint)
    appLogger.info("R2 endpoint={} bucket={}", config.r2Endpoint, config.r2Bucket)
    appLogger.info("DB host={}:{} name={}", config.dbHost, config.dbPort, config.dbName)

    // Bug#19:启动时校验关键凭证,缺失则告警(不阻断启动,降级运行)
    config.validate().takeIf { it.isNotEmpty() }?.let { missing ->
        appLogger.error("Missing critical credentials at startup: {}", missing)
    }

    install(ContentNegotiation) {
        json(json)
    }
    install(CallLogging)
    configureCors()
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Bug#22:不回传内部异常 message,避免泄漏堆栈/敏感信息;BadRequestException 可保留 message。
            val isBadRequest = cause is io.ktor.server.plugins.BadRequestException
            val isSerialization = cause is kotlinx.serialization.SerializationException
            when {
                isBadRequest -> {
                    appLogger.debug("Bad request: {}", cause.message)
                    call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", cause.message ?: "bad request"))
                }
                isSerialization -> {
                    appLogger.debug("Malformed request body: {}", cause.message)
                    call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "malformed request body"))
                }
                else -> {
                    appLogger.error("Unhandled internal error", cause)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "internal error"))
                }
            }
        }
    }

    // 服务装配
    val databaseService = DatabaseService(config)
    val aclService = AclService(config)
    val pairingService = PairingService(config, aclService, databaseService)
    val updateService = UpdateService(config)
    val emqxProxy = EmqxProxyService(config)
    // Web -> 被控端命令桥(签名 + 正确 topic),以及 MQTT 遥测 ingestor(EMQX -> MySQL)
    val commandBridge = DeviceCommandBridge(pairingService, emqxProxy)
    val telemetryIngest = TelemetryIngestService(config, databaseService, pairingService)

    configureSecurity(databaseService)

    // 关闭时释放 HTTP 客户端与数据库连接池(Bug#24)
    monitor.subscribe(ApplicationStopped) {
        telemetryIngest.close()
        emqxProxy.close()
        databaseService.close()
    }

    // 启动遥测 ingestor(凭据缺失时内部打 warning 并跳过,不影响其余路由)。
    telemetryIngest.start()

    // 路由:pair / renew / update / emqx-proxy / health + auth + admin
    routing {
        // 放在 authenticate 块之前的路由不受 auth 保护
        healthRoutes()
        get("/api/health") {
            // 避免 Ktor kotlinx 序列化对 Map<String, Any> 混合类型报错 "Serializing collections of different element types is not yet supported",
            // 显式声明类型为 Map<String, String>
            call.respond(mapOf("status" to "ok", "time" to System.currentTimeMillis().toString()))
        }
        authRoutes(databaseService)
        // 受 auth 保护的 admin 路由放在 authenticate 块里(由 AdminRoutes.kt 内部 authenticate("auth-session") 控制)
        adminRoutes(databaseService, pairingService, commandBridge)
        pairingRoutes(pairingService)
        updateRoutes(updateService)
        // EMQX REST 代理也收进会话保护:不再允许未登录访客枚举在线设备 / 订阅,
        // 否则等于公开暴露设备指纹与在线状态侦察探针
        authenticate("auth-session") {
            emqxRoutes(emqxProxy)
        }
    }

    appLogger.info("Routes mounted: /health, /api/health, /api/login, /api/me, /api/logout, /api/devices, /api/tasks, /api/pairing-tokens, /pair, /renew, /update/check, /update/report, /emqx/devices, /emqx/subscriptions")
}

/** 入口:由 Ktor Gradle 插件的 application.mainClass 指向,委托 Netty EngineMain 读取 application.conf。 */
fun main(args: Array<String>) = EngineMain.main(args)
