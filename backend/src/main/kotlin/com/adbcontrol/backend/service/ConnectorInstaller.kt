package com.adbcontrol.backend.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/** 安装过程中的单步结果(向前端逐步透出,失败时能看清卡在哪一步)。 */
data class ConnectorInstallStep(val step: String, val ok: Boolean, val detail: String? = null)

/** 安装结果汇总。 */
data class ConnectorInstallOutcome(
    val ok: Boolean,
    val steps: List<ConnectorInstallStep>,
    val manualCommand: String?,
    val message: String,
)

/**
 * cloudflared 连接器自动安装器。
 *
 * 背景:隧道本身(建隧道 / ingress / DNS)可以纯 API 完成,但**连接器必须跑在主机上**,
 * Cloudflare 没有任何接口能代劳。此前只能把 `cloudflared service install <token>`
 * 这条命令丢给用户自己去云电脑上以管理员身份执行。
 *
 * 本类把它自动化:下载对应平台二进制 → 调用 `service install <token>` 注册为系统服务
 * (Windows 上会注册并启动 Windows 服务,Linux 上注册 systemd 服务)。
 *
 * 重要限制(不是实现缺陷,是操作系统约束):
 * - Windows 的 `service install` 与 Linux 的 `service install` **都需要管理员/root 权限**。
 *   后端若以普通用户运行,这一步必然失败 —— 此时会明确回报原因并给出可手动执行的命令,
 *   绝不假装成功。
 * - macOS 的官方分发是 .tgz 压缩包,这里不自动处理(返回不支持)。
 */
