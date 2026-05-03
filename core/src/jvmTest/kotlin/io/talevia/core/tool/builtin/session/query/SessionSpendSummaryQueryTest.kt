package io.talevia.core.tool.builtin.session.query

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [runSpendSummaryQuery] — the
 * `session_query(select=spend_summary)` per-provider AIGC spend
 * roll-up. M2 §5.2 "成本可见" criterion. Cycle 106 audit: 190 LOC,
 * **zero** transitive test references; the query feeds the user's
 * "am I spending more on OpenAI than Replicate on this edit?"
 * answer, so silent regressions corrupt cost reporting.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Per-provider bucket accounting.** Pinned via `mixedBucket
 *    SumsKnownAndCountsUnknown`. The kdoc commits to "a provider
 *    bucket's `usdCents` is null only when EVERY entry in that
 *    bucket has costCents=null. Mixed buckets sum the known
 *    subset and still report the partial total." A regression
 *    flipping the partial-total semantic would either suppress
 *    real spending data ("4 calls, $0.40 known + 2 unpriced"
 *    becomes "X calls, all unpriced") or silently overcount.
 *
 * 2. **Cross-session filtering.** Pinned via
 *    `crossSessionEntriesAreFiltered`. The query walks all
 *    project lockfile entries but MUST filter by stamped
 *    sessionId. A regression dropping the filter would attribute
 *    every project-wide AIGC call to whichever session asked,
 *    making the cost dashboard meaningless.
 *
 * 3. **Project-not-found degradation.** Pinned via
 *    `projectNotFoundReturnsZeroRowWithProjectResolvedFalse`.
 *    The kdoc commits to "project-not-found returns an empty
 *    zero-row rather than erroring" — important so the query
 *    works mid-fork before the new project has any entries
 *    written. A regression that errored would force the LLM
 *    into a try/catch loop on every spend query.
 */
class SessionSpendSummaryQueryTest {

    private fun newSessions(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun primeSession(
        sessions: SqlDelightSessionStore,
        sid: String = "s-1",
        projectId: String = "p-1",
    ): Session {
        val now = Clock.System.now()
        val s = Session(
            id = SessionId(sid),
            projectId = ProjectId(projectId),
            title = "t",
            createdAt = now,
            updatedAt = now,
        )
        sessions.createSession(s)
        return s
    }

    private fun entry(
        sessionId: String?,
        providerId: String,
        costCents: Long?,
        assetId: String = "asset-${providerId}-${costCents ?: "null"}",
    ): LockfileEntry = LockfileEntry(
        inputHash = "h-$providerId-${costCents ?: "null"}-${assetId}",
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = "m1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        sessionId = sessionId,
        costCents = costCents,
        originatingMessageId = MessageId("m"),
    )

    private fun input(sid: String?) = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_SPEND_SUMMARY,
        sessionId = sid,
    )

    private suspend fun primeProjectWithLockfile(
        projects: io.talevia.core.domain.FileProjectStore,
        projectId: String,
        entries: List<LockfileEntry>,
    ) {
        val proj = Project(
            id = ProjectId(projectId),
            timeline = Timeline(),
            lockfile = EagerLockfile(entries = entries),
        )
        projects.upsert("test", proj)
    }

