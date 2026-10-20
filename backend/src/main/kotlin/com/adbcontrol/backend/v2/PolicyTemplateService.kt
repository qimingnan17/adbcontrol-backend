package com.adbcontrol.backend.v2

import com.adbcontrol.backend.model.TaskRequest
import com.adbcontrol.backend.service.DatabaseService
import com.adbcontrol.backend.service.DeviceCommandBridge
import com.adbcontrol.backend.service.TaskSchedulerService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 策略模板(docs/v2-protocol.md §7)。模板 = 一组 app_time_limit + app_time_window 规则;
 * apply 到设备时为每条规则建一个定时任务(task 表,rule_type = "policy_template:{id}"),
 * 并按 [PolicyTemplatePayload.applyNow] 立即下发一次(离线进队列)。
 */
class PolicyTemplateService(
    private val repo: V2Repository,
    private val db: DatabaseService,
    private val bridge: DeviceCommandBridge,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Invalid(val message: String) : Result<Nothing>()
        data object NotFound : Result<Nothing>()
        data object StorageError : Result<Nothing>()
    }

    fun list(): List<PolicyTemplateDto> = repo.listTemplates().mapNotNull { it.toDto() }

    fun get(id: Long): PolicyTemplateDto? = repo.getTemplate(id)?.toDto()

    fun create(dto: PolicyTemplateDto): Result<PolicyTemplateDto> {
        val payload = when (val v = validate(dto)) { is Result.Ok -> v.value; is Result.Invalid -> return v; else -> return Result.StorageError }
        val now = System.currentTimeMillis()
        val id = repo.insertTemplate(dto.name.trim(), json.encodeToString(PolicyTemplatePayload.serializer(), payload), now)
            ?: return Result.StorageError
        return Result.Ok(get(id) ?: dto.copy(id = id, createdAt = now, updatedAt = now))
    }

    fun update(id: Long, dto: PolicyTemplateDto): Result<PolicyTemplateDto> {
        val payload = when (val v = validate(dto)) { is Result.Ok -> v.value; is Result.Invalid -> return v; else -> return Result.StorageError }
        if (repo.getTemplate(id) == null) return Result.NotFound
        if (!repo.updateTemplate(id, dto.name.trim(), json.encodeToString(PolicyTemplatePayload.serializer(), payload), System.currentTimeMillis())) {
            return Result.StorageError
        }
        return get(id)?.let { Result.Ok(it) } ?: Result.NotFound
    }

    fun delete(id: Long): Boolean = repo.deleteTemplate(id)

    suspend fun apply(id: Long, deviceIds: List<String>, issuedBy: String): Result<List<ApplyTemplateResult>> {
        val record = repo.getTemplate(id) ?: return Result.NotFound
        val targets = deviceIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) return Result.Invalid("deviceIds 不能为空")
        val payload = runCatching { json.decodeFromString(PolicyTemplatePayload.serializer(), record.payloadJson) }.getOrNull()
            ?: return Result.Invalid("模板 payload_json 已损坏")
        val commands = commandsOf(payload)
        val results = targets.map { deviceId ->
            val taskIds = mutableListOf<Long>()
            val errors = mutableListOf<String>()
            var dispatched = 0
            var queued = 0
            for ((type, args) in commands) {
                val cmdJson = buildJsonObject {
                    put("type", type)
                    put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
                    put("templateId", id)
                }.toString()
                val taskId = db.upsertTask(
                    TaskRequest(
                        deviceId = deviceId,
                        ruleType = "policy_template:$id",
                        cronExpr = payload.cronExpr,
                        commandJson = cmdJson,
                        enabled = true,
                    )
                )
                if (taskId == null) errors += "$type ${args["packageName"]}: 任务创建失败" else taskIds += taskId.toLong()
                if (payload.applyNow) {
                    when (val r = bridge.dispatch(deviceId, type, args, issuedBy = issuedBy)) {
                        is DeviceCommandBridge.DispatchResult.Ok -> dispatched++
                        is DeviceCommandBridge.DispatchResult.Queued -> queued++
                        else -> errors += "$type ${args["packageName"]}: $r"
                    }
                }
            }
            ApplyTemplateResult(deviceId, taskIds, dispatched, queued, errors)
        }
        return Result.Ok(results)
    }

    private fun PolicyTemplateRecordAlias.toDto(): PolicyTemplateDto? {
        val obj = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
        return PolicyTemplateDto(id, name, obj, createdAt, updatedAt)
    }

    companion object {
        /** 模板规则 → (Web 命令 type, args),与 DeviceCommandBridge.mapCommand 的入参一致。 */
        fun commandsOf(p: PolicyTemplatePayload): List<Pair<String, Map<String, String>>> =
            p.limits.map { "app_time_limit" to mapOf("packageName" to it.packageName, "minutes" to it.minutes.toString()) } +
                p.windows.map { "app_time_window" to mapOf("packageName" to it.packageName, "startTime" to it.startTime, "endTime" to it.endTime) }

        private val lenient = Json { ignoreUnknownKeys = true }

        fun validate(dto: PolicyTemplateDto): Result<PolicyTemplatePayload> {
            if (dto.name.isBlank()) return Result.Invalid("name 不能为空")
            val p = runCatching { lenient.decodeFromJsonElement(PolicyTemplatePayload.serializer(), dto.payload) }.getOrElse {
                return Result.Invalid("payload 结构错误: ${it.message?.take(120)}")
            }
            if (p.limits.isEmpty() && p.windows.isEmpty()) return Result.Invalid("payload.limits 与 payload.windows 不能同时为空")
            if (!TaskSchedulerService.isValidCron(p.cronExpr)) return Result.Invalid("payload.cronExpr 不是合法的 UNIX cron 表达式")
            p.limits.forEach {
                if (it.packageName.isBlank()) return Result.Invalid("limits[].packageName 不能为空")
                if (it.minutes < 0 || it.minutes > 24 * 60) return Result.Invalid("limits[].minutes 必须在 0..1440")
            }
            p.windows.forEach {
                if (it.packageName.isBlank()) return Result.Invalid("windows[].packageName 不能为空")
                val s = TaskSchedulerService.parseHhMm(it.startTime)
                val e = TaskSchedulerService.parseHhMm(it.endTime)
                if (s == null || e == null) return Result.Invalid("windows[].startTime/endTime 必须是 HH:mm")
                if (TaskSchedulerService.windowLengthMinutes(s, e) == 0) return Result.Invalid("windows[] 起止时间不能相同")
            }
            return Result.Ok(p)
        }
    }
}

private typealias PolicyTemplateRecordAlias = V2Repository.TemplateRecord
