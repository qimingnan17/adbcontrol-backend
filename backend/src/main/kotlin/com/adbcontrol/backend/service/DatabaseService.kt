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
            // 2核4G 部署适配:DB 不可达时 runSchema 的多次建连重试曾把启动线程阻塞
            // 60s+,HTTP 迟迟不应答。DDL 幂等,移到后台线程执行,失败仅告警。
            Thread({ runSchema() }, "db-schema-init").apply { isDaemon = true }.start()
            // 管理员账号不在启动时种入:由 /api/setup 在首次访问时初始化(见 AuthRoutes)
        }
    }

    private fun createPool(cfg: DbConfig): HikariDataSource? =
        runCatching {
            val hikari = HikariConfig()
            hikari.jdbcUrl = buildString {
                append("jdbc:mysql://").append(cfg.host).append(':').append(cfg.port).append('/').append(cfg.name)
                append("?useUnicode=true&characterEncoding=UTF-8")
                val isLocal = cfg.host == "127.0.0.1" || cfg.host.equals("localhost", ignoreCase = true)
                if (isLocal) {
                    append("&useSSL=false&allowPublicKeyRetrieval=true")
                } else {
                    append("&useSSL=true&verifyServerCertificate=false&allowPublicKeyRetrieval=false")
                }
                append("&serverTimezone=Asia/Shanghai")

                append("&connectTimeout=5000&socketTimeout=10000")
            }
            hikari.username = cfg.user
            hikari.password = cfg.password
            hikari.poolName = "adbcontrol-backend"
            hikari.maximumPoolSize = 8            // Bug#10:原 2 过小,提升至 8
            hikari.minimumIdle = 0
            hikari.connectionTimeout = 5_000      // 降级运行时 /health 探测不必等 10s
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

        // 增量迁移:老库补列(CREATE TABLE IF NOT EXISTS 不会给已存在的表加列)。
        // 列已存在时报 Duplicate column,属预期,仅记 info。
        listOf(
            "ALTER TABLE app_usage_daily ADD COLUMN app_name VARCHAR(128) NULL",
            "ALTER TABLE app_usage_daily ADD COLUMN icon_url VARCHAR(512) NULL",
            "ALTER TABLE task ADD COLUMN last_fired_at BIGINT NULL",
        ).forEach { ddl ->
            runCatching {
                ds.connection.use { conn -> conn.createStatement().use { it.execute(ddl) } }
            }.onFailure { logger.info("migrate skipped (likely exists): {}", it.message) }
        }
    }

    /** 检查数据库连接池存活状态(供健康检查路由 /health 调用) */
    fun isHealthy(): Boolean {
        val ds = dataSource ?: return false
        return runCatching {
            ds.connection.use { conn -> conn.isValid(2) }
        }.getOrDefault(false)
    }

    /**
     * 原子记录任务发火时间。成功更新返回 true,已在该时刻或更新时刻发过则返回 false。
     * 用于多实例并发防重与重启防重。
     */
    fun markTaskFired(taskId: Long, fireAt: Long): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: run {
            logger.error("markTaskFired skipped: DataSource unavailable for taskId={}", taskId)
            return false
        }
        return runCatching {
            ds.connection.use { conn ->
                val sql = "UPDATE task SET last_fired_at = ? WHERE task_id = ? AND (last_fired_at IS NULL OR last_fired_at < ?)"
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, fireAt)
                    ps.setLong(2, taskId)
                    ps.setLong(3, fireAt)
                    ps.executeUpdate() > 0
                }
            }
        }.getOrElse {
            logger.error("markTaskFired failed for taskId={}: {}", taskId, it.message)
            false
        }
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

    // ---- 配对会话持久化(pair_session) ----
    // 目标:后端重启后恢复的配对状态,避免已配对设备被迫重新扫码(README 8.3 的约束:
    // 内存 ConcurrentHashMap 存续期与进程相同,不满足生产部署需求)。

    data class PairSessionRow(
        val deviceId: String,
        val pairToken: String,
        val sessionKey: String,
        val mqttPassword: String,
        val expiresAt: Long,
    )

    /** 配对成功 / 续期时更新会话记录(upsert)。 */
    fun upsertPairSession(row: PairSessionRow) {
        val ds = dataSource ?: ensureDataSource() ?: return
        val now = System.currentTimeMillis()
        val sql = """
            INSERT INTO pair_session
              (device_id, pair_token, session_key, mqtt_password, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              pair_token = VALUES(pair_token), session_key = VALUES(session_key),
              mqtt_password = VALUES(mqtt_password), expires_at = VALUES(expires_at),
              updated_at = VALUES(updated_at)
        """.trimIndent()
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, row.deviceId)
                    ps.setString(2, row.pairToken)
                    ps.setString(3, row.sessionKey)
                    ps.setString(4, row.mqttPassword)
                    ps.setLong(5, row.expiresAt)
                    ps.setLong(6, now)
                    ps.setLong(7, now)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("upsertPairSession failed for ${row.deviceId}: {}", it.message) }
    }

    /** 服务启动时加载全部会话(供 PairingService 恢复内存表)。 */
    fun listPairSessions(): List<PairSessionRow> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        return runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT device_id, pair_token, session_key, mqtt_password, expires_at FROM pair_session"
                    ).use { rs ->
                        val list = mutableListOf<PairSessionRow>()
                        while (rs.next()) {
                            list += PairSessionRow(
                                deviceId = rs.getString("device_id"),
                                pairToken = rs.getString("pair_token"),
                                sessionKey = rs.getString("session_key"),
                                mqttPassword = rs.getString("mqtt_password"),
                                expiresAt = rs.getLong("expires_at"),
                            )
                        }
                        list
                    }
                }
            }
        }.getOrElse {
            logger.warn("listPairSessions failed: {}", it.message)
            emptyList()
        }
    }

    /** 吊销时删除会话。 */
    fun deletePairSession(deviceId: String) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement("DELETE FROM pair_session WHERE device_id = ?").use { ps ->
                    ps.setString(1, deviceId)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("deletePairSession failed for $deviceId: {}", it.message) }
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

    fun getPrimaryAdmin(): AdminUser? {
        val ds = dataSource ?: ensureDataSource() ?: return null
        val sql = "SELECT id, username, password_hash, role, created_at, last_login_at, totp_secret FROM admin_user ORDER BY id ASC LIMIT 1"
        return runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
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
        }.getOrNull()
    }

    /**
     * 管理员是否已初始化。DB 不可达时返回 true(fail-closed):
     * 宁可不显示初始化界面,也不允许在状态未知时开放创建管理员的入口。
     */
    fun isAdminInitialized(): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: return true
        return runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM admin_user").use { rs ->
                        rs.next() && rs.getInt(1) > 0
                    }
                }
            }
        }.getOrElse {
            logger.warn("isAdminInitialized failed: {}", it.message)
            true
        }
    }

    /**
     * 首次初始化管理员(仅当 admin_user 为空时成功)。并发竞争依赖
     * username 的 UNIQUE 约束:第二个插入者抛重复键异常返回 null。
     */
    fun createInitialAdmin(username: String, passwordPlain: String): AdminUser? {
        val ds = dataSource ?: ensureDataSource() ?: return null
        if (isAdminInitialized()) return null
        return runCatching {
            val admin = createAdmin(username, passwordPlain, "admin")
            logger.info("initial admin '{}' created via /api/setup", username)
            admin
        }.onFailure {
            // 并发竞争(UNIQUE 冲突)或 DB 异常都会走到这里
            logger.warn("createInitialAdmin failed: {}", it.message)
        }.getOrNull()
    }

    /** /api/devices 响应体,需可序列化(缺 @Serializable 时 respond 500)。 */
    @kotlinx.serialization.Serializable
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

    /** 设备台账是否存在(DB 视角)。 */
    fun deviceExists(deviceId: String): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: return false
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM device WHERE device_id = ?").use { ps ->
                    ps.setString(1, deviceId)
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }
            }
        }.getOrElse {
            logger.warn("deviceExists failed for $deviceId: {}", it.message)
            false
        }
    }

    /** 设备名称是否已被占用(设备名称作为主标识,生成配对令牌时强制唯一)。 */
    fun deviceNameExists(name: String): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: return false
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM device WHERE name = ?").use { ps ->
                    ps.setString(1, name)
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }
            }
        }.getOrElse {
            logger.warn("deviceNameExists failed: {}", it.message)
            false
        }
    }

    /**
     * 彻底删除设备及全部关联数据(级联)。schema 无外键约束,逐表删除并包在事务里,
     * 任一表失败整体回滚,避免留下半删状态。返回是否成功。
     */
    fun deleteDevice(deviceId: String): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: return false
        val tables = listOf(
            "app_usage_daily", "app_activity_log", "notification_log",
            "location_history", "execution_log", "task_ack", "task",
            "app", "device_status", "pair_session", "device",
        )
        return runCatching {
            ds.connection.use { conn ->
                val prevAutoCommit = conn.autoCommit
                try {
                    conn.autoCommit = false
                    for (t in tables) {
                        conn.prepareStatement("DELETE FROM $t WHERE device_id = ?").use { ps ->
                            ps.setString(1, deviceId)
                            ps.executeUpdate()
                        }
                    }
                    conn.commit()
                    true
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                    throw e
                } finally {
                    conn.autoCommit = prevAutoCommit
                }
            }
        }.getOrElse {
            logger.warn("deleteDevice failed for $deviceId: {}", it.message)
            false
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

                val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                conn.prepareStatement(
                    "SELECT id, user_id, pkg, app_name, icon_url, usage_minutes, date, uploaded_at FROM app_usage_daily WHERE device_id = ? AND uploaded_at >= ? ORDER BY uploaded_at DESC"
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
                                "appName" to rs.getString("app_name"),
                                "iconUrl" to rs.getString("icon_url"),
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

    /**
     * 任务 upsert。id 为空走 INSERT(缺省字段用默认值);id 非空走 UPDATE,
     * 请求中为 null 的字段保留库里原值(部分更新),行不存在返回 null。
     */
    fun upsertTask(req: com.adbcontrol.backend.model.TaskRequest): Int? {
        val ds = dataSource ?: ensureDataSource() ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            ds.connection.use { conn ->
                if (req.id == null) {
                    val sql = "INSERT INTO task (device_id, rule_type, cron_expr, command_json, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?)"
                    conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                        ps.setString(1, req.deviceId ?: "")
                        ps.setString(2, req.ruleType ?: "cron")
                        ps.setString(3, req.cronExpr ?: "")
                        ps.setString(4, req.commandJson ?: "{}")
                        ps.setBoolean(5, req.enabled ?: true)
                        ps.setLong(6, now)
                        ps.executeUpdate()
                        ps.generatedKeys.use { rs ->
                            if (rs.next()) rs.getInt(1) else null
                        }
                    }
                } else {
                    // 部分更新:先取原行,null 字段沿用旧值,避免只传 enabled 时清空其余列
                    val existing = conn.prepareStatement(
                        "SELECT device_id, rule_type, cron_expr, command_json, enabled FROM task WHERE task_id = ?"
                    ).use { ps ->
                        ps.setLong(1, req.id)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) arrayOf(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)
                            ) else null
                        }
                    } ?: return@runCatching null
                    val sql = "UPDATE task SET device_id=?, rule_type=?, cron_expr=?, command_json=?, enabled=? WHERE task_id=?"
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, req.deviceId ?: existing[0] as String)
                        ps.setString(2, req.ruleType ?: existing[1] as String)
                        ps.setString(3, req.cronExpr ?: existing[2] as String)
                        ps.setString(4, req.commandJson ?: existing[3] as String)
                        ps.setBoolean(5, req.enabled ?: existing[4] as Boolean)
                        ps.setLong(6, req.id)
                        ps.executeUpdate()
                        req.id.toInt()
                    }
                }
            }
        }.getOrElse {
            logger.warn("upsertTask failed: {}", it.message)
            null
        }
    }

    fun deleteTask(id: Long): Int {
        val ds = dataSource ?: ensureDataSource() ?: return 0
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement("DELETE FROM task WHERE task_id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
            }
        }.getOrElse {
            logger.warn("deleteTask failed: {}", it.message)
            0
        }
    }

    /**
     * 通知签收回报入库(task_ack)。ack_id 即 REMINDER_RESULT 消息的 envelope id,
     * UNIQUE KEY 拦截 QoS 1 重发。
     */
    fun insertTaskAck(ackId: String, deviceId: String, taskId: Long?, buttonText: String, ackedAt: Long) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO task_ack (ack_id, task_id, device_id, button_text, acked_at) VALUES (?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, ackId)
                    if (taskId != null) ps.setLong(2, taskId) else ps.setNull(2, java.sql.Types.BIGINT)
                    ps.setString(3, deviceId)
                    ps.setString(4, buttonText)
                    ps.setLong(5, ackedAt)
                    ps.executeUpdate()
                }
            }
        }.onFailure {
            // Duplicate entry 属正常幂等拦截,不打 warning
            if (it.message?.contains("Duplicate entry") != true) {
                logger.warn("insertTaskAck failed for {}: {}", deviceId, it.message)
            }
        }
    }

    /** 按任务查询签收列表(任务详情面板)。taskId 为 null 时返回全部(含手动下发)。 */
    fun listTaskAcks(taskId: Long?): List<Map<String, Any>> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        return runCatching {
            ds.connection.use { conn ->
                val sql = if (taskId == null)
                    "SELECT ack_id, task_id, device_id, button_text, acked_at FROM task_ack ORDER BY acked_at DESC LIMIT 200"
                else
                    "SELECT ack_id, task_id, device_id, button_text, acked_at FROM task_ack WHERE task_id = ? ORDER BY acked_at DESC LIMIT 200"
                conn.prepareStatement(sql).use { ps ->
                    if (taskId != null) ps.setLong(1, taskId)
                    ps.executeQuery().use { rs ->
                        val list: MutableList<Map<String, Any>> = mutableListOf()
                        while (rs.next()) {
                            list += mapOf(
                                "ackId" to rs.getString("ack_id"),
                                "taskId" to rs.getLong("task_id"),
                                "deviceId" to rs.getString("device_id"),
                                // 空串保护:rs.getLong 对 NULL 返回 0,读到 task_id 为空时给 0
                                "buttonText" to rs.getString("button_text"),
                                "ackedAt" to rs.getLong("acked_at"),
                            )
                        }
                        list
                    }
                }
            }
        }.getOrElse {
            logger.warn("listTaskAcks failed: {}", it.message)
            emptyList()
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
                                "commandId" to (rs.getString("msg_id") ?: ""),
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
    // ---- 遥测 ingestor 写入(TelemetryIngestService 上行落库) ----
    // 全部 best-effort:DB 不可达时仅告警,不拖垮 MQTT 消费线程。

    /** status/{deviceId} 上行 → device_status 覆盖写 + device.last_seen/status 刷新。 */
    fun upsertDeviceStatus(
        deviceId: String,
        battery: Int,
        charging: Boolean,
        network: String,
        networkStrength: Int,
        screenOn: Boolean,
        foregroundPkg: String?,
        lastSeen: Long,
    ) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO device_status
                      (device_id, online, battery, charging, network, network_strength, screen_on, foreground_pkg, last_seen)
                    VALUES (?, TRUE, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      online = TRUE, battery = VALUES(battery), charging = VALUES(charging),
                      network = VALUES(network), network_strength = VALUES(network_strength),
                      screen_on = VALUES(screen_on), foreground_pkg = VALUES(foreground_pkg),
                      last_seen = VALUES(last_seen)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setInt(2, battery)
                    ps.setBoolean(3, charging)
                    ps.setString(4, network)
                    ps.setInt(5, networkStrength)
                    ps.setBoolean(6, screenOn)
                    ps.setString(7, foregroundPkg)
                    ps.setLong(8, lastSeen)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "UPDATE device SET last_seen = ?, status = 'online' WHERE device_id = ?"
                ).use { ps ->
                    ps.setLong(1, lastSeen)
                    ps.setString(2, deviceId)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("upsertDeviceStatus failed for {}: {}", deviceId, it.message) }
    }

    /** health/{deviceId} 上行 → device_status 能力列守护更新。 */
    fun applyHealth(
        deviceId: String,
        shizukuConnected: Boolean,
        root: Boolean,
        accessibility: Boolean,
        deviceAdmin: Boolean,
        androidVersion: String,
        appVersion: String,
        lastSeen: Long,
    ) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO device_status
                      (device_id, online, shizuku, root, accessibility, device_admin, android_version, app_version, last_seen)
                    VALUES (?, TRUE, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      online = TRUE, shizuku = VALUES(shizuku), root = VALUES(root),
                      accessibility = VALUES(accessibility), device_admin = VALUES(device_admin),
                      android_version = VALUES(android_version), app_version = VALUES(app_version),
                      last_seen = VALUES(last_seen)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setBoolean(2, shizukuConnected)
                    ps.setBoolean(3, root)
                    ps.setBoolean(4, accessibility)
                    ps.setBoolean(5, deviceAdmin)
                    ps.setString(6, androidVersion)
                    ps.setString(7, appVersion)
                    ps.setLong(8, lastSeen)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "UPDATE device SET last_seen = ?, status = 'online' WHERE device_id = ?"
                ).use { ps ->
                    ps.setLong(1, lastSeen)
                    ps.setString(2, deviceId)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("applyHealth failed for {}: {}", deviceId, it.message) }
    }

    /** location/{deviceId} 上行 → location_history 追加。 */
    fun insertLocation(
        deviceId: String,
        userId: String?,
        lat: Double,
        lng: Double,
        accuracy: Float,
        speed: Float,
        provider: String,
        fenceEvent: String?,
        reportedAt: Long,
    ) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO location_history (device_id, user_id, lat, lng, accuracy, speed, provider, fence_event, reported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    if (userId == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, userId)
                    ps.setDouble(3, lat)
                    ps.setDouble(4, lng)
                    ps.setFloat(5, accuracy)
                    ps.setFloat(6, speed)
                    ps.setString(7, provider)
                    if (fenceEvent == null) ps.setNull(8, java.sql.Types.VARCHAR) else ps.setString(8, fenceEvent)
                    ps.setLong(9, reportedAt)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("insertLocation failed for {}: {}", deviceId, it.message) }
    }

    /** activity/{deviceId} 上行 → app_activity_log(UNIQUE 幂等)。 */
    fun insertActivity(
        deviceId: String,
        userId: String?,
        event: String,
        pkg: String,
        appName: String?,
        durationMs: Long,
        occurredAt: Long,
    ) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT IGNORE INTO app_activity_log (device_id, user_id, event, pkg, app_name, duration_ms, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    if (userId == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, userId)
                    ps.setString(3, event)
                    ps.setString(4, pkg)
                    if (appName == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, appName)
                    ps.setLong(6, durationMs)
                    ps.setLong(7, occurredAt)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("insertActivity failed for {}: {}", deviceId, it.message) }
    }

    /** usage/{deviceId} 上行 → app_usage_daily(UNIQUE 复合键幂等)。appName/iconUrl 供 Web 展示应用名与官方图标。 */
    fun upsertUsageItem(
        deviceId: String,
        userId: String,
        pkg: String,
        usageMinutes: Int,
        date: String,
        uploadedAt: Long,
        appName: String? = null,
        iconUrl: String? = null,
    ) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO app_usage_daily (device_id, user_id, pkg, app_name, icon_url, usage_minutes, date, uploaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      app_name = COALESCE(VALUES(app_name), app_name),
                      icon_url = COALESCE(VALUES(icon_url), icon_url),
                      usage_minutes = VALUES(usage_minutes), uploaded_at = VALUES(uploaded_at)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setString(2, userId)
                    ps.setString(3, pkg)
                    if (appName == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, appName)
                    if (iconUrl == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, iconUrl)
                    ps.setInt(6, usageMinutes)
                    ps.setString(7, date)
                    ps.setLong(8, uploadedAt)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("upsertUsageItem failed for {}: {}", deviceId, it.message) }
    }

    /** result/{deviceId} 上行 → execution_log(uq_msg_id 幂等)。 */
    fun insertExecutionLog(
        deviceId: String,
        msgId: String,
        success: Boolean,
        output: String,
        durationMs: Long,
        executedAt: Long,
    ) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT IGNORE INTO execution_log (task_id, device_id, msg_id, success, output, duration_ms, executed_at) VALUES (NULL, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setString(2, msgId)
                    ps.setBoolean(3, success)
                    ps.setString(4, output)
                    ps.setLong(5, durationMs)
                    ps.setLong(6, executedAt)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("insertExecutionLog failed for {}: {}", deviceId, it.message) }
    }

    /** device/offline/{deviceId} LWT → 标记设备离线。 */
    fun markOffline(deviceId: String, ts: Long) {
        val ds = dataSource ?: ensureDataSource() ?: return
        runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE device SET status = 'offline', last_seen = ? WHERE device_id = ?"
                ).use { ps ->
                    ps.setLong(1, ts)
                    ps.setString(2, deviceId)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "UPDATE device_status SET online = FALSE, last_seen = ? WHERE device_id = ?"
                ).use { ps ->
                    ps.setLong(1, ts)
                    ps.setString(2, deviceId)
                    ps.executeUpdate()
                }
            }
        }.onFailure { logger.warn("markOffline failed for {}: {}", deviceId, it.message) }
    }

    // ---- OTA 版本清单(app_version_manifest) ----

    /** 发布/覆盖一个版本清单(UNIQUE(version_code, channel) 幂等)。返回是否成功。 */
    fun upsertVersionManifest(m: com.adbcontrol.backend.model.VersionManifest): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: return false
        val sql = """
            INSERT INTO app_version_manifest
              (version_code, version_name, channel, priority, full_apk_url,
               patch_url, patch_from_version_code, patch_to_version_code,
               sha256, size_bytes, release_notes, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              version_name = VALUES(version_name), priority = VALUES(priority),
              full_apk_url = VALUES(full_apk_url), patch_url = VALUES(patch_url),
              patch_from_version_code = VALUES(patch_from_version_code),
              patch_to_version_code = VALUES(patch_to_version_code),
              sha256 = VALUES(sha256), size_bytes = VALUES(size_bytes),
              release_notes = VALUES(release_notes)
        """.trimIndent()
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setInt(1, m.versionCode)
                    ps.setString(2, m.versionName)
                    ps.setString(3, m.channel)
                    ps.setString(4, m.priority.name)
                    ps.setString(5, m.fullApkUrl)
                    if (m.patchUrl == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, m.patchUrl)
                    ps.setInt(7, m.patchFromVersionCode)
                    ps.setInt(8, m.patchToVersionCode)
                    ps.setString(9, m.sha256)
                    ps.setLong(10, m.sizeBytes)
                    if (m.releaseNotes.isBlank()) ps.setNull(11, java.sql.Types.VARCHAR) else ps.setString(11, m.releaseNotes)
                    ps.setLong(12, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
            true
        }.getOrElse {
            logger.warn("upsertVersionManifest failed for v${m.versionCode}/${m.channel}: {}", it.message)
            false
        }
    }

    /** 全部版本清单(version_code 降序)。DB 不可达返回空列表。 */
    fun listVersionManifests(): List<Map<String, Any>> {
        val ds = dataSource ?: ensureDataSource() ?: return emptyList()
        return runCatching {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT version_code, version_name, channel, priority, full_apk_url, patch_url, " +
                            "patch_from_version_code, patch_to_version_code, sha256, size_bytes, release_notes, created_at " +
                            "FROM app_version_manifest ORDER BY version_code DESC"
                    ).use { rs ->
                        val list = mutableListOf<Map<String, Any>>()
                        while (rs.next()) {
                            list += mapOf(
                                "versionCode" to rs.getInt("version_code"),
                                "versionName" to (rs.getString("version_name") ?: ""),
                                "channel" to (rs.getString("channel") ?: "stable"),
                                "priority" to (rs.getString("priority") ?: "NORMAL"),
                                "fullApkUrl" to (rs.getString("full_apk_url") ?: ""),
                                "patchUrl" to rs.getString("patch_url"),
                                "patchFromVersionCode" to rs.getInt("patch_from_version_code"),
                                "patchToVersionCode" to rs.getInt("patch_to_version_code"),
                                "sha256" to (rs.getString("sha256") ?: ""),
                                "sizeBytes" to rs.getLong("size_bytes"),
                                "releaseNotes" to (rs.getString("release_notes") ?: ""),
                                "createdAt" to rs.getLong("created_at"),
                            )
                        }
                        list
                    }
                }
            }
        }.getOrElse {
            logger.warn("listVersionManifests failed: {}", it.message)
            emptyList()
        }
    }

    /** 管理员改密(/api/change-password),成功返回 true。 */
    fun updateAdminPassword(adminId: Int, passwordHash: String): Boolean {
        val ds = dataSource ?: ensureDataSource() ?: return false
        return runCatching {
            ds.connection.use { conn ->
                conn.prepareStatement("UPDATE admin_user SET password_hash = ? WHERE id = ?").use { ps ->
                    ps.setString(1, passwordHash)
                    ps.setInt(2, adminId)
                    ps.executeUpdate() == 1
                }
            }
        }.getOrElse {
            logger.warn("updateAdminPassword failed for id={}: {}", adminId, it.message)
            false
        }
    }

    override fun close() {
        dataSource?.let { ds ->
            runCatching { ds.close() }.onFailure { logger.warn("HikariCP close failed: {}", it.message) }
        }
        dataSource = null
    }
}
