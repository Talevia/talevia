package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [timelineDurationIssues] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/
 * ValidateProjectChecks.kt:28`. Cycle 298 audit: existing
 * [io.talevia.core.domain.ProjectValidationTest] covers the
 * function indirectly via `computeProjectValidationIssues`
 * aggregator (1 case in `computeAggregatesAcrossAllThreeAxes`),
 * but no per-axis function-level pin.
 *
 * Same audit-pattern fallback as cycles 207-297. Applied
 * cycle 289-banked duplicate-check idiom — `find -name
 * 'TimelineDurationIssues*Test*'` returns 0 files.
 *
 * `timelineDurationIssues` is the lint that detects
 * `timeline.duration < latest clip end` — engines silently
 * truncate render output past `timeline.duration`, so this
 * warning surfaces a footgun before export.
 *
 * Drift signals:
 *   - **Drift `<` to `<=`** silently fires the warning even
 *     when timeline duration exactly matches the last clip
 *     (no truncation would actually happen).
 *   - **Drift severity to "error"** would block exports
 *     where engines could legitimately truncate.
 *   - **Drift code to a different string** silently breaks
 *     downstream consumers that filter / route on this code.
 *   - **Drift in maxOfOrNull semantics** (e.g. only first
 *     track) silently misses the latest-clip-end across
 *     multiple tracks.
 *   - **Drift in default `actualMax = ZERO` for empty
 *     timeline** would surface false positives on
 *     timelines with negative durations (theoretical drift
 *     in upstream).
 */
class TimelineDurationIssuesTest {

    private fun project(timeline: Timeline): Project = Project(
        id = ProjectId("p1"),
        timeline = timeline,
    )

    private fun videoClip(id: String, start: Duration, dur: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start = start, duration = dur),
        sourceRange = TimeRange(start = Duration.ZERO, duration = dur),
        assetId = AssetId("asset-$id"),
    )

    // ── Empty / no-op cases ─────────────────────────────────

    @Test fun emptyTimelineProducesNoIssues() {
        // Pin: empty timeline → actualMax=ZERO → zero >=
        // zero → no issue. Drift to fire-on-empty would
        // surface here.
        val proj = project(Timeline())
        assertEquals(emptyList(), timelineDurationIssues(proj))
    }

    @Test fun timelineWithEmptyTracksProducesNoIssues() {
        // Edge: tracks exist but hold no clips — actualMax
        // stays ZERO (flatMap of empty clip lists).
        val proj = project(
            Timeline(tracks = listOf(Track.Video(TrackId("v1")))),
        )
        assertEquals(emptyList(), timelineDurationIssues(proj))
    }

    // ── Boundary: < / == / > ───────────────────────────────

    @Test fun timelineDurationGreaterThanLatestClipEndProducesNoIssue() {
        // Pin: timeline.duration > actualMax → no warning.
        // (No truncation possible.)
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 5.seconds)),
                    ),
                ),
                duration = 10.seconds,
            ),
        )
        assertEquals(emptyList(), timelineDurationIssues(proj))
    }

    @Test fun timelineDurationEqualToLatestClipEndProducesNoIssue() {
        // Marquee boundary pin: equality is NOT an issue.
        // Drift `<` to `<=` would fire here despite no
        // truncation.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 5.seconds)),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        assertEquals(
            emptyList(),
            timelineDurationIssues(proj),
            "timeline.duration == actualMax MUST NOT fire (drift `<` to `<=` surfaces here)",
        )
    }

    @Test fun timelineDurationLessThanLatestClipEndFiresWarning() {
        // Marquee positive pin: actual truncation case.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 10.seconds)),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        val issues = timelineDurationIssues(proj)
        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals("warn", issue.severity)
        assertEquals("duration-mismatch", issue.code)
    }

    // ── Severity + code pins ────────────────────────────────

    @Test fun severityIsExactlyWarnNotError() {
        // Marquee severity pin: drift to "error" would block
        // exports unnecessarily — engines truncate, which
        // is sub-optimal but not invalid.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 10.seconds)),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        assertEquals(
            "warn",
            timelineDurationIssues(proj).single().severity,
            "duration-mismatch MUST be 'warn' (drift to 'error' would block exports)",
        )
    }

    @Test fun codeIsExactlyDurationMismatch() {
        // Marquee code pin: drift to a different code would
        // silently break downstream consumers parsing /
        // routing on the code.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 10.seconds)),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        assertEquals(
            "duration-mismatch",
            timelineDurationIssues(proj).single().code,
        )
    }

    // ── Message format ──────────────────────────────────────

    @Test fun messageMentionsBothDurationsAndTruncateWarning() {
        // Marquee message pin: human-readable lint surfaces
        // both the timeline duration and the actual max,
        // plus the "engines will truncate output" warning.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 10.seconds)),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        val msg = timelineDurationIssues(proj).single().message
        assertTrue("5s" in msg, "message MUST contain timeline duration; got: $msg")
        assertTrue("10s" in msg, "message MUST contain actualMax; got: $msg")
        assertTrue(
            "less than the latest clip end" in msg,
            "message MUST contain the canonical comparison phrase",
        )
        assertTrue(
            "engines will truncate output" in msg,
            "message MUST warn about engine truncation behavior",
        )
    }

    @Test fun messageFormatsFractionalSeconds() {
        // Pin: secondsString format trims trailing zeros
        // (1500ms → "1.5", 1000ms → "1", 30500ms → "30.5").
        // Drift in formatter (e.g. always show .0) would
        // surface here.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 1500.toLong().let { it.milliseconds() })),
                    ),
                ),
                duration = 500.toLong().let { it.milliseconds() },
            ),
        )
        val msg = timelineDurationIssues(proj).single().message
        assertTrue("0.5s" in msg, "fractional duration MUST format as '0.5'; got: $msg")
        assertTrue("1.5s" in msg, "fractional max MUST format as '1.5'; got: $msg")
    }

    // ── Multi-track maxOfOrNull semantic ────────────────────

    @Test fun actualMaxFlattensAcrossAllTracks() {
        // Marquee multi-track pin: per source line 30,
        // tracks.flatMap{it.clips}.maxOfOrNull → max ACROSS
        // all tracks. Drift to consider only the first
        // track silently misses clips on later tracks.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 3.seconds)),
                    ),
                    Track.Audio(
                        TrackId("a1"),
                        clips = listOf(
                            Clip.Audio(
                                id = ClipId("a-c1"),
                                timeRange = TimeRange(Duration.ZERO, 8.seconds),
                                sourceRange = TimeRange(Duration.ZERO, 8.seconds),
                                assetId = AssetId("audio-asset"),
                            ),
                        ),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        // Audio track's 8s clip end > timeline 5s → fire.
        val issues = timelineDurationIssues(proj)
        assertEquals(
            1,
            issues.size,
            "multi-track max MUST find clips on non-video tracks (drift to first-track-only surfaces here)",
        )
        assertTrue("8s" in issues.single().message)
    }

    @Test fun actualMaxIsMaxOfClipEndsNotSumOfDurations() {
        // Pin: per source `it.timeRange.end` (start +
        // duration), NOT just duration. A clip starting at
        // 10s with 5s duration ends at 15s.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", start = 10.seconds, dur = 5.seconds),
                            // Note: in practice clips overlap rarely,
                            // but maxOfOrNull would still pick the latest end.
                        ),
                    ),
                ),
                duration = 12.seconds,
            ),
        )
        // c1 ends at 10+5 = 15s; timeline 12s → 12 < 15 → fire.
        val issues = timelineDurationIssues(proj)
        assertEquals(1, issues.size, "actualMax MUST be timeRange.end (start + duration), not duration alone")
        assertTrue("15s" in issues.single().message)
    }

    @Test fun actualMaxPicksLatestEndAcrossOverlappingClips() {
        // Sister maxOfOrNull pin: when clips overlap or
        // appear out of order, max picks the latest end
        // regardless of declaration order.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c-late", start = 8.seconds, dur = 7.seconds), // ends 15s
                            videoClip("c-early", start = 0.seconds, dur = 3.seconds), // ends 3s
                        ),
                    ),
                ),
                duration = 10.seconds,
            ),
        )
        // Latest end is 15s (from c-late, declared first).
        val issues = timelineDurationIssues(proj)
        assertEquals(1, issues.size)
        assertTrue("15s" in issues.single().message)
    }

    @Test fun returnsExactlyOneIssueNotPerClip() {
        // Pin: returns at most ONE issue per project, not
        // one per offending clip. Drift to per-clip would
        // surface as N issues rather than 1.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", start = 0.seconds, dur = 5.seconds),
                            videoClip("c2", start = 10.seconds, dur = 5.seconds),
                            videoClip("c3", start = 15.seconds, dur = 5.seconds),
                        ),
                    ),
                ),
                duration = 1.seconds,
            ),
        )
        // 3 clips all extend past 1s; single warning issue
        // surfaces.
        assertEquals(
            1,
            timelineDurationIssues(proj).size,
            "exactly one issue per project (NOT per offending clip)",
        )
    }

    @Test fun trackIdAndClipIdAreNotPopulatedOnDurationIssue() {
        // Pin: timeline-duration is a project-level concern,
        // NOT clip-level. trackId / clipId are null on this
        // issue. Drift to populate would surface here.
        val proj = project(
            Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", Duration.ZERO, 10.seconds)),
                    ),
                ),
                duration = 5.seconds,
            ),
        )
        val issue = timelineDurationIssues(proj).single()
        assertEquals(
            null,
            issue.trackId,
            "duration-mismatch is project-level — trackId MUST be null",
        )
        assertEquals(
            null,
            issue.clipId,
            "duration-mismatch is project-level — clipId MUST be null",
        )
    }
}

/**
 * Local helper for the "milliseconds" syntax used in fractional-
 * second tests above. kotlin.time.Duration has only `.seconds`
 * extension on Int / Long; ms requires `Duration.milliseconds`.
 */
private fun Long.milliseconds(): Duration = (this).let { kotlin.time.Duration.parse("PT${it / 1000.0}S") }
