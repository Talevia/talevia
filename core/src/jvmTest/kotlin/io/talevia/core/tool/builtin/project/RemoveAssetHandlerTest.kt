package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [executeRemoveAsset] —
 * `core/tool/builtin/project/RemoveAssetHandler.kt`. The
 * `project_lifecycle_action(action="remove_asset")`
 * handler. Cycle 187 audit: 89 LOC, 0 direct test refs
 * (used through full-tool integration but the
 * dependent-clip detection, force-flag semantics, the
 * "does NOT cascade" anti-feature, and outputForLlm dual-
 * branch summary were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Refuses by default when any clip references the
 *    asset; force=true overrides.** The marquee Unix
 *    `rm -f` semantics. Drift to "always remove" would
 *    silently produce dangling clips on every careless
 *    delete; drift to "always refuse" would block
 *    intentional cleanups. Plus dependent-clip detection
 *    walks Video + Audio assetId BUT NOT Text (text
 *    clips don't carry assetId).
 *
 * 2. **Does NOT cascade to dependent clips.** Per kdoc:
 *    "Does NOT cascade to dependent clips — keeping the
 *    surface small means the agent composes
 *    `clip_action(action=remove)` + this verb explicitly."
 *    Drift to "auto-cascade-removeClip" would silently
 *    remove user content. Pinned by checking
 *    `force=true` removes the asset but leaves clips in
 *    the timeline.
 *
 * 3. **Does NOT touch asset bytes** (cross-project
 *    sharing is real). Per kdoc: "byte-level GC is a
 *    separate concern." This handler is catalog-only.
 *    Drift to "delete bytes too" would break shared
 *    references across projects.
 */
class RemoveAssetHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun mediaAsset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File(path = "/tmp/$id.mp4"),
        metadata = MediaMetadata(duration = 1.seconds),
    )

    private fun videoClip(
        id: String,
        assetId: String,
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
        sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
        assetId = AssetId(assetId),
    )

    private fun audioClip(id: String, assetId: String): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
        sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
        assetId = AssetId(assetId),
    )

    private fun textClip(id: String): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
        text = "hi",
    )

    private suspend fun newProjectWithAssets(
        store: io.talevia.core.domain.FileProjectStore,
        assetIds: List<String>,
        clips: List<Clip> = emptyList(),
    ): io.talevia.core.domain.Project {
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Test",
        )
        // Mutate to add assets + clips.
        return store.mutate(created.id) { p ->
            p.copy(
                assets = assetIds.map { mediaAsset(it) },
                timeline = Timeline(
                    tracks = if (clips.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(Track.Video(id = TrackId("v"), clips = clips))
                    },
                ),
            )
        }
    }

    private fun removeInput(
        projectId: String?,
        assetId: String?,
        force: Boolean = false,
    ): ProjectLifecycleActionTool.Input = ProjectLifecycleActionTool.Input(
        action = "remove_asset",
        projectId = projectId,
        assetId = assetId,
        force = force,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingProjectIdThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = null, assetId = "a1"),
                ctx = ctx,
            )
        }
        assertTrue("requires `projectId`" in (ex.message ?: ""))
    }

    @Test fun missingAssetIdThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = "p", assetId = null),
                ctx = ctx,
            )
        }
        assertTrue("requires `assetId`" in (ex.message ?: ""))
    }

    @Test fun missingProjectThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = "ghost", assetId = "a1"),
                ctx = ctx,
            )
        }
        assertTrue("project ghost not found" in (ex.message ?: ""))
    }

    @Test fun assetNotInProjectThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(store, listOf("a1"))
        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = project.id.value, assetId = "ghost-asset"),
                ctx = ctx,
            )
        }
        assertTrue(
            "asset ghost-asset not found" in (ex.message ?: ""),
            "expected asset-not-found phrase; got: ${ex.message}",
        )
    }

    // ── No dependents: removes silently ──────────────────────

    @Test fun assetWithNoDependentClipsRemovesSilently() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(store, listOf("a1", "a2"))

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1"),
            ctx = ctx,
        )

        // Catalog: a1 gone, a2 remains.
        val updated = store.get(project.id)!!
        assertEquals(listOf(AssetId("a2")), updated.assets.map { it.id })
        // Result reflects removal.
        val rar = result.data.removeAssetResult!!
        assertEquals("a1", rar.assetId)
        assertEquals(true, rar.removed)
        assertEquals(emptyList(), rar.dependentClips, "no dependents")
        assertTrue(
            "No clips referenced it" in result.outputForLlm,
            "outputForLlm cites no-deps; got: ${result.outputForLlm}",
        )
    }

    // ── Has dependents WITHOUT force: refuses ────────────────

    @Test fun assetWithDependentClipsAndNoForceRefuses() = runTest {
        // Marquee refuse-by-default pin.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1"),
            clips = listOf(videoClip("c1", "a1"), videoClip("c2", "a1")),
        )

        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = project.id.value, assetId = "a1", force = false),
                ctx = ctx,
            )
        }
        // Error message lists dependent clips + cites force.
        assertTrue("a1" in (ex.message ?: ""), "asset cited; got: ${ex.message}")
        assertTrue("2 clip(s)" in (ex.message ?: ""), "count cited; got: ${ex.message}")
        assertTrue("c1" in (ex.message ?: "") && "c2" in (ex.message ?: ""), "dep ids cited")
        assertTrue(
            "force=true" in (ex.message ?: ""),
            "force-true escape hatch cited; got: ${ex.message}",
        )
        assertTrue(
            "Remove those clips first" in (ex.message ?: ""),
            "documented hint cited; got: ${ex.message}",
        )

        // Asset NOT removed (refusal preserves catalog).
        val updated = store.get(project.id)!!
        assertEquals(1, updated.assets.size, "asset preserved on refusal")
    }

    // ── Has dependents WITH force: removes anyway ────────────

    @Test fun assetWithDependentClipsAndForceTrueRemoves() = runTest {
        // Marquee force-override pin: rm -f semantics.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1"),
            clips = listOf(videoClip("c1", "a1")),
        )

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1", force = true),
            ctx = ctx,
        )

        // Asset gone.
        val updated = store.get(project.id)!!
        assertEquals(0, updated.assets.size)
        // Dependent clips reported.
        val rar = result.data.removeAssetResult!!
        assertEquals(listOf("c1"), rar.dependentClips)
        assertEquals(true, rar.removed)
    }

    // ── Marquee NO-CASCADE pin ───────────────────────────────

    @Test fun forceRemoveDoesNotCascadeRemoveDependentClips() = runTest {
        // Marquee anti-cascade pin: per kdoc "Does NOT
        // cascade to dependent clips." Drift to
        // "auto-cascade-removeClip" would silently delete
        // user content.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1"),
            clips = listOf(videoClip("c1", "a1"), videoClip("c2", "a1")),
        )

        executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1", force = true),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        // Asset gone.
        assertEquals(0, updated.assets.size)
        // Clips REMAIN in the timeline (now dangling).
        val remainingClips = updated.timeline.tracks.flatMap { it.clips }
        assertEquals(2, remainingClips.size, "clips NOT cascaded — agent must remove them explicitly")
        assertEquals(
            setOf(ClipId("c1"), ClipId("c2")),
            remainingClips.map { it.id }.toSet(),
        )
    }

    // ── Dependent-clip detection: kind discrimination ────────

    @Test fun dependentDetectionIncludesAudioClipsWithMatchingAssetId() = runTest {
        // Pin: per impl `is Clip.Audio -> clip.assetId`,
        // audio clips also count as dependents. Drift to
        // "video only" would let asset removal succeed
        // even when audio clips reference it.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1"),
            clips = listOf(audioClip("c-audio", "a1")),
        )

        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = project.id.value, assetId = "a1", force = false),
                ctx = ctx,
            )
        }
        assertTrue("c-audio" in (ex.message ?: ""), "audio clip detected")
        assertTrue("1 clip(s)" in (ex.message ?: ""))
    }

    @Test fun dependentDetectionExcludesTextClips() = runTest {
        // Pin: per impl `is Clip.Text -> null`, text clips
        // are excluded from dependent detection (text
        // clips don't carry an assetId reference).
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1"),
            clips = listOf(textClip("text-1")),
        )

        // No dependent clips → removes silently.
        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1", force = false),
            ctx = ctx,
        )
        assertEquals(true, result.data.removeAssetResult!!.removed)
        assertEquals(emptyList(), result.data.removeAssetResult!!.dependentClips)
    }

    @Test fun dependentDetectionMixedClipsCountsOnlyMatchingAssetIds() = runTest {
        // Pin: only clips with `assetId == targetId` count.
        // Clips referencing OTHER assets are not in
        // dependentClips.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1", "a2"),
            clips = listOf(
                videoClip("c-keep", "a2"),
                videoClip("c-dep", "a1"),
                textClip("c-text"),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            executeRemoveAsset(
                projects = store,
                input = removeInput(projectId = project.id.value, assetId = "a1", force = false),
                ctx = ctx,
            )
        }
        // Only c-dep cited.
        assertTrue("c-dep" in (ex.message ?: ""), "matching dep cited")
        assertTrue("1 clip(s)" in (ex.message ?: ""), "count = 1")
        assertTrue(
            "c-keep" !in (ex.message ?: ""),
            "non-matching clip NOT cited; got: ${ex.message}",
        )
    }

    // ── Force has no effect when there are no dependents ─────

    @Test fun forceTrueWithNoDependentsIsEquivalentToForceFalse() = runTest {
        // Pin: force=true is no-op when no dependents.
        // Verify same outcome as force=false.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(store, listOf("a1"))

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1", force = true),
            ctx = ctx,
        )
        assertEquals(true, result.data.removeAssetResult!!.removed)
        assertEquals(emptyList(), result.data.removeAssetResult!!.dependentClips)
        // outputForLlm follows the no-deps branch.
        assertTrue("No clips referenced it" in result.outputForLlm)
    }

    // ── outputForLlm dual-branch summary ─────────────────────

    @Test fun outputForLlmDanglingBranchCitesValidationHint() = runTest {
        // Pin: force-removed asset with dependents → body
        // cites count + dangling ids + validation-query
        // hint.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(
            store,
            assetIds = listOf("a1"),
            clips = listOf(videoClip("c1", "a1"), videoClip("c2", "a1")),
        )

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1", force = true),
            ctx = ctx,
        )
        assertTrue("Removed asset a1" in result.outputForLlm)
        assertTrue("2 clip(s) now dangle" in result.outputForLlm)
        assertTrue("c1" in result.outputForLlm && "c2" in result.outputForLlm)
        assertTrue(
            "project_query(select=validation)" in result.outputForLlm,
            "validation-query hint cited; got: ${result.outputForLlm}",
        )
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputProjectIdAndActionEchoed() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(store, listOf("a1"))

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1"),
            ctx = ctx,
        )
        assertEquals(project.id.value, result.data.projectId)
        assertEquals("remove_asset", result.data.action)
    }

    @Test fun outputCarriesRemoveAssetResultNotOtherResultFields() = runTest {
        // Polymorphic Output discipline check.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(store, listOf("a1"))

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "a1"),
            ctx = ctx,
        )
        assertTrue(result.data.removeAssetResult != null)
        assertTrue(result.data.createResult == null)
        assertTrue(result.data.deleteResult == null)
        assertTrue(result.data.openResult == null)
        assertTrue(result.data.renameResult == null)
    }

    @Test fun toolResultTitleCitesAssetId() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAssets(store, listOf("memorable-asset-id"))

        val result = executeRemoveAsset(
            projects = store,
            input = removeInput(projectId = project.id.value, assetId = "memorable-asset-id"),
            ctx = ctx,
        )
        assertTrue("memorable-asset-id" in result.title!!)
        assertTrue("remove asset" in result.title!!)
    }
}
