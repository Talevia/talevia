package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertNotEquals

/**
 * HTTP-level tests for `POST /sessions/{id}/cancel` —
 * `apps/server/src/main/kotlin/io/talevia/server/SessionRoutes.kt:167`.
 * Cycle 229 audit: 0 direct test refs (smoke test never calls
 * /cancel). Three branches in 14 LOC, each with a load-bearing
 * status-code contract.
 *
 * Same audit-pattern fallback as cycles 207-228.
 *
 * Two correctness contracts pinned:
 *
 *  1. **No-provider gate → 501 Not Implemented.** When
 *     `container.providers.default` is null (no `ANTHROPIC_API_KEY` /
 *     `OPENAI_API_KEY` set, no override), POST /cancel MUST 501 with
 *     a body citing the missing env vars. Drift to "200 silent no-op"
 *     would let clients believe a non-existent run was cancelled;
 *     drift to "500 Internal" would mask configuration errors as
 *     server bugs. Mirrors `/messages` route's same gate.
 *
 *  2. **No-in-flight-run → 409 Conflict.** When a provider IS
 *     configured but `container.cancel(sid)` returns false (no run to
 *     cancel for this session), POST /cancel MUST 409 with body
 *     `{"cancelled": false, "reason": "no in-flight run"}`. Drift to
 *     "200 with cancelled=false" would conflate "successfully
 *     cancelled" with "nothing to cancel". Drift to "404" would imply
 *     the session doesn't exist (the session ID may well be
 *     well-formed and persisted; only the run is absent).
 *
 * Plus shape pins:
 *   - 501 body has `error` field naming both env vars.
 *   - 409 body has `cancelled: false` AND `reason`.
 *   - Cancel against an unknown session id (never created) — same
 *     409 path: `container.cancel` returns false because there's no
 *     in-flight run for any session id, real or imagined. Pin: the
 *     route does NOT 404 on unknown id; the cancel-success boolean
 *     is the discriminator.
 *
 * Successful-cancel branch (200 with `cancelled: true`) requires a
 * race-window into an in-flight agent run, which is timing-fragile
 * to test deterministically here. That branch is exercised by the
 * lower-level `core.agent.Agent.cancel(...)` test
 * (`AgentCancellationTest`). What this file pins is the route's
 * branch-arithmetic — drift in any of the 3 status codes / body
 * shapes is caught here.
 */
class SessionCancelRouteTest {

    private fun isolatedEnv(): Map<String, String> {
        val tmpDir = java.nio.file.Files.createTempDirectory("session-cancel-route-test-").toFile()
        return mapOf(
            "TALEVIA_PROJECTS_HOME" to tmpDir.resolve("projects").absolutePath,
            "TALEVIA_RECENTS_PATH" to tmpDir.resolve("recents.json").absolutePath,
            "TALEVIA_DB_PATH" to ":memory:",
        )
    }

    @Test fun cancelWithNoProviderConfiguredReturns501() = testApplication {
        // Pin: empty env → no default provider → /cancel must 501,
        // matching /messages's same gate. Drift to 200 / 500 would
        // either mask the config error or silently succeed.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sid = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-cancel"))
        }.body<CreateSessionResponse>().sessionId

