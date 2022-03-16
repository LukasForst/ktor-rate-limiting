package dev.forst.ktor.ratelimiting


import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TestMinimalExampleApp {
    @Test
    fun `test minimal example app works as expected`() {
        withTestApplication(Application::minimalExample) {
            // 10 times our request should pass
            repeat(10) {
                handleRequest(HttpMethod.Get, "/").apply {
                    assertEquals(HttpStatusCode.OK, response.status())
                    assertEquals("Hello localhost", response.content)
                }
            }
            // and then it should be blocked
            handleRequest(HttpMethod.Get, "/").apply {
                assertEquals(HttpStatusCode.TooManyRequests, response.status())
            }
            // but excluded route should be still available
            handleRequest(HttpMethod.Get, "/excluded").apply {
                assertEquals(HttpStatusCode.OK, response.status())
                assertEquals("Hello localhost", response.content)
            }
        }
    }
}

