package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runAssetsQuery] — `project_query(select=assets)`.
 * The asset catalog enumeration with kind classification, reference
 * counting, and unused-asset-cleanup signals. Cycle 120 audit: 173
 * LOC, **zero** transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Reference-count and orphan classification span 3 sources:
 *    clip refs + LUT filter `assetId` + lockfile provenance.**
 *    `inUseByClips` only counts clip references; `onlyReferenced`
 *    filter checks the broader "any reference" set including LUT
 *    filters and lockfile entries. A regression dropping any of
 *    the 3 sources from the orphan set would silently mark
 *    in-use assets as deletable. The cleanup-workflow security
 *    risk is the same as cycle 118's typo-throws semantic.
 *
 * 2. **Asset classification: `image` if no audio/video codec,
 *    `audio` if only audio, `video` if video.** The LLM uses
 *    this to filter "show me only image assets" workflows. A
 *    regression in `classifyAsset` would silently mis-classify
 *    every asset.
 *
 * 3. **Inline assetIds in outputForLlm capped at 30.** Per kdoc:
 *    "without this the structured `rows` payload is invisible
 *    to the model and it has to hallucinate ids" — so the LLM-
 *    facing text MUST include inline ids for follow-up
 *    `clip_action` / `filter_action` calls. The 30-cap bounds
 *    prompt growth on huge catalogs.
 */
class AssetsQueryTest {

