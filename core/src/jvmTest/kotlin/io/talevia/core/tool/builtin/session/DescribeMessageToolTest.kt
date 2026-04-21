package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
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
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoStatus
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescribeMessageToolTest {

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

    private suspend fun newSession(store: SqlDelightSessionStore) {
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
    }

    private suspend fun appendAssistant(
        store: SqlDelightSessionStore,
        id: String,
        parentId: String = "u-1",
        finish: FinishReason? = FinishReason.STOP,
    ) {
        store.appendMessage(
            Message.Assistant(
                id = MessageId(id),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(2_000L),
                parentId = MessageId(parentId),
                model = ModelRef("anthropic", "claude"),
                tokens = TokenUsage(input = 100, output = 50),
                finish = finish,
            ),
        )
    }

    private suspend fun appendUser(store: SqlDelightSessionStore, id: String) {
        store.appendMessage(
            Message.User(
                id = MessageId(id),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )
    }

    @Test fun describesAssistantWithDiversePartKinds() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "u-1")
        appendAssistant(rig.store, "a-1")

        val sid = SessionId("s-1")
        val mid = MessageId("a-1")
        val now = Instant.fromEpochMilliseconds(2_000L)

        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                text = "hello world and more content to be truncated".padEnd(200, 'x'),
            ),
        )
        rig.store.upsertPart(
            Part.Tool(
                id = PartId("p-tool"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                callId = CallId("call-1"),
                toolId = "generate_image",
                state = ToolState.Running(input = buildJsonObject { put("prompt", JsonPrimitive("Mei")) }),
            ),
        )
        rig.store.upsertPart(
            Part.StepFinish(
                id = PartId("p-step"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                tokens = TokenUsage(input = 100, output = 50),
                finish = FinishReason.STOP,
            ),
        )
        rig.store.upsertPart(
            Part.Todos(
                id = PartId("p-todos"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                todos = listOf(
                    TodoInfo(content = "one", status = TodoStatus.COMPLETED),
                    TodoInfo(content = "two", status = TodoStatus.IN_PROGRESS),
                    TodoInfo(content = "three", status = TodoStatus.PENDING),
                ),
            ),
        )
        rig.store.upsertPart(
            Part.TimelineSnapshot(
                id = PartId("p-ts"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                timeline = Timeline(),
                producedByCallId = CallId("call-1"),
            ),
        )
        rig.store.upsertPart(
            Part.Media(
                id = PartId("p-media"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                assetId = AssetId("a-hero"),
            ),
        )

        val out = DescribeMessageTool(rig.store).execute(
            DescribeMessageTool.Input(messageId = "a-1"),
            rig.ctx,
        ).data

        assertEquals("assistant", out.role)
        assertEquals("stop", out.finish)
        assertEquals("u-1", out.parentId)
        assertEquals(100L, out.tokensInput)
        assertEquals(50L, out.tokensOutput)
        assertEquals(6, out.partCount)

        val byKind = out.parts.associateBy { it.kind }
        assertEquals(6, byKind.size, "one part per kind above")

        val textSummary = byKind["text"]!!
        assertEquals(80, textSummary.preview.length, "text preview clips to 80 chars")

        val toolSummary = byKind["tool"]!!
        assertTrue(toolSummary.preview.contains("generate_image"), toolSummary.preview)
        assertTrue(toolSummary.preview.contains("running"), toolSummary.preview)

        val stepSummary = byKind["step-finish"]!!
        assertTrue(stepSummary.preview.contains("stop"), stepSummary.preview)
        assertTrue(stepSummary.preview.contains("input=100"), stepSummary.preview)

        val todoSummary = byKind["todos"]!!
        assertTrue(todoSummary.preview.contains("3 todo"), todoSummary.preview)
        assertTrue(todoSummary.preview.contains("done=1"), todoSummary.preview)
        assertTrue(todoSummary.preview.contains("in_progress=1"), todoSummary.preview)
        assertTrue(todoSummary.preview.contains("pending=1"), todoSummary.preview)

        val tsSummary = byKind["timeline-snapshot"]!!
        assertTrue(tsSummary.preview.contains("call-1"), tsSummary.preview)

        val mediaSummary = byKind["media"]!!
        assertEquals("a-hero", mediaSummary.preview)
    }

    @Test fun describesUserMessage() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "u-1")

        val out = DescribeMessageTool(rig.store).execute(
            DescribeMessageTool.Input(messageId = "u-1"),
            rig.ctx,
        ).data

        assertEquals("user", out.role)
        assertEquals("default", out.agent)
        assertNull(out.parentId)
        assertNull(out.tokensInput)
        assertNull(out.finish)
        assertEquals(0, out.partCount)
    }

    @Test fun missingMessageFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            DescribeMessageTool(rig.store).execute(
                DescribeMessageTool.Input(messageId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("session_query(select=messages)"), ex.message)
    }

    @Test fun compactedPartExposesCompactedAt() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendAssistant(rig.store, "a-1")
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-1"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(2_000L),
                text = "compacted content",
            ),
        )
        rig.store.markPartCompacted(PartId("p-1"), Instant.fromEpochMilliseconds(3_000L))

        val out = DescribeMessageTool(rig.store).execute(
            DescribeMessageTool.Input(messageId = "a-1"),
            rig.ctx,
        ).data
        assertEquals(3_000L, out.parts.single().compactedAtEpochMs)
    }
}
