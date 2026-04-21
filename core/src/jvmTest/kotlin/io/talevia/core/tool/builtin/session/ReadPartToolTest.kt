package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadPartToolTest {

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

    private suspend fun seedSessionAndMessage(store: SqlDelightSessionStore) {
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

    @Test fun returnsTextPartFullContent() = runTest {
        val rig = rig()
        seedSessionAndMessage(rig.store)
        val longText = "full payload of the part".repeat(20)
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                text = longText,
            ),
        )

        val out = ReadPartTool(rig.store).execute(
            ReadPartTool.Input(partId = "p-text"),
            rig.ctx,
        ).data

        assertEquals("p-text", out.partId)
        assertEquals("text", out.kind)
        assertEquals("a-1", out.messageId)
        assertEquals("s-1", out.sessionId)
        val text = out.payload["text"] as? JsonPrimitive
        assertNotNull(text)
        // Full content returned — matches the seeded string verbatim.
        assertEquals(longText, text!!.content)
    }

    @Test fun returnsCompactionFullSummary() = runTest {
        val rig = rig()
        seedSessionAndMessage(rig.store)
        val summary = "compaction summary text long enough to be useful".repeat(10)
        rig.store.upsertPart(
            Part.Compaction(
                id = PartId("p-compact"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                replacedFromMessageId = MessageId("u-1"),
                replacedToMessageId = MessageId("a-1"),
                summary = summary,
            ),
        )

        val out = ReadPartTool(rig.store).execute(
            ReadPartTool.Input(partId = "p-compact"),
            rig.ctx,
        ).data

        assertEquals("compaction", out.kind)
        val summaryField = out.payload["summary"] as? JsonPrimitive
        assertNotNull(summaryField)
        assertEquals(summary, summaryField!!.content)
        // Common range fields round-trip.
        assertEquals("u-1", (out.payload["replacedFromMessageId"] as JsonPrimitive).content)
    }

    @Test fun exposesCompactedAtWhenSet() = runTest {
        val rig = rig()
        seedSessionAndMessage(rig.store)
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-1"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                text = "t",
            ),
        )
        rig.store.markPartCompacted(PartId("p-1"), Instant.fromEpochMilliseconds(2_000L))

        val out = ReadPartTool(rig.store).execute(
            ReadPartTool.Input(partId = "p-1"),
            rig.ctx,
        ).data
        assertEquals(2_000L, out.compactedAtEpochMs)
    }

    @Test fun missingPartFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ReadPartTool(rig.store).execute(
                ReadPartTool.Input(partId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("describe_message"), ex.message)
    }

    @Test fun payloadCarriesDiscriminator() = runTest {
        val rig = rig()
        seedSessionAndMessage(rig.store)
        rig.store.upsertPart(
            Part.Reasoning(
                id = PartId("p-reason"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                text = "thinking",
            ),
        )

        val out = ReadPartTool(rig.store).execute(
            ReadPartTool.Input(partId = "p-reason"),
            rig.ctx,
        ).data

        // kotlinx.serialization emits `type` as the class discriminator per JsonConfig.default.
        val typeField = out.payload["type"] as? JsonPrimitive
        assertNotNull(typeField)
        assertEquals("reasoning", typeField!!.content)
    }
}
