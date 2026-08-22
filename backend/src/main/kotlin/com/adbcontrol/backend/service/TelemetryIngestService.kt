package com.adbcontrol.backend.service

import com.adbcontrol.backend.config.BackendConfig
import com.adbcontrol.shared.model.ActivityReport
import com.adbcontrol.shared.model.ExecutionResult
import com.adbcontrol.shared.model.HealthReport
import com.adbcontrol.shared.model.LocationReport
import com.adbcontrol.shared.model.StatusReport
import com.adbcontrol.shared.model.UsageReport
import com.adbcontrol.shared.net.MqttTopics
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

/**
 * MQTT 遥测 ingestor(P0 中枢补位)。
 *
 * 原设计中「订阅遥测 + 落 MySQL」由 controller Android 承担;该仓库不在线上,
 * 导致被控端发布的 status/health/location/activity/usage/result 全部没有消费者,
 * Dashboard、设备详情、命令历史因此一直是空数据。本服务把这段搬回后端:
 *
 * - 订阅被控端上行话题:status/+ health/+ location/+ activity/+ usage/+
 *   result/+ device/offline/+(均 QoS 1)。
 * - 每条消息先按 wire 协议验签:envelope.signature =
 *   HMAC-SHA256(payload:id:timestamp, sessionKey),sessionKey 来自
 *   [PairingService.sessionKeyFor];缺失签名/验签失败/超 5 分钟重放窗口一律丢弃。
 * - 验签通过后按 shared Telemetry 模型反序列化并写 MySQL(幂等插入)。
 *
 * 账号约定:ADB_EMQX_INGEST_USERNAME / ADB_EMQX_INGEST_PASSWORD,由运维在 EMQX
 * 控制台手工创建一次并授予上述 6 个前缀的订阅 ACL;代码不自注册,缺省则
 * ingestor 打 warning 但整体不启动(其余功能不受影响)。
 */
