package com.adbcontrol.backend.service

import com.adbcontrol.backend.BackendConstants
import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.backend.model.PairingError
import com.adbcontrol.backend.model.PairingResponse
import com.adbcontrol.backend.model.PairTokenPayload
import com.adbcontrol.backend.model.RenewRequest
import com.adbcontrol.backend.model.RenewResponse
import com.adbcontrol.backend.security.CryptoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 配对 / 续期服务(README 8.3)。
 *
 * 流程:
 * 1. POST /pair:校验 pairToken(未使用且未过期)→ 签发临时 MQTT 凭证(随机 32B Base64 密码)
 *    + 长期 sessionKey(随机 32B Base64)→ 写 EMQX ACL → 返回 PairingResponse(broker+r2+sessionKey+7 天过期)。
 * 2. POST /renew:用 pairToken 证明身份 → 签发新 MQTT 密码 → 返回 RenewResponse。
 *
 * 持久化:pair session 落 MySQL pair_session 表(启动时恢复),生产部署重启不丢配对。
 * pair token 本身生命周期短(默认 10 分钟),仍存内存即可,重启后只需重新生成。
 * 被控端凭证长期落盘由 EncryptedFile 完成后端只签发,不存储明文长期凭证之外的敏感数据
 * (mqtt_password 需随会话存续以便 renew 展示,但配对完成后仅 sessionKey 长期有效)。
 */
