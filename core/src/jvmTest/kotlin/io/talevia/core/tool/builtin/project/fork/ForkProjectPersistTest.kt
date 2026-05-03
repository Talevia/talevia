package io.talevia.core.tool.builtin.project.fork

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ForkProjectTool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [persistFork] —
 * `core/tool/builtin/project/fork/ForkProjectPersist.kt`. The two-
 * branch persistence shape selector for `ForkProjectTool`: explicit
 * `path` → `createAt`+`mutate`; default-home (no path) →
 * `resolveDefaultHomeProjectId`+`upsert`. Cycle 213 audit: 77 LOC, 0
 * direct test refs (only via the `ForkProjectToolTest` dispatcher
 * tests at one happy-path each).
 *
 * Same audit-pattern fallback as cycles 207-212. Sibling extraction
 * to cycle 211's `applyVariantSpec` and cycle 212's
 * `regenerateTtsInLanguage` from the same `debt-split-fork-project-tool`
 * cycle. Direct testing reaches branches the dispatcher test never
 * does — the blank-path-treated-as-default-home edge, the
 * snapshots-stripped invariant, the parent-id stamp, the variantSpec
 * compose-with-persist behaviour.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Two-branch persistence shape selector.** `path != null &&
 *     isNotBlank()` → `createAt` at the explicit path. Otherwise →
 *     `resolveDefaultHomeProjectId` + collision-check + `upsert`.
 *     Drift to "always createAt" or "always upsert" would either
 *     refuse default-home forks or land path-explicit forks at the
 *     wrong filesystem location.
 *
 *  2. **Blank path → default-home branch.** `path = ""` and
 *     `path = "  "` are treated as absent (per impl `isNotBlank()`).
 *     Drift to "any non-null path" would crash on
 *     `"".toPath().createAt(...)`.
 *
 *  3. **Default-home collision check.** When a project already
 *     exists at the candidate id, `persistFork` fails fast with the
 *     "already exists" remediation hint citing newProjectId +
 *     list_projects. Drift to silent overwrite would clobber an
 *     unrelated project.
 *
 *  4. **`baseFork` strips snapshots + sets parentProjectId.** Even
 *     when the source payload carries snapshots, the persisted fork
 *     body has `snapshots = emptyList()` (history does NOT transfer
 *     to forks — VISION §3.4). And `parentProjectId = sourcePid`
 *     stamps lineage so forward-navigation queries can see the
 *     fork's origin.
 *
 *  5. **VariantSpec composes with persist.** When `input.variantSpec
 *     != null`, the persisted body is the reshape's project (NOT the
 *     pre-reshape baseFork) — the dropped/truncated counters in
 *     `ForkPersistResult.reshape` accurately reflect the persisted
 *     state. Drift to "persist baseFork, count reshape after" would
 *     report counts against a body the user never sees.
 *
 *  6. **`forked` is re-read from store.** Per impl `projects.get(pid)
 *     ?: error(...)`. The returned `forked` project carries any
 *     post-store stamping (recency, asset id resolution) that
 *     subsequent fork-tool steps depend on. Drift to "return the
 *     in-memory forkBody" would skip that stamping.
 *
 * Plus shape pins: explicit-path branch passes `payload.timeline` +
 * `payload.outputProfile` to `createAt` (so the bundle's initial
 * shape matches); ProjectId returned in `ForkPersistResult` is the
 * id used for `mutate` / `upsert`; reshape == null when variantSpec
 * is null.
 */
class ForkProjectPersistTest {

