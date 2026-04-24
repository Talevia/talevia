package io.talevia.core.audit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.NoopProxyGenerator
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.project.NoopMaintenanceEngine
import io.talevia.core.tool.builtin.registerClipAndTrackTools
import io.talevia.core.tool.builtin.registerMediaTools
import io.talevia.core.tool.builtin.registerProjectTools
import io.talevia.core.tool.builtin.registerSessionAndMetaTools
import io.talevia.core.tool.builtin.registerSourceNodeTools
import io.talevia.core.tool.builtin.session.SessionQueryTool
import io.talevia.core.tool.builtin.session.query.ToolSpecBudgetRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Audit** test: wires every production register-group that doesn't
 * need a live network / provider / OS shell / filesystem and asks
 * `session_query(select=tool_spec_budget)` for the current token burn.
 * Prints the total so cycles can record the monitoring-curve data point
 * via `:core:jvmTest --info`.
 *
 * Not a benchmark (no wall-time measurement) and the ceiling is set
 * generously — individual regressions surface via the printed number;
 * the ceiling catches runaways.
 *
 * ## Scope
 *
 * Groups exercised:
 *  - `registerSessionAndMetaTools` — session-lifecycle + meta
 *    (ListTools / EstimateTokens / Todo / DraftPlan / ExecutePlan /
 *    SessionQuery / ExportSession / EstimateSessionTokens /
 *    ForkSession / SetSessionSpendCap / SetToolEnabled / SwitchProject
 *    / RevertSession / SessionAction / ReadPart).
 *  - `registerMediaTools` — ImportMedia / ExtractFrame /
 *    ConsolidateMediaIntoBundle / RelinkAsset.
 *  - `registerClipAndTrackTools` — every timeline edit verb.
 *  - `registerProjectTools` — CRUD + Export + Snapshots + Maintenance.
 *  - `registerSourceNodeTools` — DAG verbs + source_query.
 *
 * **Excluded** (none of these match plain-Core registration):
 *  - `registerBuiltinFileTools` — needs `FileSystem` / `ProcessRunner`
 *    / `HttpClient` stubs; fs & shell tools add ~1-2k tokens.
 *  - `registerAigcTools` — each engine is nullable; passing null drops
 *    6 tools. CompareAigcCandidatesTool + ReplayLockfileTool *would*
 *    register unconditionally but those two live under the AIGC
 *    register group.
 *  - `ProviderQueryTool` + `CompactSessionTool` — registered in a
 *    second-pass in each container because they need `ProviderRegistry`.
 *
 * The measured number therefore **undercounts** production by roughly
 * the skipped groups (~15 tools, ~2-3k tokens last observed). When
 * comparing to the 398fb0ec baseline (22_700 tokens, registered with
 * the full CLI set including fs/shell/aigc), subtract that baseline's
 * portion for the skipped groups before treating this number as apples-
 * to-apples — or re-extend the test when the excluded groups become
 * worth the stub scaffolding.
 */
class ToolSpecBudgetAuditTest {

    @Test fun reportCurrentProductionToolSpecBudget() = runTest {
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

        val tool = SessionQueryTool(sessions = sessions, toolRegistry = registry)
        val out = tool.execute(
            SessionQueryTool.Input(select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET),
            stubCtx(),
        ).data
        val row = out.rows.decodeRowsAs(ToolSpecBudgetRow.serializer()).single()

        // `println` so `:core:jvmTest --info` captures the number for
        // the monitoring-curve commit-body entry. Kept terse so a grep
        // can locate it across logs without noise.
        println("[audit] tool-spec-budget tools=${row.toolCount} tokens=${row.estimatedTokens} bytes=${row.specBytes}")
        println("[audit] tool-spec-budget top=${row.topByTokens.joinToString("; ") { "${it.toolId}=${it.estimatedTokens}t" }}")

        assertTrue(row.toolCount > 0, "expected non-empty production registry; got ${row.toolCount}")
        assertTrue(
            row.estimatedTokens in 1..30_000,
            "tool-spec budget ${row.estimatedTokens} outside sanity ceiling; " +
                "check whether a very heavy tool spec landed, or update the ceiling if the registry genuinely grew.",
        )
    }

    private object StubBundleBlobWriter : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: ProjectId,
            assetId: AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile = MediaSource.BundleFile(relativePath = "media/${assetId.value}.$format")
    }

    private fun stubCtx(): ToolContext = ToolContext(
        sessionId = SessionId("audit"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        currentProjectId = null,
        publishEvent = { },
        spendCapCents = null,
    )
}
