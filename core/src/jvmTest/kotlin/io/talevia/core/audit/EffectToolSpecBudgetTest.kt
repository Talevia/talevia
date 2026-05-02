package io.talevia.core.audit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.NoopProxyGenerator
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.project.NoopMaintenanceEngine
import io.talevia.core.tool.builtin.registerClipAndTrackTools
import io.talevia.core.tool.builtin.registerMediaTools
import io.talevia.core.tool.builtin.registerProjectTools
import io.talevia.core.tool.builtin.registerSessionAndMetaTools
import io.talevia.core.tool.builtin.registerSourceNodeTools
import io.talevia.core.tool.builtin.session.query.computeToolSpecBudget
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.TransitionActionTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VISION §5.7 / M4 §5.2 criterion #4 (Effect tool spec budget): per-tool
 * gates on the M4-relevant effect dispatchers.
 *
 * Why this lane exists alongside [ToolSpecBudgetAuditTest]: that file
 * pins the **registry-wide** ceiling (12k–22k tokens). Per-tool growth
 * can hide under that aggregate — `filter_action` could quietly bloat
 * from 800 → 1800 tokens by accumulating filter-kind-specific clauses
 * in its helpText, and the registry-wide assertion would barely
 * notice unless it pushed past 22k. M4 #4 names individual ceilings
 * so future Filter / Transition schema growth competes for budget the
 * same way M3's overall gate forced consolidation:
 *
 *   - `filter_action` ≤ 1500 tokens — a single dispatcher covering
 *     apply / remove / set verbs across ≤ 6 FilterKind variants
 *     should not approach the top-3 threshold.
 *   - `transition_action` ≤ 1500 tokens — same shape on the
 *     transition axis.
 *   - Neither is in `topByTokens` top 3 of the full audit registry —
 *     prevents effect-related spec bloat from silently inflating
 *     per-turn cost (top spots should belong to the broad query
 *     dispatchers like `project_query` / `session_query` /
 *     `source_query`, which span more selects).
 *
 * Hard ceiling rationale: the existing `topByTokens` cap of 5 entries
 * means measurement requires direct registry inspection (the public
 * `session_query(select=tool_spec_budget)` would only surface effect
 * tools if they crept into the top 5 — by then the regression is
 * already in the wild). [computeToolSpecBudget] is the same helper
 * the public query uses, so the ratio (id + helpText + schema bytes)
 * matches what the LLM actually pays per turn.
 */
class EffectToolSpecBudgetTest {

    private val maxPerEffectToolTokens = 1500