class ConnectorInstaller : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val httpClient = HttpClient(OkHttp) {
        // 二进制约 40MB,弱网下要给足时间
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60 * 1000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 10 * 60 * 1000
        }
    }

    /** 安装目录:与 secrets 同级,便于云电脑上集中管理。 */
    private val installDir: File = run {
        val isWindows = isWindows()
        val path = if (isWindows) "C:\\adbcontrol\\cloudflared" else "data/cloudflared"
        File(path).apply { mkdirs() }
    }

    val binaryFile: File
        get() = File(installDir, if (isWindows()) "cloudflared.exe" else "cloudflared")

    /** 供前端展示的手动命令(token 由调用方拼,这里只给可复制的兜底文本)。 */
    fun manualCommand(token: String): String =
        "${resolvedBinary().absolutePath} service install $token"

    /** 实际会使用的二进制:优先本地已有(见 [findLocalBinary]),否则回落到安装目录。 */
    private fun resolvedBinary(): File {
        findLocalBinary()?.let { return it }
        return binaryFile
    }

    /**
     * 查找本机已存在的 cloudflared，命中就不下载。
     *
     * 为什么需要：国内网络直连 GitHub Releases 基本会超时，而 cloudflared 往往
     * 已经由运维手工放在应用目录里（实测 C:\adbcontrol\cloudflared.exe 就有一份
     * 52MB 的 2026.9.3）。原先只检查 installDir 下的固定文件名，那个目录是空的，
     * 于是明明本地有可用二进制，却仍去下载并超时失败。
     *
     * 命中判据不止体积：还要能真的执行 `--version`。否则一个被截断的 .exe 会被
     * 当成"已安装"，等到 service install 阶段才以更费解的方式失败。
     */
    private fun findLocalBinary(): File? {
        val exeName = if (isWindows()) "cloudflared.exe" else "cloudflared"
        val appHome = installDir.parentFile
        // 注意:所有 add 都必须留在 buildList 的 lambda 内。runCatching 的接收者是
        // Result,一旦在它的 lambda 里调用 add 就会因接收者遮蔽而编译失败。
        val pathDirs = runCatching {
            (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val subDirs = if (appHome != null) {
            runCatching { appHome.listFiles() }.getOrNull()?.filter { it.isDirectory }.orEmpty()
        } else {
            emptyList()
        }

        val candidates = buildList {
            // 1) 安装目录(本类自己下载时落地的地方)
            add(File(installDir, exeName))
            if (appHome != null) {
                // 2) 应用目录根部 —— 实测运维常把二进制直接放这儿
                add(File(appHome, exeName))
                // 3) 应用目录下一层(如 C:\adbcontrol\tools\cloudflared.exe)
                subDirs.forEach { dir -> add(File(dir, exeName)) }
            }
            // 4) PATH 上已安装的(winget / scoop / 手工加 PATH)
            pathDirs.forEach { add(File(it, exeName)) }
        }

        var firstOversized: File? = null
        for (c in candidates.distinctBy { it.absolutePath.lowercase() }) {
            if (!c.isFile) continue
            if (c.length() <= MIN_BINARY_BYTES) continue
            if (firstOversized == null) firstOversized = c
            if (looksRunnable(c)) return c
        }
        // 体积够但跑不起来 —— 记下来当候选，至少比"什么都没有"强，但要在日志里点出来
        firstOversized?.let { logger.warn("found cloudflared at {} but --version failed, ignoring", it) }
        return null
    }

    /** 执行 `--version` 验证二进制真的可用(退出码 0 且输出含版本字样)。 */
    private fun looksRunnable(binary: File): Boolean = runCatching {
        val p = ProcessBuilder(binary.absolutePath, "--version")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 &&
            out.contains("cloudflared", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * 下载(如缺失)并注册连接器服务。
     *
     * @param tunnelToken Cloudflare 隧道连接器 token(`cloudflared service install` 的参数)。
     */
    suspend fun install(tunnelToken: String): ConnectorInstallOutcome {
        val steps = mutableListOf<ConnectorInstallStep>()
        val manual = manualCommand(tunnelToken)

        if (tunnelToken.isBlank()) {
            return ConnectorInstallOutcome(false, steps, null, "缺少隧道连接器 token，无法安装")
        }

        // ---- 1. 准备二进制 ----
        val local = findLocalBinary()
        val binary = local ?: binaryFile
        if (local != null) {
            val via = if (local.absolutePath.equals(binaryFile.absolutePath, ignoreCase = true)) "安装目录" else "本机已有"
            steps.add(ConnectorInstallStep("download", true, "$via：${local.absolutePath}（跳过下载）"))
        } else {
            val urls = downloadUrls()
            if (urls.isEmpty()) {
                steps.add(ConnectorInstallStep("download", false, "当前平台不支持自动下载，请手动安装 cloudflared"))
                return ConnectorInstallOutcome(false, steps, manual, "当前平台不支持自动安装 cloudflared，请手动执行下面的命令")
            }
            var lastErr = "所有下载源均失败"
            var ok = false
            var usedUrl = ""
            for (url in urls) {
                val dl = runCatching { download(url, binary) }
                if (dl.getOrDefault(false)) {
                    ok = true; usedUrl = url; break
                }
                lastErr = dl.exceptionOrNull()?.message ?: lastErr
                logger.warn("cloudflared download failed from {}: {}", url, lastErr)
            }
            steps.add(
                ConnectorInstallStep(
                    "download", ok,
                    if (ok) "$usedUrl → ${binary.absolutePath}（${binary.length() / 1024 / 1024}MB）" else lastErr
                )
            )
            if (!ok) {
                return ConnectorInstallOutcome(false, steps, manual, "下载 cloudflared 失败，请在云电脑上手动执行下面的命令")
            }
            if (!isWindows()) runCatching { binary.setExecutable(true) }
        }

        // ---- 2. 注册并启动系统服务 ----
        val run = runCatching { runInstall(binary, tunnelToken) }
        val exitCode = run.getOrNull()?.first
        val output = run.getOrNull()?.second.orEmpty()
        val threw = run.exceptionOrNull()

        if (threw != null) {
            steps.add(ConnectorInstallStep("install", false, "执行失败: ${threw.message}"))
            return ConnectorInstallOutcome(false, steps, manual, "启动 cloudflared 安装程序失败，请以管理员身份手动执行下面的命令")
        }

        val installOk = exitCode == 0
        steps.add(
            ConnectorInstallStep(
                "install", installOk,
                "exit=$exitCode${if (output.isBlank()) "" else " · ${output.take(400)}"}",
            )
        )
        if (!installOk) {
            // 最常见原因就是权限不足,直接点出来,省得用户对着 exit code 猜
            val hint = if (looksLikePermissionDenied(output)) {
                "权限不足：该操作需要管理员/root，请以管理员身份运行后端，或手动执行下面的命令"
            } else {
                "安装未成功，请查看输出或手动执行下面的命令"
            }
            return ConnectorInstallOutcome(false, steps, manual, hint)
        }

        logger.info("cloudflared connector installed via {}", binary.absolutePath)
        return ConnectorInstallOutcome(
            true, steps, null,
            "连接器已注册为系统服务并启动。可在 Cloudflare 控制台确认隧道状态变为 healthy。",
        )
    }

    /** 下载二进制。返回是否成功；体积过小视为下载到了错误页/被拦截,判失败。 */
    private suspend fun download(url: String, target: File): Boolean {
        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("HTTP ${response.status.value}")
        }
        val bytes = response.readBytes()
        // 拦截页 / 404 HTML 通常只有几 KB;cloudflared 二进制远大于此阈值
        if (bytes.size < MIN_BINARY_BYTES) {
            throw IllegalStateException("下载内容异常（仅 ${bytes.size} 字节，疑似被网络拦截）")
        }
        // 先写临时文件再原子改名,避免半截文件被下一次启动误判为"已存在"
        val tmp = File(target.parentFile, "${target.name}.part")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return true
    }

    /** 执行 `cloudflared service install <token>`,返回 (exitCode, 合并输出)。 */
    private fun runInstall(binary: File, token: String): Pair<Int, String> {
        // 参数用列表传递(不经 shell),token 即使含特殊字符也不会被解释
        val process = ProcessBuilder(binary.absolutePath, "service", "install", token)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return -1 to "安装超时（> ${INSTALL_TIMEOUT_SECONDS}s）：$output"
        }
        return process.exitValue() to output
    }

    private fun looksLikePermissionDenied(output: String): Boolean {
        val lower = output.lowercase()
        return listOf(
            "access is denied", "拒绝访问", "administrator", "elevat",
            "permission denied", "must be run as root", "root", "privilege",
        ).any { lower.contains(it) }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    /**
     * 下载源列表(按优先级)。
     *
     * 前面几个是 GitHub 加速代理:国内机器直连 github.com/releases 普遍超时,
     * 而 update-poll.ps1 拉后端包走的正是这几个代理(已实测可用),所以复用同一组,
     * 避免"后端包能拉、连接器却拉不下来"的割裂体验。官方直连放最后兜底。
     */
    private fun downloadUrls(): List<String> {
        val asset = releaseAsset() ?: return emptyList()
        return buildList {
            GITHUB_PROXIES.forEach { add(it.trimEnd('/') + "/" + RELEASE_PATH + "/" + asset) }
            add("$RELEASE_BASE/$asset")
        }
    }

    /** 当前平台对应的官方发布资产名；不支持的平台返回 null。 */
    private fun releaseAsset(): String? {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            os.contains("win") -> if (arm) "cloudflared-windows-arm64.exe" else "cloudflared-windows-amd64.exe"
            os.contains("linux") -> if (arm) "cloudflared-linux-arm64" else "cloudflared-linux-amd64"
            // darwin 官方分发为 .tgz,需要解压,这里不处理
            else -> null
        }
    }

    override fun close() {
        httpClient.close()
    }

    private companion object {
        const val RELEASE_BASE = "https://github.com/cloudflare/cloudflared/releases/latest/download"
        const val RELEASE_PATH = "cloudflare/cloudflared/releases/latest/download"

        /** 与 deploy/update-poll.ps1 同一组加速代理，已实测从本项目云电脑可达。 */
        val GITHUB_PROXIES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
        )

        /** cloudflared 二进制约 40MB;低于此值必然是错误页或被拦截。 */
        const val MIN_BINARY_BYTES = 1L * 1024 * 1024

        const val INSTALL_TIMEOUT_SECONDS = 120L
    }
}