    private fun videoClip(
        id: String,
        start: Long = 0,
        dur: Long = 5,
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, dur.seconds),
        sourceRange = TimeRange(0.seconds, dur.seconds),
        assetId = AssetId("a-$id"),
    )

    private fun payloadWith(
        clips: List<Clip> = emptyList(),
        timelineDuration: Long = 0,
        snapshots: List<ProjectSnapshot> = emptyList(),
        resolution: Resolution = Resolution(1920, 1080),
    ): Project = Project(
        id = ProjectId("placeholder-source-or-stale-id"),
        timeline = Timeline(
            tracks = if (clips.isEmpty()) emptyList() else listOf(
                Track.Video(id = TrackId("v"), clips = clips),
            ),
            duration = timelineDuration.seconds,
            resolution = resolution,
        ),
        outputProfile = OutputProfile(
            resolution = resolution,
            frameRate = FrameRate.FPS_30,
        ),
        snapshots = snapshots,
    )

    private fun input(
        sourceProjectId: String = "p-source",
        newTitle: String = "Forked",
        newProjectId: String? = null,
        path: String? = null,
        variantSpec: ForkProjectTool.VariantSpec? = null,
    ): ForkProjectTool.Input = ForkProjectTool.Input(
        sourceProjectId = sourceProjectId,
        newTitle = newTitle,
        newProjectId = newProjectId,
        path = path,
        variantSpec = variantSpec,
    )

    // ── 1. Two-branch persistence shape selector ────────────

    @Test fun explicitPathBranchUsesCreateAt() = runTest {
        // Pin: when input.path is supplied, persistFork uses
        // ProjectStore.createAt — the bundle lands at the
        // requested filesystem location. The store assigns the id
        // (NOT input.newProjectId).
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(clips = listOf(videoClip("c1"))),
            input = input(
                newTitle = "Forked",
                path = "/projects/explicit-path",
            ),
        )
        // Bundle landed on disk at the explicit path.
        val bundlePath = store.pathOf(result.pid)
        assertEquals("/projects/explicit-path", bundlePath?.toString())
        // Project re-readable.
        assertEquals(result.forked, store.get(result.pid))
    }

    @Test fun defaultHomeBranchUsesUpsertWithSlugId() = runTest {
        // Pin: no path → default-home branch. Without explicit
        // newProjectId, id is slug(newTitle).
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(),
            input = input(newTitle = "My Forked Project", newProjectId = null, path = null),
        )
        // Slug from "My Forked Project" → e.g. "my-forked-project".
        // We don't pin the exact slug rule (that's tested elsewhere),
        // only that the id is non-empty + the project landed.
        assertTrue(result.pid.value.isNotEmpty(), "default-home assigns a non-empty id")
        assertEquals(result.forked, store.get(result.pid))
    }

    @Test fun defaultHomeBranchUsesExplicitNewProjectIdWhenSet() = runTest {
        // Pin: per resolveDefaultHomeProjectId, explicit non-blank
        // newProjectId wins over slug derivation.
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(),
            input = input(newTitle = "Whatever", newProjectId = "explicit-id-wins"),
        )
        assertEquals(ProjectId("explicit-id-wins"), result.pid)
    }

    // ── 2. Blank path → default-home ───────────────────────

    @Test fun blankPathFallsThroughToDefaultHomeBranch() = runTest {
        // Marquee blank-path pin: per impl `path != null &&
        // path.isNotBlank()`. Drift to "any non-null path" would
        // crash on "".toPath() → "" parsed as a relative path under
        // the current filesystem root.
        // Use a tagged unique id per iteration since otherwise the
        // second blank case ('' / ' ' / '\t' all of length 0/1/1)
        // would collide on the persisted id.
        val store = ProjectStoreTestKit.create()
        val cases = listOf("empty" to "", "space" to " ", "tab" to "\t")
        for ((tag, blank) in cases) {
            val result = persistFork(
                projects = store,
                sourcePid = ProjectId("p-source"),
                payload = payloadWith(),
                input = input(
                    newTitle = "T-blank-$tag",
                    newProjectId = "blank-$tag",
                    path = blank,
                ),
            )
            // Default-home: id is the explicit newProjectId (NOT
            // a slug of the blank path). Drift to "any non-null
            // path" would crash on "".toPath().createAt(...) before
            // reaching the upsert.
            assertEquals(ProjectId("blank-$tag"), result.pid)
            // Persistence succeeded — project re-readable.
            assertNotNull(
                store.get(result.pid),
                "blank-path fork persisted via default-home upsert (tag=$tag)",
            )
        }
    }

    // ── 3. Default-home collision check ─────────────────────

    @Test fun defaultHomeCollisionFailsLoudWithRemediation() = runTest {
        val store = ProjectStoreTestKit.create()
        // Seed an existing project with the candidate id.
        store.upsert(
            "Existing",
            Project(
                id = ProjectId("collision-id"),
                timeline = Timeline(),
            ),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            persistFork(
                projects = store,
                sourcePid = ProjectId("p-source"),
                payload = payloadWith(),
                input = input(newProjectId = "collision-id", path = null),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "project collision-id already exists" in msg,
            "expected collision message; got: $msg",
        )
        assertTrue(
            "pick a different newProjectId" in msg,
            "expected newProjectId remediation; got: $msg",
        )
        assertTrue(
            "list_projects" in msg,
            "expected list_projects remediation hint; got: $msg",
        )
    }

    // ── 4. baseFork strips snapshots + sets parentProjectId ──

    @Test fun baseForkStripsSnapshots() = runTest {
        // Pin: even when source payload carries snapshots, the
        // persisted fork body has `snapshots = emptyList()`.
        // History does NOT transfer to forks (VISION §3.4 — fork
        // is a fresh history root).
        val store = ProjectStoreTestKit.create()
        val payload = payloadWith(
            snapshots = listOf(
                ProjectSnapshot(
                    id = ProjectSnapshotId("snap-1"),
                    label = "v1",
                    capturedAtEpochMs = 1000L,
                    project = payloadWith(),
                ),
            ),
        )
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payload,
            input = input(newProjectId = "fork-without-snaps", path = null),
        )
        assertTrue(
            result.forked.snapshots.isEmpty(),
            "fork starts clean — source snapshots NOT transferred",
        )
    }

    @Test fun baseForkSetsParentProjectIdToSource() = runTest {
        // Pin: parentProjectId = sourcePid stamps lineage so
        // forward-nav queries see the fork's origin. Drift to
        // "parentProjectId = null" would lose lineage.
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-original-source"),
            payload = payloadWith(),
            input = input(newProjectId = "child", path = null),
        )
        assertEquals(
            ProjectId("p-original-source"),
            result.forked.parentProjectId,
            "fork's parentProjectId stamps lineage",
        )
    }

    @Test fun baseForkSetsParentProjectIdInExplicitPathBranchToo() = runTest {
        // Pin: lineage stamping applies to BOTH branches (the
        // explicit-path branch was historically separate; this pin
        // guards against drift in either branch).
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-original-source"),
            payload = payloadWith(),
            input = input(path = "/projects/explicit-fork"),
        )
        assertEquals(ProjectId("p-original-source"), result.forked.parentProjectId)
    }

    // ── 5. VariantSpec composes with persist ────────────────

    @Test fun variantSpecAppliedBeforeWriteAndReshapeReturned() = runTest {
        // Marquee compose pin: when variantSpec is set, the
        // persisted body is the RESHAPED body (not the original
        // payload). The dropped/truncated counters in
        // ForkPersistResult.reshape match what landed.
        val store = ProjectStoreTestKit.create()
        val payload = payloadWith(
            clips = listOf(
                videoClip("kept", start = 0, dur = 2),
                videoClip("dropped", start = 6, dur = 2),
            ),
            timelineDuration = 8,
        )
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payload,
            input = input(
                newProjectId = "fork-with-cap",
                path = null,
                variantSpec = ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
            ),
        )
        // Reshape result is non-null + counts what dropped.
        val reshape = result.reshape
        assertNotNull(reshape, "variantSpec set → reshape non-null")
        assertEquals(1, reshape.clipsDropped, "one clip dropped beyond cap")
        assertEquals(0, reshape.clipsTruncated)
        // Persisted body matches the reshape.
        val clips = result.forked.timeline.tracks.flatMap { it.clips }
        assertEquals(
            listOf("kept"),
            clips.map { it.id.value },
            "persisted body matches reshape (NOT pre-reshape payload)",
        )
        assertEquals(4.seconds, result.forked.timeline.duration, "duration capped to 4s")
    }

    @Test fun noVariantSpecLeavesReshapeNull() = runTest {
        // Pin: input.variantSpec == null → result.reshape == null.
        // Distinct from "variantSpec(empty) → reshape with zero
        // counts" — the null case skips reshape entirely.
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(clips = listOf(videoClip("c1"))),
            input = input(newProjectId = "no-spec", path = null, variantSpec = null),
        )
        assertNull(result.reshape, "no variantSpec → reshape == null")
        // Body NOT reshaped — original clip preserved.
        val clips = result.forked.timeline.tracks.flatMap { it.clips }
        assertEquals(listOf("c1"), clips.map { it.id.value })
    }

    @Test fun variantSpecWithAspectRewriteAppliedInExplicitPathBranchToo() = runTest {
        // Pin: the variantSpec compose path applies to BOTH
        // branches (explicit-path and default-home). Use aspect
        // rewrite — easy to verify post-write resolution.
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(resolution = Resolution(1920, 1080)),
            input = input(
                path = "/projects/vertical-fork",
                variantSpec = ForkProjectTool.VariantSpec(aspectRatio = "9:16"),
            ),
        )
        assertNotNull(result.reshape)
        assertEquals(Resolution(1080, 1920), result.forked.timeline.resolution)
    }

    // ── 6. Forked is re-read from store ─────────────────────

    @Test fun forkedIsReReadFromStoreNotInMemoryCopy() = runTest {
        // Pin: `forked` carries post-store-write state — recency
        // stamps, asset id stamping, etc. Drift to "return the
        // in-memory forkBody" would skip that stamping.
        // We pin this by checking the stamped recency field on
        // `forked.assets`/`forked.timeline.tracks` is non-null
        // post-write (FileProjectStore stamps `updatedAtEpochMs`).
        val store = ProjectStoreTestKit.create()
        val payload = payloadWith(clips = listOf(videoClip("c1")))
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payload,
            input = input(newProjectId = "stamped-fork", path = null),
        )
        // The store is FileProjectStore — recency stamps clips on
        // upsert. So the persisted clip's stamp is non-null even
        // though the in-memory payload had it null.
        val clip = result.forked.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c1" }
        assertNotNull(
            clip.updatedAtEpochMs,
            "re-read fork carries post-store recency stamp (in-memory payload had null)",
        )
    }

    // ── Shape pins ──────────────────────────────────────────

    @Test fun explicitPathBranchPassesTimelineAndOutputProfileToCreateAt() = runTest {
        // Pin: the createAt call gets payload.timeline +
        // payload.outputProfile (so the bundle's initial shape
        // matches the source). Drift to "default Timeline()" would
        // ship a 1080p 30fps bundle even when the source is 4k 60.
        val store = ProjectStoreTestKit.create()
        val customResolution = Resolution(2560, 1440)
        val payload = payloadWith(resolution = customResolution)
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payload,
            input = input(path = "/projects/4k-fork"),
        )
        assertEquals(customResolution, result.forked.timeline.resolution)
        assertEquals(customResolution, result.forked.outputProfile.resolution)
    }

    @Test fun resultPidEqualsCreateAtAssignedIdInExplicitPathBranch() = runTest {
        // Pin: in the explicit-path branch, the returned pid is
        // what `createAt` assigned (NOT input.newProjectId, which
        // is irrelevant in this branch — the bundle dir's id wins).
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(),
            input = input(
                newProjectId = "newProjectId-IGNORED-in-path-branch",
                path = "/projects/path-assigned",
            ),
        )
        // The id is assigned by FileProjectStore.createAt — typically
        // derived from the path's last segment. We don't pin the
        // exact value (that's createAt's contract), only that
        // input.newProjectId is NOT what came back.
        assertTrue(
            result.pid.value != "newProjectId-IGNORED-in-path-branch",
            "explicit-path branch ignores input.newProjectId; got: ${result.pid.value}",
        )
    }

    @Test fun emptyTimelineWithVariantSpecStillPersists() = runTest {
        // Edge case: empty timeline + variantSpec doesn't crash.
        // Reshape produces zero counts but reshape itself is non-null.
        val store = ProjectStoreTestKit.create()
        val result = persistFork(
            projects = store,
            sourcePid = ProjectId("p-source"),
            payload = payloadWith(),
            input = input(
                newProjectId = "empty-with-spec",
                variantSpec = ForkProjectTool.VariantSpec(durationSecondsMax = 5.0),
            ),
        )
        assertNotNull(result.reshape)
        assertEquals(0, result.reshape!!.clipsDropped)
        assertEquals(0, result.reshape!!.clipsTruncated)
    }
}
