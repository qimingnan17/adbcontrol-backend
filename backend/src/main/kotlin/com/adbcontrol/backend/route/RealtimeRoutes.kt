package com.adbcontrol.backend.route

import com.adbcontrol.backend.service.RealtimeEventService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GET /api/events — SSE 实时事件流(需管理员会话)。
 *
 * 事件源:遥测 ingest(设备状态/命令结果/通知签收/设备离线)。
 * 前端 EventSource 订阅后,设备上下线、命令执行结果秒级上屏,不再等 30s 轮询;
 * 轮询保留作为断线兜底。
 *
 * 传输细节:
 * - respondTextWriter + Text/EventStream 分块写出,每条事件整块 flush;
 * - 25s 注释行心跳,防止反代/浏览器空闲超时断流(CF 隧道默认空闲 ~100s);
 * - X-Accel-Buffering=no 禁 nginx 缓冲。
 */
fun Route.realtimeRoutes(realtime: RealtimeEventService) {
    authenticate("auth-session") {
        get("/api/events") {
            call.respondRealtimeStream(realtime)
        }
    }
}

private suspend fun ApplicationCall.respondRealtimeStream(realtime: RealtimeEventService) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header("X-Accel-Buffering", "no")
    respondTextWriter(contentType = ContentType.Text.EventStream) {
        coroutineScope {
            // 单写者模型:事件与心跳都进 channel,for 循环是唯一的 writer,避免并发写乱流
            val blocks = Channel<String>(capacity = 128)
            val jobs = listOf(
                launch(Dispatchers.IO) {
                    realtime.flow.collect { e ->
                        blocks.trySend("event: ${realtime.toDto(e).type}\ndata: ${realtime.serialize(e)}\n\n")
                    }
                },
                launch(Dispatchers.IO) {
                    while (true) {
                        delay(25_000)
                        blocks.trySend(": ping\n\n")
                    }
                },
            )
            try {
                write(": connected\n\n")
                flush()
                for (block in blocks) {
                    write(block)
                    flush()
                }
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }
}
