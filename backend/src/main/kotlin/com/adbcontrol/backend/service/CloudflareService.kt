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
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

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
            // /zones 每个元素都带 account 子对象;带上它才能把隧道/DNS 绑到正确的账户
            val account = obj["account"] as? JsonObject
            val accountId = account?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
            val accountName = account?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            CfZone(id = id, name = name, status = st, accountId = accountId, accountName = accountName)
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
     * 自动聚合拉取 Cloudflare 所有的授权资源：账户、域名、隧道、R2 桶、D1 数据库。
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
        val tunnelsErrors = mutableListOf<String>()
        val r2Errors = mutableListOf<String>()
        val d1Errors = mutableListOf<String>()

        for (acc in accounts) {
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
            d1 = d1Errors.joinToString("；").ifBlank { null }
        )

        return CfAllResources(
            valid = true,
            tokenInfo = tokenInfo,
            accounts = accounts,
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

    // ==================== R2 全自动配置 ====================

    /**
     * 列出适用于账户 API Token 的权限组(创建 token 时必须按 id 引用)。
     * 不能写死 id —— Cloudflare 的权限组 id 是不透明的,不同账户/时间点可能不同。
     */
    suspend fun listAccountTokenPermissionGroups(
        apiToken: String,
        accountId: String,
    ): CfFetchResult<List<CfPermissionGroup>> {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/tokens/permission_groups", apiToken)
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常（网络不通或超时）")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：Token 缺少「Account API Tokens:Edit」权限"))
        }
        val list = (root["result"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val scopes = (obj["scopes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            CfPermissionGroup(id = id, name = name, scopes = scopes)
        } ?: emptyList()
        return CfFetchResult(list)
    }

    /** 在账户下创建 R2 存储桶(已存在时由调用方按幂等处理)。 */
    suspend fun createR2Bucket(apiToken: String, accountId: String, name: String): CfWriteResult {
        val body = buildJsonObject { put("name", name) }.toString()
        val (status, root, _) = cfExchange("POST", "/accounts/$accountId/r2/buckets", apiToken, body)
        if (root == null) return CfWriteResult(false, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
            return CfWriteResult(false, root.cfErrorOrDefault("HTTP ${status.value}：请确认 Token 含有「Workers R2 Storage:Edit」权限"))
        }
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfWriteResult(false, root.cfErrorOrDefault("创建 R2 存储桶失败"))
        }
        return CfWriteResult(true, "存储桶 $name 已创建")
    }

    /** 列出账户下已有的 R2 存储桶名称(建桶前判断是否已存在,保证幂等)。 */
    suspend fun r2BucketExists(apiToken: String, accountId: String, name: String): Boolean {
        val (status, root, _) = cfExchange("GET", "/accounts/$accountId/r2/buckets", apiToken)
        if (root == null || status != HttpStatusCode.OK) return false
        val buckets = (root["result"] as? JsonObject)?.get("buckets") as? JsonArray ?: return false
        return buckets.any { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name }
    }

    /**
     * 开启 R2 桶的 r2.dev 托管公共域名,返回形如 `https://pub-xxxx.r2.dev` 的基地址。
     * 注:r2.dev 有速率限制,仅适合开发/轻量场景;正式对外建议换自定义域名。
     */
    suspend fun enableR2ManagedDomain(
        apiToken: String,
        accountId: String,
        bucket: String,
    ): CfFetchResult<String> {
        val body = buildJsonObject { put("enabled", true) }.toString()
        val (status, root, _) = cfExchange(
            "PUT", "/accounts/$accountId/r2/buckets/$bucket/domains/managed", apiToken, body,
        )
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：开启 r2.dev 公共域名失败"))
        }
        val domain = (root["result"] as? JsonObject)?.get("domain")?.jsonPrimitive?.contentOrNull
            ?: return CfFetchResult(null, "r2.dev 域名已开启但未返回域名")
        return CfFetchResult("https://$domain")
    }

    /**
     * 创建**账户级 API Token**,并限定只能读写指定 R2 桶。
     *
     * 这是拿到 R2 S3 凭据的唯一程序化路径(OAuth 票据没有 API Tokens:Write scope):
     * 需要一个带「Account API Tokens:Edit」的 bootstrap token 来调本接口。
     *
     * 返回的 [CfCreatedToken.value] **只返回一次**,之后再也取不到;
     * 对应的 S3 Secret Access Key = SHA-256 hex(value),Access Key ID = id。
     */
    suspend fun createBucketScopedToken(
        bootstrapToken: String,
        accountId: String,
        tokenName: String,
        bucket: String,
        permissionGroupId: String,
    ): Pair<CfCreatedToken?, String?> {
        // 桶级资源键格式见官方文档:com.cloudflare.edge.r2.bucket.<ACCOUNT_ID>_<JURISDICTION>_<BUCKET>
        // 默认法域为 default
        val resourceKey = "com.cloudflare.edge.r2.bucket.${accountId}_default_$bucket"
        val body = buildJsonObject {
            put("name", tokenName)
            putJsonArray("policies") {
                addJsonObject {
                    put("effect", "allow")
                    putJsonObject("resources") { put(resourceKey, "*") }
                    putJsonArray("permission_groups") {
                        addJsonObject { put("id", permissionGroupId) }
                    }
                }
            }
        }.toString()

        val (status, root, _) = cfExchange("POST", "/accounts/$accountId/tokens", bootstrapToken, body)
        if (root == null) return null to "连接 Cloudflare API 异常"
        if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
            return null to root.cfErrorOrDefault("HTTP ${status.value}：请确认 bootstrap token 含有「Account API Tokens:Edit」权限")
        }
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return null to root.cfErrorOrDefault("创建 R2 访问凭据失败")
        }
        val result = root["result"] as? JsonObject ?: return null to "创建成功但响应缺少 result"
        val id = result["id"]?.jsonPrimitive?.contentOrNull ?: return null to "创建成功但未返回 token id"
        val value = result["value"]?.jsonPrimitive?.contentOrNull ?: return null to "创建成功但未返回 token value"
        return CfCreatedToken(id = id, value = value) to null
    }

    /**
     * 给 R2 桶绑自定义域名（POST .../r2/buckets/{bucket}/domains/custom）。
     *
     * 前置条件:[domain] 必须是托管在本账户下的 Cloudflare 域名的子域 ——
     * Cloudflare 会自动创建指向桶的 DNS 记录并签发证书,无需手工干预。
     * 相比 r2.dev 托管域,自定义域名没有可变速率限制,适合生产使用。
     *
     * 返回 domain 与 status(ownership/ssl 是否 active)。
     */
    suspend fun bindR2CustomDomain(
        apiToken: String,
        accountId: String,
        bucket: String,
        domain: String,
        zoneId: String,
    ): CfFetchResult<CfR2CustomDomain> {
        val body = buildJsonObject {
            put("domain", domain.trim())
            put("enabled", true)
            put("zoneId", zoneId.trim())
        }.toString()
        val (status, root, _) = cfExchange(
            "POST", "/accounts/$accountId/r2/buckets/$bucket/domains/custom", apiToken, body,
        )
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：绑定自定义域失败（域名需托管在本账户且未被占用）"))
        }
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("绑定自定义域失败"))
        }
        val result = root["result"] as? JsonObject
            ?: return CfFetchResult(null, "绑定成功但响应缺少 result")
        val d = result["domain"]?.jsonPrimitive?.contentOrNull
            ?: return CfFetchResult(null, "绑定成功但未返回域名")
        val enabled = result["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val st = result["status"] as? JsonObject
        return CfFetchResult(
            CfR2CustomDomain(
                domain = d,
                enabled = enabled,
                ownership = st?.get("ownership")?.jsonPrimitive?.contentOrNull ?: "pending",
                ssl = st?.get("ssl")?.jsonPrimitive?.contentOrNull ?: "initializing",
            )
        )
    }

    /**
     * 查询 R2 桶自定义域的就绪状态（ownership 与 SSL 证书是否已 active）。
     * 刚绑定时通常是 pending/initializing，需要等几十秒再查。
     */
    suspend fun getR2CustomDomainStatus(
        apiToken: String,
        accountId: String,
        bucket: String,
        domain: String,
    ): CfFetchResult<CfR2CustomDomain> {
        val (status, root, _) = cfExchange(
            "GET", "/accounts/$accountId/r2/buckets/$bucket/domains/custom/${domain.trim()}", apiToken,
        )
        if (root == null) return CfFetchResult(null, "连接 Cloudflare API 异常")
        if (status != HttpStatusCode.OK || root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            return CfFetchResult(null, root.cfErrorOrDefault("HTTP ${status.value}：查询自定义域状态失败"))
        }
        val result = root["result"] as? JsonObject
            ?: return CfFetchResult(null, "响应缺少 result")
        val st = result["status"] as? JsonObject
        return CfFetchResult(
            CfR2CustomDomain(
                domain = result["domain"]?.jsonPrimitive?.contentOrNull ?: domain,
                enabled = result["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                ownership = st?.get("ownership")?.jsonPrimitive?.contentOrNull ?: "unknown",
                ssl = st?.get("ssl")?.jsonPrimitive?.contentOrNull ?: "unknown",
            )
        )
    }

    override fun close() {
        httpClient.close()
    }
}
