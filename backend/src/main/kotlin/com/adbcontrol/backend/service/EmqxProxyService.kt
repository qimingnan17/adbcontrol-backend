package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
            append(restBase).append(path)
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
        val url = restBase + path
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
