package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
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
class AclService(private val config: BackendConfig) {
    private val logger = LoggerFactory.getLogger(javaClass)

    data class DeviceAcl(
        val username: String,
        val publish: List<String>,
        val subscribe: List<String>,
    )

    /** 构造被控端的 ACL 规则集(deviceId 前缀绑定)。 */
    fun buildDeviceAcl(deviceId: String): DeviceAcl {
        val username = "${config.emqxAppId}@$deviceId"
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
     * 配对成功后为 deviceId 写入 ACL。
     * TODO: 接 EMQX REST(`POST /api/v5/authorization/...`,自建版)或控制台 API。
     *       Serverless 实例需在控制台为 username 手动配置 topic 前缀 ACL。
     * 当前:打印规则,便于运维对照控制台手工配置。
     */
    fun applyForDevice(deviceId: String) {
        val acl = buildDeviceAcl(deviceId)
        logger.info("[ACL] apply for deviceId={} (username={})", deviceId, acl.username)
        logger.info("[ACL]   publish -> {}", acl.publish)
        logger.info("[ACL]   subscribe -> {}", acl.subscribe)
        // TODO: emqx REST 写入 authorization rule
    }
}
