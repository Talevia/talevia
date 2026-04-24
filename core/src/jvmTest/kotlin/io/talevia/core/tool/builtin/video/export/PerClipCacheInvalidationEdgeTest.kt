package io.talevia.core.tool.builtin.video.export

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.render.TransitionFades
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.video.ExportTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 of the export-incremental-render split. Pins the three
 * counter-intuitive invalidation edges the bullet named plus two from
 * the phase-1 mutation/invalidation matrix that are equally load-bearing
 * but easy to get wrong in a future refactor:
 *
 * (a) Track reorder — same Clip.Video JSON always hashes to the same
 *     fingerprint regardless of enclosing track id / position.
 * (b) Multi-video-track shape falls back to whole-timeline render —
 *     `timelineFitsPerClipPath` returns null so the per-clip cache
 *     isn't pressed into service at all for that shape.
 * (c) Transitive deep-hash drift — same clip JSON + same
 *     `sourceBinding` node ids + different `boundSourceDeepHashes`
 *     values (a grandparent body edit) → different fingerprints → cache
 *     miss. This is the case that motivates including deep hashes at
 *     all; a regression here would quietly serve stale AIGC clips.
 * (d) Cross-engine — same clip × same ancestors × same output ×
 *     different engineId → different fingerprints → the cache can't
 *     cross-pollinate a FFmpeg-rendered `.mp4` into a Media3 request.
 *     End-to-end guard: two engines with different ids export the same
 *     project and observe independent cache populations.
 * (e) Path-insensitive output reuse — same profile, different
 *     `outputPath` → same fingerprint → cache hit. The "free
 *     re-export to a second file" win spelled out in phase 1's
 *     matrix; regressing this (e.g. accidentally folding
 *     `targetPath` into the fingerprint) would turn every retarget
 *     into a full re-render.
 *
 * Lives in `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/video/export/`
 * alongside `PerClipRender.kt` (the code under test) so a reader
 * changing the cache key doesn't have to hunt — the tests sit next to
 * the invariant. Unit-level fingerprint tests in
 * `core/src/jvmTest/kotlin/io/talevia/core/domain/render/ClipFingerprintTest.kt`
 * cover each segment in isolation; this file pins the named EDGES as
 * end-to-end tool-runtime behaviour where possible, fingerprint-level
 * where a full ExportTool rig would add only noise.
 */
class PerClipCacheInvalidationEdgeTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private val baseClip = Clip.Video(
        id = ClipId("c1"),
        timeRange = TimeRange(0.seconds, 3.seconds),
        sourceRange = TimeRange(0.seconds, 3.seconds),
        assetId = AssetId("a1"),
    )

    private val baseOutput = OutputSpec(
        targetPath = "/tmp/out.mp4",
        resolution = Resolution(1920, 1080),
        frameRate = 30,
        videoCodec = "h264",
        audioCodec = "aac",
    )

    // ----- (a) Track reorder must NOT perturb the fingerprint --------------

    @Test fun trackReorderDoesNotPerturbFingerprint() {
        // Fingerprint is a pure function of Clip.Video + fades + deep hashes +
        // output + engine. Track id and clip position within a track are NOT
        // in the input. Guard: the same clip moved to a different track index
        // (or between adjacent tracks on a multi-track timeline, if/when that
        // shape becomes per-clip-eligible) must still hit the cache.
        val engineId = "ffmpeg-jvm"
        val a = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput, engineId)
        val b = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput, engineId)
        assertEquals(a, b, "same Clip.Video JSON → same fingerprint regardless of track/position")
    }

    // ----- (b) Multi-video-track shape falls back to whole-timeline --------

    @Test fun multiVideoTrackFallsBackToWholeTimelinePath() = runTest {
        // Two video tracks holding the same clip shape. `timelineFitsPerClipPath`
        // rejects the multi-video shape (it's currently single-video-track
        // only), so ExportTool must dispatch to the whole-timeline `render`
        // call and bypass `renderClip`. Guard against a future refactor that
        // broadens the eligibility check without wiring multi-track concat.
        val store = ProjectStoreTestKit.create()
        val engine = CountingFakeEngine(engineId = "ffmpeg-jvm")
        val projectId = ProjectId("p-multi")
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = AssetId("a1"),
        )
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v0"), clips = listOf(clip)),
                Track.Video(id = TrackId("v1"), clips = listOf(clip.copy(id = ClipId("c2")))),
            ),
            duration = 3.seconds,
        )
        store.upsert("demo", Project(id = projectId, timeline = timeline))

        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out-multi.mp4"),
            ctx(),
        )

        assertEquals(
            1, engine.wholeTimelineCalls,
            "multi-video-track shape must dispatch to whole-timeline render",
        )
        assertEquals(
            0, engine.renderClipCalls,
            "per-clip path must NOT run on multi-video-track timelines",
        )
    }

    // ----- (c) Transitive deep-hash drift (grandparent edit) must miss -----

    @Test fun grandparentEditInvalidatesDescendantBoundClipViaDeepHash() {
        // The clip's sourceBinding points at nodeId "mei" unchanged across
        // both calls — but mei's deep hash changed (e.g. its ancestor
        // "noir"/style_bible body drifted). Fingerprint must differ.
        // Regressing this is the exact failure mode the
        // `source-consistency-propagation-runtime-test` decision
        // (2026-04-23) pinned against at the tool-runtime layer — this
        // test is the per-clip-cache equivalent.
        val engineId = "ffmpeg-jvm"
        val clip = baseClip.copy(sourceBinding = setOf(SourceNodeId("mei")))
        val before = clipMezzanineFingerprint(
            clip = clip,
            fades = null,
            boundSourceDeepHashes = mapOf(SourceNodeId("mei") to "h-pre-drift"),
            output = baseOutput,
            engineId = engineId,
        )
        val after = clipMezzanineFingerprint(
            clip = clip,
            fades = null,
            boundSourceDeepHashes = mapOf(SourceNodeId("mei") to "h-post-drift"),
            output = baseOutput,
            engineId = engineId,
        )
        assertNotEquals(
            before, after,
            "same clip + same binding id + different deep hash (ancestor drifted) must perturb the fingerprint",
        )
    }

    // ----- (d) Cross-engine end-to-end must miss ---------------------------

    @Test fun crossEngineExportForcesRerenderEvenWithIdenticalCache() = runTest {
        // Populate the clipRenderCache by exporting with engine A
        // (engineId="ffmpeg-jvm"). Then swap in engine B
        // (engineId="media3-android") carrying the same `presentPaths` set
        // (so mezzaninePresent returns true for A's files — simulating a
        // shared filesystem or a copy_into_bundle scenario). Export again.
        // Engine B must NOT reuse A's cached mezzanines: its fingerprint
        // differs at the `|engine=...` segment, `findByFingerprint` returns
        // null, and `renderClip` fires fresh.
        val store = ProjectStoreTestKit.create()
        val projectId = ProjectId("p-cross-engine")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a1"),
                        ),
                    ),
                ),
            ),
            duration = 3.seconds,
        )
        store.upsert("x", Project(id = projectId, timeline = timeline))

        val engineA = CountingFakeEngine(engineId = "ffmpeg-jvm", supportsPerClip = true)
        val toolA = ExportTool(store, engineA)
        toolA.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/a.mp4"),
            ctx(),
        )
        assertEquals(1, engineA.renderClipCalls, "engine A must render on first export (cold cache)")
        val cacheAfterA = store.get(projectId)!!.clipRenderCache
        assertEquals(1, cacheAfterA.entries.size)

        // Engine B reuses engine A's pre-rendered mezzanine paths in its
        // `presentPaths` set, so `mezzaninePresent(path)` answers true for
        // them. The gate is the fingerprint lookup — if fingerprints
        // matched cross-engine, B would reuse A's mp4. engineId in the
        // fingerprint makes the lookup miss, forcing B to render fresh.
        val engineB = CountingFakeEngine(
            engineId = "media3-android",
            supportsPerClip = true,
            presentPaths = engineA.presentPaths.toMutableSet(),
        )
        val toolB = ExportTool(store, engineB)
        toolB.execute(
            ExportTool.Input(
                projectId = projectId.value,
                outputPath = "/tmp/b.mp4",
                forceRender = true, // bypass whole-timeline cache; exercise per-clip lookup
            ),
            ctx(),
        )
        assertEquals(
            1, engineB.renderClipCalls,
            "engine B must render fresh — cross-engine fingerprint mismatch must force a cache miss",
        )
        val cacheAfterB = store.get(projectId)!!.clipRenderCache
        assertEquals(
            2, cacheAfterB.entries.size,
            "engine B produces its own cache entry alongside A's — different engineId → different fingerprint",
        )
        assertTrue(
            cacheAfterB.entries.map { it.fingerprint }.toSet().size == 2,
            "two distinct fingerprints in the cache, one per engine",
        )
    }

    // ----- (e) Path-insensitive output reuse -------------------------------

    @Test fun differentOutputPathReusesCacheAtSameProfile() = runTest {
        // Export twice with the same clip + same profile but different
        // `outputPath`. The per-clip cache must hit on the second export
        // since `outputPath` is deliberately excluded from the fingerprint.
        // Regression guard against folding `targetPath` into the
        // fingerprint, which would make every retarget a full re-render.
        val store = ProjectStoreTestKit.create()
        val engine = CountingFakeEngine(engineId = "ffmpeg-jvm", supportsPerClip = true)
        val projectId = ProjectId("p-retarget")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a1"),
                        ),
                    ),
                ),
            ),
            duration = 3.seconds,
        )
        store.upsert("r", Project(id = projectId, timeline = timeline))

        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/first.mp4"),
            ctx(),
        )
        assertEquals(1, engine.renderClipCalls, "first export renders cold")

        tool.execute(
            ExportTool.Input(
                projectId = projectId.value,
                outputPath = "/tmp/second.mp4",
                forceRender = true, // bypass whole-timeline cache
            ),
            ctx(),
        )
        assertEquals(
            1, engine.renderClipCalls,
            "retarget to different outputPath at same profile must hit the per-clip cache — no extra renderClip",
        )
        assertEquals(
            2, engine.concatCalls,
            "concat still runs to produce the second target file from the cached mezzanine",
        )
    }
}

