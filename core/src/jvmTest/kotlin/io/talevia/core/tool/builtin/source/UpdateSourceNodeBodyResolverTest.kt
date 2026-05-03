package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.domain.source.BodyRevision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [resolveBodyEdit] —
 * `core/tool/builtin/source/UpdateSourceNodeBodyResolver.kt`. The
 * three-mode mutual-exclusion validator + history fetcher for
 * `source_node_action(action="update_body")`. Cycle 220 audit:
 * 146 LOC, 0 direct test refs verified across both `commonTest`
 * and `jvmTest` source sets.
 *
 * Same audit-pattern fallback as cycles 207-219. This is a
 * marquee mode-validation contract — drift in any branch silently
 * accepts a malformed update_body input or rejects a valid one,
 * either way breaking the agent's body-edit affordance.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Three-mode mutual exclusion.** Exactly one of `body` /
 *     `restoreFromRevisionIndex` / `mergeFromRevisionIndex`
 *     required. Zero modes → fail with mode-listing remediation;
 *     2+ modes → fail with mode-listing remediation. Drift to
 *     "first wins" or "last wins" would silently mis-route the
 *     agent's intent.
 *
 *  2. **`mergeFieldPaths` exclusively in merge mode.** Required
 *     when merge mode set + non-empty list; rejected when merge
 *     mode is NOT set (regardless of whether body or restore is).
 *     Drift to "ignored when not merge" would silently swallow
 *     agent typos.
 *
 *  3. **`body` mode = direct Replace.** When `input.body != null`,
 *     return Replace(input.body) without touching the history
 *     store. Drift to "always fetch history" would burn a
 *     filesystem read on every update.
 *
 *  4. **Restore + merge negative-index rejection.** Negative
 *     indices fail with the documented "0=newest" hint. Drift to
 *     "treat as 0" or wrap-around would silently shift to a
 *     different revision.
 *
 *  5. **Restore + merge bounds-check.** Empty history → "nothing
 *     to restore/merge" with friendly hint about
 *     `source_query(select=history)`. Index >= window.size →
 *     "out of range" with valid range cited.
 *
 *  6. **Merge JsonObject + field-presence guards.** Historical
 *     body must be a JsonObject (per-field merge needs a keyed
 *     shape). All `mergeFieldPaths` keys must be present in the
 *     historical revision; missing keys fail with the historical
 *     keys cited. Drift to "skip missing keys" would silently
 *     drop fields the agent expected to copy.
 *
 * Plus shape pins: Replace.body equals input.body verbatim;
 * Merge.historicalBody equals the historical entry verbatim;
 * Merge.fieldPaths equals input.mergeFieldPaths verbatim;
 * `restore` mode also returns Replace (with the historical body).
 */
class UpdateSourceNodeBodyResolverTest {

    /**
     * Capturing fake — only `listSourceNodeHistory` is implemented;
     * other methods fail-loud if accidentally called. Records last-
     * call args + history limit so tests can verify the resolver
     * doesn't over-fetch.
     */
    private class CapturingStore(
        private val history: List<BodyRevision> = emptyList(),
    ) : ProjectStore {
        var historyCallCount: Int = 0
            private set
        var lastLimit: Int = -1
            private set

        override suspend fun listSourceNodeHistory(
            id: ProjectId,
            nodeId: SourceNodeId,
            limit: Int,
        ): List<BodyRevision> {
            historyCallCount++
            lastLimit = limit
            // Honour the limit semantically — return up to N most-recent.
            return history.take(limit)
        }

        override suspend fun get(id: ProjectId): Project? = error("get not used")
        override suspend fun upsert(title: String, project: Project): Unit = error("upsert not used")
        override suspend fun list(): List<Project> = error("list not used")
        override suspend fun delete(id: ProjectId, deleteFiles: Boolean) = error("delete not used")
        override suspend fun setTitle(id: ProjectId, title: String) = error("setTitle not used")
        override suspend fun summary(id: ProjectId): ProjectSummary? = error("summary not used")
        override suspend fun listSummaries(): List<ProjectSummary> = error("listSummaries not used")
        override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project =
            error("mutate not used")
    }

