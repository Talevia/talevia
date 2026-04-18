package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
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
    fun listingSessionsRespondsOk() = testApplication {
        application { serverModule(ServerContainer()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.get("/sessions")
        assertEquals(HttpStatusCode.OK, resp.status)
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
