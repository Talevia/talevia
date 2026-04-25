package io.talevia.core.tool.builtin.provider

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.provider.query.CostHistoryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for `provider_query(select=cost_history)` — the cross-project
 * AIGC cost ledger. See [io.talevia.core.tool.builtin.provider.query.runCostHistoryQuery].
 */
class ProviderQueryCostHistoryTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun emptyRegistry(): ProviderRegistry =
        ProviderRegistry(byId = emptyMap(), default = null)

    private fun entry(
        toolId: String,
        assetId: String,
        providerId: String,
        modelId: String,
        cents: Long?,
        createdAt: Long,
        sessionId: String? = "sess1",
    ): LockfileEntry = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = null,
            seed = 1L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAt,
        ),
        costCents = cents,
        sessionId = sessionId,
    )

    private suspend fun seed(
        store: io.talevia.core.domain.ProjectStore,
        projectId: String,
        entries: List<LockfileEntry>,
    ) {
        var lockfile = Lockfile.EMPTY
        entries.forEach { lockfile = lockfile.append(it) }
        store.upsert(
            "test-$projectId",
            Project(id = ProjectId(projectId), timeline = Timeline(), lockfile = lockfile),
        )
    }

    private fun tool(store: io.talevia.core.domain.ProjectStore): ProviderQueryTool =
        ProviderQueryTool(
            emptyRegistry(),
            ProviderWarmupStats.withSupervisor(EventBus()),
            store,
        )

    @Test fun aggregatesAcrossProjectsSortedByCreatedDesc() = runTest {
        val store = ProjectStoreTestKit.create()
        seed(
            store, "p1",
            listOf(
                entry("generate_image", "a1", "openai", "gpt-image-1", 12L, createdAt = 1_000),
                entry("generate_image", "a2", "openai", "gpt-image-1", 8L, createdAt = 3_000),
            ),
        )
        seed(
            store, "p2",
            listOf(
                entry("synthesize_speech", "v1", "openai", "tts-1", 4L, createdAt = 2_000),
            ),
        )
        val out = tool(store).execute(
            ProviderQueryTool.Input(select = "cost_history"),
            ctx(),
        ).data
        assertEquals("cost_history", out.select)
        assertEquals(3, out.total)
        val rows = out.rows.decodeRowsAs(CostHistoryRow.serializer())
        assertEquals(listOf(3_000L, 2_000L, 1_000L), rows.map { it.createdAtEpochMs })
        assertEquals(setOf("p1", "p2"), rows.map { it.projectId }.toSet())
    }

    @Test fun unpricedEntriesAreFilteredOut() = runTest {
        val store = ProjectStoreTestKit.create()
        seed(
            store, "p1",
            listOf(
                entry("generate_image", "a1", "openai", "gpt-image-1", 10L, createdAt = 1_000),
                entry("generate_image", "a2", "openai", "gpt-image-1", null, createdAt = 2_000),
            ),
        )
        val out = tool(store).execute(
            ProviderQueryTool.Input(select = "cost_history"),
            ctx(),
        ).data
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(CostHistoryRow.serializer())
        assertEquals("a1", rows.single().assetId)
    }

    @Test fun sinceEpochMsTrimsAtSource() = runTest {
        val store = ProjectStoreTestKit.create()
        seed(
            store, "p1",
            listOf(
                entry("generate_image", "old", "openai", "gpt-image-1", 5L, createdAt = 1_000),
                entry("generate_image", "new", "openai", "gpt-image-1", 7L, createdAt = 5_000),
            ),
        )
        val out = tool(store).execute(
            ProviderQueryTool.Input(select = "cost_history", sinceEpochMs = 4_000),
            ctx(),
        ).data
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(CostHistoryRow.serializer())
        assertEquals("new", rows.single().assetId)
    }

    @Test fun limitCapsReturnedRows() = runTest {
        val store = ProjectStoreTestKit.create()
        val entries = (0 until 12).map { i ->
            entry("generate_image", "a$i", "openai", "gpt-image-1", 1L, createdAt = i.toLong() * 100)
        }
        seed(store, "p1", entries)
        val out = tool(store).execute(
            ProviderQueryTool.Input(select = "cost_history", limit = 3),
            ctx(),
        ).data
        assertEquals(12, out.total)
        assertEquals(3, out.returned)
        val rows = out.rows.decodeRowsAs(CostHistoryRow.serializer())
        assertEquals(listOf("a11", "a10", "a9"), rows.map { it.assetId })
    }

    @Test fun emptyStoreReturnsZeroRowsAndCleanSummary() = runTest {
        val out = tool(ProjectStoreTestKit.create()).execute(
            ProviderQueryTool.Input(select = "cost_history"),
            ctx(),
        )
        assertEquals(0, out.data.total)
        assertTrue("0 projects message expected, got '${out.outputForLlm}'") {
            out.outputForLlm.contains("0 project")
        }
    }

    @Test fun providerIdFilterRejected() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            tool(ProjectStoreTestKit.create()).execute(
                ProviderQueryTool.Input(select = "cost_history", providerId = "openai"),
                ctx(),
            )
        }
        assertTrue("expected 'cost_history' rejection note: ${ex.message}") {
            ex.message?.contains("cost_history") == true
        }
    }

    @Test fun limitRejectedOnOtherSelects() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            tool(ProjectStoreTestKit.create()).execute(
                ProviderQueryTool.Input(select = "providers", limit = 10),
                ctx(),
            )
        }
        assertTrue("expected limit-rejection: ${ex.message}") {
            ex.message?.contains("limit") == true
        }
    }
}