    private val pid = ProjectId("p")
    private val nodeId = SourceNodeId("n1")

    private fun input(
        body: JsonObject? = null,
        restoreFromRevisionIndex: Int? = null,
        mergeFromRevisionIndex: Int? = null,
        mergeFieldPaths: List<String>? = null,
    ): SourceNodeActionTool.Input = SourceNodeActionTool.Input(
        projectId = pid.value,
        action = "update_body",
        nodeId = nodeId.value,
        body = body,
        restoreFromRevisionIndex = restoreFromRevisionIndex,
        mergeFromRevisionIndex = mergeFromRevisionIndex,
        mergeFieldPaths = mergeFieldPaths,
    )

    private fun revision(body: JsonObject, atMs: Long = 1_700_000_000_000L): BodyRevision =
        BodyRevision(body = body, overwrittenAtEpochMs = atMs)

    // ── 1. Three-mode mutual exclusion ──────────────────────

    @Test fun zeroModesRejected() = runTest {
        val store = CapturingStore()
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(store, pid, nodeId, input())
        }
        val msg = ex.message ?: ""
        assertTrue("requires one of `body`" in msg, "got: $msg")
        assertTrue("restoreFromRevisionIndex" in msg, "expected restore listed; got: $msg")
        assertTrue("mergeFromRevisionIndex" in msg, "expected merge listed; got: $msg")
        assertTrue(
            "select=history, root=${nodeId.value}" in msg,
            "expected source_query remediation; got: $msg",
        )
        assertEquals(0, store.historyCallCount, "no history fetch on zero-mode rejection")
    }

    @Test fun twoModesRejected() = runTest {
        val store = CapturingStore(history = listOf(revision(buildJsonObject { put("k", JsonPrimitive(1)) })))
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(
                    body = buildJsonObject { put("a", JsonPrimitive(1)) },
                    restoreFromRevisionIndex = 0,
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("takes exactly one of" in msg, "got: $msg")
        assertTrue("(got 2)" in msg, "expected mode count cited; got: $msg")
        assertEquals(0, store.historyCallCount, "no history fetch on 2-mode rejection")
    }

    @Test fun threeModesRejected() = runTest {
        val store = CapturingStore()
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(
                    body = JsonObject(emptyMap()),
                    restoreFromRevisionIndex = 0,
                    mergeFromRevisionIndex = 0,
                    mergeFieldPaths = listOf("k"),
                ),
            )
        }
        assertTrue("(got 3)" in (ex.message ?: ""))
    }

    // ── 2. mergeFieldPaths exclusively in merge mode ────────

    @Test fun mergeFieldPathsRejectedWhenNotMergeMode() = runTest {
        // Pin: per impl `if (input.mergeFieldPaths != null && !hasMerge)`.
        // Drift to "silently ignore" would swallow agent typos.
        val store = CapturingStore()
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(
                    body = buildJsonObject { put("a", JsonPrimitive(1)) },
                    mergeFieldPaths = listOf("a"),
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "mergeFieldPaths` requires `mergeFromRevisionIndex` to be set" in msg,
            "got: $msg",
        )
    }

    @Test fun mergeWithEmptyFieldPathsRejected() = runTest {
        val store = CapturingStore()
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(mergeFromRevisionIndex = 0, mergeFieldPaths = emptyList()),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "mergeFromRevisionIndex requires non-empty `mergeFieldPaths`" in msg,
            "got: $msg",
        )
    }

    @Test fun mergeWithNullFieldPathsRejected() = runTest {
        val store = CapturingStore()
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(mergeFromRevisionIndex = 0, mergeFieldPaths = null),
            )
        }
        assertTrue(
            "mergeFromRevisionIndex requires non-empty `mergeFieldPaths`" in (ex.message ?: ""),
        )
    }

    // ── 3. body mode = direct Replace ───────────────────────

    @Test fun bodyModeReturnsReplaceWithExactInput() = runTest {
        val newBody = buildJsonObject {
            put("name", JsonPrimitive("Alice"))
            put("description", JsonPrimitive("New protagonist"))
        }
        val store = CapturingStore()
        val res = resolveBodyEdit(store, pid, nodeId, input(body = newBody))
        assertTrue(res is BodyResolution.Replace)
        assertEquals(newBody, res.body, "body passes through verbatim")
        assertEquals(0, store.historyCallCount, "body mode does NOT fetch history")
    }

    // ── 4. Negative-index rejection ─────────────────────────

    @Test fun restoreNegativeIndexRejected() = runTest {
        for (bad in listOf(-1, -10)) {
            val store = CapturingStore()
            val ex = assertFailsWith<IllegalStateException> {
                resolveBodyEdit(store, pid, nodeId, input(restoreFromRevisionIndex = bad))
            }
            val msg = ex.message ?: ""
            assertTrue(
                "restoreFromRevisionIndex must be non-negative (got $bad)" in msg,
                "got: $msg",
            )
            assertTrue("0 = newest historical revision" in msg, "expected 0=newest hint; got: $msg")
            assertEquals(0, store.historyCallCount, "no history fetch on bad index")
        }
    }

    @Test fun mergeNegativeIndexRejected() = runTest {
        val store = CapturingStore()
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(mergeFromRevisionIndex = -1, mergeFieldPaths = listOf("k")),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("mergeFromRevisionIndex must be non-negative" in msg, "got: $msg")
    }

    // ── 5. Bounds-check ─────────────────────────────────────

    @Test fun restoreEmptyHistoryRejected() = runTest {
        // Pin: empty history → "nothing to restore" + remediation hint
        // about source_query(select=history). Drift to "throw NPE" or
        // silent default-empty would mislead the agent.
        val store = CapturingStore(history = emptyList())
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(store, pid, nodeId, input(restoreFromRevisionIndex = 0))
        }
        val msg = ex.message ?: ""
        assertTrue(
            "Source node ${nodeId.value} has no body-history entries" in msg,
            "got: $msg",
        )
        assertTrue("nothing to restore" in msg, "got: $msg")
        assertTrue(
            "Either this node was never updated, or the bundle was created before" in msg,
            "expected dual-cause hint; got: $msg",
        )
    }

    @Test fun mergeEmptyHistorySaysMergeFromInError() = runTest {
        // Pin: same empty-history check but with "merge from" wording.
        val store = CapturingStore(history = emptyList())
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(mergeFromRevisionIndex = 0, mergeFieldPaths = listOf("k")),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("nothing to merge from" in msg, "got: $msg")
    }

    @Test fun restoreOutOfRangeIndexRejected() = runTest {
        // Pin: history has 2 entries, request idx=5 → out of range.
        // Per impl: re-fetches with limit=100 to compute trueWindow size.
        val store = CapturingStore(
            history = listOf(
                revision(buildJsonObject { put("k", JsonPrimitive(1)) }),
                revision(buildJsonObject { put("k", JsonPrimitive(2)) }),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(store, pid, nodeId, input(restoreFromRevisionIndex = 5))
        }
        val msg = ex.message ?: ""
        assertTrue(
            "restoreFromRevisionIndex=5 is out of range" in msg,
            "got: $msg",
        )
        assertTrue("only 2 historical revision(s) available" in msg, "got: $msg")
        assertTrue("valid indices 0..1" in msg, "got: $msg")
    }

    // ── 6. Merge JsonObject + field-presence guards ─────────

    @Test fun mergeWithMissingFieldPathRejected() = runTest {
        // Pin: every key in mergeFieldPaths must be present in the
        // historical revision. Drift to "skip missing" would silently
        // drop fields.
        val historicalBody = buildJsonObject {
            put("name", JsonPrimitive("Alice"))
            put("description", JsonPrimitive("old desc"))
        }
        val store = CapturingStore(history = listOf(revision(historicalBody)))
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(
                    mergeFromRevisionIndex = 0,
                    mergeFieldPaths = listOf("name", "description", "notInHistory"),
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "mergeFieldPaths contains keys not present in historical revision 0" in msg,
            "got: $msg",
        )
        assertTrue("notInHistory" in msg, "missing key cited; got: $msg")
        assertTrue("name" in msg && "description" in msg, "available keys cited; got: $msg")
        assertTrue(
            "If you intend to drop these fields, pass an explicit `body`" in msg,
            "got: $msg",
        )
    }

    @Test fun mergeWithNonObjectHistoryRejected() = runTest {
        // Pin: per kdoc on BodyRevision, body is JsonElement (not
        // forced to JsonObject). When a node's history happens to be
        // a non-object (rare, but possible), merge can't run.
        val nonObjectBody = JsonArray(listOf(JsonPrimitive("string-body")))
        val store = CapturingStore(
            history = listOf(BodyRevision(body = nonObjectBody, overwrittenAtEpochMs = 0L)),
        )
        val ex = assertFailsWith<IllegalStateException> {
            resolveBodyEdit(
                store,
                pid,
                nodeId,
                input(mergeFromRevisionIndex = 0, mergeFieldPaths = listOf("k")),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "history entry 0 is not a JSON object" in msg,
            "got: $msg",
        )
        assertTrue(
            "cannot per-field merge from a non-object revision" in msg,
            "got: $msg",
        )
    }

    // ── Happy paths: Restore + Merge ─────────────────────────

    @Test fun restoreModeReturnsReplaceWithHistoricalBody() = runTest {
        val historical = buildJsonObject {
            put("name", JsonPrimitive("OldName"))
            put("description", JsonPrimitive("old desc"))
        }
        val store = CapturingStore(history = listOf(revision(historical)))
        val res = resolveBodyEdit(store, pid, nodeId, input(restoreFromRevisionIndex = 0))
        assertTrue(res is BodyResolution.Replace, "restore mode returns Replace, NOT Merge")
        assertEquals(historical, res.body, "body equals historical revision verbatim")
        assertEquals(1, store.historyCallCount, "history fetched once")
        assertEquals(1, store.lastLimit, "limit = idx + 1 = 1 (avoid over-fetch)")
    }

    @Test fun mergeModeReturnsMergeWithHistoricalAndPaths() = runTest {
        val historical = buildJsonObject {
            put("name", JsonPrimitive("Alice"))
            put("description", JsonPrimitive("hist desc"))
            put("voice", JsonPrimitive("alto"))
        }
        val store = CapturingStore(history = listOf(revision(historical)))
        val res = resolveBodyEdit(
            store,
            pid,
            nodeId,
            input(mergeFromRevisionIndex = 0, mergeFieldPaths = listOf("name", "voice")),
        )
        assertTrue(res is BodyResolution.Merge, "merge mode returns Merge, NOT Replace")
        assertEquals(historical, res.historicalBody, "historicalBody = historical revision verbatim")
        assertEquals(listOf("name", "voice"), res.fieldPaths, "fieldPaths = input verbatim")
    }

    @Test fun restoreOlderRevisionViaIndex() = runTest {
        // Pin: idx=2 fetches the 3rd most-recent revision (window has
        // newest first; window[2] is the 3rd entry).
        val rev0 = buildJsonObject { put("v", JsonPrimitive("newest")) }
        val rev1 = buildJsonObject { put("v", JsonPrimitive("middle")) }
        val rev2 = buildJsonObject { put("v", JsonPrimitive("oldest")) }
        val store = CapturingStore(history = listOf(revision(rev0), revision(rev1), revision(rev2)))
        val res = resolveBodyEdit(store, pid, nodeId, input(restoreFromRevisionIndex = 2))
        assertTrue(res is BodyResolution.Replace)
        assertEquals(rev2, res.body, "idx=2 picks window[2] (oldest of the 3)")
        assertEquals(3, store.lastLimit, "limit=idx+1=3 — fetches just enough to cover the index")
    }

    @Test fun zeroIndexPicksNewestRevision() = runTest {
        val newest = buildJsonObject { put("v", JsonPrimitive("newest")) }
        val older = buildJsonObject { put("v", JsonPrimitive("older")) }
        val store = CapturingStore(history = listOf(revision(newest), revision(older)))
        val res = resolveBodyEdit(store, pid, nodeId, input(restoreFromRevisionIndex = 0))
        assertEquals(newest, (res as BodyResolution.Replace).body, "idx=0 picks newest")
        assertEquals(1, store.lastLimit, "limit=1 (idx 0 only needs 1 entry)")
    }
}
