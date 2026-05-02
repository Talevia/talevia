package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * M7 §4 #2 cross-platform timeline viewer contract — `Timeline.toRenderable()`
 * must produce a platform-agnostic view-model that desktop / iOS / Android UIs
 * can render uniformly (each platform was previously hand-switching on
 * [Track] variants and computing display IDs / time-range labels).
 *
 * Cases pinned:
 *   - empty timeline → empty rows + `isEmpty=true`.
 *   - 4 track variants (Video / Audio / Subtitle / Effect) → headers map
 *     to the right [TrackKind] enum value.
 *   - clip rows preserve order, per-track grouping (header → clips → next
 *     header → clips), and inherit the parent track's `kind`.
 *   - clip IDs shorter than `DISPLAY_ID_PREFIX` (8 chars) survive without
 *     truncation surprise (off-by-one on `take`).
 *   - timeRange seconds are computed as milliseconds/1000 — sub-second
 *     precision survives the conversion.
 *
 * The view-model is a pure function of `Timeline`; tests don't need
 * `FileProjectStore` plumbing.
 */
class RenderableTimelineTest {

    @Test fun emptyTimelineYieldsEmptyRows() {
        val viewModel = Timeline().toRenderable()
        assertTrue(viewModel.rows.isEmpty())
        assertTrue(viewModel.isEmpty)
        assertEquals(0.0, viewModel.totalDurationSeconds)
    }

    @Test fun videoTrackHeaderMapsToVideoKind() {
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v0"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("clip-1234abcd-extra"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a1"),
                        ),
                    ),
                ),
            ),
            duration = 3.seconds,
        )
        val viewModel = timeline.toRenderable()

        assertTrue(!viewModel.isEmpty)
        assertEquals(2, viewModel.rows.size)
        val header = viewModel.rows[0] as TimelineRow.TrackHeader
        assertEquals(TrackKind.Video, header.kind)
        assertEquals("v0", header.trackId)
        assertEquals(1, header.clipCount)

        val clipLine = viewModel.rows[1] as TimelineRow.ClipLine
        assertEquals(TrackKind.Video, clipLine.kind, "clip inherits parent track kind")
        assertEquals("v0", clipLine.trackId)
        assertEquals("clip-1234abcd-extra", clipLine.clipId)
        assertEquals("clip-123", clipLine.displayId, "displayId is 8-char prefix")
        assertEquals(0.0, clipLine.startSeconds)
        assertEquals(3.0, clipLine.endSeconds)
    }

    @Test fun allFourTrackKindsMapToEnumValues() {
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v0")),
                Track.Audio(id = TrackId("a0")),
                Track.Subtitle(id = TrackId("s0")),
                Track.Effect(id = TrackId("e0")),
            ),
        )
        val viewModel = timeline.toRenderable()

        // Each empty track yields exactly one TrackHeader row.
        assertEquals(4, viewModel.rows.size)
        val kinds = viewModel.rows.filterIsInstance<TimelineRow.TrackHeader>().map { it.kind }
        assertEquals(
            listOf(TrackKind.Video, TrackKind.Audio, TrackKind.Subtitle, TrackKind.Effect),
            kinds,
            "track ordering is preserved",
        )
    }

    @Test fun multiClipTrackInterleavesHeadersAndClipsInOrder() {
        // Two tracks each with two clips — assert the row sequence is
        // [header, clip, clip, header, clip, clip] rather than
        // [headers..., clips...] (a common mis-flatten that would let UIs
        // reassemble out of order).
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("vid-1"),
                            timeRange = TimeRange(0.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a1"),
                        ),
                        Clip.Video(
                            id = ClipId("vid-2"),
                            timeRange = TimeRange(2.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
                Track.Audio(
                    id = TrackId("a"),
                    clips = listOf(
                        Clip.Audio(
                            id = ClipId("aud-1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("aud-asset"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        val viewModel = timeline.toRenderable()

        // Expect 5 rows: video-header, vid-1, vid-2, audio-header, aud-1.
        assertEquals(5, viewModel.rows.size)
        val sequence = viewModel.rows.map {
            when (it) {
                is TimelineRow.TrackHeader -> "H:${it.kind}:${it.trackId}"
                is TimelineRow.ClipLine -> "C:${it.kind}:${it.clipId}"
            }
        }
        assertEquals(
            listOf(
                "H:Video:v",
                "C:Video:vid-1",
                "C:Video:vid-2",
                "H:Audio:a",
                "C:Audio:aud-1",
            ),
            sequence,
            "rows interleave headers and clips per-track in track order",
        )
        assertEquals(5.0, viewModel.totalDurationSeconds)
    }

    @Test fun shortClipIdSurvivesWithoutTruncation() {
        // ClipId shorter than the 8-char displayId prefix must round-trip
        // intact (a naive `substring(0, 8)` would have thrown
        // StringIndexOutOfBoundsException; `take` clamps).
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 1.seconds),
                            sourceRange = TimeRange(0.seconds, 1.seconds),
                            assetId = AssetId("a"),
                        ),
                    ),
                ),
            ),
        )
        val clipLine = timeline.toRenderable().rows.last() as TimelineRow.ClipLine
        assertEquals("c1", clipLine.displayId, "short clip id displays in full (not padded, not truncated)")
        assertEquals("c1", clipLine.clipId)
    }

    @Test fun subSecondTimeRangeRoundsToMillisecondPrecision() {
        // Confirm the `inWholeMilliseconds / 1000.0` conversion preserves
        // sub-second precision typical of frame-aligned clips (e.g.
        // 1.5s start = 1500 ms = 1.5 seconds in the view-model).
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c"),
                            timeRange = TimeRange(start = 1500.milliseconds, duration = 750.milliseconds),
                            sourceRange = TimeRange(0.seconds, 1.seconds),
                            assetId = AssetId("a"),
                        ),
                    ),
                ),
            ),
        )
        val clipLine = timeline.toRenderable().rows.last() as TimelineRow.ClipLine
        assertEquals(1.5, clipLine.startSeconds)
        assertEquals(2.25, clipLine.endSeconds, "end = start + duration in seconds")
    }
}
