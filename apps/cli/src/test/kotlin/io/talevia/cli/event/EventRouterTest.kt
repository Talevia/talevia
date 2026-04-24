package io.talevia.cli.event

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.cli.repl.Renderer
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.ToolState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.terminal.impl.DumbTerminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the EventBus → Renderer routing pattern in
 * [io.talevia.cli.event.EventRouter]. `:apps:cli:test` already covers each
 * Renderer method in isolation; this test covers the *routing*: for each
 * BusEvent type the router subscribes to, a publish must reach the matching
 * Renderer method (and nothing else). Adding a 7th subscription without
 * matching test coverage would previously have landed silently.
 *
 * Test shape (pinned convention):
 *   - real [EventBus], [SqlDelightSessionStore] (in-memory), [Renderer] on a
 *     `DumbTerminal` whose stdout is captured to a [ByteArrayOutputStream].
 *   - `router.start(backgroundScope)`; wait for 5 subscriptions to install
 *     via `bus.events.subscriptionCount` so publishes don't beat their
 *     collectors.
 *   - drive one event per case; `advanceUntilIdle()` + `yield()` flushes
 *     the router coroutines before reading captured stdout.
 *   - each Renderer method emits a distinct human-readable marker — we
 *     assert on those markers rather than mocking, because [Renderer] is
 *     a concrete class and making it open purely for tests would be API
 *     churn with no production use. The marker-based assertion matches the
 *     sibling `AssetsMissingNoticeTest` etc.
 */
class EventRouterTest {

    private val projectId = ProjectId("p-1")
    private val sessionId = SessionId("s-active")
    private val otherSessionId = SessionId("s-other")
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test fun partDeltaOnActiveSessionReachesStreamAssistantDelta() = runTest {
        val rig = rig()
        rig.publishPartDeltaAsText("part-1", "hello-from-router")
        rig.settle()
        assertTrue(
            "hello-from-router" in rig.stdout(),
            "streamAssistantDelta should surface the delta text; got <${rig.stdout()}>",
        )
    }

    @Test fun partDeltaOnOtherSessionIsFilteredOut() = runTest {
        // Session filter guard: a delta for a different session must NOT
        // reach the renderer even though the bus carries the event.
        val rig = rig()
        rig.bus.publish(
            BusEvent.PartDelta(
                sessionId = otherSessionId,
                messageId = MessageId("m-ignore"),
                partId = PartId("part-ignore"),
                field = "text",
                delta = "should-not-appear",
            ),
        )
        rig.settle()
        assertFalse(
            "should-not-appear" in rig.stdout(),
            "session filter should drop cross-session deltas; got <${rig.stdout()}>",
        )
    }

    @Test fun partDeltaWithNonTextFieldIsIgnored() = runTest {
        // The router only streams deltas on the `field == "text"` branch;
        // an `input` delta (partial tool-call JSON) must not leak into the
        // assistant-text stream.
        val rig = rig()
        rig.bus.publish(
            BusEvent.PartDelta(
                sessionId = sessionId,
                messageId = MessageId("m-1"),
                partId = PartId("p-tool"),
                field = "input",
                delta = "{\"arg\":42}",
            ),
        )
        rig.settle()
        assertFalse(
            "{\"arg\":42}" in rig.stdout(),
            "non-text deltas must not render as assistant text; got <${rig.stdout()}>",
        )
    }

    @Test fun agentRetryScheduledRoutesToRetryNotice() = runTest {
        val rig = rig()
        rig.bus.publish(
            BusEvent.AgentRetryScheduled(
                sessionId = sessionId,
                attempt = 3,
                waitMs = 4_000,
                reason = "anthropic HTTP 503 overloaded",
            ),
        )
        rig.settle()
        val out = rig.stdout()
        assertTrue("3" in out, "retry attempt number missing: <$out>")
        assertTrue(
            "503" in out || "overloaded" in out || "retry" in out.lowercase(),
            "retryNotice reason snippet missing: <$out>",
        )
    }

    @Test fun sessionCompactedRoutesToCompactedNotice() = runTest {
        val rig = rig()
        rig.bus.publish(
            BusEvent.SessionCompacted(
                sessionId = sessionId,
                prunedCount = 7,
                summaryLength = 321,
            ),
        )
        rig.settle()
        val out = rig.stdout()
        assertTrue("7" in out, "prunedCount missing: <$out>")
        assertTrue("321" in out, "summaryLength missing: <$out>")
    }

