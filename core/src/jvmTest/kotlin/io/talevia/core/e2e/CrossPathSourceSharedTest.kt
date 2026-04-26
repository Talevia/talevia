package io.talevia.core.e2e

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GeneratedVideo
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.platform.VideoGenResult
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
import io.talevia.core.tool.builtin.source.SourceNodeActionTool
import io.talevia.core.tool.builtin.video.ClipActionTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * VISION §4 invariant — small-user and pro-user paths share the same
 * `Project / Source / Tool Registry`. A source node a small-user agent
 * created (via `source_node_action(action="add")`) must be directly
 * editable by the pro-user mode (via `source_node_action(action="update_body")`)
 * and the consistency-staleness machinery must propagate the edit to
 * downstream clips without a path-specific bridge.
 *
 * This is M3 criterion 3 (`cross-path-source-shared`). The bullet's
 * correctness model is the chain:
 *
 *   small-user creates character_ref →
 *   AIGC (`generate_video` w/ `consistencyBindingIds`) snapshots its
 *     deepContentHash into the lockfile entry's `sourceContentHashes` →
 *   clip lands on timeline pointing at the resulting assetId →
 *   pro-user edits character_ref body via `update_body` →
 *   `staleClipsFromLockfile()` reports the clip as drifted →
 *   re-dispatch `generate_video` mints a NEW lockfile entry whose
 *     snapshotted hash for the same `SourceNodeId` differs from the
 *     pre-edit one (proves the edit physically reached the new
 *     generation, not just the staleness signal).
 *
 * If any link in the chain breaks — e.g. `update_body` doesn't bump
 * contentHash, or `staleClipsFromLockfile` no longer compares deep
 * hashes, or the regenerate path silently uses cached inputs — this
 * test fails on the specific assertion that catches the regression.
 *
 * Test approach. Direct tool-registry dispatch (no LLM / FakeProvider).
 * The cross-path invariant lives at the tool layer — small-user vs pro-
 * user is operational mode for the agent, not separate tool sets — so
 * dispatching the tools directly proves the substrate. Trajectory-level
 * verification of the small-user path lives in [OneShotDraftE2ETest].
 */
class CrossPathSourceSharedTest {

    @Test fun proModeUpdateBodyOnSmallUserCharacterRefStalesClipAndRegenLandsNewHash() = runTest {
        val tmpDir = createTempDirectory("cross-path-").toFile()
        val projectStore = ProjectStoreTestKit.create()
        val pid = ProjectId("cross-path")
        projectStore.upsert(
            "cross-path",
            Project(id = pid, timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO)),
        )

        val videoEngine = ScriptedVideoEngine()
        val registry = ToolRegistry().apply {
            register(SourceNodeActionTool(projectStore))
            register(GenerateVideoTool(videoEngine, FakeBlobWriter(tmpDir), projectStore))
            register(ClipActionTool(projectStore))
        }
        val ctx = ToolContext(
            sessionId = SessionId("cross-path-session"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { AllowAllPermissionService().check(emptyList(), it) },
            emitPart = { },
            messages = emptyList(),
        )

        // === Small-user phase ===========================================
        // 1. Create character_ref via source_node_action.
        registry["source_node_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("action", "add")
                put("nodeId", "hero")
                put("kind", "core.consistency.character_ref")
                putJsonObject("body") {
                    put("name", "Hero")
                    put("visualDescription", "a curious red panda explorer in a denim vest")
                }
            },
            ctx,
        )

