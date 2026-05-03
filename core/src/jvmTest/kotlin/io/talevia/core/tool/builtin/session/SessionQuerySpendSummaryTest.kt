package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
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
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.session.query.SessionSpendSummaryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `select=spend_summary` on [SessionQueryTool] — M2 criterion 4
 * "成本可见". Walks the session's bound project's lockfile, filters by
 * stamped sessionId, groups by `provenance.providerId`, and returns a
 * per-provider breakdown with nullable token/USD fields.
 */
class SessionQuerySpendSummaryTest {

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
        provider: String,
        costCents: Long?,
        sessionId: String?,
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = tool,
        assetId = AssetId("asset-$hash"),
        provenance = GenerationProvenance(
            providerId = provider,
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
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
                archived = false,
            ),
        )
        return Triple(sessions, projects, sid)
    }

    @Test fun spendSummaryOnEmptySessionReportsZeros() = runTest {
        val (sessions, projects, sid) = fixture(emptyList())
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data

        assertEquals("spend_summary", out.select)
        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        assertEquals(0, row.totalCalls)
        assertEquals(0, row.unknownCostCalls)
        assertNull(row.estimatedUsdCents, "no calls → no known USD → null")
        assertNull(row.totalTokens, "tokens not yet plumbed → null (not 0)")
        assertTrue(row.perProviderBreakdown.isEmpty())
        assertTrue(row.projectResolved)
    }

    @Test fun spendSummarySingleCallSingleProvider() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf(entry("a", "generate_image", "openai", 4L, sessionId = sid())),
        )
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data

        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        assertEquals(1, row.totalCalls)
        assertEquals(0, row.unknownCostCalls)
        assertEquals(4.0, row.estimatedUsdCents)
        assertEquals(1, row.perProviderBreakdown.size)
        val only = row.perProviderBreakdown.single()
        assertEquals("openai", only.providerId)
        assertEquals(1, only.calls)
        assertEquals(4.0, only.usdCents)
        assertEquals(0, only.unknownCalls)
    }

    @Test fun spendSummaryMultipleProvidersAggregatesCorrectly() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf(
                entry("a", "generate_image", "openai", 4L, sessionId = sid()),
                entry("b", "generate_image", "openai", 6L, sessionId = sid()),
                entry("c", "generate_music", "replicate", 10L, sessionId = sid()),
                entry("d", "generate_image", "openai", 4L, sessionId = "other"),
            ),
        )
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data

        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        // The "other" session's entry is excluded — only 3 of 4 counted.
        assertEquals(3, row.totalCalls)
        assertEquals(0, row.unknownCostCalls)
        assertEquals(20.0, row.estimatedUsdCents) // 4 + 6 + 10
        assertEquals(2, row.perProviderBreakdown.size)

        // Sorted by providerId — openai before replicate.
        val (oa, rep) = row.perProviderBreakdown[0] to row.perProviderBreakdown[1]
        assertEquals("openai", oa.providerId)
        assertEquals(2, oa.calls)
        assertEquals(10.0, oa.usdCents)
        assertEquals(0, oa.unknownCalls)
        assertEquals("replicate", rep.providerId)
        assertEquals(1, rep.calls)
        assertEquals(10.0, rep.usdCents)
    }

    @Test fun spendSummaryAccumulatesOnRepeatCalls() = runTest {
        // M2 criterion 4: "同一 session 反复调用 `generate_image` 能看到数字累加"
        val (sessions, projects, sid) = fixture(
            listOf(
                entry("a", "generate_image", "openai", 4L, sessionId = sid()),
                entry("b", "generate_image", "openai", 4L, sessionId = sid()),
                entry("c", "generate_image", "openai", 4L, sessionId = sid()),
            ),
        )
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        assertEquals(3, row.totalCalls)
        assertEquals(12.0, row.estimatedUsdCents, "3 × 4 cents should accumulate to 12")
    }

    @Test fun spendSummaryUnknownCostKeepsCountsButNullsUsd() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf(
                entry("a", "generate_music", "replicate", null, sessionId = sid()),
                entry("b", "generate_music", "replicate", null, sessionId = sid()),
            ),
        )
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data

        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        assertEquals(2, row.totalCalls)
        assertEquals(2, row.unknownCostCalls)
        assertNull(row.estimatedUsdCents, "zero priced calls → null not 0.0 (per §3a #4)")
        assertEquals(1, row.perProviderBreakdown.size)
        val rep = row.perProviderBreakdown.single()
        assertEquals("replicate", rep.providerId)
        assertEquals(2, rep.calls)
        assertEquals(2, rep.unknownCalls)
        assertNull(rep.usdCents)
    }

    @Test fun spendSummaryMixedKnownAndUnknownInSameProvider() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf(
                entry("a", "generate_image", "openai", 4L, sessionId = sid()),
                entry("b", "generate_music", "openai", null, sessionId = sid()),
            ),
        )
        val out = SessionQueryTool(sessions, null, projects).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        assertEquals(2, row.totalCalls)
        assertEquals(1, row.unknownCostCalls)
        assertEquals(4.0, row.estimatedUsdCents)
        val only = row.perProviderBreakdown.single()
        assertEquals("openai", only.providerId)
        assertEquals(2, only.calls)
        assertEquals(1, only.unknownCalls)
        val cents = only.usdCents
        assertNotNull(cents)
        assertEquals(4.0, cents, "partial bucket still reports the known subset")
    }

    @Test fun spendSummaryWithoutProjectStoreFlagsNotResolved() = runTest {
        val (sessions, _, sid) = fixture(emptyList())
        val out = SessionQueryTool(sessions, null, null).execute(
            SessionQueryTool.Input(select = "spend_summary", sessionId = sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(SessionSpendSummaryRow.serializer()).single()
        assertEquals(0, row.totalCalls)
        assertFalse(row.projectResolved, "no project store wired → projectResolved=false")
    }

    @Test fun spendSummaryRejectsMissingSessionId() = runTest {
        val (sessions, projects, _) = fixture(emptyList())
        assertFailsWith<IllegalStateException> {
            SessionQueryTool(sessions, null, projects).execute(
                SessionQueryTool.Input(select = "spend_summary"),
                ctx(),
            )
        }
    }

    @Test fun spendSummaryDispatchesViaRawJson() = runTest {
        // Round-trip via the raw-JSON dispatch path (the surface the Agent
        // actually uses) to catch serialization misses that typed-input
        // tests wouldn't.
        val (sessions, projects, sid) = fixture(
            listOf(entry("a", "generate_image", "openai", 4L, sessionId = sid())),
        )
        val registry = ToolRegistry().apply {
            register(SessionQueryTool(sessions, null, projects))
        }
        val rawInput = buildJsonObject {
            put("select", "spend_summary")
            put("sessionId", sid.value)
        }
        val result = registry["session_query"]!!.dispatch(rawInput, ctx()).data as SessionQueryTool.Output
        assertEquals("spend_summary", result.select)
        assertEquals(1, result.total)
        assertEquals(1, result.returned)
    }

    private fun sid(): String = "s-focus"
}
