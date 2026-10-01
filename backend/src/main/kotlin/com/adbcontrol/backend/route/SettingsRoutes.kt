package com.adbcontrol.backend.route

import com.adbcontrol.backend.model.*
import com.adbcontrol.backend.plugin.UserSession
import com.adbcontrol.backend.security.CryptoUtil
import com.adbcontrol.backend.security.NetworkSecurity
import com.adbcontrol.backend.service.CloudflareOAuthService
import com.adbcontrol.backend.service.ConnectorInstaller
import com.adbcontrol.backend.service.CloudflareService
import com.adbcontrol.backend.service.SettingsService
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.system.exitProcess

@Serializable
private data class TestR2Request(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val accessSecret: String,
)

@Serializable
private data class TestEmqxRequest(
    val restEndpoint: String,
    val appId: String,
    val appSecret: String,
)

/** Cloudflare 资源同步请求体（apiToken 可空，空时走已保存 Token 或 OAuth 票据）。 */
@Serializable
private data class CfSyncRequest(
    val apiToken: String? = null,
)

/** OAuth 授权 state(防 CSRF + 记住回调来源),10 分钟过期。 */
private data class CfOAuthStateInfo(
    val redirectUri: String,
    val origin: String,
    val createdAt: Long = System.currentTimeMillis()
)

private val cfOAuthStateCache = java.util.concurrent.ConcurrentHashMap<String, CfOAuthStateInfo>()

/** 生成 CI 部署令牌:32 字节 CSPRNG,URL 安全字符集(无 + / = ,便于放进 curl header)。 */
private fun generateCiUpgradeToken(): String {
    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    return org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(bytes)
}

private fun cleanExpiredCfOAuthStates() {
    val now = System.currentTimeMillis()
    cfOAuthStateCache.entries.removeIf { now - it.value.createdAt > 600_000 }
}

/**
 * 解析可用的 Cloudflare 凭据,按优先级:
 *   显式传入(未脱敏) > 手工 API Token > OAuth 一键授权票据(自动续期)。
 *
 * 这样既保留原有「粘贴 Token」通道,又让一键授权在 Token 留空时自动接管 ——
 * 不必逐端点改写业务逻辑。
 */
private suspend fun resolveCfToken(
    explicit: String?,
    settingsService: SettingsService,
    cfOAuth: CloudflareOAuthService,
): String {
    val raw = explicit?.trim().orEmpty()
    if (raw.isNotEmpty() && !raw.contains("******")) return raw
    val manual = settingsService.getRawProperty("cf.api_token", "")
    if (manual.isNotBlank()) return manual
    return cfOAuth.currentAccessToken().orEmpty()
}

private suspend fun ApplicationCall.ensureTailscaleOrLocal(): Boolean {
    // 1. 本地回环或 Tailscale CGNAT 私网直连
    if (NetworkSecurity.isLocalOrTailscale(this)) return true

    // 2. 具备合法管理员会话（账号密码登录后的远程会话）
    val sess = sessions.get<UserSession>()
    if (sess != null && sess.role == "admin") {
        return true
    }

    respond(
        HttpStatusCode.Forbidden,
        SettingsOperationResponse(
            ok = false,
            code = "FORBIDDEN_AUTH_REQUIRED",
            message = "安全限制：此管理配置接口仅允许在 Tailscale 内网或具备管理员授权会话中访问，公网未登录已被严格阻断。"
        )
    )
    return false
}

