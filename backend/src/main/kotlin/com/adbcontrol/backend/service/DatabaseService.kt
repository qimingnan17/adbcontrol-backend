package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.backend.data.MysqlSchema
import com.adbcontrol.backend.model.AdminUser
import com.adbcontrol.backend.model.DbConfig
import com.adbcontrol.backend.security.PasswordHasher
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Statement

/**
 * MySQL 数据库服务(README 第七章 / 10.3)。
 *
 * - 启动时用 HikariCP 建连,执行 7.2 的 CREATE TABLE IF NOT EXISTS(幂等)。
 * - [registerDevice] upsert device 表(配对时调用,失败不影响配对)。
 *
 * 数据库不可达时不阻断服务启动:DDL 与 upsert 均 best-effort,失败仅告警。
 * [registerDevice] 在连接池为空时惰性重建(Bug#9),关闭时由 [close] 释放(Bug#24)。
 */
class DatabaseService(config: BackendConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val dbConfig: DbConfig? = config.buildDbConfig()
    @Volatile private var dataSource: HikariDataSource? = null

    init {
        if (dbConfig == null) {
            logger.warn("DB config incomplete, skipping MySQL init")
        } else {
            dataSource = createPool(dbConfig)
            runSchema()
            seedAdminIfEmpty()
        }
    }

    private fun createPool(cfg: DbConfig): HikariDataSource? =
        runCatching {
            val hikari = HikariConfig()
            hikari.jdbcUrl = buildString {
                append("jdbc:mysql://").append(cfg.host).append(':').append(cfg.port).append('/').append(cfg.name)
                append("?useUnicode=true&characterEncoding=UTF-8")
                // Bug#7:启用 SSL 加密(useSSL=true);远端 sqlpub 证书不一定可信,
                // 暂不强制校验,但关闭 allowPublicKeyRetrieval 以降低中间人重放风险。
                append("&useSSL=true&verifyServerCertificate=false&allowPublicKeyRetrieval=false")
                append("&serverTimezone=Asia/Shanghai")
                append("&connectTimeout=10000&socketTimeout=10000")
            }
            hikari.username = cfg.user
            hikari.password = cfg.password
            hikari.poolName = "adbcontrol-backend"
            hikari.maximumPoolSize = 8            // Bug#10:原 2 过小,提升至 8
            hikari.minimumIdle = 0
            hikari.connectionTimeout = 10_000
            hikari.leakDetectionThreshold = 60_000  // Bug#10:连接泄漏检测 60s
            hikari.initializationFailTimeout = 0  // 不在建池时因连不上而抛出
            HikariDataSource(hikari)
        }.onFailure { logger.warn("HikariCP pool creation failed (will retry on demand): {}", it.message) }
            .getOrNull()

    /** Bug#9:连接池为空时惰性重建,失败返回 null(调用方 best-effort 跳过)。 */
    private fun ensureDataSource(): HikariDataSource? {
        dataSource?.let { return it }
        val cfg = dbConfig ?: return null
        return synchronized(this) {
            dataSource?.let { return@synchronized it }
            val ds = createPool(cfg)
            if (ds != null) {
                dataSource = ds
                runSchema()
                seedAdminIfEmpty()
            }
            ds
        }
    }

    /** 执行 schema.sql 的 CREATE TABLE IF NOT EXISTS 以及 MysqlSchema 中定义的 DDL。 */
    private fun runSchema() {
        val ds = dataSource ?: return
        val sql = javaClass.classLoader.getResourceAsStream("db/schema.sql")?.use { it.readBytes() }
            ?.let { String(it, Charsets.UTF_8) }
        if (sql.isNullOrBlank()) {
            logger.warn("schema.sql not found in resources, skip DDL")
            return
        }
        runCatching {
            ds.connection.use { conn ->
                conn.autoCommit = true
                splitStatements(sql).forEach { stmt ->
                    if (stmt.isNotBlank()) {
                        conn.createStatement().use { it.execute(stmt) }
                    }
                }
                conn.createStatement().use { it.execute(MysqlSchema.CREATE_ADMIN_USER) }
            }
            logger.info("DDL executed (CREATE TABLE IF NOT EXISTS)")
        }.onFailure { logger.warn("DDL execution failed (best-effort): {}", it.message) }
    }

    /** 配对时 upsert device 表(幂等)。 */
    fun registerDevice(deviceId: String, name: String, appid: String, now: Long) {
        // Bug#9:连接池为空时惰性重建,仍失败则告警跳过(配对不受影响)
        val ds = dataSource ?: ensureDataSource() ?: run {
            logger.warn("registerDevice skipped: DataSource unavailable")
            return
        }
        // Bug #5:INSERT ... VALUES (...) AS new ... UPDATE col = new.col 仅 MySQL 8.0.19+ 支持,
        // 在 MySQL 5.x / MariaDB 10.3 及以下报 SQLSyntaxErrorException。
        // 回退为跨版本兼容的 VALUES(col) 写法,该语法 5.x/8.0/MariaDB 通用,
        // 8.0.19+ 虽标记废弃但长期可用,兼容性更优。
        val sql = """
            INSERT INTO device (device_id, name, appid, first_seen, last_seen, status)
            VALUES (?, ?, ?, ?, ?, 'offline')
            ON DUPLICATE KEY UPDATE
              name = VALUES(name), appid = VALUES(appid), last_seen = VALUES(last_seen)
        """.trimIndent()
        ds.connection.use { conn: Connection ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, name)
                ps.setString(3, appid)
                ps.setLong(4, now)
                ps.setLong(5, now)
                ps.executeUpdate()
            }
        }
        logger.debug("registerDevice upsert: {}", deviceId)
    }

    private fun splitStatements(sql: String): List<String> {
        // 去注释行 + 按分号切分(DDL 内无分号,安全)
        return sql.lines()
            .map { line -> line.substringBefore("--").trim() }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun createAdmin(username: String, passwordPlain: String, role: String = "admin"): AdminUser {
        val ds = dataSource ?: ensureDataSource() ?: error("DataSource unavailable")
        val now = System.currentTimeMillis()
        val passwordHash = PasswordHasher.hash(passwordPlain)
        val sql = """
            INSERT INTO admin_user (username, password_hash, role, created_at)
            VALUES (?, ?, ?, ?)
        """.trimIndent()
        ds.connection.use { conn ->
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, username)
                ps.setString(2, passwordHash)
                ps.setString(3, role)
                ps.setLong(4, now)
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    if (rs.next()) {
                        val id = rs.getInt(1)
                        return AdminUser(
                            id = id,
                            username = username,
                            passwordHash = passwordHash,
                            role = role,
                            createdAt = now,
                            lastLoginAt = 0L,
                            totpSecret = null
                        )
                    }
                }
            }
        }
        error("Failed to create admin: no generated ID returned")
    }

    fun findAdminByUsername(username: String): AdminUser? {
        val ds = dataSource ?: ensureDataSource() ?: return null
        val sql = """
            SELECT id, username, password_hash, role, created_at, last_login_at, totp_secret
            FROM admin_user WHERE username = ?
        """.trimIndent()
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, username)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            AdminUser(
                                id = rs.getInt("id"),
                                username = rs.getString("username"),
                                passwordHash = rs.getString("password_hash"),
                                role = rs.getString("role"),
                                createdAt = rs.getLong("created_at"),
                                lastLoginAt = rs.getLong("last_login_at"),
                                totpSecret = rs.getString("totp_secret")
                            )
                        } else null
                    }
                }
            }
        }.getOrElse {
            logger.warn("findAdminByUsername failed: {}", it.message)
            null
        }
    }

    fun updateAdminLastLogin(id: Int) {
        val ds = dataSource ?: ensureDataSource() ?: return
        val now = System.currentTimeMillis()
        val sql = "UPDATE admin_user SET last_login_at = ? WHERE id = ?"
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, now)
                    ps.setInt(2, id)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("updateAdminLastLogin failed: {}", it.message) }
    }

    fun listAdmins(): List<AdminUser> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        val sql = "SELECT id, username, role, created_at, last_login_at, totp_secret FROM admin_user ORDER BY id ASC"
        return runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        val list = mutableListOf<AdminUser>()
                        while (rs.next()) {
                            list += AdminUser(
                                id = rs.getInt("id"),
                                username = rs.getString("username"),
                                passwordHash = "",
                                role = rs.getString("role"),
                                createdAt = rs.getLong("created_at"),
                                lastLoginAt = rs.getLong("last_login_at"),
                                totpSecret = rs.getString("totp_secret")
                            )
                        }
                        list
                    }
                }
            }
        }.getOrElse {
            logger.warn("listAdmins failed: {}", it.message)
            emptyList()
        }
    }

    private fun seedAdminIfEmpty() {
        val ds = dataSource ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM admin_user").use { rs ->
                        if (rs.next() && rs.getInt(1) == 0) {
                            logger.warn("初始管理员账号: admin / admin123 请立即修改！")
                            createAdmin("admin", "admin123", "admin")
                        }
                    }
                }
            }
        }.onFailure {
            logger.warn("seedAdminIfEmpty skipped (best-effort): {}", it.message)
        }
    }

    data class DeviceRow(
        val deviceId: String,
        val name: String,
        val appid: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val status: String,
        val batteryPct: Int? = null,
        val isCharging: Boolean? = null,
        val netType: String? = null,
        val signalDbm: Int? = null,
        val lat: Double? = null,
        val lon: Double? = null
    )

    fun listDevicesWithStatus(): List<DeviceRow> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        val sql = """
            SELECT d.device_id, d.name, d.appid, d.first_seen, d.last_seen, d.status,
                   s.battery, s.charging, s.network, s.network_strength,
                   l.lat, l.lng
            FROM device d
            LEFT JOIN device_status s ON s.device_id = d.device_id
            LEFT JOIN location_history l ON l.device_id = d.device_id
              AND l.id = (SELECT MAX(id) FROM location_history WHERE device_id = d.device_id)
            ORDER BY d.last_seen DESC
        """.trimIndent()
        return runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        val list = mutableListOf<DeviceRow>()
                        while (rs.next()) {
                            list += DeviceRow(
                                deviceId = rs.getString("device_id"),
                                name = rs.getString("name") ?: "",
                                appid = rs.getString("appid"),
                                firstSeen = rs.getLong("first_seen"),
                                lastSeen = rs.getLong("last_seen"),
                                status = rs.getString("status"),
                                batteryPct = rs.getObject("battery")?.let { (it as Number).toInt() },
                                isCharging = rs.getObject("charging") as? Boolean,
                                netType = rs.getString("network"),
                                signalDbm = rs.getObject("network_strength")?.let { (it as Number).toInt() },
                                lat = rs.getObject("lat")?.let { (it as Number).toDouble() },
                                lon = rs.getObject("lng")?.let { (it as Number).toDouble() }
                            )
                        }
                        list
                    }
                }
            }
        }.getOrElse {
            logger.warn("listDevicesWithStatus failed: {}", it.message)
            emptyList()
        }
    }

    fun getDeviceOverview(deviceId: String): Map<String, Any?> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyMap()
        return runCatching {
            ds.connection.use { conn ->
                val result = mutableMapOf<String, Any?>()

                conn.prepareStatement(
                    "SELECT device_id, name, appid, first_seen, last_seen, status FROM device WHERE device_id = ?"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            result["device"] = mapOf(
                                "deviceId" to rs.getString("device_id"),
                                "name" to (rs.getString("name") ?: ""),
                                "appid" to rs.getString("appid"),
                                "firstSeen" to rs.getLong("first_seen"),
                                "lastSeen" to rs.getLong("last_seen"),
                                "status" to rs.getString("status")
                            )
                        }
                    }
                }

                conn.prepareStatement(
                    "SELECT online, battery, charging, network, network_strength, screen_on, foreground_pkg, shizuku, root, accessibility, device_admin, android_version, app_version, last_seen FROM device_status WHERE device_id = ?"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.executeQuery().use { rs ->
                        val statusList = mutableListOf<Map<String, Any?>>()
                        while (rs.next()) {
                            statusList += mapOf(
                                "online" to (rs.getObject("online") as? Boolean),
                                "battery" to rs.getObject("battery")?.let { (it as Number).toInt() },
                                "charging" to (rs.getObject("charging") as? Boolean),
                                "network" to rs.getString("network"),
                                "networkStrength" to rs.getObject("network_strength")?.let { (it as Number).toInt() },
                                "screenOn" to (rs.getObject("screen_on") as? Boolean),
                                "foregroundPkg" to rs.getString("foreground_pkg"),
                                "shizuku" to (rs.getObject("shizuku") as? Boolean),
                                "root" to (rs.getObject("root") as? Boolean),
                                "accessibility" to (rs.getObject("accessibility") as? Boolean),
                                "deviceAdmin" to (rs.getObject("device_admin") as? Boolean),
                                "androidVersion" to rs.getString("android_version"),
                                "appVersion" to rs.getString("app_version"),
                                "lastSeen" to rs.getLong("last_seen")
                            )
                        }
                        result["statusHistory"] = statusList
                    }
                }

                conn.prepareStatement(
                    "SELECT id, event, pkg, app_name, duration_ms, occurred_at FROM app_activity_log WHERE device_id = ? ORDER BY occurred_at DESC LIMIT 20"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.executeQuery().use { rs ->
                        val activityList = mutableListOf<Map<String, Any?>>()
                        while (rs.next()) {
                            activityList += mapOf(
                                "id" to rs.getLong("id"),
                                "event" to rs.getString("event"),
                                "pkg" to rs.getString("pkg"),
                                "appName" to rs.getString("app_name"),
                                "durationMs" to rs.getObject("duration_ms")?.let { (it as Number).toLong() },
                                "occurredAt" to rs.getLong("occurred_at")
                            )
                        }
                        result["activityLogs"] = activityList
                    }
                }

                conn.prepareStatement(
                    "SELECT id, pkg, title, text, posted_at FROM notification_log WHERE device_id = ? ORDER BY posted_at DESC LIMIT 20"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.executeQuery().use { rs ->
                        val notifList = mutableListOf<Map<String, Any?>>()
                        while (rs.next()) {
                            notifList += mapOf(
                                "id" to rs.getLong("id"),
                                "pkg" to rs.getString("pkg"),
                                "title" to rs.getString("title"),
                                "text" to rs.getString("text"),
                                "postedAt" to rs.getLong("posted_at")
                            )
                        }
                        result["notificationLogs"] = notifList
                    }
                }

                val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                conn.prepareStatement(
                    "SELECT id, user_id, pkg, usage_minutes, date, uploaded_at FROM app_usage_daily WHERE device_id = ? AND uploaded_at >= ? ORDER BY uploaded_at DESC"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setLong(2, sevenDaysAgo)
                    ps.executeQuery().use { rs ->
                        val usageList = mutableListOf<Map<String, Any?>>()
                        while (rs.next()) {
                            usageList += mapOf(
                                "id" to rs.getLong("id"),
                                "userId" to rs.getString("user_id"),
                                "pkg" to rs.getString("pkg"),
                                "usageMinutes" to rs.getInt("usage_minutes"),
                                "date" to rs.getString("date"),
                                "uploadedAt" to rs.getLong("uploaded_at")
                            )
                        }
                        result["dailyUsage"] = usageList
                    }
                }

                result
            }
        }.getOrElse {
            logger.warn("getDeviceOverview failed: {}", it.message)
            emptyMap()
        }
    }

    fun listTasks(deviceId: String? = null): List<Map<String, Any>> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        return runCatching {
            ds.connection.use { conn ->
                val sql = if (deviceId == null)
                    "SELECT task_id, device_id, rule_type, cron_expr, command_json, enabled, created_at FROM task ORDER BY created_at DESC"
                else
                    "SELECT task_id, device_id, rule_type, cron_expr, command_json, enabled, created_at FROM task WHERE device_id = ? ORDER BY created_at DESC"
                conn.prepareStatement(sql).use { ps ->
                    if (deviceId != null) ps.setString(1, deviceId)
                    ps.executeQuery().use { rs ->
                        val list: MutableList<Map<String, Any>> = mutableListOf()
                        while (rs.next()) {
                            list += mapOf(
                                "id" to rs.getLong("task_id"),
                                "deviceId" to rs.getString("device_id"),
                                "ruleType" to rs.getString("rule_type"),
                                "cronExpr" to (rs.getString("cron_expr") ?: ""),
                                "commandJson" to (rs.getString("command_json") ?: "{}"),
                                "enabled" to rs.getBoolean("enabled"),
                                "createdAt" to rs.getLong("created_at")
                            )
                        }
                        list
                    }
                }
            }
        }.getOrElse {
            logger.warn("listTasks failed: {}", it.message)
            emptyList()
        }
    }

    fun upsertTask(task: Map<String, Any>): Int? {
        val ds = dataSource ?: ensureDataSource() ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            ds.connection.use { conn ->
                val id = task["id"]?.toString()?.toLongOrNull()
                val deviceId = task["deviceId"]?.toString() ?: task["device_id"]?.toString() ?: ""
                val ruleType = task["ruleType"]?.toString() ?: task["rule_type"]?.toString() ?: "cron"
                val cronExpr = task["cronExpr"]?.toString() ?: task["cron_expr"]?.toString() ?: ""
                val commandJson = task["commandJson"]?.toString() ?: task["command_json"]?.toString() ?: task["payloadJson"]?.toString() ?: task["payload_json"]?.toString() ?: "{}"
                val enabled = (task["enabled"] as? Boolean) ?: true
                if (id == null) {
                    val sql = "INSERT INTO task (device_id, rule_type, cron_expr, command_json, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?)"
                    conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                        ps.setString(1, deviceId)
                        ps.setString(2, ruleType)
                        ps.setString(3, cronExpr)
                        ps.setString(4, commandJson)
                        ps.setBoolean(5, enabled)
                        ps.setLong(6, now)
                        ps.executeUpdate()
                        ps.generatedKeys.use { rs ->
                            if (rs.next()) rs.getInt(1) else null
                        }
                    }
                } else {
                    val sql = "UPDATE task SET device_id=?, rule_type=?, cron_expr=?, command_json=?, enabled=? WHERE task_id=?"
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, deviceId)
                        ps.setString(2, ruleType)
                        ps.setString(3, cronExpr)
                        ps.setString(4, commandJson)
                        ps.setBoolean(5, enabled)
                        ps.setLong(6, id)
                        ps.executeUpdate()
                        id.toInt()
                    }
                }
            }
        }.getOrElse {
            logger.warn("upsertTask failed: {}", it.message)
            null
        }
    }

    fun deleteTask(id: Int): Int {
        val ds = dataSource ?: ensureDataSource() ?: return 0
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement("DELETE FROM task WHERE task_id = ?").use { ps ->
                    ps.setInt(1, id)
                    ps.executeUpdate()
                }
            }
        }.getOrElse {
            logger.warn("deleteTask failed: {}", it.message)
            0
        }
    }

    fun listRecentCommands(deviceId: String, limit: Int = 50): List<Map<String, Any>> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        return runCatching {
            ds.connection.use { conn ->
                val sql = "SELECT id, task_id, device_id, msg_id, success, output, duration_ms, executed_at FROM execution_log WHERE device_id = ? ORDER BY executed_at DESC LIMIT ?"
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs ->
                        val cmdList: MutableList<Map<String, Any>> = mutableListOf()
                        while (rs.next()) {
                            val row: Map<String, Any> = mapOf(
                                "id" to rs.getLong("id"),
                                "taskId" to (rs.getObject("task_id")?.let { (it as Number).toLong() } ?: 0L),
                                "deviceId" to rs.getString("device_id"),
                                "msgId" to (rs.getString("msg_id") ?: ""),
                                "success" to rs.getBoolean("success"),
                                "output" to (rs.getString("output") ?: ""),
                                "durationMs" to rs.getInt("duration_ms"),
                                "executedAt" to rs.getLong("executed_at")
                            )
                            cmdList.add(row)
                        }
                        cmdList
                    }
                }
            }
        }.getOrElse {
            logger.warn("listRecentCommands failed: {}", it.message)
            emptyList()
        }
    }

    /** Bug#24:应用关闭时释放 HikariCP 连接池。 */
    override fun close() {
        dataSource?.let { ds ->
            runCatching { ds.close() }.onFailure { logger.warn("HikariCP close failed: {}", it.message) }
        }
        dataSource = null
    }
}
