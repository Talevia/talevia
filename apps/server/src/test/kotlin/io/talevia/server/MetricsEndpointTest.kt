package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Guards the scrape endpoint contract (prometheus-style `talevia_<name> <value>`)
 * and confirms the EventBusMetricsSink is actually attached when the module
 * starts — a silent disconnection would leave dashboards stuck at zero.
 */
class MetricsEndpointTest {

    @Test fun emptyRegistryReturnsEmptyBody() = testApplication {
        application { serverModule(ServerContainer(rawEnv = emptyMap())) }
        val resp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("", resp.bodyAsText().trim())
    }

    @Test fun sessionCreationBumpsCreatedCounter() = testApplication {
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Trigger a session.created event through the public API.
        client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-1"))
        }.body<CreateSessionResponse>()

        // Sink runs on Dispatchers.Default — await propagation.
        repeat(100) {
            if (container.metrics.snapshot().isNotEmpty()) return@repeat
            kotlinx.coroutines.delay(10)
        }

        val body = client.get("/metrics").bodyAsText()
        assertContains(body, "talevia_session_created 1")
    }

    @Test fun counterNamesUseUnderscoreNotDot() = testApplication {
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.increment("agent.run.failed", by = 3L)
        container.metrics.increment("permission.granted", by = 2L)

        val body = client.get("/metrics").bodyAsText()
        assertContains(body, "talevia_agent_run_failed 3")
        assertContains(body, "talevia_permission_granted 2")
    }
}