    @Test fun filterActionToolSpecBudgetUnder1500Tokens() = runTest {
        val registry = effectOnlyRegistry(includeFilter = true, includeTransition = false)
        val row = computeToolSpecBudget(registry)
        val all = registry.all()
        val filterEntry = all.firstOrNull { it.id == "filter_action" }
            ?: error(
                "filter_action not registered — wiring drift; expected ID 'filter_action' " +
                    "in the effect-only audit registry",
            )

        // Replicate the single-entry computation rather than rely on
        // `topByTokens` (capped at 5; effect tools shouldn't be in it
        // when the M4 #4 gate holds, so we couldn't read them out
        // through the public surface).
        val schemaJson = io.talevia.core.JsonConfig.default.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            filterEntry.spec.inputSchema,
        )
        val bytes = filterEntry.id.length + filterEntry.helpText.length + schemaJson.length
        val tokens = (bytes + 2) / 4
        // Rounding matches `computeToolSpecBudget` `bytesToTokens` so
        // failures here mirror the public query's accounting exactly.
        println("[m4-#4] filter_action spec_bytes=$bytes estimated_tokens=$tokens (gate: ≤ $maxPerEffectToolTokens)")
        assertTrue(
            tokens <= maxPerEffectToolTokens,
            "M4 #4: filter_action spec is $tokens tokens — exceeds the per-tool effect ceiling of " +
                "$maxPerEffectToolTokens. Trim helpText / property descriptions / split inner schemas " +
                "before merging. Do NOT bump the ceiling silently — the criterion is a budget gate, not " +
                "a measurement.",
        )
        // Sanity: total budget should also be sane on this skeletal
        // registry — pins the helper's correctness on a deterministic
        // 1-tool registry.
        assertTrue(row.toolCount == 1, "expected effect-only registry to hold 1 tool, got ${row.toolCount}")
    }

    @Test fun transitionActionToolSpecBudgetUnder1500Tokens() = runTest {
        val registry = effectOnlyRegistry(includeFilter = false, includeTransition = true)
        val all = registry.all()
        val transitionEntry = all.firstOrNull { it.id == "transition_action" }
            ?: error(
                "transition_action not registered — wiring drift; expected ID " +
                    "'transition_action' in the effect-only audit registry",
            )
        val schemaJson = io.talevia.core.JsonConfig.default.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            transitionEntry.spec.inputSchema,
        )
        val bytes = transitionEntry.id.length + transitionEntry.helpText.length + schemaJson.length
        val tokens = (bytes + 2) / 4
        println(
            "[m4-#4] transition_action spec_bytes=$bytes estimated_tokens=$tokens " +
                "(gate: ≤ $maxPerEffectToolTokens)",
        )
        assertTrue(
            tokens <= maxPerEffectToolTokens,
            "M4 #4: transition_action spec is $tokens tokens — exceeds the per-tool effect ceiling " +
                "of $maxPerEffectToolTokens. Trim helpText / property descriptions / split inner " +
                "schemas before merging.",
        )
    }

    @Test fun effectToolsNotInTopThreeOfFullRegistry() = runTest {
        // Mirror `ToolSpecBudgetAuditTest.reportCurrentProductionToolSpecBudget`'s
        // wiring (everything that doesn't need a live network / shell /
        // filesystem stub) so the ranking reflects production. Effect tools
        // must NOT occupy positions 0/1/2 — the broad query dispatchers
        // (project_query / session_query / source_query) are the natural
        // top-3 occupants because their schemas span many selects.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val agentStates = AgentRunStateTracker(bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))

        val registry = ToolRegistry()
        registry.registerSessionAndMetaTools(sessions, agentStates, projects, bus)
        registry.registerMediaTools(
            engine = NoopMaintenanceEngine,
            projects = projects,
            bundleBlobWriter = StubBundleBlobWriter,
            proxyGenerator = NoopProxyGenerator,
        )
        registry.registerClipAndTrackTools(projects = projects, sessions = sessions)
        registry.registerProjectTools(projects = projects, engine = NoopMaintenanceEngine)
        registry.registerSourceNodeTools(projects = projects)

        val row = computeToolSpecBudget(registry)
        val top3 = row.topByTokens.take(3).map { it.toolId }
        println("[m4-#4] full-registry top3=$top3")

        assertNotNull(row.topByTokens.firstOrNull(), "expected non-empty topByTokens")
        assertTrue(
            "filter_action" !in top3,
            "M4 #4: filter_action landed in top-3 (top3=$top3) — effect tools are bloating the budget. " +
                "Either trim filter_action's schema/helpText or document an explicit exception.",
        )
        assertTrue(
            "transition_action" !in top3,
            "M4 #4: transition_action landed in top-3 (top3=$top3) — effect tools are bloating the " +
                "budget. Either trim transition_action's schema/helpText or document an explicit exception.",
        )
    }

    /**
     * Build a tiny registry holding only the named effect dispatchers,
     * so `computeToolSpecBudget` returns a deterministic snapshot for
     * the per-tool gate. Mirrors the production constructors —
     * [FilterActionTool] / [TransitionActionTool] both take a
     * [io.talevia.core.domain.ProjectStore].
     */
    private fun effectOnlyRegistry(
        includeFilter: Boolean = true,
        includeTransition: Boolean = false,
    ): ToolRegistry {
        val registry = ToolRegistry()
        val projects = ProjectStoreTestKit.create()
        if (includeFilter) registry.register(FilterActionTool(projects))
        if (includeTransition) registry.register(TransitionActionTool(projects))
        return registry
    }

    private object StubBundleBlobWriter : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: ProjectId,
            assetId: AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile = MediaSource.BundleFile(relativePath = "media/${assetId.value}.$format")
    }
}
