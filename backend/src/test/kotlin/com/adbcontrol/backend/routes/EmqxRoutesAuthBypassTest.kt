package com.adbcontrol.backend.routes

import com.adbcontrol.backend.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归测试:/emqx/ 系列路由必须受会话鉴权保护。
 *
 * 历史缺陷:EmqxRoutes 等路由扩展声明为 `fun Routing.xxx`,在
 * `routing { authenticate("auth-session") { emqxRoutes(...) } }` 中调用时,
 * Kotlin 会把调用解析到外层 `routing {}` 的 Routing 接收者,路由实际注册在
 * authenticate 块之外 —— 鉴权静默失效,未登录可直接枚举在线设备(实测踩坑)。
 * 修复后扩展接收者改为 Route,必须命中 401。
 */
class EmqxRoutesAuthBypassTest {

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module() }
        block()
    }

    @Test
    fun `emqx devices requires auth`() = withApp {
        val resp = client.get("/emqx/devices")
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "未登录访问 /emqx/devices 必须 401(鉴权被绕过则此断言失败)")
    }

    @Test
    fun `emqx subscriptions requires auth`() = withApp {
        val resp = client.get("/emqx/subscriptions?clientId=x")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `api devices still requires auth`() = withApp {
        val resp = client.get("/api/devices")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `health stays public`() = withApp {
        val resp = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(true, resp.bodyAsText().contains("\"status\":\"ok\""))
    }
}
