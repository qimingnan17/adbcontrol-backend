package com.adbcontrol.backend.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/**
 * Cloudflare OAuth「一键授权」服务。
 *
 * 背景:此前所有 Cloudflare 能力都依赖用户手工在控制台创建 API Token 再粘贴进来
 * (权限少一个就报「Token 缺少 XX 权限」)。本服务改为标准 OAuth 2.0 授权码流程:
 * 用户在设置页点一次「连接 Cloudflare」→ 跳 Cloudflare 官方授权页 → 同意 → 回调
 * 自动换取 access_token / refresh_token,此后 CloudflareService 全程自动带 token。
 *
 * 官方端点(见 developers.cloudflare.com/fundamentals/oauth/integrate-with-cloudflare):
 *  - 授权: https://dash.cloudflare.com/oauth2/auth
 *  - 取票: https://dash.cloudflare.com/oauth2/token (支持 authorization_code / refresh_token)
 *
 * 关键约束:Cloudflare 的 OAuth access_token 是**短期会话票据**,且不含
 * 「API Tokens:Edit」权限 —— 不能用它再去签发长期 API Token,只能靠 refresh_token
 * 续期。因此这里必须实现自动续期,否则几小时后全部 Cloudflare 能力静默失效。
 *
 * 凭据持久化复用 secrets.properties(经 [SettingsService] 的通用键值通道,
 * 不新增固定键段,避免破坏既有配置文件结构)。
 */
@Serializable
data class CfOAuthStatus(
    val configured: Boolean,
    val connected: Boolean,
    val clientId: String = "",
    val scopes: String = "",
    val expiresAt: Long = 0L,
    val message: String? = null,
)

