package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Opt-in smoke test** — hits the real Anthropic API. Skipped automatically
 * unless `ANTHROPIC_API_KEY` is set in the environment, so CI and ordinary
 * `./gradlew :core:jvmTest` runs never incur a billable request.
 *
 * Coverage gap this fills: every other `AnthropicProvider*` test mocks the
 * SSE transport via Ktor's `MockEngine`. Useful for catching parsing
 * regressions against **fixed** wire format, but insufficient when:
 *  - Anthropic ships a breaking SSE schema change (new event type, renamed
 *    field, stop reason migration).
 *  - A model is retired and the HTTP 404 doesn't match the mock fixtures.
 *  - Token accounting drifts in the streaming `usage` envelope.
 *
 * Backlog called this out as "integration-test-real-provider-smoke" and
 * explicitly said CI should **not** run it (a paid API call per CI run is
 * not what Anthropic built their free tier for).
 *
 * Local run:
 *   ANTHROPIC_API_KEY=sk-ant-… ./gradlew :core:jvmTest \
 *       --tests 'io.talevia.core.agent.AnthropicRealProviderSmokeTest'
 *
 * Optional: `TALEVIA_SMOKE_MODEL=claude-haiku-4-5` overrides the default
 * smoke model (kept small + cheap).
 */
class AnthropicRealProviderSmokeTest {

    @Test
    fun realAnthropicProviderReturnsTextAndStopReason() = runTest(
        timeout = kotlin.time.Duration.parse("60s"),
    ) {
        val apiKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
            ?: return@runTest // skip silently — see class kdoc

        val modelId = System.getenv("TALEVIA_SMOKE_MODEL")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SMOKE_MODEL

        val client = HttpClient(CIO)
        try {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
            val db = TaleviaDb(driver)
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)
            val agent = Agent(
                provider = AnthropicProvider(client, apiKey = apiKey),
                registry = ToolRegistry(), // no tools — pure text reply
                store = store,
                permissions = AllowAllPermissionService(),
                bus = bus,
            )

            val sessionId = SessionId("anthropic-smoke-${(0..1_000_000).random()}")
            val now = Clock.System.now()
            store.createSession(
                Session(
                    id = sessionId,
                    projectId = ProjectId("smoke"),
                    title = "real-api smoke",
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            val asst = agent.run(
                RunInput(
                    sessionId = sessionId,
                    text = "Reply with exactly one English word.",
                    model = ModelRef("anthropic", modelId),
                ),
            )

            // Contract the backlog pinned: `text + stop reason`.
            assertNotNull(asst.finish, "assistant finish reason must be set (got null = still pending)")
            assertNull(asst.error, "assistant must not carry an error; got: ${asst.error}")
            assertTrue(
                asst.finish in setOf(FinishReason.STOP, FinishReason.END_TURN),
                "expected a clean stop reason (STOP|END_TURN); got ${asst.finish}",
            )
            // Token accounting wire-up check — if Anthropic renames `usage.input_tokens`
            // the input counter will stay at 0 while we still see text, which this catches.
            assertTrue(asst.tokens.input > 0, "expected non-zero input tokens, got ${asst.tokens.input}")
            assertTrue(asst.tokens.output > 0, "expected non-zero output tokens, got ${asst.tokens.output}")

            // Text part persisted — the agent's `TextEnd` path landed a real string.
            val textParts = store.listSessionParts(sessionId).filterIsInstance<Part.Text>()
            assertTrue(
                textParts.any { it.messageId == asst.id && it.text.isNotBlank() },
                "expected at least one non-blank Part.Text on the assistant message; " +
                    "got ${textParts.size} text parts",
            )

            // Sanity: ensure exactly one assistant message landed (no tool-loop spinning).
            val assistantMessages = store.listMessages(sessionId)
                .filterIsInstance<io.talevia.core.session.Message.Assistant>()
            assertEquals(1, assistantMessages.size, "smoke prompt should produce a single assistant turn")
        } finally {
            client.close()
        }
    }

    companion object {
        /**
         * Default model used for the smoke test. Haiku is the cheapest Claude 4.x
         * tier (see `CLAUDE.md` model catalogue). Override via
         * `TALEVIA_SMOKE_MODEL` env var when probing a different family.
         */
        const val DEFAULT_SMOKE_MODEL: String = "claude-haiku-4-5"
    }
}
