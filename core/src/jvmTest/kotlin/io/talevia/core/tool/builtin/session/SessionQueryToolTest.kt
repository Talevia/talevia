package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionQueryToolTest {

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

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        id: String,
        projectId: String = "p",
        title: String = id,
        parentId: String? = null,
        archived: Boolean = false,
        updatedAtEpochMs: Long = 1_700_000_000_000L,
    ): Session {
        val now = Instant.fromEpochMilliseconds(updatedAtEpochMs)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId(projectId),
            title = title,
            parentId = parentId?.let { SessionId(it) },
            createdAt = now,
            updatedAt = now,
            archived = archived,
        )
        store.createSession(s)
        return s
    }

    private suspend fun addUserMessage(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        epochMs: Long = 1_700_000_001_000L,
    ): Message.User {
        val m = Message.User(
            id = MessageId(messageId),
            sessionId = SessionId(sessionId),
            createdAt = Instant.fromEpochMilliseconds(epochMs),
            agent = "default",
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        store.appendMessage(m)
        return m
    }

    private suspend fun addAssistantMessage(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        parentId: String,
        tokensIn: Long = 10,
        tokensOut: Long = 20,
        finish: FinishReason? = FinishReason.STOP,
        epochMs: Long = 1_700_000_002_000L,
    ): Message.Assistant {
        val m = Message.Assistant(
            id = MessageId(messageId),
            sessionId = SessionId(sessionId),
            createdAt = Instant.fromEpochMilliseconds(epochMs),
            parentId = MessageId(parentId),
            model = ModelRef("anthropic", "claude-opus-4-7"),
            tokens = TokenUsage(input = tokensIn, output = tokensOut),
            finish = finish,
        )
        store.appendMessage(m)
        return m
    }

    // -------- select=sessions --------

    @Test fun sessionsListsByProject() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1", projectId = "p-a", updatedAtEpochMs = 1_700_000_100_000L)
        seedSession(rig.store, "s2", projectId = "p-a", updatedAtEpochMs = 1_700_000_200_000L)
        seedSession(rig.store, "s3", projectId = "p-b", updatedAtEpochMs = 1_700_000_300_000L)

        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "sessions", projectId = "p-a"),
            rig.ctx,
        ).data

        assertEquals("sessions", out.select)
        assertEquals(2, out.total)
        assertEquals(2, out.returned)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.SessionRow.serializer()),
            out.rows,
        )
        // Most recent (updatedAt desc) first.
        assertEquals("s2", rows[0].id)
        assertEquals("s1", rows[1].id)
    }

    @Test fun sessionsExcludesArchivedByDefault() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        seedSession(rig.store, "s2", archived = true)

        val noArchived = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "sessions"),
            rig.ctx,
        ).data
        assertEquals(1, noArchived.total)

        val withArchived = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "sessions", includeArchived = true),
            rig.ctx,
        ).data
        assertEquals(2, withArchived.total)
    }

    @Test fun sessionsRejectsSessionId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "sessions", sessionId = "s1"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("sessionId"), ex.message)
    }

    // -------- select=messages --------

    @Test fun messagesListsWithRoleFilter() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        addUserMessage(rig.store, "s1", "u1", epochMs = 1_700_000_100_000L)
        addAssistantMessage(rig.store, "s1", "a1", parentId = "u1", epochMs = 1_700_000_200_000L)
        addUserMessage(rig.store, "s1", "u2", epochMs = 1_700_000_300_000L)

        val all = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "messages", sessionId = "s1"),
            rig.ctx,
        ).data
        assertEquals(3, all.total)

        val users = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "messages", sessionId = "s1", role = "user"),
            rig.ctx,
        ).data
        assertEquals(2, users.total)
        val userRows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.MessageRow.serializer()),
            users.rows,
        )
        assertTrue(userRows.all { it.role == "user" })
    }

    @Test fun messagesRejectsInvalidRole() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "messages", sessionId = "s1", role = "system"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("role"), ex.message)
    }

    @Test fun messagesMissingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "messages", sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun messagesRequiresSessionId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "messages"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires sessionId"), ex.message)
    }

    // -------- select=parts --------

    @Test fun partsListsWithKindFilter() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        val um = addUserMessage(rig.store, "s1", "u1")
        rig.store.upsertPart(
            Part.Text(
                id = PartId("pt-1"),
                messageId = um.id,
                sessionId = um.sessionId,
                createdAt = um.createdAt,
                text = "hello world",
            ),
        )
        val am = addAssistantMessage(rig.store, "s1", "a1", parentId = "u1")
        rig.store.upsertPart(
            Part.Tool(
                id = PartId("pt-2"),
                messageId = am.id,
                sessionId = am.sessionId,
                createdAt = am.createdAt,
                callId = CallId("call-1"),
                toolId = "generate_image",
                state = ToolState.Pending,
            ),
        )

        val all = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "parts", sessionId = "s1"),
            rig.ctx,
        ).data
        assertEquals(2, all.total)

        val onlyText = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "parts", sessionId = "s1", kind = "text"),
            rig.ctx,
        ).data
        assertEquals(1, onlyText.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.PartRow.serializer()),
            onlyText.rows,
        )
        assertEquals("text", rows[0].kind)
        assertTrue(rows[0].preview.startsWith("hello"))
    }

    @Test fun partsRejectsUnknownKind() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "parts", sessionId = "s1", kind = "banana"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("kind"), ex.message)
    }

    // -------- select=forks --------

    @Test fun forksListsImmediateChildren() = runTest {
        val rig = rig()
        seedSession(rig.store, "s-parent")
        seedSession(rig.store, "s-child-1", parentId = "s-parent")
        seedSession(rig.store, "s-child-2", parentId = "s-parent")
        seedSession(rig.store, "s-unrelated")

        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "forks", sessionId = "s-parent"),
            rig.ctx,
        ).data
        assertEquals(2, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.ForkRow.serializer()),
            out.rows,
        )
        assertEquals(setOf("s-child-1", "s-child-2"), rows.map { it.id }.toSet())
    }

    @Test fun forksEmptyForChildlessSession() = runTest {
        val rig = rig()
        seedSession(rig.store, "s-lonely")
        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "forks", sessionId = "s-lonely"),
            rig.ctx,
        ).data
        assertEquals(0, out.total)
    }

    // -------- select=ancestors --------

    @Test fun ancestorsWalksToRoot() = runTest {
        val rig = rig()
        seedSession(rig.store, "s-root")
        seedSession(rig.store, "s-mid", parentId = "s-root")
        seedSession(rig.store, "s-leaf", parentId = "s-mid")

        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "ancestors", sessionId = "s-leaf"),
            rig.ctx,
        ).data
        assertEquals(2, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.AncestorRow.serializer()),
            out.rows,
        )
        assertEquals(listOf("s-mid", "s-root"), rows.map { it.id })
    }

    @Test fun ancestorsEmptyForRoot() = runTest {
        val rig = rig()
        seedSession(rig.store, "s-root")
        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "ancestors", sessionId = "s-root"),
            rig.ctx,
        ).data
        assertEquals(0, out.total)
    }

    // -------- select=tool_calls --------

    @Test fun toolCallsFilterByToolId() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        val am = addAssistantMessage(rig.store, "s1", "a1", parentId = "u1")
        rig.store.upsertPart(
            Part.Tool(
                id = PartId("pt-1"),
                messageId = am.id,
                sessionId = am.sessionId,
                createdAt = am.createdAt,
                callId = CallId("c-1"),
                toolId = "generate_image",
                state = ToolState.Pending,
            ),
        )
        rig.store.upsertPart(
            Part.Tool(
                id = PartId("pt-2"),
                messageId = am.id,
                sessionId = am.sessionId,
                createdAt = am.createdAt,
                callId = CallId("c-2"),
                toolId = "synthesize_speech",
                state = ToolState.Pending,
            ),
        )

        val all = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "tool_calls", sessionId = "s1"),
            rig.ctx,
        ).data
        assertEquals(2, all.total)

        val onlyImage = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "tool_calls", sessionId = "s1", toolId = "generate_image"),
            rig.ctx,
        ).data
        assertEquals(1, onlyImage.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.ToolCallRow.serializer()),
            onlyImage.rows,
        )
        assertEquals("generate_image", rows[0].toolId)
    }

    // -------- cross-select validation --------

    @Test fun invalidSelectFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "goats", sessionId = "s1"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("select must be one of"), ex.message)
    }

    @Test fun misappliedFilterFailsLoud() = runTest {
        val rig = rig()
        seedSession(rig.store, "s1")
        // role applies to messages only — passing it to tool_calls must fail.
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "tool_calls", sessionId = "s1", role = "user"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("role"), ex.message)
    }

    @Test fun limitAndOffsetPage() = runTest {
        val rig = rig()
        // Seed 5 sessions with monotonically increasing updatedAt so sort order is deterministic.
        repeat(5) { i ->
            seedSession(rig.store, "s-$i", updatedAtEpochMs = 1_700_000_100_000L + i * 1_000L)
        }
        val firstPage = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "sessions", limit = 2, offset = 0),
            rig.ctx,
        ).data
        assertEquals(5, firstPage.total)
        assertEquals(2, firstPage.returned)

        val secondPage = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "sessions", limit = 2, offset = 2),
            rig.ctx,
        ).data
        assertEquals(5, secondPage.total)
        assertEquals(2, secondPage.returned)

        assertFalse(
            firstPage.rows.toString() == secondPage.rows.toString(),
            "pages should not overlap",
        )
    }

    // -------- select=compactions --------

    @Test fun compactionsAggregatesFullSummaryMostRecentFirst() = runTest {
        val rig = rig()
        seedSession(rig.store, "sc")
        addUserMessage(rig.store, "sc", "u1")
        addAssistantMessage(rig.store, "sc", "a1", parentId = "u1")
        addAssistantMessage(rig.store, "sc", "a2", parentId = "u1")

        // Two compactions on the same session, different summaries + ranges.
        val first = Part.Compaction(
            id = PartId("cp-1"),
            messageId = MessageId("a1"),
            sessionId = SessionId("sc"),
            createdAt = Instant.fromEpochMilliseconds(1_000L),
            replacedFromMessageId = MessageId("u1"),
            replacedToMessageId = MessageId("a1"),
            summary = "first pass: compacted 2 msgs into a one-paragraph summary",
        )
        val second = Part.Compaction(
            id = PartId("cp-2"),
            messageId = MessageId("a2"),
            sessionId = SessionId("sc"),
            createdAt = Instant.fromEpochMilliseconds(5_000L),
            replacedFromMessageId = MessageId("a1"),
            replacedToMessageId = MessageId("a2"),
            summary = "second pass: captured the newer turn, prior summary still intact",
        )
        rig.store.upsertPart(first)
        rig.store.upsertPart(second)

        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "compactions", sessionId = "sc"),
            rig.ctx,
        ).data

        assertEquals(2, out.total)
        assertEquals(2, out.returned)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.CompactionRow.serializer()),
            out.rows,
        )
        // Most recent first.
        assertEquals(listOf("cp-2", "cp-1"), rows.map { it.partId })
        // Full summary, NOT truncated (unlike select=parts preview cap at 80 chars).
        assertEquals(second.summary, rows[0].summaryText)
        assertEquals(first.summary, rows[1].summaryText)
        // Message-range metadata present.
        assertEquals("a1", rows[0].fromMessageId)
        assertEquals("a2", rows[0].toMessageId)
    }

    @Test fun compactionsEmptyWhenSessionHasNoCompactionParts() = runTest {
        val rig = rig()
        seedSession(rig.store, "s-fresh")
        val result = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "compactions", sessionId = "s-fresh"),
            rig.ctx,
        )
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertTrue(result.outputForLlm.contains("not been compacted"), result.outputForLlm)
    }

    @Test fun compactionsRequiresSessionId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "compactions"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires sessionId"), ex.message)
    }

    // -------- select=session_metadata (absorbed describe_session) --------

    @Test fun sessionMetadataReturnsCountsAndTotals() = runTest {
        val rig = rig()
        seedSession(rig.store, "sm1", projectId = "proj-x", updatedAtEpochMs = 1_700_000_100_000L)
        addUserMessage(rig.store, "sm1", "u1", epochMs = 1_700_000_120_000L)
        addAssistantMessage(
            rig.store,
            "sm1",
            "a1",
            parentId = "u1",
            tokensIn = 30,
            tokensOut = 50,
            epochMs = 1_700_000_140_000L,
        )
        addUserMessage(rig.store, "sm1", "u2", epochMs = 1_700_000_150_000L)
        addAssistantMessage(
            rig.store,
            "sm1",
            "a2",
            parentId = "u2",
            tokensIn = 15,
            tokensOut = 25,
            epochMs = 1_700_000_160_000L,
        )

        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "session_metadata", sessionId = "sm1"),
            rig.ctx,
        ).data
        assertEquals("session_metadata", out.select)
        assertEquals(1, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.SessionMetadataRow.serializer()),
            out.rows,
        )
        val r = rows.single()
        assertEquals("sm1", r.sessionId)
        assertEquals("proj-x", r.projectId)
        assertEquals(4, r.messageCount)
        assertEquals(2, r.userMessageCount)
        assertEquals(2, r.assistantMessageCount)
        assertEquals(45L, r.totalTokensInput)
        assertEquals(75L, r.totalTokensOutput)
        assertFalse(r.archived)
        assertFalse(r.hasCompactionPart)
        assertEquals(1_700_000_160_000L, r.latestMessageAtEpochMs)
    }

    @Test fun sessionMetadataEmptySessionFallsBackToCreatedAt() = runTest {
        val rig = rig()
        seedSession(rig.store, "sm-empty", updatedAtEpochMs = 1_700_000_000_000L)
        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "session_metadata", sessionId = "sm-empty"),
            rig.ctx,
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.SessionMetadataRow.serializer()),
            out.rows,
        )
        val r = rows.single()
        assertEquals(0, r.messageCount)
        // Empty session → latestMessageAt defaults to session.createdAt.
        assertEquals(1_700_000_000_000L, r.latestMessageAtEpochMs)
        assertEquals(0L, r.totalTokensInput)
        assertEquals(0L, r.totalTokensOutput)
    }

    @Test fun sessionMetadataMissingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "session_metadata", sessionId = "no-such"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"), ex.message)
    }

    @Test fun sessionMetadataRequiresSessionId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "session_metadata"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires sessionId"), ex.message)
    }

    // -------- select=message (absorbed describe_message) --------

    @Test fun messageDrillDownReturnsRoleAndParts() = runTest {
        val rig = rig()
        seedSession(rig.store, "sm-msg")
        addUserMessage(rig.store, "sm-msg", "u1")
        val asst = addAssistantMessage(
            rig.store,
            "sm-msg",
            "a1",
            parentId = "u1",
            tokensIn = 12,
            tokensOut = 34,
            finish = FinishReason.STOP,
        )
        // Put a text part and a tool part on the assistant message so the preview
        // surface is exercised. `upsertPart` both inserts and updates — the session
        // store uses one verb for both.
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-text-1"),
                messageId = asst.id,
                sessionId = asst.sessionId,
                createdAt = asst.createdAt,
                text = "hello world — first part",
            ),
        )
        rig.store.upsertPart(
            Part.Tool(
                id = PartId("p-tool-1"),
                messageId = asst.id,
                sessionId = asst.sessionId,
                createdAt = asst.createdAt,
                toolId = "generate_image",
                callId = CallId("call-1"),
                state = ToolState.Completed(
                    input = kotlinx.serialization.json.JsonObject(emptyMap()),
                    outputForLlm = "done",
                    data = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
            ),
        )

        val out = SessionQueryTool(rig.store).execute(
            SessionQueryTool.Input(select = "message", messageId = "a1"),
            rig.ctx,
        ).data
        assertEquals("message", out.select)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.MessageDetailRow.serializer()),
            out.rows,
        )
        val r = rows.single()
        assertEquals("a1", r.messageId)
        assertEquals("assistant", r.role)
        assertEquals(12L, r.tokensInput)
        assertEquals(34L, r.tokensOutput)
        assertEquals(2, r.partCount)
        assertTrue(r.parts.any { it.kind == "text" && it.preview.startsWith("hello") })
        assertTrue(r.parts.any { it.kind == "tool" && it.preview == "generate_image[completed]" })
    }

    @Test fun messageDrillDownMissingMessageFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "message", messageId = "nope"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"), ex.message)
    }

    @Test fun messageDrillDownRequiresMessageId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                SessionQueryTool.Input(select = "message"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires messageId"), ex.message)
    }

    @Test fun messageIdOnOtherSelectFailsLoud() = runTest {
        val rig = rig()
        seedSession(rig.store, "sm-reject")
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                // messageId is drill-down-only — applying it to a list query should fail.
                SessionQueryTool.Input(select = "messages", sessionId = "sm-reject", messageId = "m-oops"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("messageId"), ex.message)
    }
}
