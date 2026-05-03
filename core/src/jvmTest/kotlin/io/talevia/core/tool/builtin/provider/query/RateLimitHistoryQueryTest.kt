package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.provider.RateLimitHistoryRecorder
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runRateLimitHistoryQuery] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/provider/query/RateLimitHistoryQuery.kt`.
 * Cycle 246 audit: 0 test refs (the only matches were the file
 * itself).
 *
 * Same audit-pattern fallback as cycles 207-245. Sister to cycle
 * 245's [runEngineReadinessQuery] pin — both functions share the
 * same three-branch summary shape (recorder==null / empty /
 * non-empty) over a `ProviderQueryTool.Output`. Pinning each
 * sibling independently catches drift in either copy without
 * relying on a future shared abstraction.
 *
 * `runRateLimitHistoryQuery` aggregates per-provider rate-limit
 * retry events that the [RateLimitHistoryRecorder] captures from
 * the bus. The function compresses the recorder's buffer into
 * one summary row per provider (count + first/last epoch +
 * totalWait + most-recent reason). The `outputForLlm` field is
 * the **string the LLM reads** when an operator asks "how often
 * am I hitting Anthropic's tier-1 limit today?" — drift in any
 * branch silently changes the model's diagnosis surface.
 *
 * Pins three correctness contracts:
 *
 *  1. **Three-branch summary semantics**:
 *     - `recorder == null`     → "recorder not wired" note
 *     - recorder, empty snap   → "No rate-limit retries captured" note
 *     - recorder, non-empty    → "Rate-limit retries on N provider(s):
 *       provider=count, …" line
 *     Branch separation pinned via negative substring assertions
 *     to surface silent merging of two states.
 *
 *  2. **Per-provider aggregate calculation.** Each
 *     [RateLimitHistoryRow] carries 5 derived fields
 *     (`count`, `firstEpochMs`, `lastEpochMs`, `totalWaitMs`,
 *     `mostRecentReason`). Drift in any single derivation
 *     (e.g. `totalWaitMs = avg` instead of sum, `mostRecentReason
 *     = first` instead of last) silently shifts what the
 *     dashboard reports.
 *
 *  3. **Title pluralization** + **summary join format**:
 *     - Title: "0 providers" / "1 provider" / "2 providers"
 *       (per the `if (rows.size == 1) "" else "s"` arm).
 *     - Summary: `provider=count, provider=count` (comma-space
 *       join). Drift would change LLM context tokenization.
 *
 * Plus structural pins:
 *   - `select == ProviderQueryTool.SELECT_RATE_LIMIT_HISTORY`.
 *   - `total == returned == rows.size` (no pagination).
 *   - Rows sorted by `providerId` ascending (deterministic for
 *     stable diffing across re-runs / golden files).
 *   - Empty snapshot still produces empty `JsonArray`, NOT null.
 */
class RateLimitHistoryQueryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rowsFrom(out: ProviderQueryTool.Output): List<RateLimitHistoryRow> =
        json.decodeFromJsonElement(
            ListSerializer(RateLimitHistoryRow.serializer()),
            out.rows,
        )

    private suspend fun waitForEntry(
        recorder: RateLimitHistoryRecorder,
        providerId: String,
        predicate: (List<RateLimitHistoryRecorder.Entry>) -> Boolean,
    ) {
        withTimeout(5.seconds) {
            while (!predicate(recorder.snapshot(providerId))) yield()
        }
    }

    private fun retryEvent(
        provider: String,
        waitMs: Long = 4_000L,
        reason: String = "$provider HTTP 429: tier-1 RPM exceeded",
        sessionId: String = "s1",
        attempt: Int = 2,
    ): BusEvent.AgentRetryScheduled =
        BusEvent.AgentRetryScheduled(
            sessionId = SessionId(sessionId),
            attempt = attempt,
            waitMs = waitMs,
            reason = reason,
            providerId = provider,
        )

    // ── 1. Three-branch summary semantics ───────────────────

    @Test fun nullRecorderProducesNotWiredNote() {
        // Marquee branch-A pin: when no recorder is wired (test
        // rigs without the aggregator), the LLM gets the "recorder
        // not wired in this rig" note. Drift to "no rate-limit
        // retries" would conflate the test-rig-shape with the
        // empty-history shape.
        val result = runRateLimitHistoryQuery(recorder = null)
        assertTrue(
            "recorder not wired in this rig" in result.outputForLlm,
            "null recorder MUST surface 'recorder not wired in this rig'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Production containers always wire it" in result.outputForLlm,
            "null-recorder note MUST cite production-vs-test distinction; got: ${result.outputForLlm}",
        )
        // Branch separation: must NOT carry the empty-history note.
        assertTrue(
            "No rate-limit retries captured" !in result.outputForLlm,
            "null branch MUST NOT carry the empty-history phrase",
        )
    }

    @Test fun emptyRecorderProducesNoRetriesNote() = runTest {
        // Marquee branch-B pin: live recorder with no events
        // surfaces "No rate-limit retries captured". Distinct from
        // null-branch — drift to share the message would conflate
        // test-rig-shape with empty-on-fresh-process.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            val result = runRateLimitHistoryQuery(recorder)
            assertTrue(
                "No rate-limit retries captured" in result.outputForLlm,
                "empty recorder MUST surface 'No rate-limit retries captured'; got: ${result.outputForLlm}",
            )
            // Branch separation: must NOT carry the null-branch's phrase.
            assertTrue(
                "recorder not wired" !in result.outputForLlm,
                "empty branch MUST NOT carry the null-branch's phrase",
            )
        }
    }

    @Test fun nonEmptyRecorderProducesProviderCountSummary() = runTest {
        // Marquee branch-C pin: non-empty produces "Rate-limit
        // retries on N provider(s): provider=count, ..." line.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(retryEvent(provider = "anthropic", waitMs = 4_000L))
            waitForEntry(recorder, "anthropic") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            assertTrue(
                "Rate-limit retries on 1 provider" in result.outputForLlm,
                "non-empty MUST surface 'Rate-limit retries on N provider(s)'; got: ${result.outputForLlm}",
            )
            assertTrue(
                "anthropic=1" in result.outputForLlm,
                "summary MUST include 'provider=count'; got: ${result.outputForLlm}",
            )
        }
    }

    // ── 2. Per-provider aggregate calculation ───────────────

    @Test fun rowCarriesCorrectAggregatesFromSingleEntry() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                retryEvent(
                    provider = "anthropic",
                    waitMs = 7_500L,
                    reason = "anthropic HTTP 429: input tokens exceeded",
                ),
            )
            waitForEntry(recorder, "anthropic") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            val rows = rowsFrom(result.data)
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals("anthropic", row.providerId)
            assertEquals(1, row.count)
            assertEquals(7_500L, row.totalWaitMs, "totalWaitMs MUST equal sum of waitMs (single entry)")
            assertEquals(
                "anthropic HTTP 429: input tokens exceeded",
                row.mostRecentReason,
                "single-entry mostRecentReason MUST be that entry's reason",
            )
            assertTrue(row.firstEpochMs > 0L)
            // Single entry → first == last.
            assertEquals(
                row.firstEpochMs,
                row.lastEpochMs,
                "single-entry first/last MUST be equal",
            )
        }
    }

    @Test fun rowAggregatesAcrossMultipleEntriesOnSameProvider() = runTest {
        // Marquee aggregate-correctness pin: count=N, totalWait=sum,
        // mostRecentReason=last, first=earliest, last=latest.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(retryEvent(provider = "anthropic", waitMs = 1_000L, reason = "rate limit 1"))
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            bus.publish(retryEvent(provider = "anthropic", waitMs = 2_000L, reason = "rate limit 2"))
            waitForEntry(recorder, "anthropic") { it.size == 2 }
            bus.publish(retryEvent(provider = "anthropic", waitMs = 3_000L, reason = "rate limit 3"))
            waitForEntry(recorder, "anthropic") { it.size == 3 }

            val result = runRateLimitHistoryQuery(recorder)
            val row = rowsFrom(result.data).single()
            assertEquals(3, row.count, "count MUST be ring-buffer size")
            assertEquals(
                6_000L,
                row.totalWaitMs,
                "totalWaitMs MUST be sum of all waitMs (1k + 2k + 3k)",
            )
            assertEquals(
                "rate limit 3",
                row.mostRecentReason,
                "mostRecentReason MUST be the LAST entry's reason (drift to first would break dashboards)",
            )
            assertTrue(
                row.lastEpochMs >= row.firstEpochMs,
                "lastEpochMs MUST be >= firstEpochMs (chronological)",
            )
        }
    }

    // ── 3. Sorting + multi-provider summary join ────────────

    @Test fun multipleProvidersSortedByProviderIdAscending() = runTest {
        // Marquee deterministic-output pin: rows are sorted by
        // providerId — drift to "insertion order" or "by count
        // desc" would silently change row ordering across re-
        // runs / golden files / agent traces.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            // Insert in non-alphabetical order: zzz first, aaa
            // last; output should still be a-first.
            bus.publish(retryEvent(provider = "zzz_provider", waitMs = 100L))
            waitForEntry(recorder, "zzz_provider") { it.size == 1 }
            bus.publish(retryEvent(provider = "openai", waitMs = 100L))
            waitForEntry(recorder, "openai") { it.size == 1 }
            bus.publish(retryEvent(provider = "anthropic", waitMs = 100L))
            waitForEntry(recorder, "anthropic") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            val rows = rowsFrom(result.data)
            assertEquals(
                listOf("anthropic", "openai", "zzz_provider"),
                rows.map { it.providerId },
                "rows MUST be sorted by providerId ascending",
            )
        }
    }

    @Test fun multiProviderSummaryUsesCommaSpaceJoin() = runTest {
        // Pin: per-provider entries in the summary line are
        // joined with `, ` so the LLM sees one canonical
        // delimiter. Drift to ` / ` or newline would change
        // tokenization.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(retryEvent(provider = "anthropic", waitMs = 100L))
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            bus.publish(retryEvent(provider = "openai", waitMs = 100L))
            waitForEntry(recorder, "openai") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            assertTrue(
                "anthropic=1, openai=1" in result.outputForLlm,
                "multi-provider summary MUST use ', ' join (alphabetical order); got: ${result.outputForLlm}",
            )
        }
    }

    // ── 4. Title pluralization ──────────────────────────────

    @Test fun titlePluralizationForZeroProviders() = runTest {
        // Pin: zero providers → "0 providers" (plural).
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            val result = runRateLimitHistoryQuery(recorder)
            assertTrue(
                "(0 providers)" in result.title,
                "zero providers MUST use plural; got: ${result.title}",
            )
        }
    }

    @Test fun titlePluralizationForSingleProvider() = runTest {
        // Pin: 1 → "1 provider" (singular). Drift to "1 providers"
        // surfaces here.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(retryEvent(provider = "anthropic", waitMs = 100L))
            waitForEntry(recorder, "anthropic") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            assertTrue(
                "(1 provider)" in result.title,
                "single provider MUST use singular 'provider'; got: ${result.title}",
            )
            assertTrue(
                "(1 providers)" !in result.title,
                "single provider MUST NOT use plural; got: ${result.title}",
            )
        }
    }

    @Test fun titlePluralizationForMultipleProviders() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(retryEvent(provider = "anthropic", waitMs = 100L))
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            bus.publish(retryEvent(provider = "openai", waitMs = 100L))
            waitForEntry(recorder, "openai") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            assertTrue(
                "(2 providers)" in result.title,
                "multi-provider MUST use plural; got: ${result.title}",
            )
        }
    }

    // ── 5. Output structure ─────────────────────────────────

    @Test fun selectFieldIsCanonicalRateLimitHistoryConstant() {
        // Pin: drift would mismatch downstream UIs that branch
        // on the select id.
        val result = runRateLimitHistoryQuery(recorder = null)
        assertEquals(
            ProviderQueryTool.SELECT_RATE_LIMIT_HISTORY,
            result.data.select,
            "select MUST be the canonical SELECT_RATE_LIMIT_HISTORY constant",
        )
    }

    @Test fun totalEqualsReturnedEqualsRowCount() = runTest {
        // Pin: this select doesn't paginate. Drift would confuse
        // pagination consumers.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(retryEvent(provider = "anthropic", waitMs = 100L))
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            bus.publish(retryEvent(provider = "openai", waitMs = 100L))
            waitForEntry(recorder, "openai") { it.size == 1 }

            val result = runRateLimitHistoryQuery(recorder)
            assertEquals(2, result.data.total)
            assertEquals(2, result.data.returned)
        }
    }

    @Test fun nullRecorderProducesEmptyJsonArrayNotNull() {
        // Pin: drift to "use null when empty" would crash JSON
        // consumers expecting an array.
        val result = runRateLimitHistoryQuery(recorder = null)
        assertEquals(0, result.data.rows.size)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
    }
}
