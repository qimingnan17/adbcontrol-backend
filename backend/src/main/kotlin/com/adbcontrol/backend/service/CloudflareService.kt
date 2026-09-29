package com.adbcontrol.backend.service

import com.adbcontrol.backend.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

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
     * 获取 Token 所属或有权限的 Cloudflare Accounts。
     */
    suspend fun fetchAccounts(apiToken: String): List<CfAccount> {
        return try {
            val response = httpClient.get("$cfBase/accounts") {
                header("Authorization", "Bearer ${apiToken.trim()}")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = root["result"]?.jsonArray ?: return emptyList()
            result.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unnamed Account"
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "standard"
                CfAccount(id = id, name = name, type = type)
            }
        } catch (e: Exception) {
            logger.warn("Cloudflare fetchAccounts error", e)
            emptyList()
        }
    }

    /**
     * 获取托管在 Cloudflare 上的域名 (Zones)。
     */
    suspend fun fetchZones(apiToken: String): List<CfZone> {
        return try {
            val response = httpClient.get("$cfBase/zones") {
                header("Authorization", "Bearer ${apiToken.trim()}")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = root["result"]?.jsonArray ?: return emptyList()
            result.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "active"
                CfZone(id = id, name = name, status = status)
            }
        } catch (e: Exception) {
            logger.warn("Cloudflare fetchZones error", e)
            emptyList()
        }
    }

    /**
     * 获取账户下的 Cloudflare 隧道 (Tunnels)。
     */
    suspend fun fetchTunnels(apiToken: String, accountId: String): List<CfTunnel> {
        return try {
            val response = httpClient.get("$cfBase/accounts/$accountId/tunnels?is_deleted=false") {
                header("Authorization", "Bearer ${apiToken.trim()}")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = root["result"]?.jsonArray ?: return emptyList()
            result.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unnamed Tunnel"
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "inactive"
                val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                val conns = obj["connections"]?.jsonArray?.size ?: 0
                CfTunnel(
                    id = id,
                    name = name,
                    status = status,
                    createdAt = createdAt,
                    connectionsCount = conns
                )
            }
        } catch (e: Exception) {
            logger.warn("Cloudflare fetchTunnels error", e)
            emptyList()
        }
    }

    /**
     * 获取账户下的 Cloudflare R2 存储桶列表并计算 S3 Endpoint。
     */
    suspend fun fetchR2Buckets(apiToken: String, accountId: String): List<CfR2Bucket> {
        return try {
            val response = httpClient.get("$cfBase/accounts/$accountId/r2/buckets") {
                header("Authorization", "Bearer ${apiToken.trim()}")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = root["result"]?.jsonObject
            val buckets = result?.get("buckets")?.jsonArray ?: return emptyList()
            val s3Endpoint = "https://$accountId.r2.cloudflarestorage.com"
            buckets.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val creationDate = obj["creation_date"]?.jsonPrimitive?.contentOrNull ?: ""
                CfR2Bucket(
                    name = name,
                    creationDate = creationDate,
                    s3Endpoint = s3Endpoint
                )
            }
        } catch (e: Exception) {
            logger.warn("Cloudflare fetchR2Buckets error", e)
            emptyList()
        }
    }

    /**
     * 获取账户下的 Cloudflare D1 数据库列表。
     */
    suspend fun fetchD1Databases(apiToken: String, accountId: String): List<CfD1Database> {
        return try {
            val response = httpClient.get("$cfBase/accounts/$accountId/d1/database") {
                header("Authorization", "Bearer ${apiToken.trim()}")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = root["result"]?.jsonArray ?: return emptyList()
            result.mapNotNull { el ->
                val obj = el.jsonObject
                val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unnamed D1"
                val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "beta"
                val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                CfD1Database(
                    uuid = uuid,
                    name = name,
                    version = version,
                    createdAt = createdAt
                )
            }
        } catch (e: Exception) {
            logger.warn("Cloudflare fetchD1Databases error", e)
            emptyList()
        }
    }

    /**
     * 自动聚合拉取 Cloudflare 所有的授权资源：账户、域名、隧道、R2 桶、D1 数据库。
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

        val accounts = fetchAccounts(apiToken)
        val zones = fetchZones(apiToken)

        val tunnels = mutableListOf<CfTunnel>()
        val r2Buckets = mutableListOf<CfR2Bucket>()
        val d1Databases = mutableListOf<CfD1Database>()

        for (acc in accounts) {
            tunnels.addAll(fetchTunnels(apiToken, acc.id))
            r2Buckets.addAll(fetchR2Buckets(apiToken, acc.id))
            d1Databases.addAll(fetchD1Databases(apiToken, acc.id))
        }

        return CfAllResources(
            valid = true,
            tokenInfo = tokenInfo,
            accounts = accounts,
            zones = zones,
            tunnels = tunnels,
            r2Buckets = r2Buckets,
            d1Databases = d1Databases,
            message = "成功获取 Cloudflare 资源列表"
        )
    }

    override fun close() {
        httpClient.close()
    }
}