        val resp = client.post("/sessions/$sid/cancel")
        assertEquals(
            HttpStatusCode.NotImplemented,
            resp.status,
            "/cancel without configured provider must 501 (NOT 200, NOT 500)",
        )
        val body = resp.body<Map<String, String>>()
        val errorMsg = body["error"] ?: ""
        assertEquals(
            "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY).",
            errorMsg,
            "501 body must surface the canonical 'no provider' error message",
        )
    }

    @Test fun cancelWithNoInFlightRunReturns409Conflict() = testApplication {
        // Marquee no-in-flight pin: provider IS configured but no run
        // is active → 409 with `{"cancelled": false, "reason": "no in
        // -flight run"}`. Drift to 200 would conflate "successfully
        // cancelled" with "nothing to cancel"; drift to 404 would
        // imply the session doesn't exist (it does — only the run is
        // absent).
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
            setBody(CreateSessionRequest(projectId = "p-cancel-2"))
        }.body<CreateSessionResponse>().sessionId

        // No /messages call → no in-flight run → cancel must return false.
        val resp = client.post("/sessions/$sid/cancel")
        assertEquals(
            HttpStatusCode.Conflict,
            resp.status,
            "/cancel with no in-flight run must 409 (NOT 200, NOT 404); body was: ${resp.bodyAsText()}",
        )
        val body = resp.body<CancelSessionResponse>()
        assertEquals(false, body.cancelled, "409 body must have `cancelled: false`")
        assertEquals(
            "no in-flight run",
            body.reason,
            "409 body must surface the canonical 'no in-flight run' reason",
        )
    }

    @Test fun cancelOnUnknownSessionIdAlsoReturns409NotFromNotFound() = testApplication {
        // Pin: the route does NOT 404 on unknown session id. The
        // discriminator is the cancel-success boolean, which returns
        // false for any session without an in-flight run — including
        // sessions that were never created. Drift to "404 if session
        // not found" would couple the route to session-store lookup
        // it currently doesn't perform.
        val recordingProvider = RecordingProvider("openai")
        val providers = ProviderRegistry.Builder().add(recordingProvider).build()
        val container = ServerContainer(
            rawEnv = isolatedEnv(),
            providerRegistryOverride = providers,
        )
        application { serverModule(container) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/sessions/never-was-created/cancel")
        assertEquals(
            HttpStatusCode.Conflict,
            resp.status,
            "/cancel on unknown session id must 409 (NOT 404 — route doesn't gate on session existence)",
        )
        val body = resp.body<CancelSessionResponse>()
        assertEquals(false, body.cancelled)
        assertEquals("no in-flight run", body.reason)
    }

    @Test fun cancel409BodyDistinguishesFromCancel501Body() = testApplication {
        // Comparative shape pin: the 409 body has BOTH `cancelled` and
        // `reason`; the 501 body has `error`. A client can't conflate
        // them by reading the wrong field — pin the schemas are
        // structurally distinct.
        val recordingProvider = RecordingProvider("anthropic")
        val providers = ProviderRegistry.Builder().add(recordingProvider).build()
        val containerWithProvider = ServerContainer(
            rawEnv = isolatedEnv(),
            providerRegistryOverride = providers,
        )
        application { serverModule(containerWithProvider) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val sid = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(projectId = "p-shape"))
        }.body<CreateSessionResponse>().sessionId

        val conflictResp = client.post("/sessions/$sid/cancel")
        assertEquals(HttpStatusCode.Conflict, conflictResp.status)
        val conflictBody = conflictResp.body<CancelSessionResponse>()
        assertEquals(false, conflictBody.cancelled, "409 body's `cancelled` field is false")
        assertNotEquals(null, conflictBody.reason, "409 body's `reason` field is non-null")
        // The 501 body shape is `{"error": "..."}` (Map<String, String>),
        // structurally distinct from CancelSessionResponse — pinned in
        // `cancelWithNoProviderConfiguredReturns501` above. Drift to
        // "use CancelSessionResponse for 501 too" would silently lose
        // the canonical error message format the /messages route uses.
    }

    private class RecordingProvider(override val id: String) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = emptyList()

        override fun stream(request: LlmRequest) = flow {
            val partId = PartId("$id-part")
            emit(LlmEvent.StepStart)
            emit(LlmEvent.TextStart(partId))
            emit(LlmEvent.TextDelta(partId, "handled by $id"))
            emit(LlmEvent.TextEnd(partId, "handled by $id"))
            emit(LlmEvent.StepFinish(FinishReason.STOP, TokenUsage.ZERO))
        }
    }
}