    private fun videoAsset(
        id: String,
        durationSeconds: Long = 5,
        updatedAtEpochMs: Long? = null,
    ) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(
            duration = durationSeconds.seconds,
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
            videoCodec = "h264",
            audioCodec = null,
        ),
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun audioAsset(id: String, durationSeconds: Long = 5) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.wav"),
        metadata = MediaMetadata(
            duration = durationSeconds.seconds,
            videoCodec = null,
            audioCodec = "pcm",
        ),
    )

    private fun imageAsset(id: String) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.png"),
        metadata = MediaMetadata(
            duration = Duration.ZERO,
            resolution = Resolution(512, 512),
            videoCodec = null,
            audioCodec = null,
        ),
    )

    private fun bundleAsset(id: String) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.BundleFile("media/$id.png"),
        metadata = MediaMetadata(
            duration = Duration.ZERO,
            videoCodec = null,
            audioCodec = null,
        ),
    )

    private fun videoClipUsing(assetId: String, clipId: String = "c-$assetId", filters: List<Filter> = emptyList()) = Clip.Video(
        id = ClipId(clipId),
        timeRange = TimeRange(Duration.ZERO, 5.seconds),
        sourceRange = TimeRange(Duration.ZERO, 5.seconds),
        assetId = AssetId(assetId),
        filters = filters,
    )

    private fun project(
        assets: List<MediaAsset>,
        clips: List<Clip> = emptyList(),
        lockfile: EagerLockfile = EagerLockfile(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            assets = assets,
            lockfile = lockfile,
        )
    }

    private fun input(
        kind: String? = null,
        sortBy: String? = null,
        onlyUnused: Boolean? = null,
        onlyReferenced: Boolean? = null,
    ) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_ASSETS,
        kind = kind,
        sortBy = sortBy,
        onlyUnused = onlyUnused,
        onlyReferenced = onlyReferenced,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<AssetRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(AssetRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun invalidKindErrorsLoudWithValidValues() {
        val ex = assertFailsWith<IllegalStateException> {
            runAssetsQuery(project(emptyList()), input(kind = "popcorn"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("kind must be one of" in msg, "got: $msg")
        assertTrue("video" in msg && "audio" in msg && "image" in msg && "all" in msg, "lists valid; got: $msg")
    }

    @Test fun invalidSortByErrorsLoud() {
        val ex = assertFailsWith<IllegalStateException> {
            runAssetsQuery(project(emptyList()), input(sortBy = "popularity"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("sortBy" in msg, "got: $msg")
        assertTrue("insertion" in msg && "id" in msg, "lists valid; got: $msg")
    }

    @Test fun nullKindDefaultsToAll() {
        // Per code: `(input.kind ?: "all").trim().lowercase()`.
        // Only null defaults to "all"; whitespace-only would fail
        // validation (trims to empty, not in ASSET_KINDS).
        val v = videoAsset("v1")
        val rows = decodeRows(runAssetsQuery(project(listOf(v)), input(kind = null), 100, 0).data)
        assertEquals(1, rows.size, "null kind defaults to 'all'")
    }

    @Test fun whitespaceOnlyKindFailsValidation() {
        // Pin observed quirk: `"  ".trim()` = "", and "" is NOT
        // in ASSET_KINDS — the null-coalesce only catches null,
        // not blank. Documenting the current behaviour so a
        // refactor that introduces an `ifBlank { "all" }` would
        // catch by failing this test (intentional vs accidental
        // change).
        assertFailsWith<IllegalStateException> {
            runAssetsQuery(project(emptyList()), input(kind = "  "), 100, 0)
        }
    }

    // ── classification: video / audio / image ─────────────────────

    @Test fun classificationByCodecPriority() {
        // Pin: hasVideoCodec → "video"; only-audio → "audio";
        // neither → "image". Pure derivation in classifyAsset.
        val v = videoAsset("v1")
        val a = audioAsset("a1")
        val i = imageAsset("i1")
        val rows = decodeRows(runAssetsQuery(project(listOf(v, a, i)), input(), 100, 0).data)
        val byId = rows.associateBy { it.assetId }
        assertEquals("video", byId.getValue("v1").kind)
        assertEquals("audio", byId.getValue("a1").kind)
        assertEquals("image", byId.getValue("i1").kind)
    }

    @Test fun videoCodecWinsOverAudioCodec() {
        // An asset with both video AND audio codec classifies as
        // "video". Pin precedence so audio-priority refactor would
        // surface here.
        val both = MediaAsset(
            id = AssetId("av"),
            source = MediaSource.File("/tmp/av.mp4"),
            metadata = MediaMetadata(
                duration = 5.seconds,
                videoCodec = "h264",
                audioCodec = "aac",
            ),
        )
        val rows = decodeRows(runAssetsQuery(project(listOf(both)), input(), 100, 0).data)
        assertEquals("video", rows.single().kind, "video codec wins precedence")
    }

    @Test fun kindFilterRestrictsToMatchingClassification() {
        val v = videoAsset("v1")
        val a = audioAsset("a1")
        val i = imageAsset("i1")
        val onlyVideo = decodeRows(runAssetsQuery(project(listOf(v, a, i)), input(kind = "video"), 100, 0).data)
        assertEquals(listOf("v1"), onlyVideo.map { it.assetId })
        val onlyAudio = decodeRows(runAssetsQuery(project(listOf(v, a, i)), input(kind = "audio"), 100, 0).data)
        assertEquals(listOf("a1"), onlyAudio.map { it.assetId })
        val onlyImage = decodeRows(runAssetsQuery(project(listOf(v, a, i)), input(kind = "image"), 100, 0).data)
        assertEquals(listOf("i1"), onlyImage.map { it.assetId })
    }

    // ── refCount and onlyUnused ───────────────────────────────────

    @Test fun inUseByClipsCountsClipReferences() {
        val v = videoAsset("v1")
        val clips = listOf(
            videoClipUsing("v1", "c1"),
            videoClipUsing("v1", "c2"),
        )
        val rows = decodeRows(runAssetsQuery(project(listOf(v), clips), input(), 100, 0).data)
        assertEquals(2, rows.single().inUseByClips, "2 clips reference v1")
    }

    @Test fun onlyUnusedFiltersToZeroRefAssets() {
        val used = videoAsset("used")
        val unused = videoAsset("unused")
        val clip = videoClipUsing("used")
        val rows = decodeRows(
            runAssetsQuery(project(listOf(used, unused), listOf(clip)), input(onlyUnused = true), 100, 0).data,
        )
        assertEquals(listOf("unused"), rows.map { it.assetId })
    }

    // ── onlyReferenced: any-reference set spans 3 sources ─────────

    @Test fun onlyReferencedTrueIncludesClipFilterAndLockfileSources() {
        // Pin: any-ref set spans 3 sources:
        //   1. Clip refs (videoClipUsing("clipref"))
        //   2. LUT filter assetId (Filter(assetId = ...))
        //   3. Lockfile provenance (lockfile entry produced this asset)
        val clipref = videoAsset("clipref")
        val lutref = videoAsset("lutref") // referenced ONLY via filter assetId
        val lockref = videoAsset("lockref") // referenced ONLY via lockfile
        val orphan = videoAsset("orphan") // no refs anywhere

        val clip = videoClipUsing(
            "clipref",
            "c1",
            filters = listOf(Filter(name = "lut", assetId = AssetId("lutref"))),
        )
        val lockfile = EagerLockfile(
            entries = listOf(
                LockfileEntry(
                    inputHash = "h1",
                    toolId = "generate_image",
                    assetId = AssetId("lockref"),
                    provenance = GenerationProvenance(
                        providerId = "openai",
                        modelId = "gpt-image-1",
                        modelVersion = null,
                        seed = 0,
                        parameters = JsonObject(emptyMap()),
                        createdAtEpochMs = 0L,
                    ),
                    originatingMessageId = MessageId("m"),
                ),
            ),
        )
        val proj = project(listOf(clipref, lutref, lockref, orphan), listOf(clip), lockfile)
        // onlyReferenced=true → 3 referenced (clipref, lutref, lockref); orphan dropped.
        val refRows = decodeRows(runAssetsQuery(proj, input(onlyReferenced = true), 100, 0).data)
        assertEquals(setOf("clipref", "lutref", "lockref"), refRows.map { it.assetId }.toSet())

        // onlyReferenced=false → only orphan (the inverse).
        val orphanRows = decodeRows(runAssetsQuery(proj, input(onlyReferenced = false), 100, 0).data)
        assertEquals(setOf("orphan"), orphanRows.map { it.assetId }.toSet())
    }

    @Test fun onlyReferencedNullDoesNotFilter() {
        val a = videoAsset("a")
        val b = videoAsset("b")
        val rows = decodeRows(runAssetsQuery(project(listOf(a, b)), input(), 100, 0).data)
        assertEquals(2, rows.size, "null onlyReferenced = no filter")
    }

    // ── sort modes ────────────────────────────────────────────────

    @Test fun sortByDurationIsDescending() {
        val short = videoAsset("short", durationSeconds = 1)
        val long = videoAsset("long", durationSeconds = 10)
        val rows = decodeRows(
            runAssetsQuery(project(listOf(short, long)), input(sortBy = "duration"), 100, 0).data,
        )
        // "duration" → descending. long (10s) before short (1s).
        assertEquals(listOf("long", "short"), rows.map { it.assetId })
    }

    @Test fun sortByDurationAscIsAscending() {
        val short = videoAsset("short", durationSeconds = 1)
        val long = videoAsset("long", durationSeconds = 10)
        val rows = decodeRows(
            runAssetsQuery(project(listOf(long, short)), input(sortBy = "duration-asc"), 100, 0).data,
        )
        assertEquals(listOf("short", "long"), rows.map { it.assetId })
    }

    @Test fun sortByIdIsAlphabetical() {
        val z = videoAsset("z")
        val a = videoAsset("a")
        val m = videoAsset("m")
        val rows = decodeRows(runAssetsQuery(project(listOf(z, a, m)), input(sortBy = "id"), 100, 0).data)
        assertEquals(listOf("a", "m", "z"), rows.map { it.assetId })
    }

    @Test fun sortByInsertionIsListOrder() {
        val z = videoAsset("z")
        val a = videoAsset("a")
        val m = videoAsset("m")
        val rows = decodeRows(
            runAssetsQuery(project(listOf(z, a, m)), input(sortBy = "insertion"), 100, 0).data,
        )
        // Pin: insertion preserves catalog order — z, a, m as
        // declared, NOT alphabetic.
        assertEquals(listOf("z", "a", "m"), rows.map { it.assetId })
    }

    @Test fun nullSortByDefaultsToInsertion() {
        // Same as "insertion" — preserves catalog order.
        val z = videoAsset("z")
        val a = videoAsset("a")
        val rows = decodeRows(runAssetsQuery(project(listOf(z, a)), input(), 100, 0).data)
        assertEquals(listOf("z", "a"), rows.map { it.assetId })
    }

    @Test fun sortByRecentSurfacesNewestUpdatedAtFirst() {
        // Pin: recent uses updatedAtEpochMs descending. Null
        // timestamps sort last per kdoc.
        val newer = videoAsset("newer", updatedAtEpochMs = 200L)
        val older = videoAsset("older", updatedAtEpochMs = 100L)
        val nullStamped = videoAsset("nullStamped", updatedAtEpochMs = null)
        val rows = decodeRows(
            runAssetsQuery(
                project(listOf(nullStamped, older, newer)),
                input(sortBy = "recent"),
                100,
                0,
            ).data,
        )
        // newer first, older second, null-stamped last.
        assertEquals("newer", rows[0].assetId)
        assertEquals("older", rows[1].assetId)
        assertEquals("nullStamped", rows[2].assetId)
    }

    // ── sourceKind ────────────────────────────────────────────────

    @Test fun sourceKindMapsAllFourMediaSourceShapes() {
        val file = videoAsset("file")
        val bundle = bundleAsset("bundle")
        val http = MediaAsset(
            id = AssetId("http"),
            source = MediaSource.Http("https://example.com/x.mp4"),
            metadata = MediaMetadata(duration = 1.seconds),
        )
        val platform = MediaAsset(
            id = AssetId("platform"),
            source = MediaSource.Platform("photos", "12345"),
            metadata = MediaMetadata(duration = 1.seconds),
        )
        val rows = decodeRows(
            runAssetsQuery(project(listOf(file, bundle, http, platform)), input(), 100, 0).data,
        )
        val byId = rows.associateBy { it.assetId }
        assertEquals("file", byId.getValue("file").sourceKind)
        assertEquals("bundle_file", byId.getValue("bundle").sourceKind)
        assertEquals("http", byId.getValue("http").sourceKind)
        assertEquals("platform", byId.getValue("platform").sourceKind)
    }

    // ── outputForLlm: inline assetIds ─────────────────────────────

    @Test fun outputForLlmInlinesAssetIdsForLlmReuse() {
        // Pin: per kdoc rationale, LLM-facing text inlines the
        // assetIds so the agent can pass them to clip_action /
        // filter_action without a follow-up query.
        val a = videoAsset("alpha")
        val b = videoAsset("beta")
        val out = runAssetsQuery(project(listOf(a, b)), input(), 100, 0).outputForLlm
        assertTrue("assetIds:" in out, "assetIds prefix; got: $out")
        assertTrue("alpha" in out, "alpha id surfaces; got: $out")
        assertTrue("beta" in out, "beta id surfaces; got: $out")
    }

    @Test fun outputForLlmCapsInlineIdsAtThirty() {
        // 35 assets → first 30 inlined, then "(+5 more — page
        // through with offset/limit)".
        val many = (1..35).map { videoAsset("a$it") }
        val out = runAssetsQuery(project(many), input(), 100, 0).outputForLlm
        // Pin: overflow text mentions the diff count + pagination
        // hint.
        assertTrue("(+5 more" in out, "overflow count; got: $out")
        assertTrue("offset/limit" in out, "pagination hint; got: $out")
    }

    @Test fun outputForLlmNoIdListWhenEmpty() {
        // Empty page → no "assetIds:" prefix at all (the
        // buildString guards on isNotEmpty).
        val out = runAssetsQuery(project(emptyList()), input(), 100, 0).outputForLlm
        assertTrue("assetIds:" !in out, "no inline list when empty; got: $out")
    }

    @Test fun outputForLlmIncludesProjectIdAndScopeBits() {
        val a = videoAsset("a")
        val out = runAssetsQuery(
            project(listOf(a)),
            input(kind = "video", onlyUnused = true, sortBy = "id"),
            100,
            0,
        ).outputForLlm
        assertTrue("Project p" in out, "project id; got: $out")
        assertTrue("kind=video" in out, "kind scope; got: $out")
        assertTrue("unused-only" in out, "onlyUnused scope; got: $out")
        assertTrue("sort=id" in out, "sort scope; got: $out")
    }

    @Test fun outputForLlmOnlyReferencedScopeLabels() {
        val a = videoAsset("a")
        val outRef = runAssetsQuery(project(listOf(a)), input(onlyReferenced = true), 100, 0).outputForLlm
        assertTrue("only-referenced" in outRef, "true scope; got: $outRef")
        val outOrphan = runAssetsQuery(project(listOf(a)), input(onlyReferenced = false), 100, 0).outputForLlm
        assertTrue("only-orphans" in outOrphan, "false scope; got: $outOrphan")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsPageButTotalReflectsAll() {
        val many = (1..5).map { videoAsset("a$it") }
        val result = runAssetsQuery(project(many), input(), 2, 0)
        assertEquals(2, decodeRows(result.data).size)
        assertEquals(5, result.data.total)
    }

    @Test fun offsetSkipsFirstNRows() {
        val many = (1..5).map { videoAsset("a$it") }
        val result = runAssetsQuery(project(many), input(), 100, 2)
        // Default sort = insertion → a1..a5; offset=2 → start at a3.
        assertEquals("a3", decodeRows(result.data)[0].assetId)
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val a = videoAsset("a")
        val result = runAssetsQuery(project(listOf(a)), input(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_ASSETS, result.data.select)
    }

    @Test fun titleIncludesKindFilter() {
        val a = videoAsset("a")
        val result = runAssetsQuery(project(listOf(a)), input(kind = "video"), 100, 0)
        assertTrue("(video)" in (result.title ?: ""), "title format; got: ${result.title}")
    }

    @Test fun assetRowCarriesResolutionAndCodecFlags() {
        val v = videoAsset("v1")
        val rows = decodeRows(runAssetsQuery(project(listOf(v)), input(), 100, 0).data)
        val row = rows.single()
        assertEquals(1920, row.width)
        assertEquals(1080, row.height)
        assertEquals(true, row.hasVideoTrack)
        assertEquals(false, row.hasAudioTrack)
    }
}
