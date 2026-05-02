package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.platform.OutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * M5 §3.2 criterion #2 (Render-stale 与 AIGC-stale 分轴): the
 * render-cache staleness axis exposed by [Project.renderStaleClips].
 * Distinct from [Project.staleClipsFromLockfile]'s AIGC-asset
 * staleness — a clip can be one, both, or neither.
 *
 * The cases below pin the contract M5 #1 (incremental plan primitive)
 * will consume:
 *   - cold project → every clip render-stale.
 *   - cached fingerprints present → those clips not stale; un-cached
 *     ones still stale.
 *   - editing a clip's filter param invalidates only that clip's
 *     fingerprint → only that clip stale on next query.
 *   - changing OutputSpec (resolution / fps / codec / bitrate) or
 *     engineId perturbs every fingerprint → every clip stale across
 *     the new dimension.
 *   - non-eligible shapes (multi-Video-track, empty, mixed-kind
 *     clips on a Video track) report empty — per-clip cache is
 *     undefined for those.
 *
 * The fingerprint computation isn't re-derived here; we rely on
 * [clipMezzanineFingerprint]'s own pinning in [`ClipFingerprintTest`]
 * to know it perturbs on each axis. These tests only assert that
 * `renderStaleClips` joins fingerprint computation with the cache
 * lookup correctly, and that the eligibility gate matches
 * `timelineFitsPerClipPath`.
 */
class RenderStalenessTest {

    private val output = OutputSpec(
        targetPath = "/tmp/out.mp4",
        resolution = Resolution(1920, 1080),
        frameRate = 30,
        videoCodec = "h264",
        audioCodec = "aac",
    )
    private val engineId = "test-engine"

