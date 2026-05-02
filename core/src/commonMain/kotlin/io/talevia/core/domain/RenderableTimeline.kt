package io.talevia.core.domain

import kotlinx.serialization.Serializable

/**
 * Platform-agnostic, read-only "what to render" view of a [Timeline]. M7 §4 #2
 * cross-platform timeline viewer contract — desktop / iOS / Android UIs all
 * need to render the same shape (track headers + clip rows under each track),
 * and were each independently switching on [Track] variants and computing
 * clip-id prefixes / time-range labels. Drift between those independent
 * implementations would let the "two paths, one project" UI invariant rot.
 *
 * This abstraction folds the cross-platform timeline-render logic into one
 * place: each platform UI can iterate [rows] and render glyphs / typography
 * suited to its idiom (Compose Material `Divider`, SwiftUI `List`, AppKit
 * `NSOutlineView`, …) without re-implementing the track-variant switch or
 * the clip-id prefix / time-range formatting. The Core view-model carries no
 * platform types — only primitive Kotlin values — so it round-trips through
 * SKIE / J2ObjC / Kotlin/Native untouched.
 *
 * Naming chosen from the BACKLOG bullet's "TimelineViewer / RenderableTimeline
 * / TimelineSurface" candidates. `RenderableTimeline` reads as the *output* —
 * the form ready to render — which matches the function shape `Timeline.
 * toRenderable()`.
 *
 * @property rows ordered list of header / clip rows. Tracks appear in the
 *   same order as [Timeline.tracks]; each track yields one [TimelineRow.TrackHeader]
 *   followed by one [TimelineRow.ClipLine] per clip. Empty timeline → empty
 *   rows + [isEmpty] true.
 * @property totalDurationSeconds the timeline's [Timeline.duration] expressed
 *   in seconds, for UI duration labels.
 * @property isEmpty true when [rows] is empty (no tracks). Cheap shorthand so
 *   UIs don't have to recompute.
 */
@Serializable
data class RenderableTimeline(
    val rows: List<TimelineRow>,
    val totalDurationSeconds: Double,
    val isEmpty: Boolean,
)

/**
 * Cross-platform glyph-free track classification. UIs decide their own
 * iconography (▶ / ♪ / 𝐓 / ✦ on iOS+Android already; desktop free to choose).
 */
enum class TrackKind { Video, Audio, Subtitle, Effect }

/**
 * One row in a [RenderableTimeline]. Sealed so consumers `when`-switch
 * exhaustively. Two variants — track header and clip line — model the
 * "header / list-of-children" shape every existing UI was hand-coding.
 */
@Serializable
sealed interface TimelineRow {
    /**
     * Header row introducing a track. UIs render it with track-kind-specific
     * iconography + a count badge ("Video · 3 clips").
     */
    @Serializable
    data class TrackHeader(
        val trackId: String,
        val kind: TrackKind,
        val clipCount: Int,
    ) : TimelineRow

    /**
     * One clip on the timeline. UIs render it indented under its track header.
     * `displayId` is the 8-char prefix of [clipId] suitable for monospace UI
     * (full id is also exposed for click-through / detail navigation).
     * Time-range fields are seconds (Double) so UI labels don't have to import
     * `kotlin.time.Duration` formatting.
     */
    @Serializable
    data class ClipLine(
        val clipId: String,
        val trackId: String,
        val kind: TrackKind,
        val startSeconds: Double,
        val endSeconds: Double,
        val displayId: String,
    ) : TimelineRow
}

/**
 * Build a [RenderableTimeline] from `this` [Timeline]. Pure function: no I/O,
 * no allocation beyond the result; safe to call on any thread / dispatcher.
 * Empty timeline yields empty rows + `isEmpty=true`.
 *
 * Track ordering is preserved verbatim from [Timeline.tracks]. Within each
 * track, clips appear in the same order they're stored (the Timeline invariant
 * is "ordered by `clip.timeRange.start`" — this view inherits that).
 */
fun Timeline.toRenderable(): RenderableTimeline {
    if (tracks.isEmpty()) {
        return RenderableTimeline(
            rows = emptyList(),
            totalDurationSeconds = duration.inWholeMilliseconds / 1000.0,
            isEmpty = true,
        )
    }
    val out = mutableListOf<TimelineRow>()
    for (track in tracks) {
        val kind = trackKindOf(track)
        out += TimelineRow.TrackHeader(
            trackId = track.id.value,
            kind = kind,
            clipCount = track.clips.size,
        )
        for (clip in track.clips) {
            out += TimelineRow.ClipLine(
                clipId = clip.id.value,
                trackId = track.id.value,
                kind = kind,
                startSeconds = clip.timeRange.start.inWholeMilliseconds / 1000.0,
                endSeconds = clip.timeRange.end.inWholeMilliseconds / 1000.0,
                displayId = clip.id.value.take(DISPLAY_ID_PREFIX),
            )
        }
    }
    return RenderableTimeline(
        rows = out,
        totalDurationSeconds = duration.inWholeMilliseconds / 1000.0,
        isEmpty = false,
    )
}

private fun trackKindOf(track: Track): TrackKind = when (track) {
    is Track.Video -> TrackKind.Video
    is Track.Audio -> TrackKind.Audio
    is Track.Subtitle -> TrackKind.Subtitle
    is Track.Effect -> TrackKind.Effect
}

private const val DISPLAY_ID_PREFIX = 8