fun Route.settingsRoutes(
    settingsService: SettingsService,
    cfService: CloudflareService,
    cfOAuth: CloudflareOAuthService,
    connectorInstaller: ConnectorInstaller,
) {
    // 网络模式检查：诊断探针无需强制会话，方便前端未登录或初始化时也能判断网络环境
    get("/api/admin/settings/network") {
        val isTailscale = NetworkSecurity.isLocalOrTailscale(call)
        val clientIp = NetworkSecurity.getClientIp(call)
        val sess = call.sessions.get<UserSession>()
        val isAuthed = (sess != null && sess.role == "admin")
        call.respond(
            NetworkStatusResponse(
                isTailscale = isTailscale || isAuthed,
                clientIp = clientIp,
                tip = when {
                    isTailscale -> "Tailscale / Localhost 安全内网"
                    isAuthed -> "Cloudflare SSO / 管理员授权访问"
                    else -> "公网访问（需登录管理员或内网配置）"
                }
            )
        )
    }

    /**
     * Cloudflare OAuth 授权码回调。
     *
     * 必须注册在 authenticate 之外:这是 Cloudflare 把浏览器重定向过来的入口,
     * 请求来自外部跳转而非后台 XHR。安全依赖两点:state 必须命中未过期缓存(防 CSRF),
     * 且 cloudflare 只会在 client 登记过的 redirect_uri 上带 code 过来。
     */
    get("/api/admin/settings/cloudflare/oauth/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]
        val errorDesc = call.request.queryParameters["error_description"]

        val stateInfo = if (!state.isNullOrBlank()) cfOAuthStateCache.remove(state) else null
        val origin = stateInfo?.origin
            ?: settingsService.getRawProperty("server.url").ifBlank { NetworkSecurity.clientOrigin(call) }.trimEnd('/')

        suspend fun back(query: String) = call.respondRedirect("$origin/settings?$query")

        if (!error.isNullOrBlank() || code.isNullOrBlank()) {
            back("cf_oauth_error=" + (errorDesc ?: error ?: "授权已被取消或中断").encodeURLParameter())
            return@get
        }
        if (stateInfo == null) {
            back("cf_oauth_error=" + "授权请求已失效或过期，请重新发起".encodeURLParameter())
            return@get
        }
        val (ok, message) = cfOAuth.exchangeCode(code, stateInfo.redirectUri)
        if (ok) back("cf_oauth=success") else back("cf_oauth_error=" + message.encodeURLParameter())
    }

    authenticate("auth-session") {
        route("/api/admin/settings") {

            // 读取脱敏配置
            get("/secrets") {
                if (!call.ensureTailscaleOrLocal()) return@get
                call.respond(settingsService.readMaskedSecrets())
            }

            // 保存配置
            post("/secrets") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val body = runCatching { call.receive<Map<String, String>>() }.getOrNull()
                if (body == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "请求体格式错误")
                    )
                    return@post
                }
                val ok = settingsService.saveSecrets(body)
                if (ok) {
                    call.respond(
                        SettingsOperationResponse(ok = true, message = "配置已成功保存到 secrets.properties")
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        SettingsOperationResponse(ok = false, message = "保存配置失败")
                    )
                }
            }

            // 测试 R2 连接
            post("/test-r2") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<TestR2Request>() }.getOrNull()
                if (req == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "请求参数格式错误")
                    )
                    return@post
                }
                val (ok, msg) = settingsService.testR2(req.endpoint, req.bucket, req.accessKey, req.accessSecret)
                call.respond(SettingsOperationResponse(ok = ok, message = msg))
            }

            // 测试 EMQX REST API 连接
            post("/test-emqx") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<TestEmqxRequest>() }.getOrNull()
                if (req == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "请求参数格式错误")
                    )
                    return@post
                }
                val (ok, msg) = settingsService.testEmqx(req.restEndpoint, req.appId, req.appSecret)
                call.respond(SettingsOperationResponse(ok = ok, message = msg))
            }

            // 重启后端服务生效配置
            post("/restart") {
                if (!call.ensureTailscaleOrLocal()) return@post
                call.respond(
                    SettingsOperationResponse(ok = true, message = "后端守护进程正在重启，约 5 秒内恢复上线...")
                )
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    exitProcess(0)
                }
            }

            // 同步 Cloudflare 资源：获取账户、域名、隧道、R2 桶、D1 数据库
            post("/cloudflare/sync") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfSyncRequest>() }.getOrNull()
                // 仅当用户本次显式粘了真 Token 时才回写配置，避免把 OAuth 短期票据
                // 当成长期 API Token 持久化（那会在票据过期后静默失效）。
                val provided = req?.apiToken?.trim().orEmpty()
                    .takeIf { it.isNotEmpty() && !it.contains("******") }
                val token = resolveCfToken(req?.apiToken, settingsService, cfOAuth)
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfAllResources(valid = false, message = "未检测到 Cloudflare 凭据，请粘贴 API Token 或先完成 OAuth 一键授权")
                    )
                    return@post
                }
                val resources = cfService.fetchAllResources(token)
                if (resources.valid && provided != null) {
                    settingsService.saveSecrets(mapOf("cf.api_token" to provided))
                }
                call.respond(resources)
            }

            // 一键应用 Cloudflare R2 存储桶配置到系统
            post("/cloudflare/apply-r2") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyR2Request>() }.getOrNull()
                if (req == null || req.endpoint.isBlank() || req.bucket.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "R2 Endpoint 与 Bucket 不能为空")
                    )
                    return@post
                }
                val updates = mutableMapOf(
                    "r2.endpoint" to req.endpoint.trim(),
                    "r2.bucket" to req.bucket.trim()
                )
                if (!req.accessKey.isNullOrBlank()) updates["r2.access_key"] = req.accessKey.trim()
                if (!req.accessSecret.isNullOrBlank()) updates["r2.access_secret"] = req.accessSecret.trim()
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已成功应用 Cloudflare R2 存储配置"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存 R2 配置失败"))
                }
            }

            // 一键将 Cloudflare 隧道域名应用为系统服务地址
            post("/cloudflare/apply-tunnel") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyTunnelRequest>() }.getOrNull()
                if (req == null || req.serverUrl.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "服务地址不能为空")
                    )
                    return@post
                }
                val ok = settingsService.saveSecrets(mapOf("server.url" to req.serverUrl.trim()))
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已将 Cloudflare 隧道域名设为服务地址"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存服务地址失败"))
                }
            }

            // 查询系统绑定的云端资产概览 (域名/隧道、R2 桶、D1 数据库)
            get("/cloudflare/bindings") {
                if (!call.ensureTailscaleOrLocal()) return@get
                call.respond(settingsService.getCloudBindings())
            }

            // ==================== CI 部署接入 ====================

            /**
             * 汇总 CI 自动部署接入所需的全部信息:回调 URL + 当前令牌状态。
             *
             * 令牌本身只返回掩码。运维首次接入或轮换时走 POST /ci-token/rotate,
             * 那里会明文返回一次供其复制到 GitHub Secrets —— 之后任何时候
             * 都无法再从服务端读回明文。
             */
            get("/ci-access") {
                if (!call.ensureTailscaleOrLocal()) return@get
                call.respond(settingsService.buildCiAccess(token = null))
            }

            /**
             * 轮换 CI 部署令牌:生成 32 字节随机值写入 secrets.properties 并明文返回一次。
             *
             * 旧令牌立即失效(UpdateService 每次校验都实时读文件,无需重启)。
             * 轮换后必须同步更新 GitHub Secrets,否则 CI 通知会在下次部署时静默失败,
             * 退化成 10 分钟轮询兜底 —— 不会中断部署,只是慢。
             */
            post("/ci-token/rotate") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val token = generateCiUpgradeToken()
                val ok = runCatching {
                    settingsService.saveSecrets(mapOf("ci.upgrade_token" to token))
                }.getOrDefault(false)
                if (!ok) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        SettingsOperationResponse(ok = false, message = "保存 CI 令牌失败，请检查 secrets.properties 是否可写")
                    )
                    return@post
                }
                call.respond(settingsService.buildCiAccess(token = token))
            }

            // ==================== Cloudflare OAuth 一键授权 ====================

            // 保存 OAuth 授权应用凭据（Client ID / Client Secret）
            post("/cloudflare/apply-oauth-client") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyOAuthClientRequest>() }.getOrNull()
                if (req == null || req.clientId.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "OAuth Client ID 不能为空")
                    )
                    return@post
                }
                val updates = mutableMapOf(CloudflareOAuthService.KEY_CLIENT_ID to req.clientId.trim())
                // 梳码值代表"不修改"，与其它密钥卡片一致
                if (!req.clientSecret.isNullOrBlank() && !req.clientSecret.contains("******")) {
                    updates[CloudflareOAuthService.KEY_CLIENT_SECRET] = req.clientSecret.trim()
                }
                if (!req.scopes.isNullOrBlank()) {
                    updates[CloudflareOAuthService.KEY_SCOPES] = req.scopes.trim()
                }
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "Cloudflare OAuth 应用凭据已保存"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存 OAuth 应用凭据失败"))
                }
            }

            // 获取 OAuth 官方授权跳转地址（前端据此拉起 Cloudflare 授权页）
            get("/cloudflare/oauth/authorize-url") {
                if (!call.ensureTailscaleOrLocal()) return@get
                val origin = settingsService.getRawProperty("server.url")
                    .ifBlank { NetworkSecurity.clientOrigin(call) }.trimEnd('/')
                val redirectUri = "$origin/api/admin/settings/cloudflare/oauth/callback"
                cleanExpiredCfOAuthStates()
                val state = java.util.UUID.randomUUID().toString()
                val authUrl = cfOAuth.buildAuthorizeUrl(redirectUri, state)
                if (authUrl == null) {
                    call.respond(
                        CfOAuthAuthorizeUrlResponse(
                            configured = false,
                            message = "尚未配置 Cloudflare OAuth Client ID / Secret（需同时填写两者并保存）"
                        )
                    )
                    return@get
                }
                cfOAuthStateCache[state] = CfOAuthStateInfo(redirectUri = redirectUri, origin = origin)
                call.respond(CfOAuthAuthorizeUrlResponse(configured = true, authUrl = authUrl))
            }

            // 查询 OAuth 连接状态
            get("/cloudflare/oauth/status") {
                if (!call.ensureTailscaleOrLocal()) return@get
                call.respond(cfOAuth.status())
            }

            // 断开 OAuth 授权（清空票据，保留 Client 凭据便于重新连接）
            post("/cloudflare/oauth/disconnect") {
                if (!call.ensureTailscaleOrLocal()) return@post
                cfOAuth.disconnect()
                call.respond(SettingsOperationResponse(ok = true, message = "已断开 Cloudflare 授权"))
            }

            // ==================== R2 全自动配置 ====================

            /**
             * 一键配置 R2:建桶 → 开 r2.dev 公共读域名 → 生成桶级 S3 凭据 → 实测校验 → 落盘。
             *
             * 为什么需要 bootstrap token:OAuth 票据**没有** API Tokens:Write scope,
             * 而生成 R2 的 S3 Access Key/Secret 只能走 `POST /accounts/{id}/tokens`。
             * 子 token 也拿不到该权限,所以 bootstrap 必须是用户手工创建的
             * 「Account API Tokens:Edit」token。凭据生成后校验不通过就不落盘。
             */
            post("/cloudflare/provision-r2") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfR2ProvisionRequest>() }.getOrNull() ?: CfR2ProvisionRequest()
                val steps = mutableListOf<CfProvisionStep>()

                val token = resolveCfToken(null, settingsService, cfOAuth)
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(ok = false, steps = steps, message = "未检测到 Cloudflare 凭据，请先粘贴 API Token 或完成 OAuth 一键授权")
                    )
                    return@post
                }

                // 1. 解析账户
                val accounts = cfService.fetchAccounts(token).data ?: emptyList()
                if (accounts.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(ok = false, steps = steps, message = "无法读取 Cloudflare 账户（凭据缺少「帐户设置:Read」）")
                    )
                    return@post
                }
                val account = req.accountId?.trim()?.let { id -> accounts.firstOrNull { it.id == id } } ?: accounts.first()
                val endpoint = "https://${account.id}.r2.cloudflarestorage.com"

                // 2. 确定桶名（R2 桶名规范:3-64 位小写字母/数字/中划线,首尾必须是字母数字）
                val bucket = req.bucketName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    ?: ("adbcontrol-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8))
                if (!Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$").matches(bucket)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(ok = false, steps = steps, message = "存储桶名不合法（3-64 位小写字母/数字/中划线，首尾为字母或数字）")
                    )
                    return@post
                }

                // 3. 建桶（已存在则直接复用，保证幂等）
                if (cfService.r2BucketExists(token, account.id, bucket)) {
                    steps.add(CfProvisionStep("create_bucket", true, "存储桶 $bucket 已存在，直接复用"))
                } else {
                    val created = cfService.createR2Bucket(token, account.id, bucket)
                    steps.add(CfProvisionStep("create_bucket", created.ok, created.message))
                    if (!created.ok) {
                        call.respond(CfR2ProvisionResponse(ok = false, accountId = account.id, bucket = bucket, steps = steps, message = "创建存储桶失败：${created.message}"))
                        return@post
                    }
                }

                // 4. 开启 r2.dev 公共读域名（失败不阻断，仅影响公共 URL 是否可用）
                var publicBaseUrl = ""
                if (req.enablePublic) {
                    val domain = cfService.enableR2ManagedDomain(token, account.id, bucket)
                    if (domain.data != null) {
                        publicBaseUrl = domain.data
                        steps.add(CfProvisionStep("enable_public", true, "公共读域名 $publicBaseUrl"))
                    } else {
                        steps.add(CfProvisionStep("enable_public", false, domain.error ?: "开启 r2.dev 失败"))
                    }
                }

                // 5. 解析 bootstrap token
                var bootstrap = req.bootstrapToken?.trim().orEmpty()
                if (bootstrap.isEmpty() || bootstrap.contains("******")) {
                    bootstrap = settingsService.getRawProperty("cf.bootstrap_token", "")
                }
                if (bootstrap.isBlank()) {
                    steps.add(CfProvisionStep("create_credentials", false, "缺少 bootstrap token"))
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(
                            ok = false, accountId = account.id, bucket = bucket, publicBaseUrl = publicBaseUrl, steps = steps,
                            message = "存储桶已就绪，但缺少 bootstrap token，无法生成 R2 的 S3 凭据。" +
                                "请在 Cloudflare 控制台创建一枚带「Account API Tokens:Edit」的 API Token 并粘贴到下方（只需一次）。"
                        )
                    )
                    return@post
                }

                // 6. 解析权限组（id 不透明，必须动态查，不能写死）
                val groupsRes = cfService.listAccountTokenPermissionGroups(bootstrap, account.id)
                val groups = groupsRes.data
                if (groups == null) {
                    steps.add(CfProvisionStep("resolve_permission", false, groupsRes.error))
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(ok = false, accountId = account.id, bucket = bucket, publicBaseUrl = publicBaseUrl, steps = steps,
                            message = "无法读取权限组：${groupsRes.error}")
                    )
                    return@post
                }
                val permissionGroup = groups.firstOrNull { it.name.equals("Workers R2 Storage Bucket Item Write", ignoreCase = true) }
                    ?: groups.firstOrNull { it.name.equals("Workers R2 Storage Write", ignoreCase = true) }
                if (permissionGroup == null) {
                    steps.add(CfProvisionStep("resolve_permission", false, "未找到 R2 写入权限组"))
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(ok = false, accountId = account.id, bucket = bucket, publicBaseUrl = publicBaseUrl, steps = steps,
                            message = "未在账户权限组中找到「Workers R2 Storage Bucket Item Write」，请确认账户已开通 R2")
                    )
                    return@post
                }
                steps.add(CfProvisionStep("resolve_permission", true, "使用权限组「${permissionGroup.name}」（限 $bucket）"))

                // 7. 创建桶级 API Token → 推导 S3 凭据
                val (createdToken, createErr) = cfService.createBucketScopedToken(
                    bootstrapToken = bootstrap,
                    accountId = account.id,
                    tokenName = "adbcontrol-r2-$bucket",
                    bucket = bucket,
                    permissionGroupId = permissionGroup.id,
                )
                if (createdToken == null) {
                    steps.add(CfProvisionStep("create_credentials", false, createErr))
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2ProvisionResponse(ok = false, accountId = account.id, bucket = bucket, publicBaseUrl = publicBaseUrl, steps = steps,
                            message = "生成 R2 访问凭据失败：${createErr}")
                    )
                    return@post
                }
                // Access Key ID = token.id;Secret Access Key = SHA-256 hex(token.value)
                val accessKeyId = createdToken.id
                val accessSecret = CryptoUtil.sha256Hex(createdToken.value.toByteArray(Charsets.UTF_8))

                // 8. 实测校验：没跑通就不落盘，避免把错凭据写进配置后到处 403
                val (verified, verifyMsg) = settingsService.testR2(endpoint, bucket, accessKeyId, accessSecret)
                steps.add(CfProvisionStep("verify", verified, verifyMsg))
                if (!verified) {
                    steps.add(CfProvisionStep("create_credentials", false, "凭据已生成但未通过实测，未写入配置"))
                    call.respond(
                        CfR2ProvisionResponse(ok = false, accountId = account.id, bucket = bucket, publicBaseUrl = publicBaseUrl, accessKeyId = accessKeyId, steps = steps,
                            message = "R2 凭据生成成功但实测未通过，未写入配置：$verifyMsg")
                    )
                    return@post
                }

                // 9. 落盘（含 bootstrap token 以便下次无需重粘）
                val updates = mutableMapOf(
                    "r2.endpoint" to endpoint,
                    "r2.bucket" to bucket,
                    "r2.access_key" to accessKeyId,
                    "r2.access_secret" to accessSecret,
                    "cf.bootstrap_token" to bootstrap,
                )
                if (publicBaseUrl.isNotBlank()) updates["r2.public_base_url"] = publicBaseUrl
                val saved = settingsService.saveSecrets(updates)
                if (!saved) {
                    call.respond(HttpStatusCode.InternalServerError, CfR2ProvisionResponse(ok = false, accountId = account.id, bucket = bucket, steps = steps, message = "R2 凭据校验通过，但写入配置文件失败"))
                    return@post
                }

                call.respond(
                    CfR2ProvisionResponse(
                        ok = true,
                        accountId = account.id,
                        bucket = bucket,
                        publicBaseUrl = publicBaseUrl,
                        accessKeyId = accessKeyId,
                        steps = steps,
                        message = "R2 已配置完成：桶 $bucket" +
                            (if (publicBaseUrl.isNotBlank()) "（公共读 $publicBaseUrl）" else "（未开启公共读）") +
                            "。凭据为桶级最小权限，重启后端后对设备生效。"
                    )
                )
            }

            // ==================== R2 自定义公共域名 ====================

            /**
             * 给 R2 桶绑自定义域名作为公共访问基址（生产级，无 r2.dev 限速）。
             *
             * - bucket 留空取当前绑定的 r2.bucket；
             * - zoneId 留空时按域名后缀在已同步 Zones 中自动匹配；
             * - 幂等:若该域已在桶上启用（无论之前绑在谁身上），直接返回当前状态，不重复提交；
             * - 就绪后自动把 `r2.public_base_url` 更新为 https://<domain>。
             */
            post("/cloudflare/r2/bind-custom-domain") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfR2CustomDomainRequest>() }.getOrNull()
                val domainRaw = req?.domain?.trim().orEmpty().removePrefix("https://").removePrefix("http://").trimEnd('/')
                if (domainRaw.isEmpty() || "/" in domainRaw || domainRaw.count { it == '.' } < 1) {
                    call.respond(HttpStatusCode.BadRequest, CfR2CustomDomainResponse(ok = false, message = "请填写合法的域名，如 media.example.com"))
                    return@post
                }
                val zoneIdHint = req?.zoneId?.trim().orEmpty()
                val bucketHint = req?.bucket?.trim().orEmpty()
                val accountIdHint = req?.accountId?.trim().orEmpty()

                val token = resolveCfToken(null, settingsService, cfOAuth)
                if (token.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, CfR2CustomDomainResponse(ok = false, message = "未检测到 Cloudflare 凭据，请先完成 OAuth 一键授权或粘贴 API Token"))
                    return@post
                }

                // 解析账户
                val accounts = cfService.fetchAccounts(token).data ?: emptyList()
                if (accounts.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, CfR2CustomDomainResponse(ok = false, message = "无法读取 Cloudflare 账户（凭据缺少「帐户设置:Read」）"))
                    return@post
                }
                val account = accountIdHint.takeIf { it.isNotBlank() }?.let { id -> accounts.firstOrNull { it.id == id } } ?: accounts.first()

                // 解析目标桶:显式指定 > 当前绑定 > 最后一次一键配置生成的桶名
                val bucket = bucketHint.takeIf { it.isNotBlank() }
                    ?: settingsService.getRawProperty("r2.bucket", "").takeIf { it.isNotBlank() }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, CfR2CustomDomainResponse(ok = false, message = "未指定桶，且系统尚未绑定任何 R2 存储桶"))
                        return@post
                    }

                // 解析 zone:必须使用域名后缀所属的托管域名（R2 自定义域要求域名托管在 Cloudflare）
                val zones = cfService.fetchZones(token).data ?: emptyList()
                val zone = zoneIdHint.takeIf { it.isNotBlank() }?.let { id -> zones.firstOrNull { it.id == id } }
                    ?: zones.filter { zoneName -> domainRaw == zoneName.name || domainRaw.endsWith(".$zoneName") }
                        .maxByOrNull { it.name.length } // 多级域名取后缀最长（最精确）的那个
                if (zone == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfR2CustomDomainResponse(ok = false, bucket = bucket, message = "域名 $domainRaw 不在已发现的托管域名内。请确认该域名已托管在 Cloudflare 且凭据有「区域:Read」权限；子域名需以其托管父域为后缀。")
                    )
                    return@post
                }

                // 幂等:先查当前状态，已启用则不再重复绑定
                val existing = cfService.getR2CustomDomainStatus(token, account.id, bucket, domainRaw)
                val domainInfo = if (existing.data != null && existing.data.enabled) {
                    existing.data
                } else {
                    val bound = cfService.bindR2CustomDomain(token, account.id, bucket, domainRaw, zone.id)
                    if (bound.data == null) {
                        call.respond(HttpStatusCode.BadRequest, CfR2CustomDomainResponse(ok = false, bucket = bucket, message = "绑定失败：${bound.error}"))
                        return@post
                    }
                    bound.data
                }

                // 已就绪才回写公共基址;未就绪（证书签发中）时提示稍后在界面重试
                if (domainInfo.ready) {
                    settingsService.saveSecrets(mapOf("r2.public_base_url" to "https://${domainInfo.domain}"))
                }

                call.respond(
                    CfR2CustomDomainResponse(
                        ok = true,
                        bucket = bucket,
                        domain = domainInfo,
                        publicBaseUrl = "https://${domainInfo.domain}",
                        message = when {
                            domainInfo.ready -> "自定义域已就绪：https://${domainInfo.domain}（已设为公共访问基址，重启后端后对设备生效）"
                            else -> "绑定已提交，域名归属/证书校验中（ownership=${domainInfo.ownership}, ssl=${domainInfo.ssl}）。通常几十秒内就绪，稍后重新提交本请求即可自动回写配置。"
                        }
                    )
                )
            }

            // ==================== cloudflared 连接器自动安装 ====================

            /**
             * 下载 cloudflared 并在本机注册为系统服务（隧道连接器）。
             *
             * 注意:注册为系统服务需要管理员/root 权限。以普通权限运行时本接口会明确
             * 回报权限不足,并给出可手动执行的命令,不会假装成功。
             */
            post("/cloudflare/install-connector") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfInstallConnectorRequest>() }.getOrNull() ?: CfInstallConnectorRequest()

                // 解析连接器 token:优先显式传入,其次按 tunnelId 去官方 API 取
                var tunnelToken = req.token?.trim().orEmpty()
                var resolvedTunnelId = req.tunnelId?.trim().orEmpty()
                if (tunnelToken.isBlank()) {
                    val cfToken = resolveCfToken(null, settingsService, cfOAuth)
                    if (cfToken.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            CfInstallConnectorResponse(ok = false, message = "未检测到 Cloudflare 凭据，无法获取连接器 token，请先完成 OAuth 授权或粘入连接器 token")
                        )
                        return@post
                    }
                    var accountId = req.accountId?.trim().orEmpty()
                    if (accountId.isBlank()) {
                        accountId = cfService.fetchAccounts(cfToken).data?.firstOrNull()?.id.orEmpty()
                    }
                    if (resolvedTunnelId.isBlank()) {
                        // 未指定就取第一条隧道,便于“授权完直接一把梭”
                        resolvedTunnelId = cfService.fetchTunnels(cfToken, accountId).data?.firstOrNull()?.id.orEmpty()
                    }
                    if (accountId.isBlank() || resolvedTunnelId.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            CfInstallConnectorResponse(ok = false, message = "未能确定隧道：请先在「隧道穿透绑定」里创建/绑定一条隧道，或直接提供连接器 token")
                        )
                        return@post
                    }
                    tunnelToken = cfService.getTunnelToken(cfToken, accountId, resolvedTunnelId).orEmpty()
                    if (tunnelToken.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            CfInstallConnectorResponse(ok = false, message = "未能获取隧道连接器 token（凭据可能缺少「Cloudflare Tunnel:Edit」）")
                        )
                        return@post
                    }
                }

                val outcome = connectorInstaller.install(tunnelToken)
                call.respond(
                    CfInstallConnectorResponse(
                        ok = outcome.ok,
                        steps = outcome.steps.map { CfProvisionStep(it.step, it.ok, it.detail) },
                        manualCommand = outcome.manualCommand,
                        message = outcome.message,
                    )
                )
            }

            // 一键应用 Cloudflare D1 数据库配置到系统
            post("/cloudflare/apply-d1") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfApplyD1Request>() }.getOrNull()
                if (req == null || req.databaseId.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "D1 Database ID 不能为空")
                    )
                    return@post
                }
                val updates = mutableMapOf(
                    "d1.database_id" to req.databaseId.trim(),
                    "d1.database_name" to req.databaseName.trim()
                )
                if (!req.accountId.isNullOrBlank()) {
                    updates["d1.account_id"] = req.accountId.trim()
                }
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "已成功绑定 Cloudflare D1 数据库「${req.databaseName}」"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存 D1 数据库配置失败"))
                }
            }

            // 一键全量自动探测并绑定 Cloudflare 资产 (Tunnel 域名、R2 存储桶、D1 数据库)
            post("/cloudflare/auto-bind") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfAutoBindRequest>() }.getOrNull()
                val token = resolveCfToken(req?.apiToken, settingsService, cfOAuth)
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfAutoBindResponse(ok = false, message = "未检测到 Cloudflare API Token，请先填入 API Token 后再执行自动绑定")
                    )
                    return@post
                }

                val res = cfService.fetchAllResources(token)
                if (!res.valid) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfAutoBindResponse(ok = false, message = res.message ?: "Cloudflare API Token 鉴权或探测失败")
                    )
                    return@post
                }

                val updates = mutableMapOf<String, String>()
                var boundTunnel: String? = null
                var boundR2: String? = null
                var boundD1: String? = null

                // 1. 自动绑定 Tunnel / 域名
                val healthyTunnel = res.tunnels.firstOrNull { it.status == "healthy" } ?: res.tunnels.firstOrNull()
                if (healthyTunnel != null) {
                    val url = if (healthyTunnel.name.contains(".")) {
                        "https://${healthyTunnel.name}"
                    } else if (res.zones.isNotEmpty()) {
                        val zone = res.zones.first()
                        "https://${healthyTunnel.name}.${zone.name}"
                    } else {
                        "https://${healthyTunnel.name}.trycloudflare.com"
                    }
                    updates["server.url"] = url
                    boundTunnel = "${healthyTunnel.name} ($url)"
                }

                // 2. 自动绑定 R2 存储桶
                val r2 = res.r2Buckets.firstOrNull()
                if (r2 != null) {
                    updates["r2.bucket"] = r2.name
                    updates["r2.endpoint"] = r2.s3Endpoint
                    boundR2 = "${r2.name} (${r2.s3Endpoint})"
                }

                // 3. 自动绑定 D1 数据库
                val d1 = res.d1Databases.firstOrNull()
                if (d1 != null) {
                    updates["d1.database_id"] = d1.uuid
                    updates["d1.database_name"] = d1.name
                    if (d1.accountId.isNotBlank()) {
                        updates["d1.account_id"] = d1.accountId
                    }
                    boundD1 = "${d1.name} (${d1.uuid})"
                }

                // 4. 保存 API Token
                if (req?.apiToken != null && !req.apiToken.contains("******") && req.apiToken.isNotBlank()) {
                    updates["cf.api_token"] = req.apiToken.trim()
                }

                if (updates.isEmpty()) {
                    call.respond(CfAutoBindResponse(ok = false, message = "未在 Cloudflare 账户下探测到可绑定的 Tunnel、R2 桶或 D1 数据库"))
                    return@post
                }

                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    val details = listOfNotNull(
                        boundTunnel?.let { "域名隧道: $it" },
                        boundR2?.let { "R2 存储桶: $it" },
                        boundD1?.let { "D1 数据库: $it" }
                    ).joinToString("；")
                    call.respond(
                        CfAutoBindResponse(
                            ok = true,
                            boundTunnel = boundTunnel,
                            boundR2 = boundR2,
                            boundD1 = boundD1,
                            message = if (details.isNotEmpty()) "自动绑定成功！已关联 $details" else "Cloudflare 配置已更新"
                        )
                    )
                } else {
                    call.respond(HttpStatusCode.InternalServerError, CfAutoBindResponse(ok = false, message = "保存自动绑定配置失败"))
                }
            }

            // 一键隧道穿透：将服务绑定到托管域名的指定子域名
            // (创建/选用隧道 → 配置 ingress → 绑定 DNS 路由 → 更新 server.url)
            post("/cloudflare/provision-tunnel") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfProvisionTunnelRequest>() }.getOrNull()
                if (req == null || req.subdomain.isBlank() || (req.zoneId.isNullOrBlank() && req.zoneName.isNullOrBlank())) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfProvisionTunnelResponse(ok = false, message = "请提供子域名前缀及目标域名 (zoneId 或 zoneName)")
                    )
                    return@post
                }
                val subdomain = req.subdomain.trim().lowercase()
                if (!Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$").matches(subdomain)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfProvisionTunnelResponse(ok = false, message = "子域名前缀只能包含小写字母、数字与中划线")
                    )
                    return@post
                }
                val token = resolveCfToken(req.apiToken, settingsService, cfOAuth)
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CfProvisionTunnelResponse(ok = false, message = "未检测到 Cloudflare 凭据，请粘贴 API Token 或先完成 OAuth 一键授权")
                    )
                    return@post
                }

                val steps = mutableListOf<CfProvisionStep>()

                // 1. 解析目标 Zone
                val zonesRes = cfService.fetchZones(token)
                val zones = zonesRes.data ?: emptyList()
                if (zones.isEmpty()) {
                    call.respond(
                        CfProvisionTunnelResponse(
                            ok = false,
                            message = "无法读取托管域名：${zonesRes.error ?: "账户下没有已托管的域名"}"
                        )
                    )
                    return@post
                }
                val zone = zones.firstOrNull { it.id == req.zoneId }
                    ?: zones.firstOrNull { it.name.equals(req.zoneName?.trim(), ignoreCase = true) }
                if (zone == null) {
                    call.respond(
                        CfProvisionTunnelResponse(
                            ok = false,
                            message = "未找到目标域名 ${req.zoneName ?: req.zoneId}，请确认其已托管在该 Cloudflare 账户下"
                        )
                    )
                    return@post
                }
                val hostname = "$subdomain.${zone.name}"

                // 2. 解析账户
                val accountsRes = cfService.fetchAccounts(token)
                val accounts = accountsRes.data ?: emptyList()
                if (accounts.isEmpty()) {
                    call.respond(
                        CfProvisionTunnelResponse(
                            ok = false,
                            hostname = hostname,
                            message = "无法读取 Cloudflare 账户：${accountsRes.error ?: "请确认 Token 含有「帐户设置:Read」权限"}"
                        )
                    )
                    return@post
                }
                // 必须用**域名所属账户**:/accounts 返回的是 Token 可见的全部账户，
                // 直接用 first 时，若目标域名恰好托管在第二个账户下，建隧道 / 绑 DNS 会必然失败。
                val accountId = zone.accountId.takeIf { it.isNotBlank() } ?: accounts.first().id

                // 3. 选用现有隧道或创建新隧道
                var tunnelId = req.tunnelId?.trim().orEmpty()
                var tunnelName = req.tunnelName?.trim().orEmpty()
                if (tunnelId.isBlank()) {
                    val newName = tunnelName.ifBlank { "adbcontrol" }
                    val created = cfService.createTunnel(token, accountId, newName)
                    val newId = created.tunnelId
                    steps.add(CfProvisionStep("create_tunnel", created.ok, created.message))
                    if (!created.ok || newId.isNullOrBlank()) {
                        call.respond(
                            CfProvisionTunnelResponse(
                                ok = false,
                                hostname = hostname,
                                steps = steps,
                                message = "创建隧道失败：${created.message ?: "未知错误"}"
                            )
                        )
                        return@post
                    }
                    tunnelId = newId
                    tunnelName = newName
                } else {
                    if (tunnelName.isBlank()) {
                        tunnelName = cfService.fetchTunnels(token, accountId).data
                            ?.firstOrNull { it.id == tunnelId }?.name ?: tunnelId
                    }
                    steps.add(CfProvisionStep("use_tunnel", true, "使用现有隧道「$tunnelName」"))
                }

                // 4. 配置 ingress（合并语义；本地 config.yml 托管的旧隧道可能不生效，仅提示不中断）
                val localPort = if (req.localPort in 1..65535) req.localPort else 8080
                val mqttWssHost = if (req.enableMqttWss) settingsService.getRawProperty("emqx.host", "").trim() else null
                val ingress = cfService.putTunnelIngress(
                    token, accountId, tunnelId, hostname, localPort,
                    mqttWssHost?.takeIf { it.isNotBlank() }
                )
                steps.add(CfProvisionStep("config_ingress", ingress.ok, ingress.message))
                if (req.enableMqttWss) {
                    if (mqttWssHost.isNullOrBlank()) {
                        steps.add(CfProvisionStep("enable_mqtt_wss", false, "未配置 EMQX Host，已跳过 /mqtt 入口"))
                    } else {
                        steps.add(CfProvisionStep("enable_mqtt_wss", true, "/mqtt 已指向 $mqttWssHost:8084"))
                    }
                }

                // 5. 绑定 DNS 路由（决定性步骤）
                val dns = cfService.bindTunnelDnsRoute(token, accountId, tunnelId, hostname)
                steps.add(CfProvisionStep("bind_dns", dns.ok, dns.message))
                if (!dns.ok) {
                    call.respond(
                        CfProvisionTunnelResponse(
                            ok = false,
                            hostname = hostname,
                            tunnelId = tunnelId,
                            tunnelName = tunnelName,
                            steps = steps,
                            message = "DNS 绑定失败：${dns.message ?: "请确认 Token 含有「区域 DNS:Edit」权限"}"
                        )
                    )
                    return@post
                }

                // 6. 获取连接器 Token，生成云电脑侧一键拉起命令
                val tunnelToken = cfService.getTunnelToken(token, accountId, tunnelId)
                val connectorCommand = tunnelToken?.let { "cloudflared service install $it" }
                    ?: run {
                        steps.add(CfProvisionStep("fetch_token", false, "未能获取连接器 Token（不影响域名绑定）"))
                        null
                    }

                // 7. 更新系统对外服务地址
                val serverUrl = "https://$hostname"
                settingsService.saveSecrets(mapOf("server.url" to serverUrl))

                val warn = if (!ingress.ok) {
                    "；注意：ingress 未生效，该隧道可能由本地 config.yml 托管，请确保本地配置将流量转发到 localhost:$localPort，或改用新建隧道"
                } else ""
                call.respond(
                    CfProvisionTunnelResponse(
                        ok = true,
                        hostname = hostname,
                        serverUrl = serverUrl,
                        tunnelId = tunnelId,
                        tunnelName = tunnelName,
                        connectorCommand = connectorCommand,
                        steps = steps,
                        message = "穿透绑定成功：$serverUrl 已指向本地端口 $localPort$warn"
                    )
                )
            }

            // 新建隧道（仅建隧道，不含入口规则；用于「换用新隧道」或先行创建）
            post("/cloudflare/create-tunnel") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfCreateTunnelRequest>() }.getOrNull()
                if (req == null || req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "请提供隧道名称"))
                    return@post
                }
                val name = req.name.trim()
                if (!Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,60}$").matches(name)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "隧道名称只能包含字母、数字、中划线与下划线")
                    )
                    return@post
                }
                val token = resolveCfToken(req.apiToken, settingsService, cfOAuth)
                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        SettingsOperationResponse(ok = false, message = "未检测到 Cloudflare 凭据，请先在「隧道与域名」中保存 Token 或完成 OAuth 授权")
                    )
                    return@post
                }
                val accounts = cfService.fetchAccounts(token).data ?: emptyList()
                if (accounts.isEmpty()) {
                    call.respond(
                        SettingsOperationResponse(ok = false, message = "无法读取 Cloudflare 账户，请确认 Token 含有「帐户设置:Read」权限")
                    )
                    return@post
                }
                val created = cfService.createTunnel(token, accounts.first().id, name)
                if (created.ok && created.tunnelId != null) {
                    call.respond(
                        CfCreateTunnelResponse(
                            ok = true,
                            tunnelId = created.tunnelId,
                            tunnelName = name,
                            message = "隧道「$name」已创建，可到「添加主机名」把它绑定到域名"
                        )
                    )
                } else {
                    call.respond(
                        SettingsOperationResponse(ok = false, message = created.message ?: "创建隧道失败")
                    )
                }
            }

            // 用户手动批量绑定资源 (域名隧道、R2 存储桶、D1 数据库)
            post("/cloudflare/manual-bind") {
                if (!call.ensureTailscaleOrLocal()) return@post
                val req = runCatching { call.receive<CfManualBindRequest>() }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "请求体格式错误"))
                    return@post
                }
                val updates = mutableMapOf<String, String>()
                if (!req.serverUrl.isNullOrBlank()) updates["server.url"] = req.serverUrl.trim()
                if (!req.r2Endpoint.isNullOrBlank()) updates["r2.endpoint"] = req.r2Endpoint.trim()
                if (!req.r2Bucket.isNullOrBlank()) updates["r2.bucket"] = req.r2Bucket.trim()
                if (!req.r2AccessKey.isNullOrBlank()) updates["r2.access_key"] = req.r2AccessKey.trim()
                if (!req.r2AccessSecret.isNullOrBlank() && !req.r2AccessSecret.contains("******")) {
                    updates["r2.access_secret"] = req.r2AccessSecret.trim()
                }
                if (!req.d1DatabaseId.isNullOrBlank()) updates["d1.database_id"] = req.d1DatabaseId.trim()
                if (!req.d1DatabaseName.isNullOrBlank()) updates["d1.database_name"] = req.d1DatabaseName.trim()
                if (!req.d1AccountId.isNullOrBlank()) updates["d1.account_id"] = req.d1AccountId.trim()

                if (updates.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, SettingsOperationResponse(ok = false, message = "未提交任何需要绑定的有效参数"))
                    return@post
                }
                val ok = settingsService.saveSecrets(updates)
                if (ok) {
                    call.respond(SettingsOperationResponse(ok = true, message = "手动绑定云端资源成功！"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, SettingsOperationResponse(ok = false, message = "保存绑定配置失败"))
                }
            }
        }
    }
}