    private fun clip(id: String, start: Double = 0.0, dur: Double = 3.0, asset: String = "a-$id") = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, dur.seconds),
        sourceRange = TimeRange(0.seconds, dur.seconds),
        assetId = AssetId(asset),
    )

    private fun project(clips: List<Clip.Video>, cache: ClipRenderCache = ClipRenderCache()): Project = Project(
        id = io.talevia.core.ProjectId("p-render-stale"),
        timeline = Timeline(
            tracks = listOf(Track.Video(id = TrackId("v"), clips = clips)),
            duration = clips.maxOfOrNull { it.timeRange.start + it.timeRange.duration } ?: 0.seconds,
        ),
        clipRenderCache = cache,
    )

    /** Compute the fingerprints the project would produce so a test can pre-seed a [ClipRenderCache]. */
    private fun fingerprintsFor(project: Project): Map<ClipId, String> {
        val track = project.timeline.tracks.filterIsInstance<Track.Video>().first()
        val clips = track.clips.filterIsInstance<Clip.Video>()
        return clips.associate { c ->
            c.id to clipMezzanineFingerprint(
                clip = c,
                fades = null,
                boundSourceDeepHashes = emptyMap(),
                output = output,
                engineId = engineId,
            )
        }
    }

    @Test fun coldProjectAllClipsRenderStale() {
        val p = project(listOf(clip("c1"), clip("c2", start = 3.0, dur = 2.0)))
        val stale = p.renderStaleClips(output, engineId)
        assertEquals(2, stale.size, "cold project (empty cache) → every clip render-stale")
        assertEquals(setOf(ClipId("c1"), ClipId("c2")), stale.map { it.clipId }.toSet())
    }

    @Test fun fullyCachedProjectReportsZeroStale() {
        val clips = listOf(clip("c1"), clip("c2", start = 3.0, dur = 2.0))
        val coldP = project(clips)
        val fps = fingerprintsFor(coldP)
        val cache = ClipRenderCache(
            entries = fps.values.map { fp ->
                ClipRenderCacheEntry(
                    fingerprint = fp,
                    mezzaninePath = "/tmp/cache/$fp.mp4",
                    resolutionWidth = output.resolution.width,
                    resolutionHeight = output.resolution.height,
                    durationSeconds = 3.0,
                    createdAtEpochMs = 1L,
                )
            },
        )
        val warmP = project(clips, cache)
        assertTrue(
            warmP.renderStaleClips(output, engineId).isEmpty(),
            "every clip's fingerprint is cached → render-stale list empty (full reuse possible)",
        )
    }

    @Test fun editingOneClipFilterInvalidatesOnlyThatClip() {
        val c1 = clip("c1")
        val c2 = clip("c2", start = 3.0, dur = 2.0)
        val initialP = project(listOf(c1, c2))
        val fps = fingerprintsFor(initialP)
        // Cache holds both clips' fingerprints — fully reusable.
        val cache = ClipRenderCache(
            entries = fps.values.map { fp ->
                ClipRenderCacheEntry(
                    fingerprint = fp,
                    mezzaninePath = "/tmp/cache/$fp.mp4",
                    resolutionWidth = 1920,
                    resolutionHeight = 1080,
                    durationSeconds = 3.0,
                    createdAtEpochMs = 1L,
                )
            },
        )

        // Edit only c2: add a vignette filter. c1 untouched.
        val editedC2 = c2.copy(filters = listOf(Filter(name = "vignette", params = mapOf("intensity" to 0.7f))))
        val editedP = project(listOf(c1, editedC2), cache)

        val stale = editedP.renderStaleClips(output, engineId)
        assertEquals(1, stale.size, "edited clip's fingerprint shifts; unedited clip's fingerprint matches cache")
        assertEquals(ClipId("c2"), stale.single().clipId)
    }

    @Test fun changingEngineIdInvalidatesEveryClip() {
        val clips = listOf(clip("c1"), clip("c2", start = 3.0, dur = 2.0))
        val coldP = project(clips)
        val fps = fingerprintsFor(coldP)
        val cache = ClipRenderCache(
            entries = fps.values.map { fp ->
                ClipRenderCacheEntry(
                    fingerprint = fp,
                    mezzaninePath = "/tmp/cache/$fp.mp4",
                    resolutionWidth = 1920,
                    resolutionHeight = 1080,
                    durationSeconds = 3.0,
                    createdAtEpochMs = 1L,
                )
            },
        )
        val warmP = project(clips, cache)

        // Same project, same OutputSpec, but engineId differs → every
        // fingerprint shifts at segment #5 → cache miss for all clips.
        val staleOnDifferentEngine = warmP.renderStaleClips(output, engineId = "different-engine")
        assertEquals(2, staleOnDifferentEngine.size, "engineId perturbs every clip's fingerprint")
    }

    @Test fun changingOutputSpecResolutionInvalidatesEveryClip() {
        val clips = listOf(clip("c1"))
        val coldP = project(clips)
        val fps = fingerprintsFor(coldP)
        val cache = ClipRenderCache(
            entries = fps.values.map { fp ->
                ClipRenderCacheEntry(
                    fingerprint = fp,
                    mezzaninePath = "/tmp/cache/$fp.mp4",
                    resolutionWidth = 1920,
                    resolutionHeight = 1080,
                    durationSeconds = 3.0,
                    createdAtEpochMs = 1L,
                )
            },
        )
        val warmP = project(clips, cache)

        val differentResolution = output.copy(resolution = Resolution(1280, 720))
        val staleOnNewProfile = warmP.renderStaleClips(differentResolution, engineId)
        assertEquals(1, staleOnNewProfile.size, "different resolution → fingerprint differs → cache miss")
    }

    @Test fun multiVideoTrackTimelineReportsEmpty() {
        // Per-clip cache eligibility (timelineFitsPerClipPath) requires
        // exactly one Video track. Two video tracks → `renderStaleClips`
        // returns empty list because the per-clip path doesn't apply at
        // all; whole-timeline RenderCache is the gate for those shapes.
        val p = Project(
            id = io.talevia.core.ProjectId("p-multi"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(id = TrackId("v0"), clips = listOf(clip("c1"))),
                    Track.Video(id = TrackId("v1"), clips = listOf(clip("c2"))),
                ),
                duration = 3.seconds,
            ),
        )
        assertTrue(
            p.renderStaleClips(output, engineId).isEmpty(),
            "multi-Video-track shape is non-eligible → empty list (per-clip undefined)",
        )
    }

    @Test fun emptyTimelineReportsEmpty() {
        val p = Project(
            id = io.talevia.core.ProjectId("p-empty"),
            timeline = Timeline(tracks = emptyList(), duration = 0.seconds),
        )
        assertTrue(
            p.renderStaleClips(output, engineId).isEmpty(),
            "empty timeline → empty list (no clips to report on)",
        )
    }

    @Test fun fingerprintFieldEnablesDebuggingMisses() {
        // The Output's `fingerprint` field is the same value the export
        // hot path would compute — useful for debugging "why did this
        // miss?" by comparing against `clipRenderCache.entries`.
        val p = project(listOf(clip("c1")))
        val stale = p.renderStaleClips(output, engineId)
        assertEquals(1, stale.size)
        val report = stale.single()
        // FNV-1a 64-bit hex is exactly 16 chars (8 bytes × 2 hex
        // chars). Sanity-check that the field is populated.
        assertEquals(16, report.fingerprint.length, "fingerprint must be FNV-1a 64-bit hex (16 chars)")
        // And it must equal what `clipMezzanineFingerprint` directly
        // computes — the function is just the join of fingerprint
        // computation + cache lookup, no extra transformation.
        val direct = fingerprintsFor(p)[ClipId("c1")]
        assertEquals(direct, report.fingerprint, "report fingerprint must equal the direct compute")
    }
}
