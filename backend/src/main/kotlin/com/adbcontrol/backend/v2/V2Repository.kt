package com.adbcontrol.backend.v2

import com.adbcontrol.backend.service.DatabaseService
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

/**
 * v2.0 新表的 JDBC 仓储。连接借用 [DatabaseService.withConnection],
 * 与旧代码保持同样的 best-effort 语义:DB 不可达时读返回空、写返回 false/0。
 */
class V2Repository(private val db: DatabaseService) {

    fun isAvailable(): Boolean = db.isConfigured()

    // ------------------------------------------------------------------ extension_request

    /** 插入一条申请;request_id 已存在(QoS 1 重发)返回 false。 */
    fun insertExtensionRequest(r: ExtensionRequestRow): Boolean = db.withConnection("insertExtensionRequest", false) { c ->
        c.prepareStatement(
            "INSERT IGNORE INTO extension_request (request_id, device_id, package_name, app_label, minutes, reason, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, r.requestId)
            ps.setString(2, r.deviceId)
            ps.setString(3, r.packageName)
            ps.setNullableString(4, r.appLabel)
            ps.setInt(5, r.minutes)
            ps.setNullableString(6, r.reason)
            ps.setString(7, r.status)
            ps.setLong(8, r.createdAt)
            ps.executeUpdate() > 0
        }
    }

    fun getExtensionRequest(requestId: String): ExtensionRequestRow? = db.withConnection("getExtensionRequest", null) { c ->
        c.prepareStatement("$EXT_SELECT WHERE request_id = ?").use { ps ->
            ps.setString(1, requestId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toExtension() else null }
        }
    }

