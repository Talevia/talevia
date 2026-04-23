package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.SpendSummaryRow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `select=spend` on [SessionQueryTool]: walks the session's bound
 * project's lockfile, filters entries by stamped sessionId, rolls costs,
 * reports unknown entries separately.
 */
class SessionQuerySpendTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun entry(
        hash: String,
        tool: String,
        costCents: Long?,
        sessionId: String?,
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
        sessionId = sessionId,
    )

    private suspend fun fixture(
        entries: List<LockfileEntry>,
        bindProjectId: String = "p",
    ): Triple<SqlDelightSessionStore, FileProjectStore, SessionId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, EventBus())
        val projects = ProjectStoreTestKit.create()
        val pid = ProjectId(bindProjectId)
        projects.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList()),
                assets = emptyList(),
                lockfile = Lockfile(entries = entries),
            ),
        )
        val sid = SessionId("s-focus")
        sessions.createSession(
            Session(
                id = sid,
                projectId = pid,
                title = "t",
                parentId = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
                archived = false,
            ),
        )
        return Triple(sessions, projects, sid)
    }

    @Test fun spendAttributesEntriesByStampedSessionId() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf(
                entry("a", "generate_image", 4L, sessionId = sid()),
                entry("b", "synthesize_speech", 15L, sessionId = sid()),
                entry("c", "generate_image", 4L, sessionId = "other-session"),
                entry("d", "generate_music", null, sessionId = sid()),
            ),
        )
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend", sessionId = sid.value),
            ctx(),
        ).data

        assertEquals("spend", out.select)
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SpendSummaryRow.serializer()),
            out.rows,
        ).single()
        // Matching entries: a (4), b (15), d (null)
        assertEquals(3, row.entryCount)
        assertEquals(2, row.knownCostEntries)
        assertEquals(1, row.unknownCostEntries)
        assertEquals(19L, row.totalCostCents)
        assertEquals(
            mapOf("generate_image" to 4L, "synthesize_speech" to 15L),
            row.byTool,
        )
        assertEquals(mapOf("generate_music" to 1), row.unknownByTool)
        assertTrue(row.projectResolved)
    }

    @Test fun spendOnSessionWithNoAigcReportsZero() = runTest {
        val (sessions, projects, sid) = fixture(emptyList())
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend", sessionId = sid.value),
            ctx(),
        ).data
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SpendSummaryRow.serializer()),
            out.rows,
        ).single()
        assertEquals(0L, row.totalCostCents)
        assertEquals(0, row.entryCount)
        assertTrue(row.byTool.isEmpty())
        assertTrue(row.projectResolved)
    }

    @Test fun spendWithoutProjectStoreFlagsNotResolved() = runTest {
        val (sessions, _, sid) = fixture(emptyList())
        val out = SessionQueryTool(sessions, null, null).execute(
            SessionQueryTool.Input(select = "spend", sessionId = sid.value),
            ctx(),
        ).data
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SpendSummaryRow.serializer()),
            out.rows,
        ).single()
        assertEquals(0L, row.totalCostCents)
        assertFalse(row.projectResolved, "no project store wired → projectResolved=false")
    }

    @Test fun spendRejectsMissingSessionId() = runTest {
        val (sessions, projects, _) = fixture(emptyList())
        assertFailsWith<IllegalStateException> {
            SessionQueryTool(sessions, null, projects).execute(
                SessionQueryTool.Input(select = "spend"),
                ctx(),
            )
        }
    }

    private fun sid(): String = "s-focus"
}
