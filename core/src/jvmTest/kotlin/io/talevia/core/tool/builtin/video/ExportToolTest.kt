package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.TransitionFades
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ExportToolTest {

    private class CountingEngine : VideoEngine {
        var renderCalls: Int = 0
            private set
        var lastMetadata: Map<String, String> = emptyMap()
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> = flow {
            renderCalls += 1
            lastMetadata = output.metadata
            emit(RenderProgress.Started("job"))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    private fun ctx() = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun newFixture(): Triple<FileProjectStore, CountingEngine, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val engine = CountingEngine()
        val projectId = ProjectId("p")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("a1"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("demo", Project(id = projectId, timeline = timeline))
        return Triple(store, engine, projectId)
    }

    @Test fun secondExportWithIdenticalInputsIsCacheHit() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.renderCalls)

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit, "second call with identical inputs must hit the render cache")
        assertEquals(1, engine.renderCalls, "engine must not be re-invoked on cache hit")
        assertEquals(first.data.outputPath, second.data.outputPath)
    }

    @Test fun changingTimelineInvalidatesCache() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")

        tool.execute(input, ctx())
        assertEquals(1, engine.renderCalls)

        // Mutate the project — add a second clip.
        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val extra = Clip.Video(
                id = ClipId("c2"),
                timeRange = TimeRange(5.seconds, 3.seconds),
                sourceRange = TimeRange(0.seconds, 3.seconds),
                assetId = AssetId("a2"),
            )
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(track.copy(clips = track.clips + extra)),
                    duration = 8.seconds,
                ),
            )
        }

        val after = tool.execute(input, ctx())
        assertEquals(false, after.data.cacheHit, "timeline mutation must invalidate the cache")
        assertEquals(2, engine.renderCalls)
    }

    @Test fun forceRenderBypassesCache() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")
        tool.execute(input, ctx())
        assertEquals(1, engine.renderCalls)

        val forced = tool.execute(input.copy(forceRender = true), ctx())
        assertEquals(false, forced.data.cacheHit)
        assertEquals(2, engine.renderCalls)
    }

    @Test fun perClipCostMapAttributesAigcClipsAndSumsTotal() = runTest {
        // backlog export-cost-attribution-by-clip — the export Output
        // must surface per-clip costCents (lockfile.byAssetId lookup)
        // plus a totalCostCents roll-up for the whole timeline.
        val (store, engine, pid) = newFixture()
        // c1 already exists with assetId=a1. Add a second clip c2/a2,
        // and lockfile entries for both with explicit costCents.
        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val c2 = Clip.Video(
                id = ClipId("c2"),
                timeRange = TimeRange(5.seconds, 3.seconds),
                sourceRange = TimeRange(0.seconds, 3.seconds),
                assetId = AssetId("a2"),
            )
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(track.copy(clips = track.clips + c2)),
                    duration = 8.seconds,
                ),
                lockfile = p.lockfile
                    .append(
                        LockfileEntry(
                            inputHash = "h1",
                            toolId = "generate_image",
                            assetId = AssetId("a1"),
                            provenance = GenerationProvenance(
                                providerId = "openai",
                                modelId = "gpt-image-1",
                                modelVersion = null,
                                seed = 1L,
                                parameters = JsonObject(emptyMap()),
                                createdAtEpochMs = 0L,
                            ),
                            costCents = 6L,
                        ),
                    )
                    .append(
                        LockfileEntry(
                            inputHash = "h2",
                            toolId = "generate_image",
                            assetId = AssetId("a2"),
                            provenance = GenerationProvenance(
                                providerId = "openai",
                                modelId = "gpt-image-1",
                                modelVersion = null,
                                seed = 2L,
                                parameters = JsonObject(emptyMap()),
                                createdAtEpochMs = 0L,
                            ),
                            costCents = 4L,
                        ),
                    ),
            )
        }

        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/cost-test.mp4")
        val out = tool.execute(input, ctx()).data

        assertEquals(6L, out.perClipCostCents["c1"], "c1 → a1 lockfile entry attributes 6¢")
        assertEquals(4L, out.perClipCostCents["c2"], "c2 → a2 lockfile entry attributes 4¢")
        assertEquals(10L, out.totalCostCents, "total is sum of priced clip costs")
    }

    @Test fun perClipCostMapMarksUnpricedClipsAsNullCents() = runTest {
        // c1 exists with assetId=a1 but has no lockfile entry —
        // the map must surface "c1 → null" distinct from "0¢".
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val out = tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/unpriced.mp4"),
            ctx(),
        ).data
        assertTrue(out.perClipCostCents.containsKey("c1"), "c1 must appear in the map even when unpriced")
        assertEquals(null, out.perClipCostCents["c1"], "no lockfile entry → null cents (≠ zero)")
        assertEquals(0L, out.totalCostCents, "totalCost is 0 when nothing is priced")
    }

    @Test fun perClipCostMapAlsoPopulatesOnCacheHitPath() = runTest {
        // The cache-hit short-circuit returns a different Output. It
        // still needs to carry the cost attribution (the cost was paid
        // when the cached output was originally created — the cache hit
        // means we skip re-rendering, not that the cost vanished).
        val (store, engine, pid) = newFixture()
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = AssetId("a1"),
                        provenance = GenerationProvenance(
                            providerId = "openai",
                            modelId = "gpt-image-1",
                            modelVersion = null,
                            seed = 1L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        costCents = 7L,
                    ),
                ),
            )
        }
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/cache-cost.mp4")
        val first = tool.execute(input, ctx()).data
        assertEquals(false, first.cacheHit)
        assertEquals(7L, first.totalCostCents)

        val cached = tool.execute(input, ctx()).data
        assertEquals(true, cached.cacheHit, "second call must hit the render cache")
        assertEquals(7L, cached.totalCostCents, "cache hit still reports the cost paid up front")
        assertEquals(7L, cached.perClipCostCents["c1"])
    }

    @Test fun refusesToExportWhenAigcClipIsStale() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)

        // Bind the seed clip to a character_ref and drop a matching lockfile entry
        // snapshotting the original hash.
        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val bound = (track.clips.first() as Clip.Video).copy(sourceBinding = setOf(SourceNodeId("mei")))
            p.copy(
                timeline = p.timeline.copy(tracks = listOf(track.copy(clips = listOf(bound)))),
            )
        }
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val originalHash = store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = AssetId("a1"),
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "fake",
                            modelVersion = null,
                            seed = 1L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                    ),
                ),
            )
        }

        // User edits the character → clip becomes stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }

        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")
        val ex = assertFailsWith<IllegalStateException> { tool.execute(input, ctx()) }
        assertTrue(ex.message!!.contains("stale"), "error must mention stale clips: ${ex.message}")
        assertTrue(ex.message!!.contains("c1"), "error must name the stale clip id")
        assertEquals(0, engine.renderCalls, "engine must not be invoked when the stale-guard refuses")

        // allowStale=true lets the render through and surfaces the stale ids on output.
        val forced = tool.execute(input.copy(allowStale = true), ctx())
        assertEquals(1, engine.renderCalls)
        assertEquals(listOf("c1"), forced.data.staleClipsIncluded)
    }

    /**
     * Per-clip engine that counts how many clip renders / concat calls occurred.
     * Acts as a drop-in VideoEngine with `supportsPerClipCache = true` so
     * ExportTool's per-clip branch executes. Mezzanine writes are fake (empty
     * file touch + always present) — this test lives in core and mustn't depend
     * on ffmpeg binaries.
     */
    private class FakePerClipEngine : VideoEngine {
        var wholeTimelineCalls: Int = 0
            private set
        var renderClipCalls: Int = 0
            private set
        var concatCalls: Int = 0
            private set
        val rendered: MutableList<Pair<ClipId, String>> = mutableListOf()
        val presentPaths: MutableSet<String> = mutableSetOf()

        override val supportsPerClipCache: Boolean = true

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> = flow {
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
            resolver: io.talevia.core.platform.MediaPathResolver?,
        ) {
            renderClipCalls += 1
            rendered += (clip.id to mezzaninePath)
            presentPaths += mezzaninePath
        }

        override suspend fun concatMezzanines(
            mezzaninePaths: List<String>,
            subtitles: List<Clip.Text>,
            output: OutputSpec,
        ) {
            concatCalls += 1
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    @Test fun perClipEngineRendersEveryClipOnFirstExport() = runTest {
        val store = ProjectStoreTestKit.create()
        val engine = FakePerClipEngine()
        val projectId = ProjectId("p-perclip")
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
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(3.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("perclip", Project(id = projectId, timeline = timeline))

        val tool = ExportTool(store, engine)
        val first = tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        )
        assertEquals(0, engine.wholeTimelineCalls, "per-clip path must not call whole-timeline render")
        assertEquals(2, engine.renderClipCalls, "every clip should render on first export")
        assertEquals(1, engine.concatCalls, "concat runs once per export")
        assertEquals(0, first.data.perClipCacheHits)
        assertEquals(2, first.data.perClipCacheMisses)

        // Cache entries were persisted.
        val clipCache = store.get(projectId)!!.clipRenderCache
        assertEquals(2, clipCache.entries.size)
    }

    @Test fun perClipEngineReusesCachedMezzanineOnIdenticalRerun() = runTest {
        val store = ProjectStoreTestKit.create()
        val engine = FakePerClipEngine()
        val projectId = ProjectId("p-perclip-rerun")
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
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(3.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("perclip", Project(id = projectId, timeline = timeline))
        val tool = ExportTool(store, engine)

        tool.execute(ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"), ctx())
        assertEquals(2, engine.renderClipCalls)

        // Second export with forceRender (full-timeline cache bypass) but identical
        // clip shape → all mezzanines reused from ClipRenderCache.
        val second = tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4", forceRender = true),
            ctx(),
        )
        assertEquals(2, engine.renderClipCalls, "per-clip cache hits — no new renderClip calls")
        assertEquals(2, engine.concatCalls, "concat still runs once per export")
        assertEquals(2, second.data.perClipCacheHits)
        assertEquals(0, second.data.perClipCacheMisses)
    }

    @Test fun perClipEngineReRendersOnlyTheStalyClip() = runTest {
        val store = ProjectStoreTestKit.create()
        val engine = FakePerClipEngine()
        val projectId = ProjectId("p-perclip-partial")
        val baseTimeline = Timeline(
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
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(3.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("perclip", Project(id = projectId, timeline = baseTimeline))
        val tool = ExportTool(store, engine)

        tool.execute(ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"), ctx())
        assertEquals(2, engine.renderClipCalls)

        // Edit clip c2 only (different assetId → fingerprint shifts).
        store.mutate(projectId) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val newClips = track.clips.map { c ->
                if ((c as Clip.Video).id == ClipId("c2")) c.copy(assetId = AssetId("a2-new")) else c
            }
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(track.copy(clips = newClips)),
                ),
            )
        }

        val after = tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        )
        assertEquals(3, engine.renderClipCalls, "only c2 should re-render; c1 hits cache")
        assertEquals(1, after.data.perClipCacheHits)
        assertEquals(1, after.data.perClipCacheMisses)
    }

    @Test fun changingOutputPathMissesCache() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        tool.execute(ExportTool.Input(projectId = pid.value, outputPath = "/tmp/a.mp4"), ctx())
        val second = tool.execute(ExportTool.Input(projectId = pid.value, outputPath = "/tmp/b.mp4"), ctx())
        assertEquals(false, second.data.cacheHit)
        assertEquals(2, engine.renderCalls)

        // After both exports, the cache has both entries.
        val cache = store.get(pid)!!.renderCache
        assertEquals(2, cache.entries.size)
        assertTrue(cache.entries.any { it.outputPath == "/tmp/a.mp4" })
        assertTrue(cache.entries.any { it.outputPath == "/tmp/b.mp4" })
    }

    /**
     * Engine that emits `Started`, a pair of `Preview` events at different ratios,
     * a regular `Frames`, and `Completed`. Verifies `ExportTool` translates each
     * `Preview` into a `Part.RenderProgress` that carries the engine-supplied
     * `thumbnailPath`. This is the contract behind VISION §5.4 — UIs see the
     * thumbnail path on the render-progress part as exports run.
     */
    private class PreviewEmittingEngine : VideoEngine {
        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> = flow {
            emit(RenderProgress.Started("job"))
            emit(RenderProgress.Preview("job", ratio = 0.25f, thumbnailPath = "/tmp/preview-0.jpg"))
            emit(RenderProgress.Frames("job", ratio = 0.5f))
            emit(RenderProgress.Preview("job", ratio = 0.75f, thumbnailPath = "/tmp/preview-1.jpg"))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    @Test fun previewEventsForwardedAsRenderProgressPartsWithThumbnailPath() = runTest {
        val (store, _, pid) = newFixture()
        val engine = PreviewEmittingEngine()
        val tool = ExportTool(store, engine)

        val capturedParts = mutableListOf<io.talevia.core.session.Part.RenderProgress>()
        val captureCtx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { p ->
                if (p is io.talevia.core.session.Part.RenderProgress) capturedParts += p
            },
            messages = emptyList(),
        )
        tool.execute(ExportTool.Input(projectId = pid.value, outputPath = "/tmp/preview-out.mp4"), captureCtx)

        val previewParts = capturedParts.filter { it.thumbnailPath != null }
        assertEquals(2, previewParts.size, "two Preview engine events should produce two thumbnail-carrying parts")
        assertEquals("/tmp/preview-0.jpg", previewParts[0].thumbnailPath)
        assertEquals(0.25f, previewParts[0].ratio)
        assertEquals("preview", previewParts[0].message)
        assertEquals("/tmp/preview-1.jpg", previewParts[1].thumbnailPath)
        assertEquals(0.75f, previewParts[1].ratio)

        // Non-preview events (Started, Frames, Completed) must not carry a
        // thumbnailPath — only Preview events do.
        val nonPreviewParts = capturedParts.filter { it.thumbnailPath == null }
        assertTrue(
            nonPreviewParts.isNotEmpty(),
            "Started/Frames/Completed still produce render-progress parts",
        )
        assertTrue(
            nonPreviewParts.all { it.thumbnailPath == null },
            "Non-Preview engine events must not set thumbnailPath",
        )
    }

    // -----------------------------------------------------------------------
    // Provenance manifest (VISION §5.3).
    //
    // ExportTool builds a ProvenanceManifest — pure function of (projectId,
    // timeline, lockfile) — and stamps it into OutputSpec.metadata as a
    // `"comment"` entry + surfaces it on the tool's Output.provenance. These
    // tests exercise the binding without needing ffmpeg on PATH; a round-trip
    // through real ffmpeg lives in
    // platform-impls/video-ffmpeg-jvm/.../FfmpegEndToEndTest.kt.
    // -----------------------------------------------------------------------

    @Test fun exportStampsProvenanceManifestIntoOutputAndOutputSpec() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)

        val result = tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        )

        val provenance = result.data.provenance
        assertTrue(provenance != null, "Output must carry a ProvenanceManifest on a fresh render")
        assertEquals(pid.value, provenance.projectId)
        assertEquals(
            io.talevia.core.domain.render.ProvenanceManifest.CURRENT_SCHEMA,
            provenance.schemaVersion,
        )
        // Timeline + lockfile hashes are fnv1a64 (16 hex chars).
        assertTrue(provenance.timelineHash.matches(Regex("[0-9a-f]{16}")))
        assertTrue(provenance.lockfileHash.matches(Regex("[0-9a-f]{16}")))

        // OutputSpec.metadata["comment"] must round-trip through decodeFromComment.
        val comment = engine.lastMetadata["comment"]
        assertTrue(comment != null, "engine must have received a `comment` metadata entry")
        assertTrue(
            comment.startsWith(io.talevia.core.domain.render.ProvenanceManifest.MANIFEST_PREFIX),
            "comment must begin with the Talevia manifest prefix so non-Talevia consumers can distinguish it",
        )
        val decoded = io.talevia.core.domain.render.ProvenanceManifest.decodeFromComment(comment)
        assertEquals(provenance, decoded)
    }

    @Test fun timelineMutationChangesTimelineHashButNotLockfileHash() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)

        val first = tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data.provenance!!

        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val extra = Clip.Video(
                id = ClipId("c2"),
                timeRange = TimeRange(5.seconds, 3.seconds),
                sourceRange = TimeRange(0.seconds, 3.seconds),
                assetId = AssetId("a2"),
            )
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(track.copy(clips = track.clips + extra)),
                    duration = 8.seconds,
                ),
            )
        }

        val second = tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data.provenance!!

        assertEquals(first.projectId, second.projectId, "projectId does not change on timeline edits")
        assertTrue(
            first.timelineHash != second.timelineHash,
            "timelineHash must differ after a clip append",
        )
        assertEquals(first.lockfileHash, second.lockfileHash, "untouched lockfile → same lockfileHash")
    }

    @Test fun lockfileMutationChangesLockfileHashButNotTimelineHash() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)

        val first = tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data.provenance!!

        // Append a lockfile entry without touching the timeline. timelineHash
        // must stay stable; lockfileHash must flip.
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "h-prov",
                        toolId = "generate_image",
                        assetId = AssetId("a-prov"),
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "fake",
                            modelVersion = null,
                            seed = 1L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        sourceBinding = emptySet(),
                        sourceContentHashes = emptyMap(),
                    ),
                ),
            )
        }

        val second = tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4", forceRender = true),
            ctx(),
        ).data.provenance!!

        assertEquals(first.timelineHash, second.timelineHash, "untouched timeline → same timelineHash")
        assertTrue(
            first.lockfileHash != second.lockfileHash,
            "lockfileHash must differ after appending a lockfile entry",
        )
    }

    @Test fun cacheHitExportStillReportsProvenance() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")

        val first = tool.execute(input, ctx())
        val firstProv = first.data.provenance!!

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit)
        val secondProv = second.data.provenance!!
        assertEquals(firstProv, secondProv, "cache hit must reproduce the same manifest the cached render baked")
    }

    @Test fun reexportProducesIdenticalProvenance() = runTest {
        // Within a single fixture, re-exporting the same project must mint
        // byte-identical manifests. Covers the ExportDeterminismTest-adjacent
        // contract: manifests must be pure function of (project state at
        // render time), not wall-clock or process-local non-determinism. The
        // cross-fixture "two projects in two stores" case isn't useful here —
        // FileProjectStore stamps `Track.updatedAtEpochMs` at upsert
        // time, so two fresh fixtures upserted milliseconds apart legitimately
        // differ in timelineHash.
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")

        val first = tool.execute(input, ctx()).data.provenance!!
        val second = tool.execute(input.copy(forceRender = true), ctx()).data.provenance!!

        assertEquals(first, second, "re-export of the same project must mint the same manifest")
    }

    /**
     * Per-clip engine variant that succeeds for the first [failOnRenderCall] - 1
     * calls and throws on the failOnRenderCall-th `renderClip`. Also records
     * every path passed to `deleteMezzanine` so the cleanup assertion can walk
     * them.
     */
    private class FailingPerClipEngine(private val failOnRenderCall: Int) : VideoEngine {
        var renderClipCalls: Int = 0
            private set
        val rendered: MutableList<String> = mutableListOf()
        val deleted: MutableList<String> = mutableListOf()

        override val supportsPerClipCache: Boolean = true

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> =
            flow { emit(RenderProgress.Completed("job", output.targetPath)) }

        override suspend fun mezzaninePresent(path: String): Boolean = false

        override suspend fun renderClip(
            clip: Clip.Video,
            fades: TransitionFades?,
            output: OutputSpec,
            mezzaninePath: String,
            resolver: io.talevia.core.platform.MediaPathResolver?,
        ) {
            renderClipCalls += 1
            if (renderClipCalls == failOnRenderCall) {
                error("simulated ffmpeg crash on clip $failOnRenderCall")
            }
            rendered += mezzaninePath
        }

        override suspend fun concatMezzanines(
            mezzaninePaths: List<String>,
            subtitles: List<Clip.Text>,
            output: OutputSpec,
        ) {
            // Reached only on the success path — never in the failure case
            // exercised by the cleanup test.
        }

        override suspend fun deleteMezzanine(path: String): Boolean {
            deleted += path
            return true
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    @Test fun perClipRenderFailureCleansUpFreshMezzanines() = runTest {
        // 3 clips, engine crashes on the 3rd renderClip → the first two mezzanines
        // were written to disk but neither has been persisted to Project.clipRenderCache
        // yet (cache.mutate runs only after concat completes). Without cleanup they'd
        // leak. The try/catch in runPerClipRender must invoke deleteMezzanine on each.
        val store = ProjectStoreTestKit.create()
        val engine = FailingPerClipEngine(failOnRenderCall = 3)
        val projectId = ProjectId("p-perclip-fail")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a1"),
                        ),
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(2.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                        Clip.Video(
                            id = ClipId("c3"),
                            timeRange = TimeRange(4.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a3"),
                        ),
                    ),
                ),
            ),
            duration = 6.seconds,
        )
        store.upsert("perclip-fail", Project(id = projectId, timeline = timeline))
        val tool = ExportTool(store, engine)

        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("simulated"), "original failure must propagate: ${ex.message}")

        // The two pre-failure mezzanines should have been scheduled for deletion
        // (the 3rd crashed before writing, so it's not in the cleanup list).
        assertEquals(
            engine.rendered.toSet(),
            engine.deleted.toSet(),
            "every freshly rendered mezzanine must be deleted on cleanup",
        )
        assertEquals(2, engine.deleted.size, "2 mezzanines written before crash, both deleted")

        // No cache entries should have been persisted — the append happens only
        // after concat. The post-crash project state must show an empty
        // clipRenderCache.
        val cacheAfter = store.get(projectId)!!.clipRenderCache
        assertEquals(0, cacheAfter.entries.size, "no cache entry should survive the crash")
    }
}