class TelemetryIngestService(
    private val config: BackendConfig,
    private val db: DatabaseService,
    private val pairing: PairingService,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Volatile
    private var client: MqttAsyncClient? = null

    @Volatile
    private var closed = false

    /**
     * 首连失败重试。Paho 的 automaticReconnect 只在"建立过的连接断开"后生效,
     * 首次 connect 失败(EMQX 不可达/凭证错误)不会自动重试,若不自行调度,
     * ingestor 会永久静默停摆、遥测全部丢失。
     */
    private val retryScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ingest-retry").apply { isDaemon = true }
    }

    /** wire 信封,与被控端 MqttEnvelope 字段一致。 */
    @Serializable
    private data class Envelope(
        val id: String,
        val type: String,
        val payload: String,
        val timestamp: Long,
        val signature: String? = null,
    )

    /** 启动并连接(凭据缺失时不启动,不抛异常)。 */
    @Synchronized
    fun start() {
        if (client != null || closed) return
        val username = config.emqxIngestUsername
        val password = config.emqxIngestPassword
        if (username.isBlank() || password.isBlank()) {
            logger.warn(
                "MQTT ingestor disabled: set ADB_EMQX_INGEST_USERNAME / ADB_EMQX_INGEST_PASSWORD " +
                    "(create the account once in EMQX console with subscribe ACL on status/+, health/+, " +
                    "location/+, activity/+, usage/+, result/+, device/offline/+)",
            )
            return
        }
        runCatching {
            val c = MqttAsyncClient(
                "ssl://${config.emqxHost}:${config.emqxPort}",
                "backend-ingest-" + UUID.randomUUID().toString().replace("-", "").take(10),
                null,
            )
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    subscribeTopics(c)
                    logger.info("ingestor connected to {} (reconnect={})", serverURI, reconnect)
                }

                override fun connectionLost(cause: Throwable?) {
                    logger.warn("ingestor connection lost: {}", cause?.message)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return
                    runCatching { handle(topic, message.payload) }
                        .onFailure { logger.warn("ingest handle failed on {}: {}", topic, it.message) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val options = buildOptions(username, password)
            client = c
            attemptConnect(c, options)
        }.onFailure {
            logger.error("ingestor start failed, retrying in 60s: {}", it.message)
            client?.let { c ->
                val opts = buildOptions(username, password)
                scheduleReconnect(c, opts)
            }
        }
    }

    private fun buildOptions(username: String, password: String): MqttConnectOptions =
        MqttConnectOptions().apply {
            isAutomaticReconnect = true
            maxReconnectDelay = 30_000
            isCleanSession = true
            keepAliveInterval = 45
            connectionTimeout = 15
            userName = username
            this.password = password.toCharArray()
            socketFactory = SSLSocketFactory.getDefault()
        }

    /** 异步连接;首连失败由 [scheduleReconnect] 兜底(automaticReconnect 对首连失效)。 */
    private fun attemptConnect(c: MqttAsyncClient, options: MqttConnectOptions) {
        try {
            c.connect(options, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {}

                override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) {
                    logger.error("ingestor connect failed, retrying in 60s: {}", exception?.message)
                    scheduleReconnect(c, options)
                }
            })
        } catch (t: Throwable) {
            // connect() 在非 CONNECTED 状态下可能同步抛异常(如重复调用),同样兜底重试
            logger.error("ingestor connect threw, retrying in 60s: {}", t.message)
            scheduleReconnect(c, options)
        }
    }

    private fun scheduleReconnect(c: MqttAsyncClient, options: MqttConnectOptions) {
        if (closed) return
        runCatching {
            retryScheduler.schedule({
                if (!closed && !c.isConnected) attemptConnect(c, options)
            }, 60, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { logger.warn("ingestor retry scheduling failed: {}", it.message) }
    }

    private fun subscribeTopics(c: MqttAsyncClient) {
        val topics = arrayOf(
            "status/+", "health/+", "location/+",
            "activity/+", "usage/+", "result/+",
            "device/offline/+",
        )
        val qos = IntArray(topics.size) { 1 }
        runCatching { c.subscribe(topics, qos) }
            .onFailure { logger.error("ingestor subscribe failed", it) }
    }

    private fun handle(topic: String, payload: ByteArray) {
        val deviceId = MqttTopics.parseDeviceId(topic) ?: run {
            logger.debug("drop message with unparsable device topic: {}", topic)
            return
        }

        // LWT: device/offline/<deviceId> 的 payload 是裸 deviceId,非 envelope。
        if (topic.startsWith(MqttTopics.DEVICE_OFFLINE_PREFIX)) {
            db.markOffline(deviceId, System.currentTimeMillis())
            return
        }

        val envelope = runCatching {
            json.decodeFromString(Envelope.serializer(), String(payload, Charsets.UTF_8))
        }.getOrNull() ?: run {
            logger.debug("drop malformed envelope on {}", topic)
            return
        }

        // 分层鉴权:未配对设备的消息、无签名的消息、验签失败、超窗口一律丢。
        val sessionKey = pairing.sessionKeyFor(deviceId) ?: run {
            logger.debug("drop message from unpaired device {}", deviceId)
            return
        }
        val sig = envelope.signature
        if (sig.isNullOrEmpty()) {
            logger.warn("drop unsigned message on {}", topic)
            return
        }
        val signingData = HmacSigner.buildSigningData(envelope.payload, envelope.id, envelope.timestamp)
        if (!HmacSigner.verify(signingData, sessionKey, sig)) {
            logger.warn("drop bad-signature message on {}", topic)
            return
        }
        if (!HmacSigner.isWithinReplayWindow(envelope.timestamp)) {
            logger.warn("drop replayed/stale message on {} (ts={})", topic, envelope.timestamp)
            return
        }

        when {
            topic.startsWith(MqttTopics.STATUS_PREFIX) -> {
                val r = json.decodeFromString(StatusReport.serializer(), envelope.payload)
                db.upsertDeviceStatus(
                    deviceId = deviceId,
                    battery = r.battery,
                    charging = r.charging,
                    network = r.network.name,
                    networkStrength = r.signalDbm,
                    screenOn = r.screenOn,
                    foregroundPkg = r.foregroundPackage,
                    lastSeen = r.timestamp,
                )
            }
            topic.startsWith(MqttTopics.HEALTH_PREFIX) -> {
                val r = json.decodeFromString(HealthReport.serializer(), envelope.payload)
                db.applyHealth(
                    deviceId = deviceId,
                    shizukuConnected = r.shizuku == HealthReport.ShizukuState.CONNECTED,
                    root = r.root,
                    accessibility = r.accessibility,
                    deviceAdmin = r.deviceAdmin,
                    androidVersion = r.androidVersion,
                    appVersion = r.appVersion,
                    lastSeen = r.timestamp,
                )
            }
            topic.startsWith(MqttTopics.LOCATION_PREFIX) -> {
                val r = json.decodeFromString(LocationReport.serializer(), envelope.payload)
                db.insertLocation(
                    deviceId = deviceId,
                    userId = null,
                    lat = r.lat,
                    lng = r.lng,
                    accuracy = r.accuracy,
                    speed = r.speed,
                    provider = r.provider,
                    fenceEvent = r.fenceEvent,
                    reportedAt = r.timestamp,
                )
            }
            topic.startsWith(MqttTopics.ACTIVITY_PREFIX) -> {
                val r = json.decodeFromString(ActivityReport.serializer(), envelope.payload)
                db.insertActivity(
                    deviceId = deviceId,
                    userId = r.userId,
                    event = r.event.name,
                    pkg = r.pkg,
                    appName = r.appName,
                    durationMs = r.durationMs,
                    occurredAt = r.timestamp,
                )
            }
            topic.startsWith(MqttTopics.USAGE_PREFIX) -> {
                val r = json.decodeFromString(UsageReport.serializer(), envelope.payload)
                r.items.forEach { item ->
                    db.upsertUsageItem(
                        deviceId = deviceId,
                        userId = r.userId,
                        pkg = item.pkg,
                        usageMinutes = item.usageMinutes,
                        date = r.date,
                        uploadedAt = r.timestamp,
                    )
                }
            }
            topic.startsWith(MqttTopics.RESULT_PREFIX) -> {
                val r = json.decodeFromString(ExecutionResult.serializer(), envelope.payload)
                db.insertExecutionLog(
                    deviceId = deviceId,
                    msgId = envelope.id,
                    success = r.success,
                    output = r.output,
                    durationMs = r.durationMs,
                    executedAt = r.timestamp,
                )
            }
            else -> logger.debug("ignore unhandled ingest topic {}", topic)
        }
    }

    override fun close() {
        closed = true
        runCatching { retryScheduler.shutdownNow() }
        runCatching {
            client?.disconnectForcibly()
            client?.close()
        }
        client = null
    }
}
