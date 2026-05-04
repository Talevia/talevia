package io.talevia.core.tool.builtin.video.export

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [timelineFitsPerClipPath] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/
 * export/PerClipRender.kt:36`. Cycle 296 audit: 0 prior direct test
 * files (verified via cycle 289-banked duplicate-check idiom —
 * `find -name 'TimelineFitsPerClipPath*' -o -name 'PerClipShape*'`
 * returns nothing). Indirect mentions in
 * [PerClipCacheInvalidationEdgeTest] / [RenderStalenessTest], but no
 * function-level pin on the 5 gating conditions.
 *
 * Same audit-pattern fallback as cycles 207-295.
 *
 * `timelineFitsPerClipPath` is the gate that decides whether
 * `ExportTool` dispatches to `runPerClipRender` (cached
 * mezzanine path, FFmpeg JVM only) vs falls back to
 * `runWholeTimelineRender`. Drift in any condition silently
 * shifts which exports get the per-clip cache benefit.
 *
 * Drift signals:
 *   - **Drift to allow > 1 Track.Video** silently routes
 *     multi-video timelines through the per-clip path that
 *     can't handle them (would corrupt output).
 *   - **Drift to require > 0 video clips** would let empty
 *     timelines take the per-clip path and ship 0-byte
 *     mezzanines.
 *   - **Drift to drop the "track holds only Video clips"
 *     check** — mixed-type tracks (defensive null) would
 *     get filtered down by `filterIsInstance<Clip.Video>()`
 *     elsewhere, silently producing different outputs
 *     between per-clip and whole-timeline paths.
 *   - **Subtitle harvest drift** (drop a Track.Subtitle
 *     branch) silently strips subtitles on the per-clip
 *     path that the whole-timeline path keeps.
 *
 * Pins all 5 gating conditions + 3 happy-path subtitle /
 * shape contracts.
 */
class TimelineFitsPerClipPathTest {

    private val tr = TimeRange(0.seconds, 1.seconds)
    private val sr = TimeRange(0.seconds, 1.seconds)

    private fun videoClip(id: String = "v1") = Clip.Video(
        id = ClipId(id),
        timeRange = tr,
        sourceRange = sr,
        assetId = AssetId("a1"),
    )

    private fun audioClip(id: String = "a1") = Clip.Audio(
        id = ClipId(id),
        timeRange = tr,
        sourceRange = sr,
        assetId = AssetId("asset-a1"),
    )

    private fun textClip(id: String = "t1") = Clip.Text(
        id = ClipId(id),
        timeRange = tr,
        text = "hello",
        style = TextStyle(),
    )

    // ── Gating: exactly 1 Track.Video ───────────────────────

    @Test fun zeroVideoTracksReturnsNull() {
        // Marquee gate-1 pin: "no video track" falls back to
        // whole-timeline path. Drift to allow 0-video would
        // produce zero mezzanines and ship empty output.
        val timeline = Timeline(
            tracks = listOf(Track.Audio(id = TrackId("a"))),
        )
        assertNull(
            timelineFitsPerClipPath(timeline),
            "0 Track.Video MUST return null (per-clip path requires exactly 1)",
        )
    }

    @Test fun twoVideoTracksReturnsNull() {
        // Marquee gate-1 pin: per-clip path can't merge 2
        // video tracks (PiP / multi-cam) — must fall back.
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = listOf(videoClip("v1"))),
                Track.Video(id = TrackId("v2"), clips = listOf(videoClip("v2"))),
            ),
        )
        assertNull(
            timelineFitsPerClipPath(timeline),
            "2 Track.Video MUST return null (drift to allow multi-video corrupts output)",
        )
    }

    @Test fun threeVideoTracksReturnsNull() {
        // Sister gate-1 pin: any number > 1 Track.Video → null.
        val timeline = Timeline(
            tracks = (1..3).map {
                Track.Video(id = TrackId("v$it"), clips = listOf(videoClip("v$it")))
            },
        )
        assertNull(timelineFitsPerClipPath(timeline))
    }

    // ── Gating: ≥ 1 Clip.Video on the video track ──────────

    @Test fun emptyVideoTrackReturnsNull() {
        // Marquee gate-2 pin: empty Track.Video → null.
        // Drift to "0-clip is fine" would let empty
        // timelines take per-clip path and ship 0-byte
        // mezzanines.
        val timeline = Timeline(
            tracks = listOf(Track.Video(id = TrackId("v1"), clips = emptyList())),
        )
        assertNull(
            timelineFitsPerClipPath(timeline),
            "empty Track.Video MUST return null (per-clip path requires ≥ 1 Clip.Video)",
        )
    }

    // ── Gating: track holds ONLY Clip.Video ────────────────

    @Test fun videoTrackWithMixedClipKindsReturnsNull() {
        // Marquee gate-3 pin: defensively reject when the
        // video track contains non-Video clips. Drift to
        // drop this check would have per-clip path and
        // whole-timeline path silently differ on the same
        // input (whole-timeline filters via
        // filterIsInstance<Clip.Video>(), per-clip would
        // also do that and miss the Audio clip — but
        // semantics differ).
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClip("v1"), audioClip("audio-on-video-track")),
                ),
            ),
        )
        assertNull(
            timelineFitsPerClipPath(timeline),
            "Track.Video with mixed clip kinds MUST return null (defensive)",
        )
    }

    @Test fun videoTrackWithTextClipReturnsNull() {
        // Sister gate-3 pin: Text clip on a video track
        // also disqualifies (defensive — same fall-back).
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClip("v1"), textClip("text-on-video")),
                ),
            ),
        )
        assertNull(timelineFitsPerClipPath(timeline))
    }

    // ── Happy path: returns PerClipShape ───────────────────

    @Test fun singleVideoTrackWithVideoClipReturnsShape() {
        // Marquee happy-path pin: 1 Track.Video with N
        // Clip.Video → returns PerClipShape with those
        // clips and empty subtitles.
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClip("v1"), videoClip("v2"), videoClip("v3")),
                ),
            ),
        )
        val shape = timelineFitsPerClipPath(timeline)
        assertNotNull(shape, "happy path MUST return non-null PerClipShape")
        assertEquals(3, shape.videoClips.size)
        assertEquals(
            listOf(ClipId("v1"), ClipId("v2"), ClipId("v3")),
            shape.videoClips.map { it.id },
            "video clips MUST round-trip in order",
        )
        assertEquals(
            emptyList(),
            shape.subtitles,
            "no subtitle tracks → empty subtitles list",
        )
    }

    @Test fun videoPlusSubtitleTrackHarvestsSubtitlesIntoShape() {
        // Marquee subtitle-harvest pin: subtitles from ALL
        // Track.Subtitle tracks flatten into shape.subtitles.
        // Drift to drop a Track.Subtitle branch would silently
        // strip subtitles on per-clip path that
        // whole-timeline keeps.
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClip("v1")),
                ),
                Track.Subtitle(
                    id = TrackId("s1"),
                    clips = listOf(textClip("sub1"), textClip("sub2")),
                ),
                Track.Subtitle(
                    id = TrackId("s2"),
                    clips = listOf(textClip("sub3")),
                ),
            ),
        )
        val shape = timelineFitsPerClipPath(timeline)
        assertNotNull(shape)
        assertEquals(1, shape.videoClips.size)
        // Subtitles flattened across BOTH subtitle tracks.
        assertEquals(
            3,
            shape.subtitles.size,
            "subtitles MUST flatten from ALL Track.Subtitle tracks (not just first)",
        )
        assertEquals(
            listOf(ClipId("sub1"), ClipId("sub2"), ClipId("sub3")),
            shape.subtitles.map { it.id },
            "subtitle order MUST follow track-order × within-track-order",
        )
    }

    @Test fun nonTextClipsOnSubtitleTrackAreFilteredOut() {
        // Pin: per source line 47, subtitle harvest uses
        // `filterIsInstance<Clip.Text>()`. Drift would let
        // a non-Text clip on a Subtitle track surface as a
        // subtitle (defensive filter).
        // (This is unusual — Subtitle tracks are conventionally
        // text-only — but the filter is still load-bearing
        // for the defensive path.)
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = listOf(videoClip("v1"))),
                Track.Subtitle(
                    id = TrackId("s1"),
                    clips = listOf(textClip("real-sub"), audioClip("audio-on-subtitle-track")),
                ),
            ),
        )
        val shape = timelineFitsPerClipPath(timeline)
        assertNotNull(shape)
        assertEquals(
            1,
            shape.subtitles.size,
            "non-Text clips on Subtitle track MUST be filtered out via filterIsInstance",
        )
        assertEquals(ClipId("real-sub"), shape.subtitles.single().id)
    }

    // ── Cross-track invariants ─────────────────────────────

    @Test fun audioTracksDoNotAffectShape() {
        // Pin: presence/absence of Track.Audio doesn't
        // affect either gate or shape. Drift to consider
        // audio in the gate would surface here.
        val withoutAudio = Timeline(
            tracks = listOf(Track.Video(id = TrackId("v1"), clips = listOf(videoClip("v1")))),
        )
        val withAudio = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = listOf(videoClip("v1"))),
                Track.Audio(id = TrackId("a1"), clips = listOf(audioClip("a1"))),
            ),
        )
        val shapeNoAudio = timelineFitsPerClipPath(withoutAudio)
        val shapeWithAudio = timelineFitsPerClipPath(withAudio)
        assertNotNull(shapeNoAudio)
        assertNotNull(shapeWithAudio)
        assertEquals(
            shapeNoAudio.videoClips,
            shapeWithAudio.videoClips,
            "audio tracks MUST NOT affect video-clip harvest",
        )
        assertEquals(
            shapeNoAudio.subtitles,
            shapeWithAudio.subtitles,
            "audio tracks MUST NOT affect subtitle harvest",
        )
    }

    @Test fun effectTracksDoNotAffectShape() {
        // Sister pin: Track.Effect also doesn't affect gate
        // or shape — only Video count + Video-track contents
        // + Subtitle harvest matter.
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = listOf(videoClip("v1"))),
                Track.Effect(id = TrackId("fx1"), clips = listOf(textClip("effect-on-effect-track"))),
            ),
        )
        val shape = timelineFitsPerClipPath(timeline)
        assertNotNull(shape)
        // Effect track text clips MUST NOT leak into subtitles.
        assertEquals(
            0,
            shape.subtitles.size,
            "Track.Effect contents MUST NOT leak into subtitles harvest",
        )
    }

    @Test fun emptyTimelineReturnsNull() {
        // Edge: completely empty timeline → null (no
        // Track.Video at all, fails gate-1).
        val timeline = Timeline(tracks = emptyList())
        assertNull(timelineFitsPerClipPath(timeline))
    }

    @Test fun shapeMembersAreReferentialAndNotCopied() {
        // Pin: returned PerClipShape's videoClips list is
        // the result of filterIsInstance — fresh list, but
        // each Clip.Video is referentially identical to
        // input. Drift to deep-copy would silently break
        // identity comparisons elsewhere.
        val original = videoClip("v1")
        val timeline = Timeline(
            tracks = listOf(Track.Video(id = TrackId("v"), clips = listOf(original))),
        )
        val shape = timelineFitsPerClipPath(timeline)
        assertNotNull(shape)
        // Same reference (Kotlin === check) — drift to copy
        // would make this assertion fail.
        assertEquals(
            true,
            shape.videoClips.single() === original,
            "Clip.Video instances MUST be referentially passed (not copied)",
        )
    }
}
