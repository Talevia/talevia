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
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportSessionToolTest {

    private data class Rig(
        val store: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(dispatchSessionId: String = "s"): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val ctx = ToolContext(
            sessionId = SessionId(dispatchSessionId),
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
        id: String = "sx",
        projectId: String = "p",
        title: String = "test-session",
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId(projectId),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        store.createSession(s)
        return s
    }

    @Test fun envelopeIncludesSessionMessagesAndParts() = runTest {
        val rig = rig()
        seedSession(rig.store)
        // One user + one assistant message, with one text part.
        val userMsg = Message.User(
            id = MessageId("u1"),
            sessionId = SessionId("sx"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
            agent = "default",
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        rig.store.appendMessage(userMsg)
        val asstMsg = Message.Assistant(
            id = MessageId("a1"),
            sessionId = SessionId("sx"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_002_000L),
            parentId = MessageId("u1"),
            model = ModelRef("anthropic", "claude-opus-4-7"),
            tokens = TokenUsage(input = 10, output = 20),
            finish = FinishReason.STOP,
        )
        rig.store.appendMessage(asstMsg)
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("a1"),
                sessionId = SessionId("sx"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_002_500L),
                text = "Hello from the test.",
            ),
        )

        val out = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "sx"),
            rig.ctx,
        ).data

        assertEquals("sx", out.sessionId)
        assertEquals("test-session", out.title)
        assertEquals("p", out.projectId)
        assertEquals(ExportSessionTool.FORMAT_VERSION, out.formatVersion)
        assertEquals(2, out.messageCount)
        assertEquals(1, out.partCount)
        assertTrue(out.envelope.isNotBlank())

        // Round-trip: decode envelope and verify shape.
        val decoded = JsonConfig.default.decodeFromString(
            SessionEnvelope.serializer(),
            out.envelope,
        )
        assertEquals(ExportSessionTool.FORMAT_VERSION, decoded.formatVersion)
        assertEquals(SessionId("sx"), decoded.session.id)
        assertEquals("test-session", decoded.session.title)
        assertEquals(2, decoded.messages.size)
        assertEquals(MessageId("u1"), decoded.messages[0].id)
        assertEquals(MessageId("a1"), decoded.messages[1].id)
        assertEquals(1, decoded.parts.size)
        val textPart = decoded.parts.single() as Part.Text
        assertEquals("Hello from the test.", textPart.text)
    }

    @Test fun emptySessionRoundTripsCleanly() = runTest {
        val rig = rig()
        seedSession(rig.store, "sempty")
        val out = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "sempty"),
            rig.ctx,
        ).data
        assertEquals(0, out.messageCount)
        assertEquals(0, out.partCount)
        val decoded = JsonConfig.default.decodeFromString(SessionEnvelope.serializer(), out.envelope)
        assertEquals(SessionId("sempty"), decoded.session.id)
        assertTrue(decoded.messages.isEmpty())
        assertTrue(decoded.parts.isEmpty())
    }

    @Test fun missingSessionFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ExportSessionTool(rig.store).execute(
                ExportSessionTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun sessionIdDefaultsFromContext() = runTest {
        val rig = rig(dispatchSessionId = "sctx")
        seedSession(rig.store, "sctx")
        val out = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(),
            rig.ctx,
        ).data
        assertEquals("sctx", out.sessionId)
    }

    @Test fun prettyPrintProducesLargerEnvelope() = runTest {
        val rig = rig()
        seedSession(rig.store, "scmp")
        rig.store.appendMessage(
            Message.User(
                id = MessageId("u1"),
                sessionId = SessionId("scmp"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        val compact = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "scmp", prettyPrint = false),
            rig.ctx,
        ).data
        val pretty = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "scmp", prettyPrint = true),
            rig.ctx,
        ).data
        assertTrue(pretty.envelope.length > compact.envelope.length)
        // Both round-trip.
        val compactDecoded = JsonConfig.default.decodeFromString(SessionEnvelope.serializer(), compact.envelope)
        val prettyDecoded = JsonConfig.default.decodeFromString(SessionEnvelope.serializer(), pretty.envelope)
        assertEquals(compactDecoded.messages.map { it.id }, prettyDecoded.messages.map { it.id })
    }

    @Test fun markdownFormatRendersHumanReadableTranscript() = runTest {
        val rig = rig()
        seedSession(rig.store, "smd", title = "Markdown demo")
        rig.store.appendMessage(
            Message.User(
                id = MessageId("u-md"),
                sessionId = SessionId("smd"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-u-md"),
                messageId = MessageId("u-md"),
                sessionId = SessionId("smd"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_001_500L),
                text = "render this thumbnail in red",
            ),
        )
        rig.store.appendMessage(
            Message.Assistant(
                id = MessageId("a-md"),
                sessionId = SessionId("smd"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_002_000L),
                parentId = MessageId("u-md"),
                model = ModelRef("anthropic", "claude-opus-4-7"),
                tokens = TokenUsage(input = 10, output = 20),
                finish = FinishReason.STOP,
            ),
        )
        rig.store.upsertPart(
            Part.Reasoning(
                id = PartId("p-r"),
                messageId = MessageId("a-md"),
                sessionId = SessionId("smd"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_002_100L),
                text = "I should call generate_image with red as the prompt.",
            ),
        )
        rig.store.upsertPart(
            Part.Text(
                id = PartId("p-a-md"),
                messageId = MessageId("a-md"),
                sessionId = SessionId("smd"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_002_500L),
                text = "Here's a red thumbnail.",
            ),
        )

        val out = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "smd", format = "markdown"),
            rig.ctx,
        ).data

        assertEquals("markdown", out.format)
        assertEquals("talevia-session-export-md-v1", out.formatVersion)
        val md = out.envelope
        // Header carries the title + ids verbatim.
        assertTrue(md.startsWith("# Session: Markdown demo"), "header missing title; got: ${md.take(80)}")
        assertTrue("- **id**: `smd`" in md)
        assertTrue("- **project**: `p`" in md)
        assertTrue("- **format**: talevia-session-export-md-v1" in md)
        // User + assistant headers are present.
        assertTrue(Regex("(?m)^## user · ").containsMatchIn(md))
        assertTrue(
            Regex("(?m)^## assistant \\(claude-opus-4-7\\) · ").containsMatchIn(md),
            "assistant header should embed model id",
        )
        // Body text reaches the output.
        assertTrue("render this thumbnail in red" in md)
        assertTrue("Here's a red thumbnail." in md)
        // Reasoning becomes a callout.
        assertTrue(
            "> [!REASONING]" in md && "> I should call generate_image" in md,
            "reasoning callout missing",
        )
    }

    @Test fun markdownFormatFoldsToolCallsAsCallouts() = runTest {
        val rig = rig()
        seedSession(rig.store, "stool")
        rig.store.appendMessage(
            Message.User(
                id = MessageId("u-tool"),
                sessionId = SessionId("stool"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        rig.store.appendMessage(
            Message.Assistant(
                id = MessageId("a-tool"),
                sessionId = SessionId("stool"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_002_000L),
                parentId = MessageId("u-tool"),
                model = ModelRef("anthropic", "claude-opus-4-7"),
                tokens = TokenUsage(input = 10, output = 5),
                finish = FinishReason.TOOL_CALLS,
            ),
        )
        rig.store.upsertPart(
            Part.Tool(
                id = PartId("p-tool"),
                messageId = MessageId("a-tool"),
                sessionId = SessionId("stool"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_002_500L),
                callId = CallId("call-abc123"),
                toolId = "echo",
                state = io.talevia.core.session.ToolState.Completed(
                    input = kotlinx.serialization.json.buildJsonObject {
                        put("text", kotlinx.serialization.json.JsonPrimitive("hi"))
                    },
                    outputForLlm = "hi",
                    data = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
            ),
        )

        val md = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "stool", format = "md"),
            rig.ctx,
        ).data.envelope

        assertTrue("> [!TOOL] `echo`" in md, "tool callout header missing")
        assertTrue("`abc123`" in md, "callId tail missing")
        assertTrue("completed" in md, "state label missing")
        assertTrue("> ```json" in md, "input block missing")
        assertTrue("> **output**" in md, "output label missing")
    }

    @Test fun unknownFormatFallsBackToJson() = runTest {
        // Defensive contract: a typo in `format` must not silently strip
        // the portable wire shape — fallback emits the JSON envelope so
        // downstream import_session can still consume it.
        val rig = rig()
        seedSession(rig.store, "sfb")
        val out = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "sfb", format = "frobnicate"),
            rig.ctx,
        ).data
        assertEquals("json", out.format)
        // Round-trips as JSON envelope.
        val decoded = JsonConfig.default.decodeFromString(SessionEnvelope.serializer(), out.envelope)
        assertEquals(SessionId("sfb"), decoded.session.id)
    }

    @Test fun emptySessionMarkdownIsValid() = runTest {
        val rig = rig()
        seedSession(rig.store, "sempty-md", title = "empty")
        val md = ExportSessionTool(rig.store).execute(
            ExportSessionTool.Input(sessionId = "sempty-md", format = "markdown"),
            rig.ctx,
        ).data.envelope
        assertTrue(md.startsWith("# Session: empty"))
        assertTrue("(empty session — no messages yet)" in md)
    }
}
