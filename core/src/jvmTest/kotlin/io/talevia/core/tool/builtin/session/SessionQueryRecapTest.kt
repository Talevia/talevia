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
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.SessionRecapRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `select=recap` on [SessionQueryTool]: single-row session
 * orientation summary collapsing turn count, token totals, lockfile
 * cost, distinct tool ids, last model, first/last timestamps.
 */
class SessionQueryRecapTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private data class Rig(
        val sessions: SqlDelightSessionStore,
        val projects: FileProjectStore,
        val sid: SessionId,
        val pid: ProjectId,
    )

    private suspend fun rig(
        entries: List<LockfileEntry> = emptyList(),
    ): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, EventBus())
        val projects = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        projects.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList()),
                assets = emptyList(),
                lockfile = EagerLockfile(entries = entries),
            ),
        )
        val sid = SessionId("s-focus")
        sessions.createSession(
            Session(
                id = sid,
                projectId = pid,
                title = "t",
                parentId = null,
                createdAt = Instant.fromEpochMilliseconds(1_000),
                updatedAt = Instant.fromEpochMilliseconds(1_000),
                archived = false,
            ),
        )
        return Rig(sessions, projects, sid, pid)
    }

    private fun lockfileEntry(
        hash: String,
        tool: String,
        costCents: Long?,
        sessionIdValue: String,
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = tool,
        assetId = AssetId("asset-$hash"),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        costCents = costCents,
        sessionId = sessionIdValue,
    )

    private suspend fun appendTurn(
        sessions: SqlDelightSessionStore,
        sid: SessionId,
        userIdSuffix: String,
        assistantIdSuffix: String,
        createdAt: Instant,
        tokens: TokenUsage,
        modelId: String,
        toolIds: List<String> = emptyList(),
    ) {
        val uid = MessageId("u-$userIdSuffix")
        sessions.appendMessage(
            Message.User(
                id = uid,
                sessionId = sid,
                createdAt = createdAt,
                agent = "default",
                model = ModelRef("anthropic", modelId),
            ),
        )
        val aid = MessageId("a-$assistantIdSuffix")
        sessions.appendMessage(
            Message.Assistant(
                id = aid,
                sessionId = sid,
                createdAt = createdAt,
                parentId = uid,
                model = ModelRef("anthropic", modelId),
                tokens = tokens,
            ),
        )
        toolIds.forEachIndexed { i, toolId ->
            sessions.upsertPart(
                Part.Tool(
                    id = PartId("t-$assistantIdSuffix-$i"),
                    messageId = aid,
                    sessionId = sid,
                    createdAt = createdAt,
                    callId = CallId("c-$assistantIdSuffix-$i"),
                    toolId = toolId,
                    state = ToolState.Pending,
                ),
            )
        }
    }

    @Test fun emptySessionReturnsZeroRow() = runTest {
        val r = rig()
        val out = SessionQueryTool(r.sessions, null, r.projects).execute(
            SessionQueryTool.Input(select = "recap", sessionId = r.sid.value),
            ctx(),
        ).data
        assertEquals(SessionQueryTool.SELECT_RECAP, out.select)
        assertEquals(1, out.total)
        val row = out.rows.decodeRowsAs(SessionRecapRow.serializer()).single()
        assertEquals(r.sid.value, row.sessionId)
        assertEquals("p", row.projectId)
        assertEquals(0, row.turnCount)
        assertEquals(0L, row.totalTokensIn)
        assertEquals(0L, row.totalTokensOut)
        assertEquals(0L, row.totalCostCents)
        assertEquals(0, row.unknownCostEntries)
        assertTrue(row.distinctToolsUsed.isEmpty())
        assertNull(row.lastModelId)
        assertNull(row.firstAtEpochMs)
        assertNull(row.lastAtEpochMs)
        assertTrue(row.projectResolved)
    }

    @Test fun multiTurnSessionAggregatesTokensToolsAndLastModel() = runTest {
        val r = rig(
            entries = listOf(
                lockfileEntry("a", "generate_image", 4L, sessionIdValue = "s-focus"),
                lockfileEntry("b", "synthesize_speech", 15L, sessionIdValue = "s-focus"),
                lockfileEntry("c", "generate_music", null, sessionIdValue = "s-focus"),
                lockfileEntry("d", "generate_image", 99L, sessionIdValue = "other"),
            ),
        )
        appendTurn(
            r.sessions, r.sid, "1", "1",
            Instant.fromEpochMilliseconds(2_000),
            TokenUsage(input = 100, output = 50),
            modelId = "claude-opus-4-7",
            toolIds = listOf("generate_image", "synthesize_speech"),
        )
        appendTurn(
            r.sessions, r.sid, "2", "2",
            Instant.fromEpochMilliseconds(5_000),
            TokenUsage(input = 200, output = 75),
            modelId = "claude-sonnet-4-6",
            toolIds = listOf("generate_image"),
        )

        val out = SessionQueryTool(r.sessions, null, r.projects).execute(
            SessionQueryTool.Input(select = "recap", sessionId = r.sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(SessionRecapRow.serializer()).single()

        assertEquals(2, row.turnCount, "two assistant messages → two turns")
        assertEquals(300L, row.totalTokensIn)
        assertEquals(125L, row.totalTokensOut)
        assertEquals(
            listOf("generate_image", "synthesize_speech"),
            row.distinctToolsUsed,
            "tool ids deduped + sorted alphabetically",
        )
        assertEquals(
            "anthropic/claude-sonnet-4-6",
            row.lastModelId,
            "last model is the most recent assistant message's model",
        )
        // firstAt is the first user message (2_000); lastAt is the last
        // event which is the second assistant message (5_000).
        assertEquals(2_000L, row.firstAtEpochMs)
        assertEquals(5_000L, row.lastAtEpochMs)
        assertEquals(19L, row.totalCostCents, "stamped cost: 4 + 15 (music has null cost)")
        assertEquals(1, row.unknownCostEntries, "music entry's null cost flagged separately")
        assertTrue(row.projectResolved)
    }

    @Test fun missingProjectStoreFlagsProjectResolvedFalse() = runTest {
        val r = rig()
        appendTurn(
            r.sessions, r.sid, "1", "1",
            Instant.fromEpochMilliseconds(2_000),
            TokenUsage(input = 10, output = 5),
            modelId = "claude-opus-4-7",
        )
        val out = SessionQueryTool(r.sessions, null, null).execute(
            SessionQueryTool.Input(select = "recap", sessionId = r.sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(SessionRecapRow.serializer()).single()
        // Token + turn aggregation works without project store...
        assertEquals(1, row.turnCount)
        assertEquals(10L, row.totalTokensIn)
        // ...but cost can't resolve.
        assertEquals(0L, row.totalCostCents)
        assertFalse(row.projectResolved)
    }

    @Test fun rejectsMissingSessionId() = runTest {
        val r = rig()
        assertFailsWith<IllegalStateException> {
            SessionQueryTool(r.sessions, null, r.projects).execute(
                SessionQueryTool.Input(select = "recap"),
                ctx(),
            )
        }
    }

    @Test fun rejectsUnknownSession() = runTest {
        val r = rig()
        assertFailsWith<IllegalStateException> {
            SessionQueryTool(r.sessions, null, r.projects).execute(
                SessionQueryTool.Input(select = "recap", sessionId = "ghost"),
                ctx(),
            )
        }
    }
}
