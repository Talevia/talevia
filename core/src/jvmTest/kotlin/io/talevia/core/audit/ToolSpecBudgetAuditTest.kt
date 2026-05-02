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
 *  - `ProviderQueryTool` and the second-pass re-registration of
 *    `SessionActionTool` (with `providers=` wired in for
 *    `action="compact"`) — both deferred to each container's second
 *    pass because they need `ProviderRegistry`.
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

        // Three-tier budget gate (R.6 #1 perf surface, M6 §5.7 criterion #1):
        //
        // - LOWER ($MIN): catches regressions that silently shrink the
        //   registry (e.g. an `register*Tools` call accidentally elided
        //   under a refactor); current audit-subset baseline ≈ 16.5k so
        //   any drop > 27% means "tools went missing", not "we got
        //   leaner".
        // - WARNING ($SOFT): printed as `[warn]` when crossed but does
        //   NOT fail the test. Lets cycles detect creep early without
        //   blocking work in flight; the `:core:jvmTest --info` log /
        //   commit-body monitoring lane records the trigger so the
        //   pattern is visible across cycles. M6 #1 lowered the SOFT
        //   from 18k to 15k — the criterion's strict-assertion target.
        //   The warning prints every `:core:jvmTest --info` run until
        //   the audit-subset crosses below 15k (currently 16,431 after
        //   cycle 5's 42b71191 trim of project_query). That's the
        //   intended signal: "criterion #1 strict half not yet met;
        //   trim work pending" surfaces every cycle until the
        //   m6-audit-subset-strict-15k follow-up bullet ships.
        // - HARD CEILING ($MAX): assertion fails. Hard limit on the
        //   audit subset's per-turn LLM context tax. M6 #1 (cycle 10)
        //   tightened MAX from 22k → 18k now that the audit-subset has
        //   been at ~16.4k since cycle 5's project_query helpText trim
        //   (42b71191) — 6k headroom was generous; 1.5k headroom is
        //   tight enough to catch runaway growth fast, loose enough to
        //   not block legitimate prod-only registry adds (full registry
        //   is ~26k including the audit-excluded fs/shell/aigc groups).
        //   The criterion's second half — strict `audit-subset ≤ 15k`
        //   assertion — depends on a token-reduction pass; tracked as
        //   the m6-audit-subset-strict-15k follow-up bullet that picks
        //   up where cycle 5 left off (consolidate next-3 dispatchers'
        //   helpText: clip_action 1264t / source_node_action 1240t /
        //   session_action 1205t; each ~10% trim ≈ 350-400 tokens off
        //   total; aggregate gets the audit-subset under 15k).
        //
        // If the registry genuinely needs to grow past MAX (e.g. a
        // major new tool family), do it as an explicit ceiling-bump
        // commit with rationale, not silently.
        val MIN = 12_000
        val SOFT = 15_000
        val MAX = 18_000
        assertTrue(
            row.estimatedTokens >= MIN,
            "tool-spec budget ${row.estimatedTokens} below $MIN — registry shrank unexpectedly; " +
                "check if a register*Tools call was accidentally dropped",
        )
        assertTrue(
            row.estimatedTokens <= MAX,
            "tool-spec budget ${row.estimatedTokens} exceeds hard ceiling $MAX — runaway tool-spec growth; " +
                "consolidate or shrink before merging, do not bump the ceiling silently",
        )
        if (row.estimatedTokens > SOFT) {
            println(
                "[warn] tool-spec budget ${row.estimatedTokens} crossed soft threshold $SOFT (hard $MAX) — " +
                    "consolidate near-prefix tool groups or trim helpText before the next cycle",
            )
        }
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