    private fun decodeRows(out: SessionQueryTool.Output): List<SessionSpendSummaryRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionSpendSummaryRow.serializer()),
            out.rows,
        )

    // ── Input validation ──────────────────────────────────────────

    @Test fun missingSessionIdErrorsLoud() = runTest {
        val sessions = newSessions()
        val ex = assertFailsWith<IllegalStateException> {
            runSpendSummaryQuery(sessions, projects = null, input = input(null))
        }
        // Pin: error message points at the recovery action so the
        // LLM knows what to do next without guessing.
        val msg = ex.message ?: ""
        assertTrue("sessionId" in msg, "must mention sessionId; got: $msg")
        assertTrue("session_query(select=sessions)" in msg, "must point at recovery; got: $msg")
    }

    @Test fun unknownSessionIdErrorsLoud() = runTest {
        val sessions = newSessions()
        val ex = assertFailsWith<IllegalStateException> {
            runSpendSummaryQuery(sessions, projects = null, input = input("ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "must name the session; got: $msg")
        assertTrue("session_query(select=sessions)" in msg)
    }

    // ── projectResolved degradation ───────────────────────────────

    @Test fun projectNotFoundReturnsZeroRowWithProjectResolvedFalse() = runTest {
        // Pin the kdoc contract: "project-not-found returns an
        // empty zero-row rather than erroring". Important so
        // queries work mid-fork before lockfile entries land.
        val sessions = newSessions()
        primeSession(sessions, sid = "s-1", projectId = "p-missing")
        val projects = ProjectStoreTestKit.create()
        // Project p-missing not upserted in the store.
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val row = decodeRows(result.data).single()
        assertEquals(0, row.totalCalls)
        assertEquals(false, row.projectResolved, "projectResolved=false flags missing project")
        assertEquals(emptyList(), row.perProviderBreakdown)
        assertNull(row.estimatedUsdCents, "no priced calls when project absent")
    }

    @Test fun nullProjectsParamReturnsZeroRow() = runTest {
        // Defensive: passing null for the projects store is the
        // "no project layer wired" container shape. Should
        // degrade gracefully, not crash.
        val sessions = newSessions()
        primeSession(sessions)
        val result = runSpendSummaryQuery(sessions, projects = null, input = input("s-1"))
        val row = decodeRows(result.data).single()
        assertEquals(0, row.totalCalls)
        assertEquals(false, row.projectResolved)
    }

    // ── Aggregation: empty / single provider ──────────────────────

    @Test fun projectFoundButEmptyLockfileReturnsZeroRowWithResolvedTrue() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(projects, "p-1", entries = emptyList())
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val row = decodeRows(result.data).single()
        assertEquals(0, row.totalCalls)
        assertEquals(true, row.projectResolved, "project resolved cleanly even with no entries")
        assertEquals(0, row.unknownCostCalls)
    }

    @Test fun singleProviderAllKnownCostsSumsCorrectly() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "openai", costCents = 100, assetId = "a1"),
                entry("s-1", "openai", costCents = 50, assetId = "a2"),
                entry("s-1", "openai", costCents = 25, assetId = "a3"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val row = decodeRows(result.data).single()
        assertEquals(3, row.totalCalls)
        assertEquals(0, row.unknownCostCalls)
        assertEquals(175.0, row.estimatedUsdCents)
        // Single bucket per provider.
        val provider = row.perProviderBreakdown.single()
        assertEquals("openai", provider.providerId)
        assertEquals(3, provider.calls)
        assertEquals(175.0, provider.usdCents)
        assertEquals(0, provider.unknownCalls)
    }

    @Test fun singleProviderAllUnknownCostsHasNullEstimateAndCounts() = runTest {
        // Pin: every entry costCents=null → estimatedUsdCents=null,
        // unknownCostCalls=N. Distinguishes "no calls" from "calls
        // with unknown pricing".
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "replicate", costCents = null, assetId = "r1"),
                entry("s-1", "replicate", costCents = null, assetId = "r2"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val row = decodeRows(result.data).single()
        assertEquals(2, row.totalCalls)
        assertEquals(2, row.unknownCostCalls, "all 2 calls unpriced")
        assertNull(row.estimatedUsdCents, "no known cents → null estimate")
        val provider = row.perProviderBreakdown.single()
        assertEquals(2, provider.calls)
        assertEquals(2, provider.unknownCalls, "all unpriced in this bucket")
        assertNull(provider.usdCents, "all-unknown bucket has null usdCents")
    }

    // ── The kdoc's marquee mixed-bucket pin ───────────────────────

    @Test fun mixedBucketSumsKnownAndCountsUnknown() = runTest {
        // The most load-bearing pin in this file. Per the kdoc:
        // "Mixed buckets sum the known subset and still report
        // the partial total ... so the reader can distinguish
        // '$0.40 known across 4 calls' from '$0.40 known across
        // 4 calls + 2 unpriced'."
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "openai", costCents = 100, assetId = "k1"),
                entry("s-1", "openai", costCents = 200, assetId = "k2"),
                entry("s-1", "openai", costCents = null, assetId = "u1"),
                entry("s-1", "openai", costCents = null, assetId = "u2"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val row = decodeRows(result.data).single()
        assertEquals(4, row.totalCalls)
        assertEquals(2, row.unknownCostCalls, "2 of 4 are unpriced")
        // estimatedUsdCents = 100 + 200 = 300.0 (NOT null, NOT
        // 4 * something). Partial total — known subset only.
        assertEquals(300.0, row.estimatedUsdCents)

        val provider = row.perProviderBreakdown.single()
        assertEquals(4, provider.calls)
        assertEquals(2, provider.unknownCalls)
        // Pin: bucket usdCents NOT null even though some entries
        // are unpriced — partial sum surfaces.
        assertEquals(300.0, provider.usdCents)
    }

    // ── Multi-provider sorting ────────────────────────────────────

    @Test fun multipleProvidersSortBreakdownByProviderId() = runTest {
        // Pin sortedBy stable order — `replicate` < `openai` <
        // `together` alphabetically. UI dashboards expect a stable
        // order so re-renders don't shuffle rows visually.
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "together", costCents = 30, assetId = "t1"),
                entry("s-1", "openai", costCents = 100, assetId = "o1"),
                entry("s-1", "replicate", costCents = 50, assetId = "r1"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val row = decodeRows(result.data).single()
        // Pin alphabetical order on providerId (insertion order
        // would have been together, openai, replicate).
        assertEquals(
            listOf("openai", "replicate", "together"),
            row.perProviderBreakdown.map { it.providerId },
        )
        // Total = 100 + 50 + 30 = 180.0
        assertEquals(180.0, row.estimatedUsdCents)
    }

    // ── Cross-session isolation ───────────────────────────────────

    @Test fun crossSessionEntriesAreFiltered() = runTest {
        // Pin: only entries stamped with the queried sessionId are
        // counted. A regression dropping the filter would attribute
        // every project-wide AIGC call to whichever session asked,
        // wrecking the cost dashboard's accuracy.
        val sessions = newSessions()
        primeSession(sessions, sid = "s-target")
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-target", "openai", costCents = 100, assetId = "a-mine"),
                entry("s-other", "openai", costCents = 9_999, assetId = "a-other"),
                entry(null, "openai", costCents = 8_888, assetId = "a-null"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-target"))
        val row = decodeRows(result.data).single()
        assertEquals(1, row.totalCalls, "only the s-target entry counts")
        assertEquals(100.0, row.estimatedUsdCents, "s-other and null-session entries filtered")
        assertEquals(0, row.unknownCostCalls)
    }

    // ── Output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelectAndTotalAndReturnedFields() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(entry("s-1", "openai", costCents = 50)),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        assertEquals(SessionQueryTool.SELECT_SPEND_SUMMARY, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertNotNull(result.title, "tool result has a non-null title")
        assertTrue("session_query spend_summary s-1" in result.title!!, "title format; got: ${result.title}")
    }

    @Test fun summaryTextSinglePresenterOnSingleProvider() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "openai", costCents = 100, assetId = "a1"),
                entry("s-1", "openai", costCents = 50, assetId = "a2"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val out = result.outputForLlm
        // Pin the natural-language summary fields the LLM reads:
        // session id, call count, provider name, dollar amount.
        assertTrue("Session s-1" in out, "session id; got: $out")
        assertTrue("2 AIGC call(s)" in out, "call count; got: $out")
        assertTrue("on openai" in out, "single-provider tail; got: $out")
        assertTrue("$1.5" in out, "dollar amount; got: $out")
    }

    @Test fun summaryTextAcrossNProvidersUsesPluralisedTail() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "openai", costCents = 100, assetId = "a1"),
                entry("s-1", "replicate", costCents = 50, assetId = "a2"),
                entry("s-1", "together", costCents = 25, assetId = "a3"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val out = result.outputForLlm
        // Pin: multi-provider uses "across N provider(s)" not the
        // "on X" form. UI/LLM consumes a different render shape
        // for ≥ 2 providers.
        assertTrue("across 3 provider(s)" in out, "multi-provider tail; got: $out")
    }

    @Test fun summaryTextWithUnpricedTailIncludesCorrectPlural() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "openai", costCents = 100, assetId = "a1"),
                entry("s-1", "openai", costCents = null, assetId = "a2"),
                entry("s-1", "openai", costCents = null, assetId = "a3"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val out = result.outputForLlm
        // Pin: "(+2 unpriced calls)" — plural 's', count > 1.
        assertTrue("+2 unpriced calls" in out, "plural form; got: $out")
    }

    @Test fun summaryTextWithSingleUnpricedUsesSingularForm() = runTest {
        val sessions = newSessions()
        primeSession(sessions)
        val projects = ProjectStoreTestKit.create()
        primeProjectWithLockfile(
            projects,
            "p-1",
            entries = listOf(
                entry("s-1", "openai", costCents = 100, assetId = "a1"),
                entry("s-1", "openai", costCents = null, assetId = "a2"),
            ),
        )
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val out = result.outputForLlm
        // Pin: count == 1 → "(+1 unpriced call)" — singular.
        // A regression in the pluralisation would output "+1 unpriced
        // calls" — minor but reads sloppy.
        assertTrue("+1 unpriced call)" in out, "singular form; got: $out")
        assertTrue("calls" !in out.substringAfter("+1 unpriced call)"), "no trailing 's'")
    }

    @Test fun summaryTextNotesProjectNotFound() = runTest {
        // Pin: when the project couldn't be resolved, the summary
        // surfaces "[project X not found]" so the LLM (and any
        // human reader) sees why the cost is empty.
        val sessions = newSessions()
        primeSession(sessions, sid = "s-1", projectId = "p-ghost")
        val projects = ProjectStoreTestKit.create()
        // Don't prime p-ghost.
        val result = runSpendSummaryQuery(sessions, projects, input("s-1"))
        val out = result.outputForLlm
        assertTrue(
            "[project p-ghost not found]" in out,
            "project-missing tail; got: $out",
        )
    }
}
