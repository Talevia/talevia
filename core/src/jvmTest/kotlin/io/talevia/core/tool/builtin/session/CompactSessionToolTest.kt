package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.FakeProvider
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.ProviderRegistry
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompactSessionToolTest {

    private data class Rig(
        val tool: CompactSessionTool,
        val store: SqlDelightSessionStore,
        val bus: EventBus,
        val ctx: ToolContext,
    )

    private fun rig(turns: List<List<LlmEvent>>): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(TaleviaDb(driver), bus)
        val provider = FakeProvider(turns)
        val registry = ProviderRegistry.Builder().add(provider).build()
        val tool = CompactSessionTool(registry, store, bus)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(tool, store, bus, ctx)
    }

    private suspend fun seedLongSession(store: SqlDelightSessionStore) {
        val sid = SessionId("s-1")
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        store.createSession(
            Session(id = sid, projectId = ProjectId("p"), title = "t", createdAt = now, updatedAt = now),
        )
        // Three user→assistant turns with tool outputs so pruning has something to drop.
        for (i in 1..3) {
            val uid = MessageId("u-$i")
            val aid = MessageId("a-$i")
            val tidx = i.toLong()
            store.appendMessage(
                Message.User(
                    id = uid,
                    sessionId = sid,
                    createdAt = Instant.fromEpochMilliseconds(1_000L * tidx),
                    agent = "default",
                    model = ModelRef("fake", "fake-model"),
                ),
            )
            store.upsertPart(
                Part.Text(
                    id = PartId("u-text-$i"),
                    messageId = uid,
                    sessionId = sid,
                    createdAt = Instant.fromEpochMilliseconds(1_000L * tidx),
                    text = "user turn $i",
                ),
            )
            store.appendMessage(
                Message.Assistant(
                    id = aid,
                    sessionId = sid,
                    createdAt = Instant.fromEpochMilliseconds(1_000L * tidx + 500),
                    parentId = uid,
                    model = ModelRef("fake", "fake-model"),
                    tokens = TokenUsage(input = 1000, output = 1000),
                    finish = FinishReason.STOP,
                ),
            )
            // Big tool output so it's worth pruning.
            store.upsertPart(
                Part.Tool(
                    id = PartId("tool-$i"),
                    messageId = aid,
                    sessionId = sid,
                    createdAt = Instant.fromEpochMilliseconds(1_000L * tidx + 600),
                    callId = CallId("call-$i"),
                    toolId = "generate_image",
                    state = io.talevia.core.session.ToolState.Completed(
                        input = kotlinx.serialization.json.JsonObject(emptyMap()),
                        outputForLlm = "output for turn $i ".repeat(5000), // >40k token budget so pruning fires
                        data = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                ),
            )
        }
    }

    @Test fun compactsWhenHistoryHasStuffToPrune() = runTest {
        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("sum-text")),
            LlmEvent.TextEnd(PartId("sum-text"), "Goal: three image turns. Discoveries: …"),
            LlmEvent.StepFinish(FinishReason.STOP, tokenUsage()),
        )
        val rig = rig(turns = listOf(summaryTurn))
        seedLongSession(rig.store)

        val out = rig.tool.execute(
            CompactSessionTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertTrue(out.compacted, "expected compacted=true, got skipReason=${out.skipReason}")
        assertNotNull(out.compactionPartId)
        assertTrue((out.prunedPartCount ?: 0) >= 1, "prunedPartCount should be non-zero")
        assertTrue(out.summaryPreview!!.contains("Goal"), out.summaryPreview)

        // Compaction part landed on the store.
        val parts = rig.store.listSessionParts(SessionId("s-1"))
        assertEquals(1, parts.filterIsInstance<Part.Compaction>().size)
    }

    @Test fun emptySessionSkipsWithReason() = runTest {
        val rig = rig(emptyList())
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        rig.store.createSession(
            Session(
                id = SessionId("s-empty"),
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )

        val out = rig.tool.execute(
            CompactSessionTool.Input(sessionId = "s-empty"),
            rig.ctx,
        ).data

        assertFalse(out.compacted)
        assertTrue(out.skipReason!!.contains("no assistant messages"), out.skipReason)
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig(emptyList())
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                CompactSessionTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun providerForSessionMissingIsSkipped() = runTest {
        val rig = rig(emptyList())
        val sid = SessionId("s-1")
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        rig.store.createSession(
            Session(id = sid, projectId = ProjectId("p"), title = "t", createdAt = now, updatedAt = now),
        )
        rig.store.appendMessage(
            Message.Assistant(
                id = MessageId("a-1"),
                sessionId = sid,
                createdAt = now,
                parentId = MessageId("u-1"),
                model = ModelRef("unregistered-provider", "some-model"),
            ),
        )

        val out = rig.tool.execute(
            CompactSessionTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertFalse(out.compacted)
        assertTrue(out.skipReason!!.contains("unregistered-provider"), out.skipReason)
    }

    private fun tokenUsage(): TokenUsage = TokenUsage(input = 10, output = 10)
}
