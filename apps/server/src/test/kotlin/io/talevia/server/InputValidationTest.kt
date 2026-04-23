package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the server's validation surface: length caps on text fields, ID format
 * checks, and the 400-vs-500 distinction for IllegalArgumentException.
 */
class InputValidationTest {

    @Test
    fun rejectsOversizedText() = testApplication {
        application { serverModule(ServerContainer(rawEnv = emptyMap())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val created = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-1"))
        }.body<CreateSessionResponse>()

        // 200k chars >> our 128KB text cap; anything near this size in a real
        // prompt is a misuse / attack.
        val bigText = "x".repeat(200_000)
        val resp = client.post("/sessions/${created.sessionId}/parts") {
            contentType(ContentType.Application.Json)
            setBody(AppendTextRequest(text = bigText))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun rejectsIllegalProjectIdCharacters() = testApplication {
        application { serverModule(ServerContainer(rawEnv = emptyMap())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "evil project/../etc"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun rejectsOverlongTitle() = testApplication {
        application { serverModule(ServerContainer(rawEnv = emptyMap())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-1", title = "t".repeat(500)))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun acceptsOrdinaryInput() = testApplication {
        // Sanity check: validation doesn't false-positive on realistic requests.
        application { serverModule(ServerContainer(rawEnv = emptyMap())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "proj_01", title = "My project"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