class PairingService(
    private val config: BackendConfig,
    private val aclService: AclService,
    private val databaseService: DatabaseService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private data class PairTokenRecord(
        val deviceId: String,
        val deviceName: String?,
        val createdAt: Long,
        val expiresAt: Long,
        /** 0L 表示未使用;非 0 表示首次使用时间(本线程在 compute 内写入)。 */
        @Volatile var usedAt: Long = 0L,
    )

    private data class SessionRecord(
        val deviceId: String,
        val pairToken: String,
        val sessionKey: String,
        @Volatile var mqttPassword: String,
        @Volatile var expiresAt: Long,
    )

    private val tokens = ConcurrentHashMap<String, PairTokenRecord>()
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    init {
        // 启动时从 pair_session 表恢复配对会话(Bug#25 修复:重启后丢配对)。
        // DB 可用时覆盖内存;DB 不可达时 pass-through,与 memoryOnly 退化为原行为一致。
        val restored = databaseService.listPairSessions()
        restored.forEach { row ->
            sessions[row.deviceId] = SessionRecord(
                deviceId = row.deviceId,
                pairToken = row.pairToken,
                sessionKey = row.sessionKey,
                mqttPassword = row.mqttPassword,
                expiresAt = row.expiresAt,
            )
        }
        if (restored.isNotEmpty()) {
            logger.info("Restored {} pairing session(s) from DB", restored.size)
        }

        // Bug#1:演示 token 仅在显式设置 ADB_SEED_DEMO_TOKEN=true 时注入。
        // 不再按 serverUrl 域名隐式开启 —— 默认 serverUrl 恰好是 example.com,
        // 会导致忘记配置 ADB_SERVER_URL 的生产实例带着全平台已知的 pt_demo_001 后门上线。
        val seedDemo = System.getenv("ADB_SEED_DEMO_TOKEN")?.trim()?.lowercase() == "true"
        if (seedDemo) {
            val demo = "pt_demo_001"
            val now = System.currentTimeMillis()
            tokens[demo] = PairTokenRecord(
                deviceId = "device-a001",
                deviceName = "demo-device",
                createdAt = now,
                expiresAt = now + PAIR_TOKEN_TTL_MS,
            )
            logger.info("Seeded demo pair token '{}' for device-a001 (valid 10min) [dev-only]", demo)
        } else {
            logger.info("Demo pair token seeding skipped (serverUrl={})", config.serverUrl)
        }
    }

    sealed class PairResult {
        data class Ok(val response: PairingResponse) : PairResult()
        data class Fail(val error: PairingError) : PairResult()
    }

    sealed class RenewResult {
        data class Ok(val response: RenewResponse) : RenewResult()
        data class Fail(val error: PairingError) : RenewResult()
    }

    suspend fun pair(payload: PairTokenPayload): PairResult {
        val now = System.currentTimeMillis()
        cleanupExpired(now)

        // Bug#2:配额预检必须在消耗令牌之前。用 compute 在单一临界区内完成
        // "读 usedAt + 校验 deviceId/expiresAt + 配额预检",超限或校验失败均不翻转 usedAt,
        // 令牌可重试(避免 DEVICE_LIMIT 后重试得 TOKEN_USED)。
        var quotaExceeded = false
        var validated = false
        val record = tokens.compute(payload.pairToken) { _, rec ->
            if (rec == null) return@compute rec
            if (rec.usedAt != 0L) return@compute rec          // 已被使用
            if (payload.deviceId != rec.deviceId) return@compute rec  // deviceId 不匹配
            if (now > rec.expiresAt) return@compute rec       // 已过期
            // 配额预检:超限直接 DEVICE_LIMIT,不消耗令牌
            if (sessions.size >= BackendConstants.MAX_DEVICES) {
                quotaExceeded = true
                return@compute rec
            }
            validated = true
            rec
        } ?: return PairResult.Fail(PairingError("TOKEN_INVALID", "未知配对令牌"))

        if (quotaExceeded) {
            return PairResult.Fail(
                PairingError("DEVICE_LIMIT", "已达设备数上限 ${BackendConstants.MAX_DEVICES}")
            )
        }
        if (!validated) {
            // 校验未通过(已使用 / deviceId 不匹配 / 已过期)—— usedAt 仍为 0,令牌可重试
            return when {
                payload.deviceId != record.deviceId ->
                    PairResult.Fail(PairingError("TOKEN_INVALID", "deviceId 与令牌不匹配"))
                now > record.expiresAt ->
                    PairResult.Fail(PairingError("TOKEN_EXPIRED", "配对令牌已过期"))
                else ->
                    PairResult.Fail(PairingError("TOKEN_USED", "配对令牌已使用"))
            }
        }

        val sessionKey = CryptoUtil.randomBase64(BackendConstants.SESSION_KEY_BYTES)
        val mqttPassword = CryptoUtil.randomBase64(BackendConstants.MQTT_PASSWORD_BYTES)
        val username = "${config.emqxAppId}@${payload.deviceId}"
        val expiresAt = now + BackendConstants.CREDENTIAL_TTL_MS

        // putIfAbsent 原子占位:同 deviceId 并发只允许首个 session 写入成功
        val session = SessionRecord(
            deviceId = payload.deviceId,
            pairToken = payload.pairToken,
            sessionKey = sessionKey,
            mqttPassword = mqttPassword,
            expiresAt = expiresAt,
        )
        val existing = sessions.putIfAbsent(payload.deviceId, session)
        if (existing != null) {
            return PairResult.Fail(PairingError("TOKEN_USED", "设备已配对"))
        }

        // Bug#2:成功占位后再翻转 usedAt,令牌仅在配对真正成功时才标记为已使用
        record.usedAt = now

        var success = false
        try {
            // 写 EMQX ACL(防越权 topic)
            aclService.applyForDevice(payload.deviceId)

            // 配对会话持久化(失败不影响配对流程,DB 不可达则退回原来的内存-only 模式)
            withContext(Dispatchers.IO) {
                runCatching {
                    databaseService.upsertPairSession(
                        DatabaseService.PairSessionRow(
                            deviceId = payload.deviceId,
                            pairToken = payload.pairToken,
                            sessionKey = sessionKey,
                            mqttPassword = mqttPassword,
                            expiresAt = expiresAt,
                        )
                    )
                }.onFailure { logger.warn("persist pair session failed (best-effort): {}", it.message) }
            }

            // 设备台账入库(幂等,失败不影响配对)
            withContext(Dispatchers.IO) {
                runCatching {
                    databaseService.registerDevice(
                        deviceId = payload.deviceId,
                        name = payload.deviceName ?: record.deviceName ?: payload.deviceId,
                        appid = config.emqxAppId,
                        now = now,
                    )
                }.onFailure { logger.warn("registerDevice failed (best-effort): {}", it.message) }
            }

            logger.info("Paired deviceId={}, username={}, expiresAt={}", payload.deviceId, username, expiresAt)

            success = true
            val broker = config.buildBroker(username, mqttPassword)
            return PairResult.Ok(
                PairingResponse(
                    broker = broker,
                    r2 = config.buildR2(),
                    sessionKey = sessionKey,
                    expiresAt = expiresAt,
                )
            )
        } finally {
            if (!success) {
                sessions.remove(payload.deviceId, session)
                record.usedAt = 0L
            }
        }
    }

    suspend fun renew(req: RenewRequest): RenewResult {
        val now = System.currentTimeMillis()
        cleanupExpired(now)

        // Bug#4:用 compute 在单一临界区内完成"读旧值→校验→生成新值→写回→返回",
        // 保证返回给客户端的密码与存储一致,并做过期校验。
        var outcome: RenewResult? = null
        sessions.compute(req.deviceId) { _, s ->
            if (s == null) return@compute null  // 未找到,由外部返回 DEVICE_NOT_FOUND
            // Bug#6:常数时间比较 pairToken,避免时序侧信道
            if (!MessageDigest.isEqual(
                    req.pairToken.toByteArray(Charsets.UTF_8),
                    s.pairToken.toByteArray(Charsets.UTF_8),
                )
            ) {
                outcome = RenewResult.Fail(PairingError("TOKEN_INVALID", "身份校验失败"))
                return@compute s
            }
            // Bug#4:超过续期宽限期(过期 +24h)则拒绝续期
            if (now > s.expiresAt + RENEW_GRACE_MS) {
                outcome = RenewResult.Fail(PairingError("SESSION_EXPIRED", "会话已过期,请重新配对"))
                return@compute s
            }
            val newPassword = CryptoUtil.randomBase64(BackendConstants.MQTT_PASSWORD_BYTES)
            val newExpiresAt = now + BackendConstants.CREDENTIAL_TTL_MS
            s.mqttPassword = newPassword
            s.expiresAt = newExpiresAt
                outcome = RenewResult.Ok(
                RenewResponse(
                    broker = config.buildBroker("${config.emqxAppId}@${req.deviceId}", newPassword),
                    expiresAt = newExpiresAt,
                )
            )
            s
        } ?: return RenewResult.Fail(PairingError("DEVICE_NOT_FOUND", "未找到配对会话"))

        // Bug#25:renew 后同步更新 DB 里的 mqtt_password 与 expires_at,避免重启后倒退
        if (outcome is RenewResult.Ok) {
            runCatching {
                sessions[req.deviceId]?.let { s ->
                    databaseService.upsertPairSession(
                        DatabaseService.PairSessionRow(
                            deviceId = s.deviceId,
                            pairToken = s.pairToken,
                            sessionKey = s.sessionKey,
                            mqttPassword = s.mqttPassword,
                            expiresAt = s.expiresAt,
                        )
                    )
                }
            }.onFailure { logger.warn("persist renewed session failed (best-effort): {}", it.message) }
        }

        return outcome ?: RenewResult.Fail(PairingError("DEVICE_NOT_FOUND", "未找到配对会话"))
    }

    /** Bug#5:清理过期 pair token 与超期会话,避免内存无限增长。在 pair/renew 入口顺手触发。 */
    private fun cleanupExpired(now: Long) {
        tokens.entries.removeAll { (_, t) -> now > t.expiresAt }
        sessions.entries.removeAll { (_, s) -> now > s.expiresAt + RENEW_GRACE_MS }
    }

    /** 响应体走 kotlinx 序列化,必须可序列化(缺 @Serializable 时 respond 500)。 */
    @kotlinx.serialization.Serializable
    data class GeneratedPairToken(
        val pairToken: String,
        val deviceId: String,
        val deviceName: String,
        val createdAt: Long,
        val expiresAt: Long,
        val qrPayload: String
    )

    fun generatePairToken(deviceName: String?, ttlMs: Long = PAIR_TOKEN_TTL_MS): GeneratedPairToken {
        val now = System.currentTimeMillis()
        val deviceId = "dev_" + java.util.UUID.randomUUID().toString().replace("-","").substring(0,14)
        val pairToken = "pt_" + java.security.SecureRandom().let { sr ->
            val buf = ByteArray(18); sr.nextBytes(buf)
            org.apache.commons.codec.binary.Base32().encodeToString(buf).trimEnd('=')
        }
        val name = if (deviceName.isNullOrBlank()) "新设备-$deviceId" else deviceName
        tokens[pairToken] = PairTokenRecord(
            deviceId = deviceId,
            deviceName = name,
            createdAt = now,
            expiresAt = now + ttlMs,
            usedAt = 0L,
        )
        val qrPayload = buildString {
            val safeServer = config.serverUrl.replace("\"", "\\\"")
            val safeName = name.replace("\"", "\\\"")
            append("{\"pairToken\":\"").append(pairToken).append("\"")
            append(",\"deviceId\":\"").append(deviceId).append("\"")
            append(",\"serverUrl\":\"").append(safeServer).append("\"")
            append(",\"deviceName\":\"").append(safeName).append("\"}")
        }
        return GeneratedPairToken(pairToken, deviceId, name, now, now + ttlMs, qrPayload)
    }

    fun listPairingTokens(includeUsed: Boolean = false): List<Map<String, Any>> =
        tokens.entries.mapNotNull { (tk, rec) ->
            if (!includeUsed && rec.usedAt != 0L) null
            else mapOf(
                // 已使用的令牌不再外泄明文(配对已完成,展示已无意义),未使用的保留以便重发/补扫码
                "pairToken" to (if (rec.usedAt == 0L) tk else ""),
                "deviceId" to rec.deviceId,
                "deviceName" to (rec.deviceName ?: ""),
                "createdAt" to rec.createdAt,
                "expiresAt" to rec.expiresAt,
                "usedAt" to rec.usedAt,
                "expired" to (System.currentTimeMillis() > rec.expiresAt)
            )
        }

    /**
     * 按 deviceId 查配对会话的 sessionKey(HMAC-SHA256,Base64)。
     * Web 管理端下发远程命令时签名用(DeviceCommandBridge)。
     * 配对会话目前存内存:服务重启后丢失,调用方缺失时应提示设备重新配对。
     */
    fun sessionKeyFor(deviceId: String): String? = sessions[deviceId]?.sessionKey

    fun revokePairingToken(pairTokenPrefixOrId: String) {
        val it = tokens.entries.iterator()
        while (it.hasNext()) {
            val (tk, rec) = it.next()
            if (rec.deviceId == pairTokenPrefixOrId || tk == pairTokenPrefixOrId) {
                // 同步删除 DB 里的配对会话,避免吊销后端重启后旧 sessionKey 复活
                runCatching {
                    sessions.remove(rec.deviceId)?.let {
                        runCatching { databaseService.deletePairSession(rec.deviceId) }
                            .onFailure { e -> logger.warn("deletePairSession on revoke failed: {}", e.message) }
                    }
                }
                it.remove(); sessions.remove(rec.deviceId)
            }
        }
    }

    companion object {
        /** pair token 有效期:10 分钟(README 8.3.1)。 */
        const val PAIR_TOKEN_TTL_MS = 10L * 60 * 1000

        /** Bug#4:会话过期后仍允许续期的宽限期(24h),超过则强制重新配对。 */
        const val RENEW_GRACE_MS = 24L * 60 * 60 * 1000
    }
}