    fun listExtensionRequests(status: String?, deviceId: String?, from: Long?, to: Long?, limit: Int = 200): List<ExtensionRequestRow> =
        db.withConnection("listExtensionRequests", emptyList()) { c ->
            val where = mutableListOf<String>()
            val args = mutableListOf<Any>()
            if (status != null) { where += "status = ?"; args += status }
            if (deviceId != null) { where += "device_id = ?"; args += deviceId }
            if (from != null) { where += "created_at >= ?"; args += from }
            if (to != null) { where += "created_at < ?"; args += to }
            val sql = buildString {
                append(EXT_SELECT)
                if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
                append(" ORDER BY created_at DESC LIMIT ?")
            }
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.bind(i + 1, a) }
                ps.setInt(args.size + 1, limit)
                ps.executeQuery().use { rs -> rs.collect { it.toExtension() } }
            }
        }

    /**
     * 只允许 PENDING → APPROVED/DENIED/EXPIRED(乐观并发:并发双击审批只有一次成功)。
     * 返回是否真正更新了一行。
     */
    fun decideExtensionRequest(requestId: String, status: String, decidedBy: String?, approvedMinutes: Int?, now: Long): Boolean =
        db.withConnection("decideExtensionRequest", false) { c ->
            c.prepareStatement(
                "UPDATE extension_request SET status = ?, decided_at = ?, decided_by = ?, approved_minutes = ? WHERE request_id = ? AND status = 'PENDING'"
            ).use { ps ->
                ps.setString(1, status)
                ps.setLong(2, now)
                ps.setNullableString(3, decidedBy)
                if (approvedMinutes == null) ps.setNull(4, Types.INTEGER) else ps.setInt(4, approvedMinutes)
                ps.setString(5, requestId)
                ps.executeUpdate() > 0
            }
        }

    /** 把 created_at < cutoff 的 PENDING 申请标记 EXPIRED,返回被过期的行(供 SSE 推送)。 */
    fun expireExtensionRequests(cutoff: Long, now: Long): List<ExtensionRequestRow> =
        db.withConnection("expireExtensionRequests", emptyList()) { c ->
            val stale = c.prepareStatement("$EXT_SELECT WHERE status = 'PENDING' AND created_at < ? LIMIT 500").use { ps ->
                ps.setLong(1, cutoff)
                ps.executeQuery().use { rs -> rs.collect { it.toExtension() } }
            }
            stale.filter { row ->
                c.prepareStatement(
                    "UPDATE extension_request SET status = 'EXPIRED', decided_at = ?, decided_by = 'system' WHERE request_id = ? AND status = 'PENDING'"
                ).use { ps ->
                    ps.setLong(1, now)
                    ps.setString(2, row.requestId)
                    ps.executeUpdate() > 0
                }
            }.map { it.copy(status = "EXPIRED", decidedAt = now, decidedBy = "system") }
        }

    // ------------------------------------------------------------------ device_event

    fun insertDeviceEvent(deviceId: String, type: String, title: String, detail: String?, ts: Long): Boolean =
        db.withConnection("insertDeviceEvent", false) { c ->
            c.prepareStatement("INSERT INTO device_event (device_id, type, title, detail, occurred_at) VALUES (?, ?, ?, ?, ?)").use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, type)
                ps.setString(3, title.take(256))
                ps.setNullableString(4, detail?.take(1024))
                ps.setLong(5, ts)
                ps.executeUpdate() > 0
            }
        }

    fun listDeviceEvents(deviceId: String, from: Long, to: Long): List<TimelineItem> =
        db.withConnection("listDeviceEvents", emptyList()) { c ->
            c.prepareStatement(
                "SELECT type, title, detail, occurred_at FROM device_event WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at LIMIT 5000"
            ).use { ps ->
                ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
                ps.executeQuery().use { rs ->
                    rs.collect { TimelineItem(it.getLong("occurred_at"), it.getString("type"), it.getString("title"), it.getString("detail")) }
                }
            }
        }

    /** 时间线数据源:app_activity_log 的 APP_FOREGROUND。 */
    fun listForegroundActivity(deviceId: String, from: Long, to: Long): List<TimelineItem> =
        db.withConnection("listForegroundActivity", emptyList()) { c ->
            c.prepareStatement(
                "SELECT pkg, app_name, occurred_at FROM app_activity_log WHERE device_id = ? AND event = 'APP_FOREGROUND' AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at LIMIT 5000"
            ).use { ps ->
                ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
                ps.executeQuery().use { rs ->
                    rs.collect {
                        val pkg = it.getString("pkg")
                        val label = it.getString("app_name")?.takeIf { n -> n.isNotBlank() } ?: pkg
                        TimelineItem(it.getLong("occurred_at"), "APP_FOREGROUND", "打开 $label", pkg)
                    }
                }
            }
        }

    /** 时间线数据源:location_history.fence_event(FENCE_ENTER / FENCE_EXIT)。 */
    fun listFenceEvents(deviceId: String, from: Long, to: Long): List<Pair<Long, String>> =
        db.withConnection("listFenceEvents", emptyList()) { c ->
            c.prepareStatement(
                "SELECT fence_event, reported_at FROM location_history WHERE device_id = ? AND fence_event IS NOT NULL AND fence_event <> '' AND reported_at >= ? AND reported_at < ? ORDER BY reported_at LIMIT 2000"
            ).use { ps ->
                ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
                ps.executeQuery().use { rs -> rs.collect { it.getLong("reported_at") to it.getString("fence_event") } }
            }
        }

    /** 时间线数据源:task_ack(REMINDER_ACK)。 */
    fun listReminderAcks(deviceId: String, from: Long, to: Long): List<TimelineItem> =
        db.withConnection("listReminderAcks", emptyList()) { c ->
            c.prepareStatement(
                "SELECT task_id, button_text, acked_at FROM task_ack WHERE device_id = ? AND acked_at >= ? AND acked_at < ? ORDER BY acked_at LIMIT 2000"
            ).use { ps ->
                ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
                ps.executeQuery().use { rs ->
                    rs.collect {
                        val taskId = it.getObject("task_id")?.let { v -> (v as Number).toLong() }
                        TimelineItem(
                            it.getLong("acked_at"), "REMINDER_ACK",
                            "签收通知:${it.getString("button_text")}",
                            taskId?.let { id -> "taskId=$id" },
                        )
                    }
                }
            }
        }

    fun listLocations(deviceId: String, from: Long, to: Long): List<LocationPoint> =
        db.withConnection("listLocations", emptyList()) { c ->
            c.prepareStatement(
                "SELECT lat, lng, accuracy, speed, reported_at FROM location_history WHERE device_id = ? AND reported_at >= ? AND reported_at < ? ORDER BY reported_at LIMIT 10000"
            ).use { ps ->
                ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
                ps.executeQuery().use { rs ->
                    rs.collect {
                        val acc = it.getFloat("accuracy").let { v -> if (it.wasNull()) null else v }
                        val spd = it.getFloat("speed").let { v -> if (it.wasNull()) null else v }
                        LocationPoint(it.getLong("reported_at"), it.getDouble("lat"), it.getDouble("lng"), acc, spd)
                    }
                }
            }
        }

    // ------------------------------------------------------------------ geofence

    fun listGeofences(deviceId: String): List<GeofenceDto> = db.withConnection("listGeofences", emptyList()) { c ->
        c.prepareStatement("SELECT id, name, lat, lng, radius_meters FROM geofence WHERE device_id = ? ORDER BY id").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                rs.collect { GeofenceDto(it.getLong("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lng"), it.getDouble("radius_meters")) }
            }
        }
    }

    /** 全量替换某设备的围栏(事务)。成功返回替换后的列表(含新 id),失败返回 null。 */
    fun replaceGeofences(deviceId: String, fences: List<GeofenceDto>, now: Long): List<GeofenceDto>? =
        db.withConnection<List<GeofenceDto>?>("replaceGeofences", null) { c ->
            c.inTransaction {
                c.prepareStatement("DELETE FROM geofence WHERE device_id = ?").use { ps ->
                    ps.setString(1, deviceId); ps.executeUpdate()
                }
                fences.map { f ->
                    c.prepareStatement(
                        "INSERT INTO geofence (device_id, name, lat, lng, radius_meters, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { ps ->
                        ps.setString(1, deviceId)
                        ps.setString(2, f.name)
                        ps.setDouble(3, f.lat)
                        ps.setDouble(4, f.lng)
                        ps.setDouble(5, f.radiusMeters)
                        ps.setLong(6, now)
                        ps.executeUpdate()
                        val id = ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                        f.copy(id = id)
                    }
                }
            }
        }

    // ------------------------------------------------------------------ usage trend

    /** [fromDate, toDate](含两端,yyyy-MM-dd)按日期+包名聚合(多用户求和)。 */
    fun usageTrend(deviceId: String, fromDate: String, toDate: String): List<UsageTrendItem> =
        db.withConnection("usageTrend", emptyList()) { c ->
            c.prepareStatement(
                """
                SELECT DATE_FORMAT(date, '%Y-%m-%d') AS d, pkg, MAX(app_name) AS app_name, SUM(usage_minutes) AS minutes
                FROM app_usage_daily
                WHERE device_id = ? AND date >= ? AND date <= ?
                GROUP BY date, pkg
                ORDER BY date, minutes DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, deviceId); ps.setString(2, fromDate); ps.setString(3, toDate)
                ps.executeQuery().use { rs ->
                    rs.collect { UsageTrendItem(it.getString("d"), it.getString("pkg"), it.getString("app_name"), it.getInt("minutes")) }
                }
            }
        }

    // ------------------------------------------------------------------ policy_template

    data class TemplateRecord(val id: Long, val name: String, val payloadJson: String, val createdAt: Long, val updatedAt: Long)

    fun listTemplates(): List<TemplateRecord> = db.withConnection("listTemplates", emptyList()) { c ->
        c.prepareStatement("SELECT id, name, payload_json, created_at, updated_at FROM policy_template ORDER BY id").use { ps ->
            ps.executeQuery().use { rs -> rs.collect { it.toTemplate() } }
        }
    }

    fun getTemplate(id: Long): TemplateRecord? = db.withConnection("getTemplate", null) { c ->
        c.prepareStatement("SELECT id, name, payload_json, created_at, updated_at FROM policy_template WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toTemplate() else null }
        }
    }

    fun insertTemplate(name: String, payloadJson: String, now: Long): Long? = db.withConnection<Long?>("insertTemplate", null) { c ->
        c.prepareStatement(
            "INSERT INTO policy_template (name, payload_json, created_at, updated_at) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, name); ps.setString(2, payloadJson); ps.setLong(3, now); ps.setLong(4, now)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    fun updateTemplate(id: Long, name: String, payloadJson: String, now: Long): Boolean = db.withConnection("updateTemplate", false) { c ->
        c.prepareStatement("UPDATE policy_template SET name = ?, payload_json = ?, updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, name); ps.setString(2, payloadJson); ps.setLong(3, now); ps.setLong(4, id)
            ps.executeUpdate() > 0
        }
    }

    fun deleteTemplate(id: Long): Boolean = db.withConnection("deleteTemplate", false) { c ->
        c.prepareStatement("DELETE FROM policy_template WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeUpdate() > 0
        }
    }

    // ------------------------------------------------------------------ pending_command

    fun insertPendingCommand(r: PendingCommandRow): Boolean = db.withConnection("insertPendingCommand", false) { c ->
        c.prepareStatement(
            "INSERT INTO pending_command (command_id, device_id, command_type, topic, envelope_type, payload_json, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)"
        ).use { ps ->
            ps.setString(1, r.commandId)
            ps.setString(2, r.deviceId)
            ps.setString(3, r.commandType)
            ps.setString(4, r.topic)
            ps.setString(5, r.envelopeType)
            ps.setString(6, r.payloadJson)
            ps.setLong(7, r.createdAt)
            ps.setLong(8, r.expiresAt)
            ps.executeUpdate() > 0
        }
    }

    /** 某设备全部 PENDING 命令(按入队顺序)。 */
    fun listPendingCommands(deviceId: String): List<PendingCommandRow> = db.withConnection("listPendingCommands", emptyList()) { c ->
        c.prepareStatement("$PENDING_SELECT WHERE device_id = ? AND status = 'PENDING' ORDER BY id").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs -> rs.collect { it.toPending() } }
        }
    }

    /** 管理端查询:某设备最近的队列记录(含 SENT / EXPIRED)。 */
    fun listRecentPendingCommands(deviceId: String, limit: Int): List<PendingCommandRow> = db.withConnection("listRecentPendingCommands", emptyList()) { c ->
        c.prepareStatement("$PENDING_SELECT WHERE device_id = ? ORDER BY id DESC LIMIT ?").use { ps ->
            ps.setString(1, deviceId); ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.collect { it.toPending() } }
        }
    }

    /** PENDING → SENT/EXPIRED(条件更新,防两个投递者重复发)。返回是否真正抢到这一行。 */
    fun transitionPendingCommand(commandId: String, newStatus: String, now: Long): Boolean = db.withConnection("transitionPendingCommand", false) { c ->
        c.prepareStatement("UPDATE pending_command SET status = ?, sent_at = ? WHERE command_id = ? AND status = 'PENDING'").use { ps ->
            ps.setString(1, newStatus)
            if (newStatus == "SENT") ps.setLong(2, now) else ps.setNull(2, Types.BIGINT)
            ps.setString(3, commandId)
            ps.executeUpdate() > 0
        }
    }

    /** 投递发布失败:SENT 退回 PENDING,等下次上线信号重投。 */
    fun revertPendingCommand(commandId: String): Boolean = db.withConnection("revertPendingCommand", false) { c ->
        c.prepareStatement("UPDATE pending_command SET status = 'PENDING', sent_at = NULL WHERE command_id = ? AND status = 'SENT'").use { ps ->
            ps.setString(1, commandId); ps.executeUpdate() > 0
        }
    }

    /** 全表:已过期仍 PENDING 的命令 id(供维护任务批量过期)。 */
    fun listExpiredPendingCommandIds(now: Long, limit: Int = 500): List<String> = db.withConnection("listExpiredPendingCommandIds", emptyList()) { c ->
        c.prepareStatement("SELECT command_id FROM pending_command WHERE status = 'PENDING' AND expires_at <= ? LIMIT ?").use { ps ->
            ps.setLong(1, now); ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.collect { it.getString(1) } }
        }
    }

    /** 设备在线状态(device_status.online);无记录或 DB 不可达返回 null。 */
    fun deviceOnline(deviceId: String): Boolean? = db.withConnection<Boolean?>("deviceOnline", null) { c ->
        c.prepareStatement("SELECT online FROM device_status WHERE device_id = ?").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
        }
    }

    // ------------------------------------------------------------------ command_audit

    fun insertAudit(commandId: String, deviceId: String, type: String, summary: String?, issuedBy: String, issuedAt: Long, result: String): Boolean =
        db.withConnection("insertAudit", false) { c ->
            c.prepareStatement(
                "INSERT IGNORE INTO command_audit (command_id, device_id, type, payload_summary, issued_by, issued_at, result) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setString(2, deviceId)
                ps.setString(3, type.take(64))
                ps.setNullableString(4, summary?.take(512))
                ps.setString(5, issuedBy.take(64))
                ps.setLong(6, issuedAt)
                ps.setString(7, result)
                ps.executeUpdate() > 0
            }
        }

    /** 投递状态变化(QUEUED→SENT / EXPIRED 等),不写 finished_at,除非是终态。 */
    fun updateAuditResult(commandId: String, result: String, channel: String?, finishedAt: Long?): Boolean =
        db.withConnection("updateAuditResult", false) { c ->
            c.prepareStatement(
                "UPDATE command_audit SET result = ?, channel = COALESCE(?, channel), finished_at = COALESCE(?, finished_at) WHERE command_id = ?"
            ).use { ps ->
                ps.setString(1, result)
                ps.setNullableString(2, channel?.take(32))
                if (finishedAt == null) ps.setNull(3, Types.BIGINT) else ps.setLong(3, finishedAt)
                ps.setString(4, commandId)
                ps.executeUpdate() > 0
            }
        }

    fun listAudit(deviceId: String?, from: Long?, to: Long?, limit: Int): List<AuditRow> = db.withConnection("listAudit", emptyList()) { c ->
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (deviceId != null) { where += "device_id = ?"; args += deviceId }
        if (from != null) { where += "issued_at >= ?"; args += from }
        if (to != null) { where += "issued_at < ?"; args += to }
        val sql = buildString {
            append("SELECT id, command_id, device_id, type, payload_summary, issued_by, issued_at, channel, result, finished_at FROM command_audit")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY issued_at DESC LIMIT ?")
        }
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.bind(i + 1, a) }
            ps.setInt(args.size + 1, limit)
            ps.executeQuery().use { rs ->
                rs.collect {
                    AuditRow(
                        id = it.getLong("id"),
                        commandId = it.getString("command_id"),
                        deviceId = it.getString("device_id"),
                        type = it.getString("type"),
                        payloadSummary = it.getString("payload_summary"),
                        issuedBy = it.getString("issued_by"),
                        issuedAt = it.getLong("issued_at"),
                        channel = it.getString("channel"),
                        result = it.getString("result"),
                        finishedAt = it.getLong("finished_at").let { v -> if (it.wasNull()) null else v },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ OTA

    fun updateManifestSha256(versionCode: Int, channel: String, sha256: String): Boolean = db.withConnection("updateManifestSha256", false) { c ->
        c.prepareStatement("UPDATE app_version_manifest SET sha256 = ? WHERE version_code = ? AND channel = ?").use { ps ->
            ps.setString(1, sha256); ps.setInt(2, versionCode); ps.setString(3, channel)
            ps.executeUpdate() > 0
        }
    }

    // ------------------------------------------------------------------ helpers

    private companion object {
        const val EXT_SELECT =
            "SELECT id, request_id, device_id, package_name, app_label, minutes, reason, status, created_at, decided_at, decided_by, approved_minutes FROM extension_request"
        const val PENDING_SELECT =
            "SELECT id, command_id, device_id, command_type, topic, envelope_type, payload_json, status, created_at, expires_at, sent_at FROM pending_command"
    }

    private fun ResultSet.toExtension() = ExtensionRequestRow(
        id = getLong("id"),
        requestId = getString("request_id"),
        deviceId = getString("device_id"),
        packageName = getString("package_name"),
        appLabel = getString("app_label"),
        minutes = getInt("minutes"),
        reason = getString("reason"),
        status = getString("status"),
        createdAt = getLong("created_at"),
        decidedAt = getLong("decided_at").let { if (wasNull()) null else it },
        decidedBy = getString("decided_by"),
        approvedMinutes = getInt("approved_minutes").let { if (wasNull()) null else it },
    )

    private fun ResultSet.toTemplate() = TemplateRecord(
        getLong("id"), getString("name"), getString("payload_json"), getLong("created_at"), getLong("updated_at"),
    )

    private fun ResultSet.toPending() = PendingCommandRow(
        id = getLong("id"),
        commandId = getString("command_id"),
        deviceId = getString("device_id"),
        commandType = getString("command_type"),
        topic = getString("topic"),
        envelopeType = getString("envelope_type"),
        payloadJson = getString("payload_json"),
        status = getString("status"),
        createdAt = getLong("created_at"),
        expiresAt = getLong("expires_at"),
        sentAt = getLong("sent_at").let { if (wasNull()) null else it },
    )
}

private inline fun <T> ResultSet.collect(map: (ResultSet) -> T): List<T> {
    val out = mutableListOf<T>()
    while (next()) out += map(this)
    return out
}

private fun PreparedStatement.setNullableString(i: Int, v: String?) {
    if (v == null) setNull(i, Types.VARCHAR) else setString(i, v)
}

private fun PreparedStatement.bind(i: Int, v: Any) {
    when (v) {
        is String -> setString(i, v)
        is Long -> setLong(i, v)
        is Int -> setInt(i, v)
        else -> setObject(i, v)
    }
}

private inline fun <T> Connection.inTransaction(block: () -> T): T {
    val prev = autoCommit
    autoCommit = false
    try {
        val r = block()
        commit()
        return r
    } catch (e: Exception) {
        runCatching { rollback() }
        throw e
    } finally {
        autoCommit = prev
    }
}
