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

    // ---------- 内置数据库授权:设备 topic ACL(AclService 用) ----------
    // 每设备按 username=deviceId 写 allow 规则,把 pub/sub 限制在自己的 topic 集合内。
    // EMQX 5.4+ 规则字段是 actions 数组;更早的 5.x 用单数字符串 action。写入时先按新
    // schema,400 再回退旧 schema,兼容两个版本段。

    // EMQX 5.8+:规则按作用域管理(用户名/ClientID/全部),创建端点为
    //   POST /authorization/sources/built_in_database/rules/users/{username}
    // 5.4-5.7 为 POST /authorization/sources/built_in_database/rules(裸 rules 表)。
    // 两种 API 形态都在这里支持,由 [AclService] 先新后旧回退。
    private val aclRulesPath = "/authorization/sources/built_in_database/rules"

    /** 新版(5.8+):按用户名作用域整体替换该用户的全部规则(PUT create-or-replace)。 */
    suspend fun putScopedAclRules(username: String, rules: List<Pair<String, String>>): EmqxResponse {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("username", username)
                put("rules", kotlinx.serialization.json.JsonArray(rules.map { (action, topic) ->
                    buildJsonObject {
                        put("permission", "allow")
                        put("action", action)
                        put("topic", topic)
                    }
                }))
            },
        )
        return proxyPut("$aclRulesPath/users/${encode(username)}", body)
    }

    /** 新版(5.8+):按用户名作用域删除该用户全部规则。 */
    suspend fun deleteScopedAclRules(username: String): EmqxResponse =
        proxyDelete("$aclRulesPath/users/${encode(username)}")

    /** 旧版(5.4-5.7):裸 rules 表写入(先新 schema 后旧 schema 字段名回退)。 */
    suspend fun createLegacyAclRule(username: String, action: String, topic: String): EmqxResponse {
        val newSchema = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("username", username)
                put("permission", "allow")
                put("actions", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(action))))
                put("topic", topic)
            },
        )
        val resp = proxyPost(aclRulesPath, newSchema)
        if (resp.status != HttpStatusCode.BadRequest.value) return resp
        val legacySchema = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("username", username)
                put("permission", "allow")
                put("action", action)
                put("topic", topic)
            },
        )
        return proxyPost(aclRulesPath, legacySchema)
    }

    suspend fun listAclRules(): EmqxResponse = proxyGet(aclRulesPath, "_limit=500")

    suspend fun deleteAclRule(ruleId: String): EmqxResponse =
        proxyDelete("$aclRulesPath/${encode(ruleId)}")

    /**
     * 主动踢掉某台设备的在线连接(EMQX `DELETE /api/v5/clients/{clientid}`)。
     *
     * 删设备时必须做这件事:删 ACL 规则 / 删认证用户都**不会**断开已建立的
     * MQTT 长连接,被控端会继续在线并持续上报,表现为"云端已删除但 App 还登着"。
     * 踢连接是让端侧立刻感知失联的唯一手段(端侧重连时因账号已删而失败,
     * 从而触发其自身的登出逻辑)。
     *
     * clientId 不等于 deviceId:实测被控端以 `device-{deviceId}` 作为 clientId,
     * 而 username 才是裸 deviceId。因此两种形式都试一遍,谁命中算谁。
     * 404/204 都视为"已不在线",不算失败。
     */
    suspend fun kickDevice(deviceId: String): EmqxResponse {
        var last = EmqxResponse(HttpStatusCode.ServiceUnavailable.value, "")
        for (candidate in listOf("device-$deviceId", deviceId)) {
            val r = proxyDelete("/clients/${encode(candidate)}")
            last = r
            if (r.status in 200..299 || r.status == 404) return r
        }
        return last
    }

    /**
     * 删除 built_in_database 授权源里的某个用户记录。
     * 自建 EMQX 上有效;EMQX Cloud Serverless 返回 403(不开放),属预期。
     */
    suspend fun deleteAuthorizationUser(username: String): EmqxResponse =
        proxyDelete("/authorization/sources/built_in_database/users/${encode(username)}")

    suspend fun publish(topic: String, payload: String, qos: Int = 1): EmqxResponse {
        // buildJsonObject 构造:topic/deviceId 含引号、反斜杠等字符时由序列化器正确转义,
        // 避免手拼 JSON 字符串的注入/格式损坏。
        // payload 直接放原始 UTF-8 字符串(EMQX 5 的 publish schema 只认 payload 字段,
        // 没有 payload_base64;信封本就是 JSON 文本)。此前把 Base64 文本放进 payload,
        // 订阅端(被控端 MessageCodec)收到的是 Base64 字符串而非信封 JSON,解码静默
        // 失败、所有 Web 下发命令失效(本地全链路联调实测复现)。
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("topic", topic)
                put("payload", payload)
                put("qos", qos)
                put("retain", false)
            },
        )
        return proxyPost(config.emqxPublishPath, body)
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)

    override fun close() = client.close()
}