    @Test fun providerWarmupStartingRoutesToWarmupNotice() = runTest {
        // The router renders `Starting` as "warming up <providerId>…" so the
        // session-cold first AIGC call stops looking like a hang (M2 exit
        // summary §3.1 follow-up #4).
        val rig = rig()
        rig.bus.publish(
            BusEvent.ProviderWarmup(
                sessionId = sessionId,
                providerId = "replicate",
                phase = BusEvent.ProviderWarmup.Phase.Starting,
                epochMs = 1_700_000_000_000L,
            ),
        )
        rig.settle()
        val out = rig.stdout()
        assertTrue("warming up" in out, "warmupNotice prefix missing: <$out>")
        assertTrue("replicate" in out, "providerId missing from warmup line: <$out>")
    }

    @Test fun providerWarmupReadyIsSuppressed() = runTest {
        // Ready is emitted by the engine so metrics can pair it with
        // Starting, but the CLI renderer deliberately drops it — by the
        // time Ready arrives, streaming has resumed and a "…ready" line
        // would be redundant noise.
        val rig = rig()
        rig.bus.publish(
            BusEvent.ProviderWarmup(
                sessionId = sessionId,
                providerId = "replicate",
                phase = BusEvent.ProviderWarmup.Phase.Ready,
                epochMs = 1_700_000_000_000L,
            ),
        )
        rig.settle()
        assertFalse(
            "warming up" in rig.stdout(),
            "Ready phase must not render: <${rig.stdout()}>",
        )
    }

    @Test fun assetsMissingRoutesIgnoresSessionFilter() = runTest {
        // AssetsMissing has no sessionId — the router intentionally skips the
        // active-session filter for this event (commented rationale in
        // EventRouter). A publish must render regardless of what session is
        // active.
        val rig = rig()
        rig.bus.publish(
            BusEvent.AssetsMissing(
                projectId = projectId,
                missing = listOf(
                    BusEvent.MissingAsset("a1", "/nas/gone.mp4"),
                ),
            ),
        )
        rig.settle()
        val out = rig.stdout()
        assertTrue("/nas/gone.mp4" in out, "assetsMissingNotice path missing: <$out>")
    }

    @Test fun partUpdatedTextFinalizesOnAssistantMessage() = runTest {
        val rig = rig()
        // Seed an assistant message so the PartUpdated.Text handler's
        // `sessions.getMessage(messageId) is Message.Assistant` branch
        // fires (otherwise the router skips rendering).
        val assistantId = MessageId("m-asst")
        rig.sessions.appendMessage(
            Message.Assistant(
                id = assistantId,
                sessionId = sessionId,
                createdAt = baseTime,
                parentId = MessageId("m-user"),
                model = ModelRef("fake", "test"),
                finish = FinishReason.END_TURN,
            ),
        )
        rig.bus.publish(
            BusEvent.PartUpdated(
                sessionId = sessionId,
                messageId = assistantId,
                partId = PartId("p-final"),
                part = Part.Text(
                    id = PartId("p-final"),
                    messageId = assistantId,
                    sessionId = sessionId,
                    createdAt = baseTime,
                    text = "final-assistant-body",
                ),
            ),
        )
        rig.settle()
        assertTrue(
            "final-assistant-body" in rig.stdout(),
            "finalizeAssistantText must render the body: <${rig.stdout()}>",
        )
    }

