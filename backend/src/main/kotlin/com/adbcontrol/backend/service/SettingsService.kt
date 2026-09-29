package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.backend.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SettingsService(private val config: BackendConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SettingsService::class.java)

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 4_000
            socketTimeoutMillis = 8_000
        }
    }

    /**
     * 获取 secrets.properties 文件对象。
     */
    fun getSecretsFile(): File {
        for (candidate in BackendConfig.SECRET_CANDIDATES) {
            val f = File(candidate)
            if (f.exists() && f.canWrite()) return f
        }
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val targetPath = if (isWindows) "C:\\adbcontrol\\secrets.properties" else "secrets.properties"
        val fallback = File(targetPath)
        if (!fallback.exists()) {
            fallback.parentFile?.mkdirs()
            fallback.createNewFile()
        }
        return fallback
    }

    /**
     * 读取配置并进行安全脱敏。
     */
    fun readMaskedSecrets(): SecretsResponse {
        val file = getSecretsFile()
        val props = Properties()
        if (file.exists() && file.canRead()) {
            file.inputStream().use { props.load(it) }
        }

        fun maskSecret(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            if (raw.length <= 6) return "******"
            val prefix = raw.take(2)
            val suffix = raw.takeLast(4)
            return "$prefix******$suffix"
        }

        val r2Secret = props.getProperty("r2.access_secret", "")
        val emqxSecret = props.getProperty("emqx.app_secret", "")
        val emqxIngestPwd = props.getProperty("emqx.ingest_password", "")
        val dbPwd = props.getProperty("db.password", "")
        val pmTok = props.getProperty("pm.token", "")

        return SecretsResponse(
            filePath = file.absolutePath,
            exists = file.exists(),
            r2 = R2Secrets(
                endpoint = props.getProperty("r2.endpoint", config.r2Endpoint),
                bucket = props.getProperty("r2.bucket", config.r2Bucket),
                accessKey = props.getProperty("r2.access_key", config.r2AccessKey),
                accessSecret = maskSecret(r2Secret.ifEmpty { config.r2AccessSecret }),
                hasSecret = (r2Secret.isNotBlank() || config.r2AccessSecret.isNotBlank()),
            ),
            emqx = EmqxSecrets(
                host = props.getProperty("emqx.host", config.emqxHost),
                port = props.getProperty("emqx.port", config.emqxPort.toString()),
                appId = props.getProperty("emqx.appid", config.emqxAppId),
                restEndpoint = props.getProperty("emqx.rest_endpoint", config.emqxRestEndpoint),
                appSecret = maskSecret(emqxSecret.ifEmpty { config.emqxAppSecret }),
                hasAppSecret = (emqxSecret.isNotBlank() || config.emqxAppSecret.isNotBlank()),
                ingestUsername = props.getProperty("emqx.ingest_username", config.emqxIngestUsername),
                ingestPassword = maskSecret(emqxIngestPwd.ifEmpty { config.emqxIngestPassword }),
                hasIngestPassword = (emqxIngestPwd.isNotBlank() || config.emqxIngestPassword.isNotBlank()),
            ),
            server = ServerSecrets(
                url = props.getProperty("server.url", config.serverUrl),
                pmToken = maskSecret(pmTok.ifEmpty { config.pmToken }),
                hasPmToken = (pmTok.isNotBlank() || config.pmToken.isNotBlank()),
            ),
            db = DbSecrets(
                host = props.getProperty("db.host", config.dbHost),
                port = props.getProperty("db.port", config.dbPort.toString()),
                name = props.getProperty("db.name", config.dbName),
                user = props.getProperty("db.user", config.dbUser),
                password = maskSecret(dbPwd.ifEmpty { config.dbPassword }),
                hasPassword = (dbPwd.isNotBlank() || config.dbPassword.isNotBlank()),
            )
        )
    }

    /**
     * 保存修改并写回 secrets.properties 文件。
     */
    @Synchronized
    fun saveSecrets(updates: Map<String, String>): Boolean {
        val file = getSecretsFile()
        val existingProps = Properties()
        if (file.exists() && file.canRead()) {
            file.inputStream().use { existingProps.load(it) }
        }

        val map = mutableMapOf<String, String>()
        for (name in existingProps.stringPropertyNames()) {
            map[name] = existingProps.getProperty(name)
        }

        // 处理并覆写更新
        for ((key, value) in updates) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) continue
            // 如果是以星号掩码保留的值，则不覆盖原有真实密钥
            if (trimmed.contains("******")) continue
            map[key] = trimmed
        }

        val content = buildString {
            appendLine("# ============================================================================")
            appendLine("# AdbControl Backend Secrets Configuration")
            appendLine("# Updated by Web Settings Manager at ${Date()}")
            appendLine("# ============================================================================")
            appendLine()
            appendLine("# ---- Server & PM ----")
            appendLine("server.url = ${map["server.url"] ?: config.serverUrl}")
            appendLine("pm.token = ${map["pm.token"] ?: ""}")
            appendLine()
            appendLine("# ---- Cloudflare R2 ----")
            appendLine("r2.endpoint = ${map["r2.endpoint"] ?: config.r2Endpoint}")
            appendLine("r2.bucket = ${map["r2.bucket"] ?: config.r2Bucket}")
            appendLine("r2.access_key = ${map["r2.access_key"] ?: ""}")
            appendLine("r2.access_secret = ${map["r2.access_secret"] ?: ""}")
            appendLine()
            appendLine("# ---- EMQX MQTT ----")
            appendLine("emqx.host = ${map["emqx.host"] ?: config.emqxHost}")
            appendLine("emqx.port = ${map["emqx.port"] ?: config.emqxPort}")
            appendLine("emqx.appid = ${map["emqx.appid"] ?: config.emqxAppId}")
            appendLine("emqx.rest_endpoint = ${map["emqx.rest_endpoint"] ?: config.emqxRestEndpoint}")
            appendLine("emqx.app_secret = ${map["emqx.app_secret"] ?: ""}")
            appendLine("emqx.ingest_username = ${map["emqx.ingest_username"] ?: ""}")
            appendLine("emqx.ingest_password = ${map["emqx.ingest_password"] ?: ""}")
            appendLine()
            appendLine("# ---- Database ----")
            appendLine("db.host = ${map["db.host"] ?: config.dbHost}")
            appendLine("db.port = ${map["db.port"] ?: config.dbPort}")
            appendLine("db.name = ${map["db.name"] ?: config.dbName}")
            appendLine("db.user = ${map["db.user"] ?: config.dbUser}")
            appendLine("db.password = ${map["db.password"] ?: ""}")
        }

        file.writeText(content, StandardCharsets.UTF_8)
        logger.info("Successfully updated {}", file.absolutePath)
        return true
    }

    /**
     * 测试 EMQX REST API 连通性。
     */
    suspend fun testEmqx(restEndpoint: String, appId: String, appSecretInput: String): Pair<Boolean, String> {
        val secret = if (appSecretInput.contains("******")) {
            val file = getSecretsFile()
            val p = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
            p.getProperty("emqx.app_secret", config.emqxAppSecret)
        } else {
            appSecretInput.trim()
        }

        if (restEndpoint.isBlank() || appId.isBlank() || secret.isBlank()) {
            return false to "Endpoint、App ID 或 App Secret 不能为空"
        }

        val base = restEndpoint.trimEnd('/')
        val url = if (base.endsWith("/api/v5")) "$base/nodes" else "$base/api/v5/nodes"
        val auth = "Basic " + java.util.Base64.getEncoder().encodeToString("$appId:$secret".toByteArray())

        return try {
            val response = httpClient.get(url) {
                header("Authorization", auth)
            }
            if (response.status == HttpStatusCode.OK) {
                true to "EMQX REST API 连通成功！(HTTP 200)"
            } else if (response.status == HttpStatusCode.Unauthorized) {
                false to "EMQX 鉴权失败 (HTTP 401)：App ID 与 App Secret 不匹配"
            } else {
                false to "EMQX 返回状态码: ${response.status.value}"
            }
        } catch (e: Exception) {
            logger.warn("testEmqx failed", e)
            false to "连接 EMQX 异常: ${e.message}"
        }
    }

    /**
     * 测试 Cloudflare R2 存储桶连通性 (AWS SigV4 认证)。
     */
    suspend fun testR2(endpoint: String, bucket: String, accessKey: String, accessSecretInput: String): Pair<Boolean, String> {
        val secret = if (accessSecretInput.contains("******")) {
            val file = getSecretsFile()
            val p = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
            p.getProperty("r2.access_secret", config.r2AccessSecret)
        } else {
            accessSecretInput.trim()
        }

        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secret.isBlank()) {
            return false to "Endpoint、Bucket、Access Key 或 Secret 不能为空"
        }

        return try {
            val uri = URI(endpoint.trimEnd('/'))
            val host = uri.host ?: return false to "无效的 R2 Endpoint 域名"
            val path = "/${bucket.trim('/')}"
            val query = "max-keys=0"
            val targetUrl = "https://$host$path?$query"

            val utcTz = TimeZone.getTimeZone("UTC")
            val amzDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = utcTz }
            val dateStampFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = utcTz }
            val now = Date()
            val amzDate = amzDateFormat.format(now)
            val dateStamp = dateStampFormat.format(now)

            val emptyPayloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            val canonicalHeaders = "host:$host\nx-amz-content-sha256:$emptyPayloadHash\nx-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n$path\n$query\n$canonicalHeaders\n$signedHeaders\n$emptyPayloadHash"

            fun sha256Hex(data: String): String {
                val digest = MessageDigest.getInstance("SHA-256")
                return digest.digest(data.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }

            fun hmacSha256(key: ByteArray, data: String): ByteArray {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key, "HmacSHA256"))
                return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            }

            val credentialScope = "$dateStamp/auto/s3/aws4_request"
            val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest)}"

            val kDate = hmacSha256("AWS4$secret".toByteArray(StandardCharsets.UTF_8), dateStamp)
            val kRegion = hmacSha256(kDate, "auto")
            val kService = hmacSha256(kRegion, "s3")
            val kSigning = hmacSha256(kService, "aws4_request")
            val signature = hmacSha256(kSigning, stringToSign).joinToString("") { "%02x".format(it) }

            val authHeader = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

            val response = httpClient.get(targetUrl) {
                header("Host", host)
                header("x-amz-date", amzDate)
                header("x-amz-content-sha256", emptyPayloadHash)
                header("Authorization", authHeader)
            }

            when (response.status) {
                HttpStatusCode.OK -> true to "Cloudflare R2 存储桶连通成功！凭据有效且桶存在 (HTTP 200)"
                HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized -> false to "R2 鉴权失败 (HTTP ${response.status.value})：Access Key 或 Secret 无效"
                HttpStatusCode.NotFound -> false to "R2 存储桶不存在 (HTTP 404)：请检查 Bucket 名称是否正确"
                else -> false to "R2 返回状态: ${response.status.value} ${response.bodyAsText().take(200)}"
            }
        } catch (e: Exception) {
            logger.warn("testR2 failed", e)
            false to "连接 R2 异常: ${e.message}"
        }
    }

    override fun close() {
        httpClient.close()
    }
}
