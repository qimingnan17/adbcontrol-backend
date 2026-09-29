package com.adbcontrol.backend.service

import com.adbcontrol.backend.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.Base64

/** 单类 Cloudflare 资源的探测结果：error 非空表示读取失败（向前端透出真实原因，不再静默吞错） */
data class CfFetchResult<T>(val data: T?, val error: String? = null)

/** Cloudflare API 写操作结果 */
data class CfWriteResult(val ok: Boolean, val message: String? = null, val tunnelId: String? = null)

class CloudflareService : AutoCloseable {
    private val logger = LoggerFactory.getLogger(CloudflareService::class.java)

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val cfBase = "https://api.cloudflare.com/client/v4"

    /**
     * 校验 Cloudflare API Token 是否有效并处于活跃状态。
     */
    suspend fun verifyToken(apiToken: String): CfTokenVerifyInfo {
        if (apiToken.isBlank()) {
            return CfTokenVerifyInfo(valid = false, message = "Token 不能为空")
        }
        return try {
            val response = httpClient.get("$cfBase/user/tokens/verify") {
                header("Authorization", "Bearer ${apiToken.trim()}")
                header("Content-Type", "application/json")
            }
            val text = response.bodyAsText()
            val root = json.parseToJsonElement(text).jsonObject
            val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (response.status == HttpStatusCode.OK && success) {
                val result = root["result"]?.jsonObject
                val id = result?.get("id")?.jsonPrimitive?.contentOrNull
                val status = result?.get("status")?.jsonPrimitive?.contentOrNull ?: "active"
                CfTokenVerifyInfo(valid = true, tokenId = id, status = status, message = "Token 校验成功")
            } else {
                val errors = root["errors"]?.jsonArray
                val firstErr = errors?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                CfTokenVerifyInfo(valid = false, message = firstErr ?: "Token 无效或无权访问")
            }
        } catch (e: Exception) {
            logger.warn("Cloudflare verifyToken error", e)
            CfTokenVerifyInfo(valid = false, message = "连接 Cloudflare 校验接口异常: ${e.message}")
        }
    }

    /**
     * 统一的 Cloudflare API 调用通道：返回 (HTTP 状态码, 解析后的 JSON 根对象, 原始响应文本)。
     */
    private suspend fun cfExchange(
        method: String,
        path: String,
        apiToken: String,
        bodyJson: String? = null
    ): Triple<HttpStatusCode, JsonObject?, String?> {
        return try {
            val response = when (method) {
                "GET" -> httpClient.get("$cfBase$path") {
                    header("Authorization", "Bearer ${apiToken.trim()}")
                }
                "POST" -> httpClient.post("$cfBase$path") {
                    header("Authorization", "Bearer ${apiToken.trim()}")
                    header("Content-Type", "application/json")
                    if (bodyJson != null) setBody(bodyJson)
                }
                "PUT" -> httpClient.put("$cfBase$path") {
                    header("Authorization", "Bearer ${apiToken.trim()}")
                    header("Content-Type", "application/json")
                    if (bodyJson != null) setBody(bodyJson)
                }
                else -> return Triple(HttpStatusCode.MethodNotAllowed, null, null)
            }
            val text = response.bodyAsText()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            Triple(response.status, root, text)
        } catch (e: Exception) {
            logger.warn("Cloudflare {} {} error: {}", method, path, e.message)
            Triple(HttpStatusCode.ServiceUnavailable, null, null)
        }
    }

    /**
     * 从 Cloudflare 响应中提取首个错误信息；无法提取时回退到默认描述。
     */
    private fun JsonObject?.cfErrorOrDefault(fallback: String): String {
        val root = this ?: return fallback
        if (root["success"]?.jsonPrimitive?.booleanOrNull == true) return fallback
        val first = (root["errors"] as? JsonArray)?.firstOrNull() as? JsonObject
        val msg = first?.get("message")?.jsonPrimitive?.contentOrNull
        val code = first?.get("code")?.jsonPrimitive?.contentOrNull
        return when {
            msg != null && code != null -> "[$code] $msg"
            msg != null -> msg
            else -> fallback
        }
    }

