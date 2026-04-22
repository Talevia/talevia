package io.talevia.cli.repl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.cli.event.EventRouter
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jline.terminal.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end test for the streaming renderer path. Mirrors what
 * [io.talevia.core.agent.AgentTurnExecutor] emits for a single text response:
 *
 *  1. `upsertPart(Part.Text(text=""))` on TextStart — fires PartUpdated(empty)
 *  2. `bus.publish(PartDelta(delta="..."))` per streamed token
 *  3. `upsertPart(Part.Text(text=full))` on TextEnd — fires PartUpdated(full)
 *
 * Regression: the empty PartUpdated from step 1 used to race the PartDelta
 * subscriber and mark the part finalised before any delta arrived, silently
 * dropping part or all of the assistant's reply. Also, the `· tokens=…` line
 * printed by the REPL wrote on the same line as the last streamed delta
 * because `Renderer.println` didn't break the assistant line first.
 */
class TextStreamingIntegrationTest {

    @Test
    fun `streamed text renders in full and token line sits on its own row`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val now = Clock.System.now()
        val sessionId = SessionId("s-1")
        val messageId = MessageId("m-1")
        val partId = PartId("p-1")
        val fullText = "Hello world! The files are a.mov, b.mov, c.mov."

        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("proj-1"),
                title = "test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        // EventRouter.finalizeAssistantText gates on the message being an Assistant —
        // appendMessage before upsertPart so getMessage(id) returns the right type.
        store.appendMessage(
            Message.Assistant(
                id = messageId,
                sessionId = sessionId,
                createdAt = now,
                parentId = MessageId("user-0"),
                model = ModelRef("openai", "gpt-test"),
            ),
        )

        val renderer = Renderer(terminal, markdownEnabled = false)
        val routerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val router = EventRouter(
            bus = bus,
            sessions = store,
            renderer = renderer,
            activeSessionId = { sessionId },
        )
        router.start(routerScope)

        // Step 1 — TextStart: empty Part.Text upsert. Before the fix, this
        // triggered finalizeAssistantText(partId, "") which added partId to the
        // renderer's `finalised` set and made every subsequent PartDelta a no-op.
        store.upsertPart(
            Part.Text(
                id = partId,
                messageId = messageId,
                sessionId = sessionId,
                createdAt = now,
                text = "",
            ),
        )
        // Give the PartUpdated subscriber a real chance to run — this is the
        // worst-case race we're defending against.
        delay(50)

        // Step 2 — streaming deltas.
        for (chunk in listOf("Hello ", "world! ", "The files are a.mov, b.mov, c.mov.")) {
            bus.publish(
                BusEvent.PartDelta(
                    sessionId = sessionId,
                    messageId = messageId,
                    partId = partId,
                    field = "text",
                    delta = chunk,
                ),
            )
            delay(5)
        }

        // Step 3 — TextEnd: canonical full text.
        store.upsertPart(
            Part.Text(
                id = partId,
                messageId = messageId,
                sessionId = sessionId,
                createdAt = now,
                text = fullText,
            ),
        )
        delay(50)

        // Repl.kt post-turn fallback + token summary + endTurn.
        renderer.finalizeAssistantText(partId, fullText)
        renderer.println("· tokens in=100 out=${fullText.length}")
        renderer.endTurn()
        terminal.flush()

        routerScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()

        val captured = out.toString(Charsets.UTF_8)
        // Full reply must be present — this is the regression we are guarding.
        assertTrue(
            fullText in captured,
            "streamed text missing from terminal output. captured=<<<$captured>>>",
        )
        // Token line must not be glued onto the streamed text.
        assertFalse(
            "$fullText·" in captured,
            "token summary glued onto streamed text without a newline. captured=<<<$captured>>>",
        )
        assertTrue("tokens in=100" in captured, "token summary missing. captured=<<<$captured>>>")
    }

    @Test
    fun `renderer println breaks an open assistant line`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false)

        val partId = PartId("p-only")
        val body = "Assistant reply body."
        renderer.streamAssistantDelta(partId, body)
        renderer.println("· tokens in=5 out=13")
        renderer.endTurn()
        terminal.flush()

        val captured = out.toString(Charsets.UTF_8)
        assertTrue(body in captured, "streamed text missing. captured=<<<$captured>>>")
        // The exact bug the user reported — delta end + '·' must not share a line.
        assertFalse(
            "$body·" in captured,
            "token line collided with streamed text. captured=<<<$captured>>>",
        )
    }

    // TerminalBuilder silently promotes to a PTY-backed terminal even with
    // .streams(...), which drops our captured bytes on the floor. Construct
    // DumbTerminal directly so writes land in `out`.
    private fun dumbTerminal(out: ByteArrayOutputStream): Terminal =
        org.jline.terminal.impl.DumbTerminal(
            "test",
            "dumb",
            ByteArrayInputStream(ByteArray(0)),
            out,
            StandardCharsets.UTF_8,
        )
}
