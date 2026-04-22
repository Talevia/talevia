package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `select=spend` on [ProjectQueryTool]: aggregates lockfile entries,
 * rolls costCents, breaks down by toolId + sessionId, and keeps unknown-cost
 * entries out of the total while surfacing them separately.
 */
class ProjectQuerySpendTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private fun entry(
        hash: String,
        tool: String,
        costCents: Long?,
        sessionId: String? = "s",
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
    ): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = emptyList()),
                assets = emptyList(),
                lockfile = Lockfile(entries = entries),
            ),
        )
        return store to pid
    }

    @Test fun spendSumsKnownCostsAndTracksUnknown() = runTest {
        val (store, pid) = fixture(
            listOf(
                entry("a", "generate_image", costCents = 4L),
                entry("b", "generate_image", costCents = 4L),
                entry("c", "synthesize_speech", costCents = 15L),
                entry("d", "generate_music", costCents = null), // unknown
                entry("e", "upscale_asset", costCents = 5L, sessionId = "s2"),
            ),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "spend"),
            ctx(),
        ).data

        assertEquals("spend", out.select)
        assertEquals(1, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.SpendSummaryRow.serializer()),
            out.rows,
        )
        val row = rows.single()
        assertEquals(5, row.entryCount)
        assertEquals(4, row.knownCostEntries)
        assertEquals(1, row.unknownCostEntries)
        // 4 + 4 + 15 + 5 = 28 cents; the null does NOT contribute.
        assertEquals(28L, row.totalCostCents)
        assertEquals(
            mapOf(
                "generate_image" to 8L,
                "synthesize_speech" to 15L,
                "upscale_asset" to 5L,
            ),
            row.byTool,
        )
        // Session "s" contributed 4+4+15=23¢; session "s2" contributed 5¢.
        assertEquals(
            mapOf("s" to 23L, "s2" to 5L),
            row.bySession,
        )
        assertEquals(mapOf("generate_music" to 1), row.unknownByTool)
    }

    @Test fun spendOnEmptyLockfileYieldsZero() = runTest {
        val (store, pid) = fixture(emptyList())
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "spend"),
            ctx(),
        ).data
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.SpendSummaryRow.serializer()),
            out.rows,
        ).single()
        assertEquals(0L, row.totalCostCents)
        assertEquals(0, row.entryCount)
        assertEquals(0, row.knownCostEntries)
        assertEquals(0, row.unknownCostEntries)
        assertTrue(row.byTool.isEmpty())
        assertTrue(row.bySession.isEmpty())
    }

    @Test fun spendIgnoresZeroAsAValidCost() = runTest {
        // 0L should be distinguished from null: it counts as a known-cost entry
        // that contributes 0. Forward-compat for "free tiers".
        val (store, pid) = fixture(
            listOf(entry("a", "generate_image", costCents = 0L)),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "spend"),
            ctx(),
        ).data
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.SpendSummaryRow.serializer()),
            out.rows,
        ).single()
        assertEquals(0L, row.totalCostCents)
        assertEquals(1, row.knownCostEntries)
        assertEquals(0, row.unknownCostEntries)
    }
}
