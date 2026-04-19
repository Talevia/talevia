package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSmokeTest {

    @Test
    fun createSessionThenPostText() = testApplication {
        application {
            serverModule(ServerContainer())
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val createResp: HttpResponse = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-1", title = "smoke"))
        }
        assertEquals(HttpStatusCode.OK, createResp.status)
        val sessionId = createResp.body<CreateSessionResponse>().sessionId
        assertTrue(sessionId.isNotBlank(), "server should return a non-empty session id")

        val partResp: HttpResponse = client.post("/sessions/$sessionId/parts") {
            contentType(ContentType.Application.Json)
            setBody(AppendTextRequest(text = "hello server"))
        }
        assertEquals(HttpStatusCode.OK, partResp.status)
        val messageId = partResp.body<AppendTextResponse>().messageId
        assertTrue(messageId.isNotBlank(), "server should return a non-empty message id")
    }

    @Test
    fun listingSessionsReturnsCreatedSessions() = testApplication {
        application { serverModule(ServerContainer()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-1", title = "alpha"))
        }
        client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-2", title = "beta"))
        }

        val all = client.get("/sessions").body<List<SessionSummary>>()
        assertEquals(2, all.size)

        val filtered = client.get("/sessions?projectId=p-1").body<List<SessionSummary>>()
        assertEquals(1, filtered.size)
        assertEquals("alpha", filtered.single().title)
    }

    @Test
    fun authTokenRequiredWhenEnvSet() = testApplication {
        application {
            serverModule(ServerContainer(env = mapOf("TALEVIA_SERVER_TOKEN" to "secret-123")))
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val unauthed = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-auth"))
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthed.status)

        val authed = client.post("/sessions") {
            header(HttpHeaders.Authorization, "Bearer secret-123")
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-auth"))
        }
        assertEquals(HttpStatusCode.OK, authed.status)

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status, "/health must bypass auth")
    }

    @Test
    fun submitMessageFailsWithoutProviderKeys() = testApplication {
        // env = empty → ProviderRegistry.default is null → 501 Not Implemented
        application { serverModule(ServerContainer(env = emptyMap())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResp = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-2"))
        }
        val sessionId = createResp.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            setBody(SubmitMessageRequest(text = "hello"))
        }
        assertEquals(HttpStatusCode.NotImplemented, resp.status)
    }
}
