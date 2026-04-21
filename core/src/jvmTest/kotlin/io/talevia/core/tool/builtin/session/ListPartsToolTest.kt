package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListPartsToolTest {

    private data class Rig(
        val store: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private suspend fun seedSession(store: SqlDelightSessionStore) {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        store.createSession(
            Session(
                id = SessionId("s-1"),
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        store.appendMessage(
            Message.Assistant(
                id = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = now,
                parentId = MessageId("u-1"),
                model = ModelRef("anthropic", "claude"),
            ),
        )
    }

    private suspend fun seedParts(store: SqlDelightSessionStore) {
        val mid = MessageId("a-1")
        val sid = SessionId("s-1")
        store.upsertPart(
            Part.Text(id = PartId("p-text"), messageId = mid, sessionId = sid, createdAt = Instant.fromEpochMilliseconds(1_000L), text = "hi"),
        )
        store.upsertPart(
            Part.StepFinish(
                id = PartId("p-step"),
                messageId = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(2_000L),
                tokens = TokenUsage(input = 100, output = 50),
                finish = FinishReason.STOP,
            ),
        )
        store.upsertPart(
            Part.TimelineSnapshot(
                id = PartId("p-ts"),
                messageId = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(3_000L),
                timeline = Timeline(),
            ),
        )
        store.upsertPart(
            Part.Compaction(
                id = PartId("p-comp"),
                messageId = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(4_000L),
                replacedFromMessageId = MessageId("u-1"),
                replacedToMessageId = MessageId("a-1"),
                summary = "done",
            ),
        )
    }

    @Test fun returnsAllPartsMostRecentFirst() = runTest {
        val rig = rig()
        seedSession(rig.store)
        seedParts(rig.store)

        val out = ListPartsTool(rig.store).execute(
            ListPartsTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals(4, out.totalParts)
        assertEquals(listOf("p-comp", "p-ts", "p-step", "p-text"), out.parts.map { it.partId })
    }

    @Test fun kindFilterScopes() = runTest {
        val rig = rig()
        seedSession(rig.store)
        seedParts(rig.store)

        val out = ListPartsTool(rig.store).execute(
            ListPartsTool.Input(sessionId = "s-1", kind = "timeline-snapshot"),
            rig.ctx,
        ).data

        assertEquals(1, out.totalParts)
        assertEquals("p-ts", out.parts.single().partId)
    }

    @Test fun unknownKindRejected() = runTest {
        val rig = rig()
        seedSession(rig.store)
        val ex = assertFailsWith<IllegalArgumentException> {
            ListPartsTool(rig.store).execute(
                ListPartsTool.Input(sessionId = "s-1", kind = "bogus"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("bogus"), ex.message)
    }

    @Test fun previewsAreKindSpecific() = runTest {
        val rig = rig()
        seedSession(rig.store)
        seedParts(rig.store)

        val out = ListPartsTool(rig.store).execute(
            ListPartsTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        val byKind = out.parts.associateBy { it.kind }
        assertTrue(byKind.getValue("step-finish").preview.contains("input=100"))
        assertTrue(byKind.getValue("compaction").preview.contains("compacted"))
        assertTrue(byKind.getValue("timeline-snapshot").preview.contains("clip(s)"))
    }

    @Test fun limitCaps() = runTest {
        val rig = rig()
        seedSession(rig.store)
        seedParts(rig.store)

        val out = ListPartsTool(rig.store).execute(
            ListPartsTool.Input(sessionId = "s-1", limit = 2),
            rig.ctx,
        ).data

        assertEquals(4, out.totalParts)
        assertEquals(2, out.returnedParts)
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ListPartsTool(rig.store).execute(
                ListPartsTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }
}
