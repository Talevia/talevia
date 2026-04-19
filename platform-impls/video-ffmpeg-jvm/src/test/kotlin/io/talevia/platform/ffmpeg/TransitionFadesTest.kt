package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.platform.MediaPathResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level verification of transition detection and fade chain emission.
 * Exercises [FfmpegVideoEngine.transitionFadesFor] against hand-built
 * timelines — no ffmpeg binary involved.
 */
class TransitionFadesTest {
    private val engine = FfmpegVideoEngine(pathResolver = NullResolver)

    @Test
    fun emptyTimelineProducesNoFades() {
        val timeline = Timeline(tracks = emptyList())
        assertEquals(emptyMap(), engine.transitionFadesFor(timeline, emptyList()))
    }

    @Test
    fun timelineWithoutTransitionClipsProducesNoFades() {
        val clipA = videoClip("a", start = 0.seconds, duration = 2.seconds)
        val clipB = videoClip("b", start = 2.seconds, duration = 2.seconds)
        val timeline = Timeline(
            tracks = listOf(Track.Video(TrackId("v"), clips = listOf(clipA, clipB))),
        )
        assertTrue(engine.transitionFadesFor(timeline, listOf(clipA, clipB)).isEmpty())
    }

    @Test
    fun transitionAtBoundaryProducesHalfDurationFadesOnBothNeighbours() {
        val clipA = videoClip("a", start = 0.seconds, duration = 2.seconds)
        val clipB = videoClip("b", start = 2.seconds, duration = 2.seconds)
        // Transition is centered on the boundary (t=2s) and spans 0.5s.
        val trans = transitionClip("t", midpoint = 2.seconds, duration = 0.5.seconds)
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(TrackId("v"), clips = listOf(clipA, clipB)),
                Track.Effect(TrackId("fx"), clips = listOf(trans)),
            ),
        )
        val fades = engine.transitionFadesFor(timeline, listOf(clipA, clipB))
        assertEquals(0.25.seconds, fades["a"]?.tailFade)
        assertNull(fades["a"]?.headFade)
        assertEquals(0.25.seconds, fades["b"]?.headFade)
        assertNull(fades["b"]?.tailFade)
    }

    @Test
    fun middleClipAccumulatesBothHeadAndTailFadesFromTwoTransitions() {
        val a = videoClip("a", start = 0.seconds, duration = 2.seconds)
        val b = videoClip("b", start = 2.seconds, duration = 2.seconds)
        val c = videoClip("c", start = 4.seconds, duration = 2.seconds)
        val ab = transitionClip("ab", midpoint = 2.seconds, duration = 0.4.seconds)
        val bc = transitionClip("bc", midpoint = 4.seconds, duration = 0.6.seconds)
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(TrackId("v"), clips = listOf(a, b, c)),
                Track.Effect(TrackId("fx"), clips = listOf(ab, bc)),
            ),
        )
        val fades = engine.transitionFadesFor(timeline, listOf(a, b, c))
        assertEquals(0.2.seconds, fades["b"]?.headFade)
        assertEquals(0.3.seconds, fades["b"]?.tailFade)
    }

    @Test
    fun fadesProduceFiltergraphFadeFilters() {
        val a = videoClip("a", start = 0.seconds, duration = 2.seconds)
        val b = videoClip("b", start = 2.seconds, duration = 2.seconds)
        val trans = transitionClip("t", midpoint = 2.seconds, duration = 0.5.seconds)
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(TrackId("v"), clips = listOf(a, b)),
                Track.Effect(TrackId("fx"), clips = listOf(trans)),
            ),
        )
        val fades = engine.transitionFadesFor(timeline, listOf(a, b))
        // Tail fade on `a`: starts at clipDur - halfDur = 1.75s, lasts 0.25s.
        // Head fade on `b`: starts at 0, lasts 0.25s.
        assertEquals(
            "fade=t=out:st=1.75:d=0.25:c=black",
            engine.buildFadeChain(a, fades["a"]),
        )
        assertEquals(
            "fade=t=in:st=0:d=0.25:c=black",
            engine.buildFadeChain(b, fades["b"]),
        )
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("asset-$id"),
    )

    private fun transitionClip(id: String, midpoint: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        // The tool stores transitionRange = TimeRange(midpoint - duration/2, duration),
        // centered on the boundary between two adjacent video clips.
        timeRange = TimeRange(midpoint - duration / 2, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("transition:fade"),
        filters = listOf(Filter("fade", mapOf("durationSeconds" to duration.inWholeMilliseconds.toFloat() / 1000f))),
    )

    private object NullResolver : MediaPathResolver {
        override suspend fun resolve(assetId: AssetId): String = error("not used")
    }
}
