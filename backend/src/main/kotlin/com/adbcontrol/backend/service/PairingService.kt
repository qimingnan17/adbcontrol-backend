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
import kotlinx.coroutines.launch
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
    private val emqxProxy: EmqxProxyService,
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

    /** 过期会话的深度清理(EMQX 账号/ACL/DB 行)异步执行,不阻塞 pair/renew 路径。 */
    private val cleanupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

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
        // 用户名即 deviceId:Serverless 无 ACL,设备隔离靠 clientId/topic/HMAC 签名;
        // 账号本身经 EMQX 部署 API 动态注册(见 provisionEmqxUser),配对即生效。
        val username = payload.deviceId
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

            // EMQX 注册设备 MQTT 账号:失败则整个配对失败(finally 会回滚 session 与 usedAt),
            // 绝不下发 broker 不认识的凭证(那会让设备陷入永久 401 重连)
            provisionEmqxUser(username, mqttPassword)?.let { err ->
                return PairResult.Fail(PairingError("SERVER_ERROR", err))
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

    /** 在 EMQX 内置数据库中注册设备账号;已存在(重配对)则改密复用。返回 null 表示成功。 */
    private suspend fun provisionEmqxUser(username: String, password: String): String? {
        val created = emqxProxy.createAuthUser(username, password)
        if (created.status in 200..299) return null
        val updated = emqxProxy.updateAuthUserPassword(username, password)
        if (updated.status in 200..299) return null
        logger.warn(
            "provision emqx user '{}' failed: create={} update={}",
            username, created.status, updated.status,
        )
        return "EMQX 账号注册失败(create=${created.status}, update=${updated.status})"
    }

    suspend fun renew(req: RenewRequest): RenewResult {
        val now = System.currentTimeMillis()
        cleanupExpired(now)

        // Bug#4:用 compute 在单一临界区内完成"读旧值→校验→生成新值→写回→返回",
        // 保证返回给客户端的密码与存储一致,并做过期校验。
        var outcome: RenewResult? = null
        var renewedOldPassword: String = ""
        var renewedNewPassword: String = ""
        var renewedOldExpiresAt: Long = 0L
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
            val oldPassword = s.mqttPassword
            val oldExpiresAt = s.expiresAt
            val newPassword = CryptoUtil.randomBase64(BackendConstants.MQTT_PASSWORD_BYTES)
            val newExpiresAt = now + BackendConstants.CREDENTIAL_TTL_MS
            s.mqttPassword = newPassword
            s.expiresAt = newExpiresAt
                outcome = RenewResult.Ok(
                RenewResponse(
                    broker = config.buildBroker(req.deviceId, newPassword),
                    expiresAt = newExpiresAt,
                )
            )
            renewedOldPassword = oldPassword
            renewedNewPassword = newPassword
            renewedOldExpiresAt = oldExpiresAt
            s
        } ?: return RenewResult.Fail(PairingError("DEVICE_NOT_FOUND", "未找到配对会话"))

        // EMQX 侧同步改密:失败则回滚 session 密码并拒绝本次续期
        // (设备继续用旧凭证,连接不受影响;绝不返回 broker 还不认识的新密码)
        if (outcome is RenewResult.Ok) {
            val upd = emqxProxy.updateAuthUserPassword(req.deviceId, renewedNewPassword)
            if (upd.status !in 200..299) {
                logger.warn("renew: emqx password update failed {} for {}", upd.status, req.deviceId)
                // 带身份守卫的回滚:仅当记录仍是本次 renew 写入的状态时才回退,
                // 避免并发第二次 renew 已写入更新值后被旧值覆盖(实测竞态)
                sessions.compute(req.deviceId) { _, cur ->
                    if (cur != null && cur.mqttPassword == renewedNewPassword) {
                        cur.mqttPassword = renewedOldPassword
                        cur.expiresAt = renewedOldExpiresAt
                    }
                    cur
                }
                outcome = RenewResult.Fail(PairingError("SERVER_ERROR", "EMQX 密码更新失败(${upd.status}),请稍后重试"))
            }
        }

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
        val expired = mutableListOf<String>()
        sessions.entries.removeAll { (_, s) ->
            if (now > s.expiresAt + RENEW_GRACE_MS) { expired.add(s.deviceId); true } else false
        }
        // 深度清理:过期会话的 EMQX 账号与 ACL 规则此前永不回收(实测问题),
        // 设备在过期+宽限后仍能收发 MQTT。这里异步删账号/ACL/DB 行;删失败仅告警,
        // revokePairingToken/removeDevice 路径仍可手动彻底清除。
        for (deviceId in expired) {
            cleanupScope.launch {
                logger.info("session expired, deep-cleaning device {}", deviceId)
                runCatching { emqxProxy.deleteAuthUser(deviceId) }
                    .onFailure { e -> logger.warn("deep cleanup deleteAuthUser {}: {}", deviceId, e.message) }
                runCatching { aclService.removeForDevice(deviceId) }
                    .onFailure { e -> logger.warn("deep cleanup acl {}: {}", deviceId, e.message) }
                runCatching { databaseService.deletePairSession(deviceId) }
                    .onFailure { e -> logger.warn("deep cleanup db row {}: {}", deviceId, e.message) }
            }
        }
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

    fun generatePairToken(
        deviceName: String?,
        ttlMs: Long = PAIR_TOKEN_TTL_MS,
        preferredServerUrl: String? = null
    ): GeneratedPairToken {
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
        // 优先使用真实可达地址:当配置为 example.com 占位或为空时，回退到请求 Origin
        val targetServerUrl = when {
            config.serverUrl.isNotBlank() && !config.serverUrl.contains("example.com") -> config.serverUrl
            !preferredServerUrl.isNullOrBlank() && !preferredServerUrl.contains("example.com") -> preferredServerUrl
            config.serverUrl.isNotBlank() -> config.serverUrl
            else -> preferredServerUrl ?: "http://localhost:8080"
        }
        val qrPayload = buildString {
            val safeServer = targetServerUrl.trimEnd('/').replace("\"", "\\\"")
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

    /** 该 deviceId 是否存在任何配对痕迹(会话或令牌),删除设备前的存在性校验用。 */
    fun knowsDevice(deviceId: String): Boolean =
        sessions.containsKey(deviceId) || tokens.values.any { it.deviceId == deviceId }

    /** 当前全部有效配对会话的 deviceId(OTA 更新广播用),过期会话先清理。 */
    fun allSessionDeviceIds(): List<String> {
        cleanupExpired(System.currentTimeMillis())
        return sessions.keys.toList()
    }

    /** 是否已有待使用的配对令牌占用该设备名(生成令牌时的名称唯一性预检)。 */
    fun isPendingTokenNameTaken(deviceName: String): Boolean {
        val n = deviceName.trim()
        if (n.isEmpty()) return false
        return tokens.values.any { it.usedAt == 0L && it.deviceName?.trim() == n }
    }

    /**
     * 吊销配对令牌(按完整 token 或 deviceId 匹配)。命中则同时:
     * 删内存令牌/会话 + 删 DB pair_session + 删 EMQX 设备账号(best-effort)。
     * 返回是否真的命中并移除了记录,供路由返回 404 而非永远 ok:true。
     */
    suspend fun revokePairingToken(pairTokenPrefixOrId: String): Boolean {
        var removed = false
        val it = tokens.entries.iterator()
        while (it.hasNext()) {
            val (tk, rec) = it.next()
            if (rec.deviceId == pairTokenPrefixOrId || tk == pairTokenPrefixOrId) {
                removeSessionAndEmqx(rec.deviceId)
                it.remove()
                removed = true
            }
        }
        // 兼容:token 记录已被过期清扫,但 DB 里还留着该设备的会话
        if (!removed && sessions.containsKey(pairTokenPrefixOrId)) {
            removeSessionAndEmqx(pairTokenPrefixOrId)
            removed = true
        }
        return removed
    }

    /**
     * 彻底移除一台设备:吊销其名下全部令牌、删除配对会话与 EMQX 账号。
     * 返回是否存在过该设备的配对痕迹(供路由判断 404)。
     */
    suspend fun removeDevice(deviceId: String): Boolean {
        val had = knowsDevice(deviceId)
        val it = tokens.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.deviceId == deviceId) it.remove()
        }
        removeSessionAndEmqx(deviceId)
        return had
    }

    /**
     * 删除内存会话 + DB pair_session + EMQX 设备账号与 ACL 规则,并主动踢掉在线连接。
     *
     * 顺序有讲究:先踢连接,再删账号/规则。反过来做的话,被踢后设备会立刻重连,
     * 而此刻账号还在、规则还在 —— 重连成功,等于没踢。
     */
    private suspend fun removeSessionAndEmqx(deviceId: String) {
        sessions.remove(deviceId)

        // 1) 先断连接,让端侧立刻感知失联(重连失败后由端侧自行登出)
        runCatching { emqxProxy.kickDevice(deviceId) }.onSuccess { r ->
            when {
                r.status in 200..299 -> logger.info("[KICK] device {} connection kicked", deviceId)
                r.status == 404 -> logger.info("[KICK] device {} was already offline", deviceId)
                else -> logger.warn("[KICK] device {} kick returned HTTP {}", deviceId, r.status)
            }
        }.onFailure { e -> logger.warn("kickDevice failed for {}: {}", deviceId, e.message) }

        // 2) 再删账号与规则,阻止其重连
        runCatching { databaseService.deletePairSession(deviceId) }
            .onFailure { e -> logger.warn("deletePairSession failed for {}: {}", deviceId, e.message) }
        runCatching { emqxProxy.deleteAuthUser(deviceId) }
            .onFailure { e -> logger.warn("deleteAuthUser failed for {}: {}", deviceId, e.message) }
        runCatching { aclService.removeForDevice(deviceId) }
            .onFailure { e -> logger.warn("removeAclRules failed for {}: {}", deviceId, e.message) }
    }

    companion object {
        /** pair token 有效期:10 分钟(README 8.3.1)。 */
        const val PAIR_TOKEN_TTL_MS = 10L * 60 * 1000

        /** Bug#4:会话过期后仍允许续期的宽限期(24h),超过则强制重新配对。 */
        const val RENEW_GRACE_MS = 24L * 60 * 60 * 1000
    }
}
