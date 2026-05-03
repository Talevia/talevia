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
import io.talevia.core.PartId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.FinishReason
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP-level test for `POST /sessions/{id}/messages` with an
 * **unknown providerId** —
 * `apps/server/src/main/kotlin/io/talevia/server/SessionRoutes.kt:111-114`.
 * Cycle 230 audit: ServerSmokeTest covers two of /messages's three
 * branches (501 no-provider, 202 valid provider) but never the 400
 * "Provider 'X' is not configured." branch when `body.providerId`
 * names a provider that isn't registered.
 *
 * Same audit-pattern fallback as cycles 207-229.
 *
 * The branch arithmetic for /messages:
 *   1. body.providerId == null AND container.providers.default == null
 *      → 501 (covered by smoke `submitMessageFailsWithoutProviderKeys`).
 *   2. body.providerId resolved (either explicit or default) AND
 *      container.agentFor(providerId) == null → **400 here**.
 *   3. agent resolves → 202 Accepted (covered by smoke
 *      `submitMessageUsesRequestedProviderInsteadOfDefault`).
 *
 * Two correctness contracts pinned:
 *
 *  1. **Unknown explicit providerId → 400 Bad Request** (NOT 404, NOT
 *     501, NOT 202). Drift to 404 would imply the session doesn't
 *     exist (it does); drift to 501 would conflate "no provider
 *     configured" with "wrong provider name typed". Drift to 202
 *     would silently start an agent run with a non-existent provider.
 *
 *  2. **Error message names the missing providerId.** Body shape:
 *     `{"error": "Provider 'X' is not configured."}` — the `'X'`
 *     placeholder MUST contain the caller's verbatim providerId
 *     string so a typo's diagnosis is "did I spell anthropic
 *     wrong?" rather than a generic "config missing" hint.
 */
class SubmitMessageUnknownProviderTest {

    private fun isolatedEnv(): Map<String, String> {
        val tmpDir = java.nio.file.Files.createTempDirectory("submit-message-unknown-provider-").toFile()
        return mapOf(
            "TALEVIA_PROJECTS_HOME" to tmpDir.resolve("projects").absolutePath,
            "TALEVIA_RECENTS_PATH" to tmpDir.resolve("recents.json").absolutePath,
            "TALEVIA_DB_PATH" to ":memory:",
        )
    }

    @Test fun submitMessageWithUnknownProviderIdReturns400() = testApplication {
        // Marquee pin: explicit body.providerId names a provider that
        // isn't in the registry. Even though SOME provider IS
        // configured (so the 501 gate doesn't fire), agentFor("ghost")
        // returns null → 400 with the "Provider 'X' is not
        // configured." message.
        val recordingProvider = RecordingProvider("anthropic")
        val providers = ProviderRegistry.Builder().add(recordingProvider).build()
        val container = ServerContainer(
            rawEnv = isolatedEnv(),
            providerRegistryOverride = providers,
        )
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sid = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-unknown-provider"))
        }.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sid/messages") {
            contentType(ContentType.Application.Json)
            setBody(SubmitMessageRequest(text = "hello", providerId = "ghost-provider"))
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "/messages with unknown providerId must 400 (NOT 501, NOT 404, NOT 202)",
        )
        val body = resp.body<Map<String, String>>()
        val errorMsg = body["error"] ?: ""
        // Pin: the providerId the caller passed is named verbatim so a
        // typo's diagnosis is "did I spell `ghost-provider` wrong?"
        assertTrue(
            "ghost-provider" in errorMsg,
            "error message must cite the missing providerId verbatim; got: $errorMsg",
        )
        // Pin: canonical message format.
        assertEquals(
            "Provider 'ghost-provider' is not configured.",
            errorMsg,
            "canonical 400 error message format pinned",
        )
    }

    @Test fun submitMessageWithUnknownProviderIdEvenWhenDefaultExists() = testApplication {
        // Pin: explicit body.providerId takes precedence over the
        // default (per `body.providerId ?: container.providers.default
        // ?.id`). With a non-null default AND an unknown explicit
        // providerId, the route still 400s on the explicit one
        // instead of silently falling through to the default. Drift
        // to "fall back to default on unknown explicit" would surprise
        // a caller who wanted to test a specific provider.
        val anthropic = RecordingProvider("anthropic")
        val openai = RecordingProvider("openai")
        val providers = ProviderRegistry.Builder()
            .add(anthropic) // first → becomes the default
            .add(openai)
            .build()
        assertNotNull(providers.default, "test setup: registry has a default provider")
        val container = ServerContainer(
            rawEnv = isolatedEnv(),
            providerRegistryOverride = providers,
        )
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sid = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-unknown-explicit"))
        }.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sid/messages") {
            contentType(ContentType.Application.Json)
            setBody(SubmitMessageRequest(text = "hi", providerId = "deepseek"))
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "explicit unknown providerId must 400 even when a default IS configured (no silent fallback)",
        )
        val body = resp.body<Map<String, String>>()
        assertEquals(
            "Provider 'deepseek' is not configured.",
            body["error"],
        )

        // Cross-check: the default provider was NOT silently used.
        // Neither anthropic nor openai should have received a request.
        // (No SSE wait / sleep — the route doesn't launch the agent on
        // the 400 branch.)
        assertTrue(
            anthropic.requests.isEmpty(),
            "default provider must NOT receive a request when the explicit providerId is unknown",
        )
        assertTrue(
            openai.requests.isEmpty(),
            "no provider should receive a request on the 400 path",
        )
    }

    @Test fun submitMessageWithEmptyExplicitProviderIdFallsBackToDefault() = testApplication {
        // Pin: per `body.providerId ?: ...`, only `null` triggers the
        // fallback. An empty string `""` is NOT null, so it would
        // reach `agentFor("")` → 400. Document this is the existing
        // contract — drift to "treat empty as null and fall back"
        // would surprise callers who explicitly set "" for testing.
        val recordingProvider = RecordingProvider("anthropic")
        val providers = ProviderRegistry.Builder().add(recordingProvider).build()
        val container = ServerContainer(
            rawEnv = isolatedEnv(),
            providerRegistryOverride = providers,
        )
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sid = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-empty-providerId"))
        }.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sid/messages") {
            contentType(ContentType.Application.Json)
            setBody(SubmitMessageRequest(text = "hi", providerId = ""))
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "empty explicit providerId is NOT treated as null (no fallback to default)",
        )
        val body = resp.body<Map<String, String>>()
        assertEquals(
            "Provider '' is not configured.",
            body["error"],
            "empty providerId surfaces in the error message verbatim (caller can see what they sent)",
        )
    }

    private class RecordingProvider(override val id: String) : LlmProvider {
        val requests = java.util.concurrent.CopyOnWriteArrayList<LlmRequest>()

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
}
