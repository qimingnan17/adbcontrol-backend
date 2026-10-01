package com.adbcontrol.backend.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.Base64

/**
 * 文件中转的设备侧鉴权与文件名清洗回归测试。
 *
 * 这里覆盖的是**安全边界**,不是业务分支:
 * - 签名必须同时绑定 deviceId / direction / expiresAt,任一被改都验签失败
 *   (否则一次上行票据可被复用去拉取别人的下行文件);
 * - 过期票据必须被拒(否则票据等价于长期凭证);
 * - 文件名清洗必须挡住目录穿越(手机端完全可控该字段)。
 */
class DeviceFileStoreTest {

    private val sessionKey: String =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })

    private fun futureExpiry(): Long = System.currentTimeMillis() + 60_000

    @Test
    fun `signed ticket verifies for the same device direction and expiry`() {
        val exp = futureExpiry()
        val sig = DeviceFileStore.signForDevice("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sessionKey)
        assertTrue(
            DeviceFileStore.verifyDeviceSignature("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sig, sessionKey)
        )
    }

    @Test
    fun `direction is part of the signature so an upload ticket cannot fetch a downlink file`() {
        val exp = futureExpiry()
        val sig = DeviceFileStore.signForDevice("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sessionKey)
        assertFalse(
            DeviceFileStore.verifyDeviceSignature("dev_abc", DeviceFileStore.DIRECTION_DOWN, exp, sig, sessionKey)
        )
    }

    @Test
    fun `deviceId is part of the signature so tickets are not transferable between devices`() {
        val exp = futureExpiry()
        val sig = DeviceFileStore.signForDevice("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sessionKey)
        assertFalse(
            DeviceFileStore.verifyDeviceSignature("dev_xyz", DeviceFileStore.DIRECTION_UP, exp, sig, sessionKey)
        )
    }

    @Test
    fun `expiry is part of the signature so extending the deadline invalidates it`() {
        val exp = futureExpiry()
        val sig = DeviceFileStore.signForDevice("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sessionKey)
        assertFalse(
            DeviceFileStore.verifyDeviceSignature("dev_abc", DeviceFileStore.DIRECTION_UP, exp + 3_600_000, sig, sessionKey)
        )
    }

    @Test
    fun `stale expiry is rejected even when the signature matches`() {
        val staleExp = System.currentTimeMillis() - 10 * 60 * 1000
        val sig = DeviceFileStore.signForDevice("dev_abc", DeviceFileStore.DIRECTION_DOWN, staleExp, sessionKey)
        assertFalse(
            DeviceFileStore.verifyDeviceSignature("dev_abc", DeviceFileStore.DIRECTION_DOWN, staleExp, sig, sessionKey)
        )
    }

    @Test
    fun `signature from another sessionKey is rejected`() {
        val exp = futureExpiry()
        val sig = DeviceFileStore.signForDevice("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sessionKey)
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 99).toByte() })
        assertFalse(
            DeviceFileStore.verifyDeviceSignature("dev_abc", DeviceFileStore.DIRECTION_UP, exp, sig, otherKey)
        )
    }

    @Test
    fun `blank signature is rejected`() {
        assertFalse(
            DeviceFileStore.verifyDeviceSignature(
                "dev_abc", DeviceFileStore.DIRECTION_UP, futureExpiry(), "", sessionKey,
            )
        )
    }

    @Test
    fun `signing data format is stable`() {
        // 该字符串是与被控端共享的约定,改动会直接让手机端全部鉴权失败,因此锁死断言
        assertEquals("file:dev_abc:up:1700000000000", DeviceFileStore.signingData("dev_abc", "up", 1700000000000))
    }

    @Test
    fun `file name cannot escape its directory`() {
        // 关键不变量:清洗后不再含任何路径分隔符
        listOf(
            "../../etc/passwd",
            "/etc/passwd",
            "..\\..\\windows\\system32\\x",
            ".hidden\u0000",
            "a/b\\c",
        ).forEach { raw ->
            val safe = DeviceFileStore.sanitizeFileName(raw)
            assertFalse(safe.contains('/'), "still contains '/': $safe")
            assertFalse(safe.contains('\\'), "still contains '\\': $safe")
            assertFalse(safe.startsWith('.'), "starts with '.': $safe")
        }
    }

    @Test
    fun `file name keeps a readable remainder after sanitizing`() {
        assertEquals("_etc_passwd", DeviceFileStore.sanitizeFileName("/etc/passwd"))
        assertEquals("_etc_passwd", DeviceFileStore.sanitizeFileName("../etc/passwd"))
        assertEquals("_.._windows_system32_x", DeviceFileStore.sanitizeFileName("..\\..\\windows\\system32\\x"))
        assertEquals("hidden", DeviceFileStore.sanitizeFileName(".hidden\u0000"))
        assertEquals("shot.png", DeviceFileStore.sanitizeFileName("shot.png"))
    }

    @Test
    fun `empty file name falls back to a safe default`() {
        assertEquals("file", DeviceFileStore.sanitizeFileName(""))
        assertEquals("file", DeviceFileStore.sanitizeFileName(null))
        assertEquals("file", DeviceFileStore.sanitizeFileName("..."))
    }

    @Test
    fun `device segment sanitizing removes separators`() {
        assertEquals("dev_.._x", DeviceFileStore.sanitizeSegment("dev/../x"))
        assertEquals("unknown", DeviceFileStore.sanitizeSegment(""))
        assertEquals("dev_abc", DeviceFileStore.sanitizeSegment("dev_abc"))
    }

    @Test
    fun `extension is kept only when it is a plausible alphanumeric suffix`() {
        assertEquals(".png", DeviceFileStore.extensionOf("shot.png"))
        assertEquals("", DeviceFileStore.extensionOf("noext"))
        assertEquals("", DeviceFileStore.extensionOf("trailing."))
        assertEquals("", DeviceFileStore.extensionOf(".hidden"))
        assertEquals("", DeviceFileStore.extensionOf("weird.p n g"))
        assertEquals("", DeviceFileStore.extensionOf("file.superlongext"))
    }

    @Test
    fun `sha256 hex matches the known digest of empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DeviceFileStore.sha256Hex(ByteArray(0)),
        )
    }
}
