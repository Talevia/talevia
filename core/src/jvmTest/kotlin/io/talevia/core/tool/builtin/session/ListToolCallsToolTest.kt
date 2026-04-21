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
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListToolCallsToolTest {

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

    private suspend fun seedSessionWithAssistant(store: SqlDelightSessionStore) {
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

    private suspend fun toolPart(
        store: SqlDelightSessionStore,
        partId: String,
        toolId: String,
        atMs: Long,
        state: ToolState = ToolState.Completed(
            input = JsonObject(emptyMap()),
            outputForLlm = "ok",
            data = JsonObject(emptyMap()),
        ),
    ) {
        store.upsertPart(
            Part.Tool(
                id = PartId(partId),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(atMs),
                callId = CallId("call-$partId"),
                toolId = toolId,
                state = state,
                title = "$toolId-title",
            ),
        )
    }

    @Test fun returnsToolPartsMostRecentFirst() = runTest {
        val rig = rig()
        seedSessionWithAssistant(rig.store)
        toolPart(rig.store, "p-1", "generate_image", atMs = 1_000L)
        toolPart(rig.store, "p-2", "synthesize_speech", atMs = 2_000L)
        toolPart(rig.store, "p-3", "generate_image", atMs = 3_000L)

        // Also seed a non-tool part to prove filter-type works.
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(500L),
                text = "irrelevant",
            ),
        )

        val out = ListToolCallsTool(rig.store).execute(
            ListToolCallsTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals(3, out.totalToolParts)
        // Most-recent-first.
        assertEquals(listOf("p-3", "p-2", "p-1"), out.toolCalls.map { it.partId })
    }

    @Test fun toolIdFilterScopes() = runTest {
        val rig = rig()
        seedSessionWithAssistant(rig.store)
        toolPart(rig.store, "p-1", "generate_image", atMs = 1_000L)
        toolPart(rig.store, "p-2", "synthesize_speech", atMs = 2_000L)
        toolPart(rig.store, "p-3", "generate_image", atMs = 3_000L)

        val out = ListToolCallsTool(rig.store).execute(
            ListToolCallsTool.Input(sessionId = "s-1", toolId = "generate_image"),
            rig.ctx,
        ).data

        assertEquals(2, out.totalToolParts)
        assertEquals(setOf("p-1", "p-3"), out.toolCalls.map { it.partId }.toSet())
    }

    @Test fun stateDiscriminatorIsSurfaced() = runTest {
        val rig = rig()
        seedSessionWithAssistant(rig.store)
        toolPart(rig.store, "p-done", "generate_image", atMs = 1_000L)
        toolPart(
            rig.store, "p-failed", "generate_image", atMs = 2_000L,
            state = ToolState.Failed(input = null, message = "provider 500"),
        )

        val out = ListToolCallsTool(rig.store).execute(
            ListToolCallsTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        val byId = out.toolCalls.associateBy { it.partId }
        assertEquals("completed", byId.getValue("p-done").state)
        assertEquals("error", byId.getValue("p-failed").state)
    }

    @Test fun limitCaps() = runTest {
        val rig = rig()
        seedSessionWithAssistant(rig.store)
        repeat(5) { i -> toolPart(rig.store, "p-$i", "generate_image", atMs = 1_000L + i) }

        val out = ListToolCallsTool(rig.store).execute(
            ListToolCallsTool.Input(sessionId = "s-1", limit = 2),
            rig.ctx,
        ).data

        assertEquals(5, out.totalToolParts)
        assertEquals(2, out.returnedToolParts)
        assertEquals(listOf("p-4", "p-3"), out.toolCalls.map { it.partId })
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ListToolCallsTool(rig.store).execute(
                ListToolCallsTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun emptySessionReturnsZero() = runTest {
        val rig = rig()
        seedSessionWithAssistant(rig.store)
        val out = ListToolCallsTool(rig.store).execute(
            ListToolCallsTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        assertEquals(0, out.totalToolParts)
        assertTrue(out.toolCalls.isEmpty())
    }
}