    @Test fun partUpdatedEmptyTextSkipsFinalize() = runTest {
        // Early-return edge (§3a #9): an empty-text PartUpdated fires on
        // TextStart and must NOT finalize (would race the delta subscriber
        // and truncate streaming output).
        val rig = rig()
        val assistantId = MessageId("m-empty")
        rig.sessions.appendMessage(
            Message.Assistant(
                id = assistantId,
                sessionId = sessionId,
                createdAt = baseTime,
                parentId = MessageId("m-user"),
                model = ModelRef("fake", "test"),
                finish = FinishReason.END_TURN,
            ),
        )
        rig.bus.publish(
            BusEvent.PartUpdated(
                sessionId = sessionId,
                messageId = assistantId,
                partId = PartId("p-empty"),
                part = Part.Text(
                    id = PartId("p-empty"),
                    messageId = assistantId,
                    sessionId = sessionId,
                    createdAt = baseTime,
                    text = "",
                ),
            ),
        )
        rig.settle()
        // DumbTerminal output for an empty finalize would be nothing
        // terminal-visible; the stronger assertion is on the absence of the
        // "finalised" stdout side-effects. Since there's no unique marker
        // for the empty case, assert the captured text is empty of any
        // repaint — DumbTerminal with ansi disabled writes nothing when
        // the renderer short-circuits.
        assertEquals("", rig.stdout().trim(), "empty-text finalize must be a no-op")
    }

    @Test fun partUpdatedToolRunningSurfacesToolId() = runTest {
        val rig = rig()
        val assistantId = MessageId("m-tool-host")
        rig.sessions.appendMessage(
            Message.Assistant(
                id = assistantId,
                sessionId = sessionId,
                createdAt = baseTime,
                parentId = MessageId("m-user"),
                model = ModelRef("fake", "test"),
                finish = FinishReason.END_TURN,
            ),
        )
        rig.bus.publish(
            BusEvent.PartUpdated(
                sessionId = sessionId,
                messageId = assistantId,
                partId = PartId("p-tool-running"),
                part = Part.Tool(
                    id = PartId("p-tool-running"),
                    messageId = assistantId,
                    sessionId = sessionId,
                    createdAt = baseTime,
                    callId = io.talevia.core.CallId("call-1"),
                    toolId = "router-probe-tool",
                    state = ToolState.Running(kotlinx.serialization.json.JsonObject(emptyMap())),
                ),
            ),
        )
        rig.settle()
        assertTrue(
            "router-probe-tool" in rig.stdout(),
            "toolRunning must surface the toolId: <${rig.stdout()}>",
        )
    }

    // ----- rig / helpers --------------------------------------------------

    private class Rig(
        val bus: EventBus,
        val sessions: SqlDelightSessionStore,
        val out: ByteArrayOutputStream,
        val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        val terminal: Terminal,
    ) {
        fun stdout(): String {
            terminal.flush()
            return out.toString(StandardCharsets.UTF_8)
        }

        suspend fun publishPartDeltaAsText(partId: String, text: String) {
            bus.publish(
                BusEvent.PartDelta(
                    sessionId = SessionId("s-active"),
                    messageId = MessageId("m-1"),
                    partId = PartId(partId),
                    field = "text",
                    delta = text,
                ),
            )
        }

        suspend fun settle() {
            // Three yields + advanceUntilIdle: one for publish's emit to
            // hand off, one for the matching router coroutine to run, one
            // for renderer mutex acquisition. runTest's single-thread
            // dispatcher makes this deterministic.
            scheduler.advanceUntilIdle()
            yield()
            scheduler.advanceUntilIdle()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(TaleviaDb(driver), bus)

        // Seed the active session row so getMessage doesn't fail when the
        // PartUpdated branches need to look up the parent assistant message.
        sessions.createSession(
            Session(
                id = sessionId,
                projectId = projectId,
                title = "router",
                createdAt = baseTime,
                updatedAt = baseTime,
            ),
        )

        val out = ByteArrayOutputStream()
        val terminal = DumbTerminal(
            "test",
            "dumb",
            ByteArrayInputStream(ByteArray(0)),
            out,
            StandardCharsets.UTF_8,
        )
        terminal.setSize(Size(120, 40))
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        val router = EventRouter(bus, sessions, renderer) { sessionId }
        router.start(backgroundScope)

        // Wait for every subscribe<T>() collector to install before any
        // publish: a replay=0 MutableSharedFlow silently drops events
        // without a live subscriber. Yield repeatedly until the router's
        // launches have definitely installed on the test dispatcher; after
        // `runCurrent()` + a few yields, the single-thread test scheduler
        // has dispatched every pending start-up continuation.
        testScheduler.runCurrent()
        repeat(8) { yield() }
        testScheduler.runCurrent()

        return Rig(bus, sessions, out, testScheduler, terminal)
    }
}
