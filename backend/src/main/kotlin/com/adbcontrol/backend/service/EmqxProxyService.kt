package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * EMQX REST 代理(README 4.4)。主控端通过后端调用,避免跨域与泄漏 app_secret。
 *
 * 鉴权:HTTP Basic,用 `appid:app_secret`(EMQX 控制台分配)。
 * REST 端口默认 8443(HTTPS,与 8883 共用证书)。
 *
 * 透传 EMQX 原始响应体,后端不重新建模其 JSON,降低耦合。
 */
class EmqxProxyService(private val config: BackendConfig) : AutoCloseable {
    private val restBase = config.emqxRestEndpoint.trimEnd('/')

    /**
     * Serverless 部署 API 实测挂在 /api/v5 前缀下(裸 /publish 返回 Go 网关 404);
     * 兼容旧配置里已带前缀的写法。所有代理路径统一基于此。
     */
    private val v5Base = if (restBase.endsWith("/api/v5")) restBase else "$restBase/api/v5"

    /** 内置数据库认证的用户管理路径(冒号 URL 编码;Serverless 未公开文档但实测可用)。 */
    private val authUsersPath = "/authentication/password_based%3Abuilt_in_database/users"

    data class EmqxResponse(val status: Int, val body: String)

    @Serializable
    private data class ProxyError(val error: String, val message: String)

    private val json = Json { encodeDefaults = true }

    private val client = HttpClient(OkHttp) {
        // Bug#11:HttpClient 无超时,EMQX 不可达会无限挂起并拖垮请求线程。
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
    }

    private val basicAuth: String = "Basic " + java.util.Base64.getEncoder()
        .encodeToString("${config.emqxAppId}:${config.emqxAppSecret}".toByteArray())

    /** GET /clients — 列出在线客户端(可附加 ?_page=1&_limit=100)。 */
    suspend fun listClients(query: String): EmqxResponse =
        proxyGet("/clients", query)

    /** GET /clients/{clientId}/subscriptions — 查指定被控端订阅;clientId 为空则 GET /subscriptions。 */
    suspend fun listSubscriptions(clientId: String?, query: String): EmqxResponse =
        if (clientId.isNullOrBlank()) proxyGet("/subscriptions", query)
        else proxyGet("/clients/${encode(clientId)}/subscriptions", query)

    private suspend fun proxyGet(path: String, query: String): EmqxResponse {
        val url = buildString {
            append(v5Base).append(path)
            if (query.isNotBlank()) append('?').append(query)
        }
        return try {
            val resp = client.get(url) {
                headers.append(HttpHeaders.Authorization, basicAuth)
            }
            EmqxResponse(resp.status.value, resp.bodyAsText())
        } catch (e: Exception) {
            // Bug#12:用序列化构造错误对象,避免手工拼 JSON 导致注入(e.message 含特殊字符)。
            EmqxResponse(
                HttpStatusCode.ServiceUnavailable.value,
                json.encodeToString(
                    ProxyError.serializer(),
                    ProxyError("emqx_unreachable", e.message ?: "emqx unreachable"),
                ),
            )
        }
    }

    private suspend fun proxyPost(path: String, jsonBody: String): EmqxResponse {
        val url = v5Base + path
        return try {
            val resp = client.post(url) {
                headers.append(HttpHeaders.Authorization, basicAuth)
                headers.append(HttpHeaders.ContentType, "application/json")
                setBody(jsonBody)
            }
            EmqxResponse(resp.status.value, resp.bodyAsText())
        } catch (e: Exception) {
            EmqxResponse(
                HttpStatusCode.ServiceUnavailable.value,
                json.encodeToString(
                    ProxyError.serializer(),
                    ProxyError("emqx_unreachable", e.message ?: "emqx unreachable"),
                ),
            )
        }
    }

    private suspend fun proxyPut(path: String, jsonBody: String): EmqxResponse {
        val url = v5Base + path
        return try {
            val resp = client.put(url) {
                headers.append(HttpHeaders.Authorization, basicAuth)
                headers.append(HttpHeaders.ContentType, "application/json")
                setBody(jsonBody)
            }
            EmqxResponse(resp.status.value, resp.bodyAsText())
        } catch (e: Exception) {
            EmqxResponse(
                HttpStatusCode.ServiceUnavailable.value,
                json.encodeToString(ProxyError.serializer(), ProxyError("emqx_unreachable", e.message ?: "emqx unreachable")),
            )
        }
    }

    private suspend fun proxyDelete(path: String): EmqxResponse {
        val url = v5Base + path
        return try {
            val resp = client.delete(url) {
                headers.append(HttpHeaders.Authorization, basicAuth)
            }
            EmqxResponse(resp.status.value, resp.bodyAsText())
        } catch (e: Exception) {
            EmqxResponse(
                HttpStatusCode.ServiceUnavailable.value,
                json.encodeToString(ProxyError.serializer(), ProxyError("emqx_unreachable", e.message ?: "emqx unreachable")),
            )
        }
    }

    // ---------- 内置数据库认证:设备 MQTT 账号的动态注册 ----------
    // Serverless 未开放账号注册的公开文档,但该组端点实测可用(POST 201 / PUT 204 / DELETE 204)。
    // 配对时由 PairingService 调用,让"随机签发的账密"真正被 broker 认识。

    suspend fun createAuthUser(userId: String, password: String): EmqxResponse {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("user_id", userId)
                put("password", password)
                put("is_superuser", false)
            },
        )
        return proxyPost(authUsersPath, body)
    }

    suspend fun updateAuthUserPassword(userId: String, newPassword: String): EmqxResponse {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("password", newPassword)
            },
        )
        return proxyPut("${authUsersPath}/${encode(userId)}", body)
    }

    suspend fun deleteAuthUser(userId: String): EmqxResponse =
        proxyDelete("${authUsersPath}/${encode(userId)}")

    suspend fun publish(topic: String, payload: String, qos: Int = 1): EmqxResponse {
        val body = buildString {
            append("{\"topic\":\"")
            append(topic.replace("\"", "\\\""))
            append("\",\"payload\":\"")
            append(java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8)).replace("\"", "\\\""))
            append("\",\"qos\":").append(qos)
            append(",\"retain\":false}")
        }
        return proxyPost(config.emqxPublishPath, body)
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)

    override fun close() = client.close()
}
