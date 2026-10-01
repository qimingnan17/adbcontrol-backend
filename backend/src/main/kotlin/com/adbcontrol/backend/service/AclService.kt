package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * EMQX ACL 管理(README 4.2 + 8.1)。
 *
 * 为每个 `${appid}@${deviceId}` 用户名限定可 publish/subscribe 的 topic 前缀,防越权:
 * - 被控端只能 publish 自己的 result/{id} status/{id} health/{id} location/{id}
 *   activity/{id} usage/{id} pong/{id} device/offline/{id};
 * - 被控端只能 subscribe cmd/{id} reminder/{id} push/{id} ping/{id} controller/offline/+;
 * - 主控端订阅用通配 `+`、publish 到各被控 cmd/... 等(单独配主控用户名 ACL)。
 *
 * EMQX Cloud Serverless 的 ACL 在控制台按用户名配置,REST 写入方式随部署类型不同。
 * 当前实现先按数据建模 + 日志记录,实际 EMQX REST/控制台写入留 TODO(参见 README 10.2)。
 */
class AclService(
    private val config: BackendConfig,
    private val emqx: EmqxProxyService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    data class DeviceAcl(
        val username: String,
        val publish: List<String>,
        val subscribe: List<String>,
    )

    /** 构造被控端的 ACL 规则集(deviceId 前缀绑定)。 */
    fun buildDeviceAcl(deviceId: String): DeviceAcl {
        // 与 PairingService.provisionEmqxUser 的账号名保持一致:username 就是 deviceId。
        // 之前用 `${appid}@${deviceId}`,与实际签发的 EMQX 账号对不上,接入 ACL REST 后会静默错配。
        val username = deviceId
        val pub = listOf(
            "result/$deviceId",
            "status/$deviceId",
            "health/$deviceId",
            "location/$deviceId",
            "activity/$deviceId",
            "usage/$deviceId",
            "pong/$deviceId",
            "device/offline/$deviceId",
        )
        val sub = listOf(
            "cmd/$deviceId",
            "reminder/$deviceId",
            "push/$deviceId",
            "ping/$deviceId",
            "controller/offline/+",
        )
        return DeviceAcl(username = username, publish = pub, subscribe = sub)
    }

    /**
     * 配对成功后为 deviceId 写入 EMQX 授权规则(内置数据库 authorization source)。
     * 5.8+:一次 PUT 整体写入该用户全部规则;5.4-5.7 回退为裸 rules 表逐条写入。
     * Best-effort:失败仅告警不抛异常,不阻塞配对;全部失败时设备仍可收发
     * (与历史"Serverless 无 ACL"行为一致),但日志会明确暴露缺口供运维介入。
     */
    suspend fun applyForDevice(deviceId: String) {
        val acl = buildDeviceAcl(deviceId)
        logger.info("[ACL] apply for deviceId={} (username={})", deviceId, acl.username)
        val rules = acl.publish.map { "publish" to it } + acl.subscribe.map { "subscribe" to it }
        val r = emqx.putScopedAclRules(acl.username, rules)
        if (r.status in 200..299) {
            logger.info("[ACL] device {} rules written: {} rule(s) via scoped API", deviceId, rules.size)
            return
        }
        if (r.status == 405 || r.status == 404) {
            // 旧版 EMQX:裸 rules 表逐条写入(新/旧字段名自动回退)
            var ok = 0
            for ((action, topic) in rules) {
                if (applyLegacyRule(acl.username, action, topic)) ok++
            }
            logger.info("[ACL] device {} rules written via legacy API: ok={}/{}", deviceId, ok, rules.size)
            return
        }
        logger.warn("[ACL] write rules failed for {}: HTTP {}", deviceId, r.status)
    }

    private suspend fun applyLegacyRule(username: String, action: String, topic: String): Boolean {
        val r = emqx.createLegacyAclRule(username, action, topic)
        if (r.status in 200..299 || r.status == 409) return true // 409=已存在(重配对),视为成功
        logger.warn("[ACL] write rule failed: username={} {} '{}' -> HTTP {}", username, action, topic, r.status)
        return false
    }

    /**
     * 移除设备的全部 ACL 规则(删除设备/吊销令牌时调用,best-effort)。
     *
     * 5.8+ 走按用户名的作用域整体删除;旧版回退为"拉全量列表再逐条删"。
     * 但回退路径在 EMQX Cloud Serverless 上必然失败(裸 rules 表端点不开放,405),
     * 此前只留一句 warn 就返回 —— 残留规则会让已删除的设备在重连后仍能收发
     * 部分 topic。这里补一层兜底:删除该用户的 authorization 记录,
     * 记录没了,残留 rule 行也匹配不到任何用户,等效失权。
     */
    suspend fun removeForDevice(deviceId: String) {
        val acl = buildDeviceAcl(deviceId)
        // 5.8+:按作用域一次删除该用户全部规则;旧版回退到"拉全量列表+逐条删"
        val scoped = emqx.deleteScopedAclRules(acl.username)
        if (scoped.status in 200..299) {
            logger.info("[ACL] removed scoped rules for {}", deviceId)
            return
        }
        val list = emqx.listAclRules()
        if (list.status !in 200..299) {
            val dropped = emqx.deleteAuthorizationUser(acl.username)
            if (dropped.status in 200..299) {
                logger.info("[ACL] removed authorization user for {} (rules endpoint unavailable)", deviceId)
            } else {
                logger.warn(
                    "[ACL] remove rules for {} failed (scoped={} list={} dropUser={})",
                    deviceId, scoped.status, list.status, dropped.status,
                )
            }
            return
        }
        runCatching {
            val root = json.parseToJsonElement(list.body).jsonObject
            val data = root["data"] as? JsonArray ?: return
            var removed = 0
            for (rule in data) {
                val obj = rule as? JsonObject ?: continue
                val ruleUser = obj["username"]?.jsonPrimitive?.contentOrNull
                if (ruleUser != acl.username) continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val del = emqx.deleteAclRule(id)
                if (del.status in 200..299) removed++
            }
            if (removed > 0) logger.info("[ACL] removed {} rule(s) for {}", removed, deviceId)
        }.onFailure { logger.warn("[ACL] remove rules for {} failed: {}", deviceId, it.message) }
    }
}
