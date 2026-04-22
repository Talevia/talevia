package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Edge-case coverage for [AigcBudgetGuard]. The guard is the critical
 * path for VISION §5.2 spend-gating, so tests must cover: null cap
 * (no-op), spend < cap (no-op), spend == cap (ASK), spend > cap (ASK),
 * Reject branch (throws), Once branch (proceeds), Always branch
 * (proceeds), missing project (no-op), null projectId (no-op), entries
 * with null costCents (ignored in the sum), cross-session entries
 * (filtered out).
 */
class AigcBudgetGuardTest {

    private val sid = SessionId("s-budget")
    private val pid = ProjectId("p-budget")
    private val other = SessionId("s-other")

    private fun ctx(
        cap: Long?,
        askPermission: suspend (PermissionRequest) -> PermissionDecision = { PermissionDecision.Once },
    ): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = askPermission,
        emitPart = { },
        messages = emptyList(),
        spendCapCents = cap,
    )

    private fun freshStore(): FileProjectStore {
        return ProjectStoreTestKit.create()
    }

    private suspend fun FileProjectStore.seedProject(entries: List<LockfileEntry> = emptyList()) {
        upsert(
            "demo",
            Project(id = pid, timeline = Timeline(), assets = emptyList()),
        )
        if (entries.isNotEmpty()) {
            mutate(pid) { project ->
                project.copy(
                    assets = project.assets + entries.map {
                        MediaAsset(
                            id = it.assetId,
                            source = MediaSource.File("/tmp/${it.assetId.value}.png"),
                            metadata = MediaMetadata(duration = 0.seconds),
                        )
                    },
                    lockfile = entries.fold(project.lockfile) { acc, e -> acc.append(e) },
                )
            }
        }
    }

    private fun entry(
        hash: String,
        session: SessionId?,
        cents: Long?,
        asset: String = "a-$hash",
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(asset),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        baseInputs = JsonObject(emptyMap()),
        costCents = cents,
        sessionId = session?.value,
    )

    @Test fun nullCapIsAlwaysNoOp() = runTest {
        val store = freshStore()
        store.seedProject(listOf(entry("h1", sid, 9999L)))
        // Even with a massively over-budget history, null cap means no guard.
        var askCount = 0
        AigcBudgetGuard.enforce(
            toolId = "generate_image",
            projectStore = store,
            projectId = pid,
            ctx = ctx(cap = null, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount, "null cap must never trigger a permission ask")
    }

    @Test fun spendUnderCapDoesNotAsk() = runTest {
        val store = freshStore()
        store.seedProject(listOf(entry("h1", sid, 50L)))
        var askCount = 0
        AigcBudgetGuard.enforce(
            "generate_image", store, pid,
            ctx(cap = 100L, askPermission = { askCount++; PermissionDecision.Once }),
        )
        assertEquals(0, askCount)
    }

    @Test fun spendAtCapTriggersAskAndRejectThrows() = runTest {
        // Boundary case: cumulative exactly equals cap → guard must fire.
        val store = freshStore()
        store.seedProject(listOf(entry("h1", sid, 100L)))
        var askCount = 0
        val err = assertFailsWith<IllegalStateException> {
            AigcBudgetGuard.enforce(
                "generate_image", store, pid,
                ctx(cap = 100L, askPermission = { askCount++; PermissionDecision.Reject }),
            )
        }
        assertEquals(1, askCount)
        assertTrue("spend cap" in err.message.orEmpty(), "error must mention the cap, got: ${err.message}")
    }

    @Test fun spendOverCapAskedAndOnceAllowsContinuation() = runTest {
        val store = freshStore()
        store.seedProject(listOf(entry("h1", sid, 500L)))
        var askedRequest: PermissionRequest? = null
        AigcBudgetGuard.enforce(
            "generate_music", store, pid,
            ctx(cap = 100L, askPermission = { req ->
                askedRequest = req
                PermissionDecision.Once
            }),
        )
        assertEquals("aigc.budget", askedRequest?.permission)
        assertEquals("exceeded", askedRequest?.pattern)
        assertEquals("generate_music", askedRequest?.metadata?.get("toolId"))
        assertEquals("100", askedRequest?.metadata?.get("capCents"))
        assertEquals("500", askedRequest?.metadata?.get("currentCents"))
    }

    @Test fun alwaysDecisionProceedsWithoutThrow() = runTest {
        val store = freshStore()
        store.seedProject(listOf(entry("h1", sid, 100L)))
        // Exception not thrown = success.
        AigcBudgetGuard.enforce(
            "generate_image", store, pid,
            ctx(cap = 100L, askPermission = { PermissionDecision.Always }),
        )
    }

    @Test fun crossSessionEntriesDoNotCountAgainstThisSessionsCap() = runTest {
        // Another session in the same project burned $10. This session's
        // own spend is still 0; guard must not fire at cap=$5 because the
        // other session's cost isn't attributed here.
        val store = freshStore()
        store.seedProject(listOf(entry("h1", other, 1000L)))
        var askCount = 0
        AigcBudgetGuard.enforce(
            "generate_image", store, pid,
            ctx(cap = 500L, askPermission = { askCount++; PermissionDecision.Once }),
        )
        assertEquals(0, askCount)
    }

    @Test fun nullCostCentsEntriesAreIgnoredInSum() = runTest {
        // Legacy entries without pricing don't contribute to the cumulative.
        // Otherwise one old entry would silently peg the session over cap.
        val store = freshStore()
        store.seedProject(
            listOf(
                entry("h1", sid, null),
                entry("h2", sid, null),
                entry("h3", sid, 50L),
            ),
        )
        var askCount = 0
        AigcBudgetGuard.enforce(
            "generate_image", store, pid,
            ctx(cap = 100L, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount, "unknown-cost entries must not trigger the cap")
    }

    @Test fun noProjectIdShortCircuitsGuard() = runTest {
        val store = freshStore()
        var askCount = 0
        // No project means no lockfile to sum → guard silently passes
        // (matches the way AIGC tools skip lockfile recording without a pid).
        AigcBudgetGuard.enforce(
            "generate_image",
            projectStore = store,
            projectId = null,
            ctx = ctx(cap = 0L, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount)
    }

    @Test fun missingProjectRowShortCircuitsGuard() = runTest {
        val store = freshStore()
        var askCount = 0
        // Project id given but project row doesn't exist (deleted) → no spend
        // to count, guard passes. Matches SpendQuery's "silent bail".
        AigcBudgetGuard.enforce(
            "generate_image", store, ProjectId("nonexistent"),
            ctx(cap = 0L, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount)
    }

    @Test fun zeroCapBlocksEvenWithZeroSpend() = runTest {
        // cap=0 is the "spend nothing" intent. Every paid AIGC call must ASK,
        // starting with the very first (zero cumulative, cap=0, 0>=0).
        val store = freshStore()
        store.seedProject()  // no entries
        var asked = false
        assertFailsWith<IllegalStateException> {
            AigcBudgetGuard.enforce(
                "generate_image", store, pid,
                ctx(cap = 0L, askPermission = { asked = true; PermissionDecision.Reject }),
            )
        }
        assertTrue(asked)
    }

    @Test fun guardRunsWithEmptyLockfile() = runTest {
        val store = freshStore()
        store.seedProject()
        var askCount = 0
        // cap set but no entries → cumulative 0 < cap → no ask.
        AigcBudgetGuard.enforce(
            "generate_image", store, pid,
            ctx(cap = 100L, askPermission = { askCount++; PermissionDecision.Once }),
        )
        assertEquals(0, askCount)
        assertFalse(askCount > 0)
    }
}