        // 2. Generate a video bound to the character_ref. This snapshots
        //    the character_ref's deepContentHash into the lockfile entry.
        registry["generate_video"]!!.dispatch(
            buildJsonObject {
                put("prompt", "the red panda explorer at dusk")
                put("projectId", pid.value)
                put("width", 256)
                put("height", 256)
                put("durationSeconds", 4.0)
                put("seed", 7L)
                put("model", "stub-video-1")
                put(
                    "consistencyBindingIds",
                    buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("hero")) },
                )
            },
            ctx,
        )

        val firstAssetId = projectStore.get(pid)!!.assets.last().id
        val firstLockfileEntry = projectStore.get(pid)!!.lockfile.findByAssetId(firstAssetId)
            ?: error("first generate_video must mint a lockfile entry")
        assertTrue(
            firstLockfileEntry.sourceContentHashes.containsKey(SourceNodeId("hero")),
            "lockfile entry must snapshot character_ref's content hash so future drift is detectable",
        )
        val firstHeroHash = firstLockfileEntry.sourceContentHashes[SourceNodeId("hero")]!!

        // 3. Drop the clip onto the timeline.
        registry["clip_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("action", "add")
                put(
                    "addItems",
                    buildJsonArray {
                        addJsonObject { put("assetId", firstAssetId.value) }
                    },
                )
            },
            ctx,
        )

        // Sanity: clip exists and points at the freshly-generated asset.
        val initialStale = projectStore.get(pid)!!.staleClipsFromLockfile()
        assertEquals(
            0, initialStale.size,
            "no edit yet — staleClipsFromLockfile must be empty (got: $initialStale)",
        )

        // === Pro-user phase =============================================
        // 4. Edit the character_ref body — same tool the small-user phase
        //    used, just a different verb. The cross-path invariant says
        //    this MUST propagate staleness without any "manual mode"
        //    shadow state.
        registry["source_node_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("action", "update_body")
                put("nodeId", "hero")
                putJsonObject("body") {
                    put("name", "Hero")
                    // Distinct from the small-user value so the deep hash
                    // genuinely shifts.
                    put("visualDescription", "a fierce red panda explorer in tactical gear")
                }
            },
            ctx,
        )

        // 5. Stale propagation: the clip referencing the bound asset must
        //    now report stale because the snapshotted hash no longer
        //    matches the current deep hash of `hero`.
        val postEditStale = projectStore.get(pid)!!.staleClipsFromLockfile()
        assertEquals(
            1, postEditStale.size,
            "exactly one stale clip expected after editing the bound character_ref (got: $postEditStale)",
        )
        val staleReport = postEditStale.single()
        assertEquals(
            firstAssetId, staleReport.assetId,
            "stale report must point at the asset the bound clip plays",
        )
        assertTrue(
            SourceNodeId("hero") in staleReport.changedSourceIds,
            "stale report must name `hero` as the drifted source — got: ${staleReport.changedSourceIds}",
        )

        // === Regenerate (proves the edit reaches the next AIGC call) ==
        // 6. Re-dispatch generate_video with the same bindings. The new
        //    lockfile entry's snapshotted hash for `hero` must differ
        //    from the pre-edit snapshot — that's the load-bearing proof
        //    that edit → stale → regen actually moved the underlying
        //    bytes the regen depends on, not just the staleness flag.
        registry["generate_video"]!!.dispatch(
            buildJsonObject {
                put("prompt", "the red panda explorer at dusk")
                put("projectId", pid.value)
                put("width", 256)
                put("height", 256)
                put("durationSeconds", 4.0)
                // Different seed so the lockfile cache misses; otherwise
                // a cache-hit would replay the OLD asset and not exercise
                // the edit-flowed-through-to-AIGC half of the chain.
                put("seed", 8L)
                put("model", "stub-video-1")
                put(
                    "consistencyBindingIds",
                    buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("hero")) },
                )
            },
            ctx,
        )

        val secondAssetId = projectStore.get(pid)!!.assets.last().id
        assertNotEquals(firstAssetId, secondAssetId, "regenerate must mint a new asset (different seed = cache miss)")
        val secondLockfileEntry = projectStore.get(pid)!!.lockfile.findByAssetId(secondAssetId)
            ?: error("regenerate must mint a fresh lockfile entry")
        val secondHeroHash = secondLockfileEntry.sourceContentHashes[SourceNodeId("hero")]
            ?: error("second lockfile entry must include the bound character_ref's hash")
        assertNotEquals(
            firstHeroHash, secondHeroHash,
            "regenerate's lockfile entry must snapshot the POST-edit hash — got identical hashes pre/post edit, " +
                "which means update_body didn't reach the next AIGC dispatch",
        )

        // The video engine fired twice — proves no cache hit short-
        // circuited the second call (which would have masked the edit).
        assertEquals(2, videoEngine.calls, "video engine must have fired twice — no cache hit on the regenerate path")
    }

    /**
     * Records call count + returns deterministic bytes — same minimal
     * shape as [OneShotDraftE2ETest.ScriptedVideoEngine]. Copying ~25 LOC
     * across two e2e files keeps both test bodies self-contained
     * (per the §3a #11 "test bodies stay readable" hint that costs less
     * than a shared helper file in jvmTest's e2e package).
     */
    private class ScriptedVideoEngine : VideoGenEngine {
        override val providerId: String = "scripted-video"
        var calls: Int = 0
            private set

        override suspend fun generate(request: VideoGenRequest): VideoGenResult {
            calls += 1
            return VideoGenResult(
                videos = listOf(
                    GeneratedVideo(
                        mp4Bytes = ByteArray(16) { 0x37 },
                        width = request.width,
                        height = request.height,
                        durationSeconds = request.durationSeconds,
                    ),
                ),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = request.seed,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: ProjectId,
            assetId: AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile {
            val file = File(rootDir, "${assetId.value}.$format")
            file.writeBytes(bytes)
            return MediaSource.BundleFile("media/${file.name}")
        }
    }
}
