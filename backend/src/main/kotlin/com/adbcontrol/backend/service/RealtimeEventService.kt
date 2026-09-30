package com.adbcontrol.backend.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * 进程内实时事件总线:遥测 ingest 产生事件,SSE 端点(/api/events)推给 Web 控制台。
 *
 * 此前 Web 只能 30s 轮询 /api/devices,设备上下线、命令执行结果、通知签收都要等下一轮
 * 刷新才能看到。事件经 ingest 单点产生,这里只做扇出;SSE 断开由前端 EventSource 自动重连。
 *
 * 背压:extraBufferCapacity=256 + DROP_OLDEST —— SSE 消费者只读最新状态,丢旧事件无害
 * (设备状态类事件天然幂等,列表 30s 轮询仍是兜底)。
 */
class RealtimeEventService {

    @Serializable
    data class EventDto(
        val type: String,
        val deviceId: String,
        val status: String? = null,
        val battery: Int? = null,
        val network: String? = null,
        val lastSeen: Long? = null,
        val msgId: String? = null,
        val success: Boolean? = null,
        val output: String? = null,
        val ts: Long = System.currentTimeMillis(),
    )

    sealed class Event {
        abstract val deviceId: String

        data class DeviceStatus(
            override val deviceId: String,
            val battery: Int?,
            val network: String?,
            val lastSeen: Long,
        ) : Event()

        data class CommandResult(
            override val deviceId: String,
            val msgId: String,
            val success: Boolean,
            val output: String,
        ) : Event()

        data class ReminderAckEvent(
            override val deviceId: String,
            val msgId: String,
            val buttonText: String,
        ) : Event()

        data class DeviceOffline(override val deviceId: String) : Event()
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 256)

    val flow: SharedFlow<Event> = events

    fun emit(event: Event) {
        if (!events.tryEmit(event)) {
            logger.debug("realtime event buffer full, dropped one event")
        }
    }

    fun toDto(event: Event): EventDto = when (event) {
        is Event.DeviceStatus -> EventDto(
            type = "device_status", deviceId = event.deviceId,
            battery = event.battery, network = event.network, lastSeen = event.lastSeen,
        )
        is Event.CommandResult -> EventDto(
            type = "command_result", deviceId = event.deviceId,
            msgId = event.msgId, success = event.success,
            output = event.output.take(160),
        )
        is Event.ReminderAckEvent -> EventDto(
            type = "reminder_ack", deviceId = event.deviceId,
            msgId = event.msgId, output = event.buttonText,
        )
        is Event.DeviceOffline -> EventDto(type = "device_offline", deviceId = event.deviceId)
    }

    fun serialize(event: Event): String = json.encodeToString(EventDto.serializer(), toDto(event))
}
