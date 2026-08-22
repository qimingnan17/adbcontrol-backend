package com.adbcontrol.backend.config

import com.adbcontrol.backend.model.BrokerConfig
import com.adbcontrol.backend.model.DbConfig
import com.adbcontrol.backend.model.R2Config
import java.io.File
import java.util.Properties

/**
 * 后端配置。启动时从 **环境变量** 或 **secrets.properties** 文件读取(README 第九章 / 第十章)。
 *
 * 解析优先级:环境变量 > secrets.properties > 已知默认值。
 * 已知凭证(README 中给出)直接预填为默认值;未知凭证(EMQX app_secret / R2 密钥)
 * 由用户在 secrets.properties 中填入(见 /workspace/secrets.properties.template)。
 *
 * secrets.properties 不入 git(见 /workspace/.gitignore)。
 *
 * env 变量约定:均以 `ADB_` 前缀,如 `ADB_EMQX_APP_SECRET`、`ADB_DB_PASSWORD` 等。
 */
data class BackendConfig(
    /** 后端自身对外 URL,用于生成 QR 码 */
    val serverUrl: String,
    val emqxHost: String,
    val emqxPort: Int,
    val emqxAppId: String,
    val emqxRestEndpoint: String,
    val emqxAppSecret: String,
    /** EMQX 发布接口路径(拼在 emqxRestEndpoint 之后)。EMQX 5.x=/publish,旧版 4.x=/mqtt/v1/publish。 */
    val emqxPublishPath: String,
    val r2Endpoint: String,
    val r2Bucket: String,
    val r2AccessKey: String,
    val r2AccessSecret: String,
    val dbHost: String,
    val dbPort: Int,
    val dbName: String,
    val dbUser: String,
    val dbPassword: String,
    /** 遥测 ingestor 使用的 MQTT 账号(运维在 EMQX 控制台手工创建一次,非代码自注册)。 */
    val emqxIngestUsername: String = "",
    val emqxIngestPassword: String = "",
) {
    /** 由模板拼装单设备 Broker 凭证(用户名/密码在配对时签发)。 */
    fun buildBroker(username: String, password: String): BrokerConfig = BrokerConfig(
        host = emqxHost,
        port = emqxPort,
        useTls = true,
        appid = emqxAppId,
        username = username,
        password = password,
    )

    /** R2 凭证完整时返回配置,否则返回 null(配对响应里 r2 可空)。 */
    fun buildR2(): R2Config? = if (r2AccessKey.isBlank() || r2AccessSecret.isBlank()) {
        null
    } else {
        R2Config(
            endpoint = r2Endpoint,
            bucket = r2Bucket,
            region = "auto",
            accessKey = r2AccessKey,
            accessSecret = r2AccessSecret,
            publicRead = true,
        )
    }

    /** MySQL 配置完整时返回,否则返回 null(DDL 跳过)。 */
    fun buildDbConfig(): DbConfig? = if (dbHost.isBlank() || dbName.isBlank()) {
        null
    } else {
        DbConfig(
            type = "mysql",
            host = dbHost,
            port = dbPort,
            name = dbName,
            user = dbUser,
            password = dbPassword,
        )
    }

    /** Bug#19:校验关键凭证是否就绪,返回缺失凭证的描述列表(空列表表示全部就绪)。 */
    fun validate(): List<String> {
        val missing = mutableListOf<String>()
        if (emqxAppSecret.isBlank()) missing += "ADB_EMQX_APP_SECRET (EMQX app secret)"
        if (r2AccessKey.isBlank()) missing += "ADB_R2_ACCESS_KEY (R2 access key)"
        if (r2AccessSecret.isBlank()) missing += "ADB_R2_ACCESS_SECRET (R2 access secret)"
        if (dbPassword.isBlank()) missing += "ADB_DB_PASSWORD (MySQL password)"
        return missing
    }

    companion object {
        // 已知默认值(README 第十章可直接用)
        private const val DEFAULT_SERVER_URL = "https://api.adbcontrol.example.com"
        private const val DEFAULT_EMQX_HOST = "o8cc1111.ala.cn-hangzhou.emqxsl.cn"
        private const val DEFAULT_EMQX_PORT = 8883
        private const val DEFAULT_EMQX_APP_ID = "o8cc1111"
        private const val DEFAULT_EMQX_REST = "https://o8cc1111.ala.cn-hangzhou.emqxsl.cn:8443"
        // EMQX 5.x REST API 发布路径(拼在 rest_endpoint=.../api/v5 之后 → /api/v5/publish)
        private const val DEFAULT_EMQX_PUBLISH_PATH = "/publish"
        private const val DEFAULT_R2_ENDPOINT = "https://696e933486bc331658bce6378aaceaea.r2.cloudflarestorage.com"
        private const val DEFAULT_R2_BUCKET = "slss-boby"
        private const val DEFAULT_DB_HOST = "mysql6.sqlpub.com"
        private const val DEFAULT_DB_PORT = 3311
        private const val DEFAULT_DB_NAME = "slss12"
        private const val DEFAULT_DB_USER = "slss12"

        /** secrets.properties 候选路径(相对工作目录逐级向上,直至项目根)。 */
        private val SECRET_CANDIDATES: List<String> = listOf(
            "secrets.properties",
            "../secrets.properties",
            "../../secrets.properties",
            "../../../secrets.properties",
            "/workspace/secrets.properties",
        )

        fun load(): BackendConfig {
            val props = loadSecretsFile()
            fun get(envKey: String, propKey: String, default: String): String =
                System.getenv(envKey)?.takeIf { it.isNotBlank() }
                    ?: props.getProperty(propKey)?.takeIf { it.isNotBlank() }
                    ?: default

            fun getInt(envKey: String, propKey: String, default: Int): Int {
                val result = System.getenv(envKey)?.toIntOrNull()
                    ?: props.getProperty(propKey)?.toIntOrNull()
                    ?: default
                // Bug#21:校验端口合法范围
                require(result in 1..65535) { "Port out of range 1..65535: $envKey/$propKey = $result" }
                return result
            }

            return BackendConfig(
                serverUrl = get("ADB_SERVER_URL", "server.url", DEFAULT_SERVER_URL),
                emqxHost = get("ADB_EMQX_HOST", "emqx.host", DEFAULT_EMQX_HOST),
                emqxPort = getInt("ADB_EMQX_PORT", "emqx.port", DEFAULT_EMQX_PORT),
                emqxAppId = get("ADB_EMQX_APP_ID", "emqx.appid", DEFAULT_EMQX_APP_ID),
                emqxRestEndpoint = get("ADB_EMQX_REST_ENDPOINT", "emqx.rest_endpoint", DEFAULT_EMQX_REST),
                emqxAppSecret = get("ADB_EMQX_APP_SECRET", "emqx.app_secret", ""),
                emqxPublishPath = get("ADB_EMQX_PUBLISH_PATH", "emqx.publish_path", DEFAULT_EMQX_PUBLISH_PATH),
                r2Endpoint = get("ADB_R2_ENDPOINT", "r2.endpoint", DEFAULT_R2_ENDPOINT),
                r2Bucket = get("ADB_R2_BUCKET", "r2.bucket", DEFAULT_R2_BUCKET),
                r2AccessKey = get("ADB_R2_ACCESS_KEY", "r2.access_key", ""),
                r2AccessSecret = get("ADB_R2_ACCESS_SECRET", "r2.access_secret", ""),
                dbHost = get("ADB_DB_HOST", "db.host", DEFAULT_DB_HOST),
                dbPort = getInt("ADB_DB_PORT", "db.port", DEFAULT_DB_PORT),
                dbName = get("ADB_DB_NAME", "db.name", DEFAULT_DB_NAME),
                dbUser = get("ADB_DB_USER", "db.user", DEFAULT_DB_USER),
                dbPassword = get("ADB_DB_PASSWORD", "db.password", ""),
                emqxIngestUsername = get("ADB_EMQX_INGEST_USERNAME", "emqx.ingest_username", ""),
                emqxIngestPassword = get("ADB_EMQX_INGEST_PASSWORD", "emqx.ingest_password", ""),
            )
        }

        private fun loadSecretsFile(): Properties {
            val props = Properties()
            val file = SECRET_CANDIDATES.map(::File).firstOrNull { it.exists() && it.canRead() }
            if (file != null) {
                file.inputStream().use { props.load(it) }
            }
            return props
        }
    }
}