    /**
     * 获取 Token 所属或有权限的 Cloudflare Accounts。
     */
    suspend fun fetchAccounts(apiToken: String): CfFetchResult<List<CfAccount>> {
        val (status, root, _) = cfExchange("GET", "/accounts", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常（网络不通或超时）")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「帐户设置:Read」权限"))
        }
        val accounts = (root["result"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unnamed Account"
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "standard"
            CfAccount(id = id, name = name, type = type)
        } ?: emptyList()
        return CfFetchResult(accounts)
    }

    /**
     * 获取托管在 Cloudflare 上的域名 (Zones)。
     */
    suspend fun fetchZones(apiToken: String): CfFetchResult<List<CfZone>> {
        val (status, root, _) = cfExchange("GET", "/zones", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常（网络不通或超时）")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「区域:Read」权限"))
        }
        val zones = (root["result"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val st = obj["status"]?.jsonPrimitive?.contentOrNull ?: "active"
            CfZone(id = id, name = name, status = st)
        } ?: emptyList()
        return CfFetchResult(zones)
    }

    /**
     * 获取账户下的 Cloudflare 隧道 (Tunnels)。
     */
    suspend fun fetchTunnels(apiToken: String, accountId: String): CfFetchResult<List<CfTunnel>> {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/tunnels?is_deleted=false", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「Cloudflare Tunnel:Read」权限"))
        }
        val tunnels = (root["result"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unnamed Tunnel"
            val st = obj["status"]?.jsonPrimitive?.contentOrNull ?: "inactive"
            val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
            val conns = obj["connections"]?.jsonArray?.size ?: 0
            CfTunnel(id = id, name = name, status = st, createdAt = createdAt, connectionsCount = conns)
        } ?: emptyList()
        return CfFetchResult(tunnels)
    }

    /**
     * 获取账户下的 Cloudflare R2 存储桶列表并计算 S3 Endpoint。
     */
    suspend fun fetchR2Buckets(apiToken: String, accountId: String): CfFetchResult<List<CfR2Bucket>> {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/r2/buckets", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「Workers R2 存储:Read」权限"))
        }
        val buckets = (root["result"] as? JsonObject)?.get("buckets") as? JsonArray
        val s3Endpoint = "https://$accountId.r2.cloudflarestorage.com"
        val list = buckets?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val creationDate = obj["creation_date"]?.jsonPrimitive?.contentOrNull ?: ""
            CfR2Bucket(name = name, creationDate = creationDate, s3Endpoint = s3Endpoint)
        } ?: emptyList()
        return CfFetchResult(list)
    }

    /**
     * 获取账户下的 Cloudflare D1 数据库列表。
     */
    suspend fun fetchD1Databases(apiToken: String, accountId: String): CfFetchResult<List<CfD1Database>> {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/d1/database", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「D1:Read」权限"))
        }
        val list = (root["result"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unnamed D1"
            val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "beta"
            val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
            CfD1Database(uuid = uuid, name = name, version = version, createdAt = createdAt, accountId = accountId)
        } ?: emptyList()
        return CfFetchResult(list)
    }

    /**
     * 获取账户下的 Cloudflare Zero Trust Access Organization (Team Domain)。
     */
    suspend fun fetchAccessOrg(apiToken: String, accountId: String): CfFetchResult<CfAccessOrg?> {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/access/organizations", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「访问:组织、标识提供程序和组:Read」权限"))
        }
        val result = root["result"] as? JsonObject
        val authDomain = result?.get("auth_domain")?.jsonPrimitive?.contentOrNull
            ?: return CfFetchResult(null, "账户尚未启用 Zero Trust Access 组织")
        val name = result["name"]?.jsonPrimitive?.contentOrNull ?: ""
        return CfFetchResult(CfAccessOrg(name = name, authDomain = authDomain))
    }

    /**
     * 自动聚合拉取 Cloudflare 所有的授权资源：账户、Access Org、域名、隧道、R2 桶、D1 数据库。
     * 各类资源的失败原因会汇总到 errors 字段，前端据此展示真实错误而不是笼统的"未获取到"。
     */
    suspend fun fetchAllResources(apiToken: String): CfAllResources {
        val tokenInfo = verifyToken(apiToken)
        if (!tokenInfo.valid) {
            return CfAllResources(
                valid = false,
                tokenInfo = tokenInfo,
                message = tokenInfo.message ?: "Token 校验失败"
            )
        }

        val accountsRes = fetchAccounts(apiToken)
        val zonesRes = fetchZones(apiToken)
        val accounts = accountsRes.data ?: emptyList()
        val zones = zonesRes.data ?: emptyList()

        val tunnels = mutableListOf<CfTunnel>()
        val r2Buckets = mutableListOf<CfR2Bucket>()
        val d1Databases = mutableListOf<CfD1Database>()
        var accessOrg: CfAccessOrg? = null
        var accessOrgError: String? = null
        val tunnelsErrors = mutableListOf<String>()
        val r2Errors = mutableListOf<String>()
        val d1Errors = mutableListOf<String>()

        for (acc in accounts) {
            if (accessOrg == null && accessOrgError == null) {
                val orgRes = fetchAccessOrg(apiToken, acc.id)
                accessOrg = orgRes.data
                accessOrgError = orgRes.error
            }
            fetchTunnels(apiToken, acc.id).let {
                it.data?.let { list -> tunnels.addAll(list) }
                it.error?.let { err -> tunnelsErrors.add(err) }
            }
            fetchR2Buckets(apiToken, acc.id).let {
                it.data?.let { list -> r2Buckets.addAll(list) }
                it.error?.let { err -> r2Errors.add(err) }
            }
            fetchD1Databases(apiToken, acc.id).let {
                it.data?.let { list -> d1Databases.addAll(list) }
                it.error?.let { err -> d1Errors.add(err) }
            }
        }

        val errors = CfResourceErrors(
            accounts = accountsRes.error,
            zones = zonesRes.error,
            tunnels = tunnelsErrors.joinToString("；").ifBlank { null },
            r2 = r2Errors.joinToString("；").ifBlank { null },
            d1 = d1Errors.joinToString("；").ifBlank { null },
            accessOrg = accessOrgError
        )

        return CfAllResources(
            valid = true,
            tokenInfo = tokenInfo,
            accounts = accounts,
            accessOrg = accessOrg,
            zones = zones,
            tunnels = tunnels,
            r2Buckets = r2Buckets,
            d1Databases = d1Databases,
            errors = errors,
            message = "成功获取 Cloudflare 资源列表"
        )
    }

    // ==================== 隧道穿透写操作 ====================

    /**
     * 创建命名隧道（config_src=cloudflare，远程托管配置模式，ingress 可通过 API 管理）。
     * 需要 Token 含有「Cloudflare Tunnel:Edit」权限。
     */
    suspend fun createTunnel(apiToken: String, accountId: String, name: String): CfWriteResult {
        val body = buildJsonObject {
            put("name", name)
            put("config_src", "cloudflare")
        }.toString()
        val (status, root, _) = cfExchange("POST", "/accounts/$accountId/cfd_tunnel", apiToken, body)
        if (root == null) return CfWriteResult(false, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfWriteResult(
                false,
                root.cfErrorOrDefault("HTTP ${status.value}：请确认 Token 含有「Cloudflare Tunnel:Edit」权限")
            )
        }
        val id = (root["result"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: return CfWriteResult(false, "创建成功但未返回 Tunnel ID")
        return CfWriteResult(true, "隧道已创建", tunnelId = id)
    }

    /**
     * 读取隧道当前的远程 ingress 规则（无配置 / 读取失败时返回空列表）。
     */
    private suspend fun fetchTunnelIngress(
        apiToken: String,
        accountId: String,
        tunnelId: String
    ): List<JsonObject> {
        val (status, root, _) = cfExchange(
            "GET", "/accounts/$accountId/cfd_tunnel/$tunnelId/configurations", apiToken
        )
        if (root == null || status != HttpStatusCode.OK) return emptyList()
        val arr = (((root["result"] as? JsonObject)?.get("config") as? JsonObject)?.get("ingress")) as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            buildJsonObject {
                obj["hostname"]?.jsonPrimitive?.contentOrNull?.let { put("hostname", it) }
                obj["path"]?.jsonPrimitive?.contentOrNull?.let { put("path", it) }
                obj["service"]?.jsonPrimitive?.contentOrNull?.let { put("service", it) }
            }
        }
    }

    /**
     * 写入隧道 ingress 规则（**合并语义**）：保留已有其他主机名的规则，追加/更新目标 hostname，
     * 可选追加 MQTT over WSS 的 /mqtt 入口，最后以 404 兑底。
     * 仅对远程托管（remotely-managed）隧道生效；本地 config.yml 托管的旧隧道会由 Cloudflare 返回错误。
     *
     * @param mqttWssHost 非空时追加 `{ path: "/mqtt", service: "https://<host>:8084" }`，为受控端提供双栈 WSS 入口。
     */
    suspend fun putTunnelIngress(
        apiToken: String,
        accountId: String,
        tunnelId: String,
        hostname: String,
        localPort: Int,
        mqttWssHost: String? = null
    ): CfWriteResult {
        // 1. 读取现有规则，保留不属于本次变更的条目（不再整份覆盖）
        val kept = fetchTunnelIngress(apiToken, accountId, tunnelId).filter { rule ->
            val h = rule["hostname"]?.jsonPrimitive?.contentOrNull
            val p = rule["path"]?.jsonPrimitive?.contentOrNull
            val svc = rule["service"]?.jsonPrimitive?.contentOrNull ?: ""
            when {
                h == null && p == null && svc.startsWith("http_status:") -> false // 丢弃旧兑底
                h == hostname -> false                                              // 同主机名重新写入
                !mqttWssHost.isNullOrBlank() && p == "/mqtt" -> false              // 旧 MQTT 入口重新写入
                else -> true
            }
        }

        // 2. 组装新的完整 ingress：已有规则 + 目标主机名 + 可选 MQTT + 404 兑底
        val ingress = buildJsonArray {
            kept.forEach { add(it) }
            add(buildJsonObject {
                put("hostname", hostname)
                put("service", "http://localhost:$localPort")
            })
            if (!mqttWssHost.isNullOrBlank()) {
                add(buildJsonObject {
                    put("path", "/mqtt")
                    put("service", "https://$mqttWssHost:8084")
                })
            }
            add(buildJsonObject { put("service", "http_status:404") })
        }

        val body = buildJsonObject {
            put("config", buildJsonObject { put("ingress", ingress) })
        }.toString()
        val (status, root, _) = cfExchange(
            "PUT", "/accounts/$accountId/cfd_tunnel/$tunnelId/configurations", apiToken, body
        )
        if (root == null) return CfWriteResult(false, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfWriteResult(
                false,
                root.cfErrorOrDefault("HTTP ${status.value}：ingress 未生效（该隧道可能由本地 config.yml 托管）")
            )
        }
        val merged = if (!mqttWssHost.isNullOrBlank()) "，已保留 ${kept.size} 条原有规则并追加 /mqtt 入口" else "（已保留 ${kept.size} 条原有规则）"
        return CfWriteResult(true, "$hostname → http://localhost:$localPort$merged")
    }

    /**
     * 将 hostname 通过 DNS 路由绑定到隧道：Cloudflare 自动创建指向 {tunnelId}.cfargotunnel.com 的代理 CNAME。
     * 需要 Token 含有「Cloudflare Tunnel:Edit」权限，且域名托管在该账户下。
     */
    suspend fun bindTunnelDnsRoute(
        apiToken: String,
        accountId: String,
        tunnelId: String,
        hostname: String
    ): CfWriteResult {
        val body = buildJsonObject { put("name", hostname) }.toString()
        val (status, root, _) = cfExchange(
            "POST", "/accounts/$accountId/cfd_tunnel/$tunnelId/routes/dns", apiToken, body
        )
        if (root == null) return CfWriteResult(false, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfWriteResult(
                false,
                root.cfErrorOrDefault("HTTP ${status.value}：请确认 Token 含有「区域 DNS:Edit」权限且域名托管在该账户")
            )
        }
        return CfWriteResult(true, "已创建 $hostname 的隧道 CNAME 记录")
    }

    /**
     * 获取隧道连接器 Token（用于云电脑上执行 cloudflared service install <token>）。
     */
    suspend fun getTunnelToken(apiToken: String, accountId: String, tunnelId: String): String? {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/cfd_tunnel/$tunnelId/token", apiToken)
        if (root == null || status != HttpStatusCode.OK) return null
        return root["result"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * 通过 Cloudflare Zero Trust (Access) OIDC 授权码置换身份信息。
     */
    suspend fun exchangeOidcCode(
        teamDomain: String,
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String
    ): Pair<Boolean, CfOidcUserInfo?> {
        val domain = teamDomain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .substringBefore("/cdn-cgi")

        val tokenUrl = "https://$domain/cdn-cgi/access/sso/oidc/$clientId/token"
        return try {
            val response = httpClient.submitForm(
                url = tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("client_id", clientId.trim())
                    append("client_secret", clientSecret.trim())
                    append("code", code.trim())
                    append("redirect_uri", redirectUri.trim())
                }
            )
            val text = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                logger.warn("OIDC token exchange failed: {} {}", response.status, text)
                return false to null
            }
            val root = json.parseToJsonElement(text).jsonObject
            val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull
            val idToken = root["id_token"]?.jsonPrimitive?.contentOrNull

            var email: String? = null
            var name: String? = null
            var sub: String? = null

            // 1. 尝试从 userinfo 接口拉取
            if (!accessToken.isNullOrBlank()) {
                try {
                    val userinfoUrl = "https://$domain/cdn-cgi/access/sso/oidc/$clientId/userinfo"
                    val userinfoResp = httpClient.get(userinfoUrl) {
                        header("Authorization", "Bearer $accessToken")
                    }
                    if (userinfoResp.status == HttpStatusCode.OK) {
                        val uRoot = json.parseToJsonElement(userinfoResp.bodyAsText()).jsonObject
                        email = uRoot["email"]?.jsonPrimitive?.contentOrNull
                        name = uRoot["name"]?.jsonPrimitive?.contentOrNull
                        sub = uRoot["sub"]?.jsonPrimitive?.contentOrNull
                    }
                } catch (e: Exception) {
                    logger.warn("OIDC userinfo fetch failed, falling back to id_token", e)
                }
            }

            // 2. 兜底策略：直接解析 id_token JWT payload
            if (email.isNullOrBlank() && !idToken.isNullOrBlank()) {
                val parts = idToken.split(".")
                if (parts.size >= 2) {
                    val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
                    val idRoot = json.parseToJsonElement(payloadBytes.decodeToString()).jsonObject
                    email = idRoot["email"]?.jsonPrimitive?.contentOrNull
                    name = idRoot["name"]?.jsonPrimitive?.contentOrNull ?: name
                    sub = idRoot["sub"]?.jsonPrimitive?.contentOrNull ?: sub
                }
            }

            if (!email.isNullOrBlank()) {
                true to CfOidcUserInfo(email = email, name = name, sub = sub)
            } else {
                false to null
            }
        } catch (e: Exception) {
            logger.error("OIDC exchange error", e)
            false to null
        }
    }

    override fun close() {
        httpClient.close()
    }
}