class CloudflareOAuthService(private val settings: SettingsService) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 续期临界区:避免多请求并发同时用同一个 refresh_token 去换票(会被 CF 判失效)。 */
    private val refreshMutex = Mutex()

    /**
     * 生成授权跳转地址。未配置 Client ID 时返回 null,由调用方提示先配置。
     *
     * @param redirectUri 必须是 Cloudflare OAuth client 里登记过的回调地址之一。
     */
    fun buildAuthorizeUrl(redirectUri: String, state: String): String? {
        val clientId = clientId()
        if (clientId.isBlank()) return null
        val scopes = scopes()
        return buildString {
            append(AUTH_ENDPOINT)
            append("?response_type=code")
            append("&client_id=").append(clientId.urlEncoded())
            append("&redirect_uri=").append(redirectUri.urlEncoded())
            append("&scope=").append(scopes.urlEncoded())
            append("&state=").append(state.urlEncoded())
        }
    }

    /**
     * 授权码换取票据。成功时把 access/refresh/过期时间落盘。
     */
    suspend fun exchangeCode(code: String, redirectUri: String): Pair<Boolean, String> {
        val clientId = clientId()
        val clientSecret = clientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return false to "尚未配置 Cloudflare OAuth Client ID / Secret"
        }
        return try {
            val response = httpClient.submitForm(
                url = TOKEN_ENDPOINT,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code.trim())
                    append("redirect_uri", redirectUri.trim())
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                },
            )
            val text = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                logger.warn("CF OAuth code exchange failed: {} {}", response.status, text)
                return false to (parseOAuthError(text) ?: "授权码置换失败 (HTTP ${response.status.value})")
            }
            persistTokens(text) ?: return false to "授权响应缺少 access_token"
            true to "Cloudflare 授权成功"
        } catch (e: Exception) {
            logger.error("CF OAuth code exchange error", e)
            false to "连接 Cloudflare 换取票据异常: ${e.message}"
        }
    }

    /**
     * 返回当前可用的 access token;过期前自动用 refresh_token 续期。
     * 未连接 / 续期失败返回 null(调用方应提示用户重新授权)。
     */
    suspend fun currentAccessToken(): String? {
        val access = settings.getRawProperty(KEY_ACCESS)
        // 空值或"已断开"哨兵都视为未连接;绝不能把哨兵当成真 token 发出去
        if (access.isBlank() || access == REVOKED_SENTINEL) return null
        val expiresAt = settings.getRawProperty(KEY_EXPIRES).toLongOrNull() ?: 0L
        // 提前 60s 视为过期,避免请求发出瞬间正好失效
        if (expiresAt > System.currentTimeMillis() + REFRESH_SKEW_MS) return access

        refreshMutex.withLock {
            // 双检:等锁期间可能已被其它请求刷新成功
            val freshAccess = settings.getRawProperty(KEY_ACCESS)
            if (freshAccess == REVOKED_SENTINEL) return null
            val freshExpires = settings.getRawProperty(KEY_EXPIRES).toLongOrNull() ?: 0L
            if (freshAccess.isNotBlank() && freshExpires > System.currentTimeMillis() + REFRESH_SKEW_MS) {
                return freshAccess
            }
            val refreshToken = settings.getRawProperty(KEY_REFRESH)
            if (refreshToken.isBlank() || refreshToken == REVOKED_SENTINEL) return null
            return refresh(refreshToken = refreshToken)
        }
    }

    /**
     * 用 refresh_token 续期。失败时**保留**旧 refresh_token(Cloudflare 的 refresh 若未返回
     * 新 refresh_token,说明旧值仍有效,不能清空,否则下次续期必失败)。
     */
    private suspend fun refresh(refreshToken: String): String? {
        val clientId = clientId()
        val clientSecret = clientSecret()
        if (refreshToken.isBlank() || refreshToken == REVOKED_SENTINEL ||
            clientId.isBlank() || clientSecret.isBlank()
        ) return null
        return try {
            val response = httpClient.submitForm(
                url = TOKEN_ENDPOINT,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                },
            )
            val text = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                logger.warn("CF OAuth refresh failed: {} {}", response.status, text)
                return null
            }
            persistTokens(text, fallbackRefreshToken = refreshToken)
                ?.let { settings.getRawProperty(KEY_ACCESS).ifBlank { null } }
        } catch (e: Exception) {
            logger.warn("CF OAuth refresh error: {}", e.message)
            null
        }
    }

    /**
     * 解析 token 响应并落盘。返回 access_token(缺失时 null)。
     */
    private fun persistTokens(raw: String, fallbackRefreshToken: String? = null): String? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val access = root["access_token"]?.jsonPrimitive?.contentOrNull
        if (access.isNullOrBlank()) return null
        val refresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: fallbackRefreshToken
            ?: settings.getRawProperty(KEY_REFRESH)
        val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_TTL_SECONDS
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L

        val updates = mutableMapOf(KEY_ACCESS to access, KEY_EXPIRES to expiresAt.toString())
        if (!refresh.isNullOrBlank()) updates[KEY_REFRESH] = refresh
        runCatching { settings.saveSecrets(updates) }
            .onFailure { logger.warn("persist CF OAuth tokens failed: {}", it.message) }
        return access
    }

    /**
     * 断开授权:把 access / refresh 置为哨兵、过期时间归零,使 [currentAccessToken]
     * 立即判定为未连接(保留 client_id/secret,便于再次一键连接)。
     *
     * 注意:[SettingsService.saveSecrets] 会跳过空值,所以无法直接"删键",
     * 只能用哨兵覆盖;access 与 refresh 必须一起置哨兵,否则续期逻辑会拿残留的
     * refresh_token 把连接又刷回来。
     */
    @Synchronized
    fun disconnect() {
        runCatching {
            settings.saveSecrets(
                mapOf(
                    KEY_ACCESS to REVOKED_SENTINEL,
                    KEY_REFRESH to REVOKED_SENTINEL,
                    KEY_EXPIRES to "0",
                )
            )
        }.onFailure { logger.warn("disconnect CF OAuth failed: {}", it.message) }
    }

    fun status(): CfOAuthStatus {
        val clientId = clientId()
        val access = settings.getRawProperty(KEY_ACCESS)
        val expiresAt = settings.getRawProperty(KEY_EXPIRES).toLongOrNull() ?: 0L
        val connected = access.isNotBlank() && access != REVOKED_SENTINEL
        return CfOAuthStatus(
            configured = clientId.isNotBlank() && clientSecret().isNotBlank(),
            connected = connected,
            clientId = clientId,
            scopes = scopes(),
            expiresAt = if (connected) expiresAt else 0L,
        )
    }

    /** 校验 OAuth token 是否真能访问 Cloudflare API(用 /accounts 做最小探活)。 */
    suspend fun probe(token: String): Pair<Boolean, String?> {
        if (token.isBlank()) return false to "未连接 Cloudflare"
        return try {
            val resp = httpClient.get("https://api.cloudflare.com/client/v4/accounts") {
                header("Authorization", "Bearer ${token.trim()}")
            }
            val text = resp.bodyAsText()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val success = root?.get("success")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (resp.status == HttpStatusCode.OK && success) true to null
            else false to (parseOAuthError(text) ?: "HTTP ${resp.status.value}")
        } catch (e: Exception) {
            false to "连接 Cloudflare API 异常: ${e.message}"
        }
    }

    private fun clientId(): String = settings.getRawProperty(KEY_CLIENT_ID).trim()

    private fun clientSecret(): String = settings.getRawProperty(KEY_CLIENT_SECRET).trim()

    /** 授权 scope:默认 offline_access(取 refresh_token 的前提),可用 secrets 覆盖。 */
    private fun scopes(): String =
        settings.getRawProperty(KEY_SCOPES).ifBlank { DEFAULT_SCOPES }

    private fun parseOAuthError(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val desc = root["error_description"]?.jsonPrimitive?.contentOrNull
        val err = root["error"]?.jsonPrimitive?.contentOrNull
        return when {
            !desc.isNullOrBlank() -> desc
            !err.isNullOrBlank() -> err
            else -> null
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val AUTH_ENDPOINT = "https://dash.cloudflare.com/oauth2/auth"
        const val TOKEN_ENDPOINT = "https://dash.cloudflare.com/oauth2/token"

        const val KEY_CLIENT_ID = "cf.oauth_client_id"
        const val KEY_CLIENT_SECRET = "cf.oauth_client_secret"
        const val KEY_SCOPES = "cf.oauth_scopes"
        const val KEY_ACCESS = "cf.oauth_access_token"
        const val KEY_REFRESH = "cf.oauth_refresh_token"
        const val KEY_EXPIRES = "cf.oauth_expires_at"

        /**
         * 默认 scope。Cloudflare 要求至少请求一个 scope;`offline_access` 是拿到
         * refresh_token(自动续期)的前提。业务权限(隧道/域名/R2/D1)由 OAuth client
         * 自身在控制台登记,无需在此重复声明;若你的 client 配置要求显式请求,
         * 可在 secrets.properties 用 `cf.oauth_scopes` 覆盖为空格分隔的 scope 列表。
         */
        const val DEFAULT_SCOPES = "offline_access"

        /** 提前续期窗口。 */
        private const val REFRESH_SKEW_MS = 60_000L

        /** Cloudflare 未返回 expires_in 时的兜底有效期(1 小时)。 */
        private const val DEFAULT_TTL_SECONDS = 3600L

        /** 断开标记:saveSecrets 跳过空值,用哨兵把 access token 置为不可用。 */
        const val REVOKED_SENTINEL = "REVOKED"
    }
}
