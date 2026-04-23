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
import io.talevia.core.PartId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.FinishReason
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSmokeTest {

    private class RecordingProvider(override val id: String) : LlmProvider {
        val requests = CopyOnWriteArrayList<LlmRequest>()

        override suspend fun listModels(): List<ModelInfo> = emptyList()

        override fun stream(request: LlmRequest) = flow {
            requests += request
            val partId = PartId("$id-part")
            emit(LlmEvent.StepStart)
            emit(LlmEvent.TextStart(partId))
            emit(LlmEvent.TextDelta(partId, "handled by $id"))
            emit(LlmEvent.TextEnd(partId, "handled by $id"))
            emit(LlmEvent.StepFinish(FinishReason.STOP, TokenUsage.ZERO))
        }
    }

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
            serverModule(ServerContainer(rawEnv = mapOf("TALEVIA_SERVER_TOKEN" to "secret-123")))
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
        application { serverModule(ServerContainer(rawEnv = emptyMap())) }
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

    @Test
    fun submitMessageUsesRequestedProviderInsteadOfDefault() = testApplication {
        val anthropic = RecordingProvider("anthropic")
        val openai = RecordingProvider("openai")
        val providers = ProviderRegistry.Builder()
            .add(anthropic)
            .add(openai)
            .build()
        val container = ServerContainer(rawEnv = emptyMap(), providerRegistryOverride = providers)
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sessionId = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-provider"))
        }.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            setBody(SubmitMessageRequest(text = "hello", providerId = "openai", modelId = "gpt-4o"))
        }
        assertEquals(HttpStatusCode.Accepted, resp.status)

        // The agent fires a request through the requested provider; the SessionTitler
        // (also wired to the same provider in ServerContainer) may fire a second one
        // shortly after for the placeholder-titled session. Both are valid traffic to
        // openai — the test's job is to prove anthropic (the default) saw nothing and
        // openai's first request matches the requested model.
        for (i in 0 until 100) {
            if (openai.requests.isNotEmpty()) break
            delay(10)
        }

        assertEquals(0, anthropic.requests.size, "default provider should not receive the request")
        assertTrue(openai.requests.size >= 1, "openai should have received at least one request")
        assertEquals("openai", openai.requests.first().model.providerId)
    }

    @Test
    fun submitMessageRejectsUnknownProviderWhenOthersExist() = testApplication {
        val anthropic = RecordingProvider("anthropic")
        val providers = ProviderRegistry.Builder().add(anthropic).build()
        val container = ServerContainer(rawEnv = emptyMap(), providerRegistryOverride = providers)
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sessionId = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-provider"))
        }.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            setBody(SubmitMessageRequest(text = "hello", providerId = "openai"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