/**
 * Small counting fake engine local to this test file. Mirrors
 * `ExportToolTest.FakePerClipEngine` but exposes `engineId` +
 * `presentPaths` as constructor args so the cross-engine test can seed
 * a shared-filesystem scenario.
 *
 * `supportsPerClip = false` (default) routes through whole-timeline
 * render; `true` opts into the per-clip path. Both paths increment the
 * relevant counters.
 */
private class CountingFakeEngine(
    override val engineId: String,
    private val supportsPerClip: Boolean = false,
    val presentPaths: MutableSet<String> = mutableSetOf(),
) : VideoEngine {

    var wholeTimelineCalls: Int = 0
        private set
    var renderClipCalls: Int = 0
        private set
    var concatCalls: Int = 0
        private set

    override val supportsPerClipCache: Boolean get() = supportsPerClip

    override suspend fun probe(source: MediaSource): MediaMetadata =
        MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

    override fun render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: MediaPathResolver?,
    ): Flow<RenderProgress> = flow {
        wholeTimelineCalls += 1
        emit(RenderProgress.Started("job"))
        emit(RenderProgress.Completed("job", output.targetPath))
    }

    override suspend fun mezzaninePresent(path: String): Boolean = path in presentPaths

    override suspend fun renderClip(
        clip: Clip.Video,
        fades: TransitionFades?,
        output: OutputSpec,
        mezzaninePath: String,
        resolver: MediaPathResolver?,
    ) {
        renderClipCalls += 1
        presentPaths += mezzaninePath
    }

    override suspend fun concatMezzanines(
        mezzaninePaths: List<String>,
        subtitles: List<Clip.Text>,
        output: OutputSpec,
    ) {
        concatCalls += 1
    }

    override suspend fun thumbnail(
        asset: AssetId,
        source: MediaSource,
        time: Duration,
    ): ByteArray = ByteArray(0)
}
