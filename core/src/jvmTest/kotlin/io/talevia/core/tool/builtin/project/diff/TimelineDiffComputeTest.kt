package io.talevia.core.tool.builtin.project.diff

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [computeTimelineDiffRaw] —
 * `core/tool/builtin/project/diff/TimelineDiffCompute.kt`. Pure
 * function shared by `DiffProjectsTool` and
 * `project_query(select=timeline_diff)`. Cycle 219 audit: 152
 * LOC, 0 direct test refs verified across both `commonTest` and
 * `jvmTest`.
 *
 * Same audit-pattern fallback as cycles 207-218. Pure-function
 * diff with rich per-subtype semantics — drift in any branch
 * silently mis-reports timeline edits to the agent (e.g. "track
 * field unchanged" when a clip actually moved tracks would mask
 * a key signal in the diff).
 *
 * Six correctness contracts pinned:
 *
 *  1. **Track add/remove via id-set diff.** Tracks added in `to`
 *     but not in `from` → `tracksAdded`; reverse → `tracksRemoved`.
 *     Common track ids never count as track changes (the clips
 *     inside might).
 *
 *  2. **Cross-track clip move is "change", NOT "remove + add".**
 *     Marquee semantic pin: per kdoc, "cross-track moves are
 *     'change' with a `track` field". A clip whose id stays the
 *     same but moves between tracks shows up in `clipsChanged`
 *     with `changedFields = ["track"]`. Drift to "remove + add"
 *     would inflate the diff and lose the move signal.
 *
 *  3. **Subtype change → catch-all "kind" field.** Per kdoc, when
 *     the same id flips between Video/Audio/Text variants, the
 *     diff emits a single `"kind"` field instead of enumerating
 *     per-subtype fields (which would be misleading — comparing
 *     `Video.filters` against `Text.style` makes no sense).
 *
 *  4. **Per-subtype field comparison.** When subtype matches:
 *     - Common: timeRange / sourceRange / transforms / sourceBinding
 *     - Video-specific: assetId, filters
 *     - Audio-specific: assetId, volume
 *     - Text-specific: text, style
 *
 *  5. **No-change clips filtered out.** `mapNotNull` drops clips
 *     that are present in both projects but have zero changed
 *     fields. Drift to "always emit a row" would let `totalChanges`
 *     diverge from list sums.
 *
 *  6. **`totalChanges` = exact pre-cap sum across all 5 lists.**
 *     Plus `capTimelineDiff` truncates lists to TIMELINE_DIFF_MAX_DETAIL
 *     (default 50) without affecting the raw counts.
 *
 * Plus shape pins: track / clip kindString mapping; identical
 * projects produce zero-everything diff.
 */
class TimelineDiffComputeTest {

    private fun videoClip(
        id: String,
        start: Long = 0,
        duration: Long = 5,
        assetId: String = "a-$id",
        transforms: List<Transform> = emptyList(),
        filters: List<Filter> = emptyList(),
        sourceBinding: Set<String> = emptySet(),
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, duration.seconds),
        sourceRange = TimeRange(0.seconds, duration.seconds),
        assetId = AssetId(assetId),
        transforms = transforms,
        filters = filters,
        sourceBinding = sourceBinding.map { SourceNodeId(it) }.toSet(),
    )

    private fun audioClip(
        id: String,
        start: Long = 0,
        duration: Long = 5,
        volume: Float = 1.0f,
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, duration.seconds),
        sourceRange = TimeRange(0.seconds, duration.seconds),
        assetId = AssetId("a-$id"),
        volume = volume,
    )

    private fun textClip(
        id: String,
        text: String = "hi",
        style: TextStyle = TextStyle(),
    ): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        text = text,
        style = style,
    )

    private fun project(tracks: List<Track> = emptyList()): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(tracks = tracks),
    )

    // ── 1. Track add/remove ─────────────────────────────────

    @Test fun tracksAddedAndRemovedByIdSetDiff() {
        val from = project(
            tracks = listOf(
                Track.Video(id = TrackId("v1")),
                Track.Audio(id = TrackId("a1")),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(id = TrackId("v1")),
                Track.Subtitle(id = TrackId("s1")),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        assertEquals(listOf("s1"), diff.tracksAdded.map { it.trackId })
        assertEquals(listOf("subtitle"), diff.tracksAdded.map { it.kind })
        assertEquals(listOf("a1"), diff.tracksRemoved.map { it.trackId })
        assertEquals(listOf("audio"), diff.tracksRemoved.map { it.kind })
        // No clips on either side → no clip rows.
        assertEquals(0, diff.clipsAdded.size)
        assertEquals(0, diff.clipsRemoved.size)
        assertEquals(0, diff.clipsChanged.size)
    }

    @Test fun trackKindStringMatrix() {
        // Pin: per impl `kindString` over the 4 Track variants.
        for ((track, expected) in listOf(
            Track.Video(id = TrackId("v")) to "video",
            Track.Audio(id = TrackId("a")) to "audio",
            Track.Subtitle(id = TrackId("s")) to "subtitle",
            Track.Effect(id = TrackId("e")) to "effect",
        )) {
            val from = project()
            val to = project(tracks = listOf(track))
            val diff = computeTimelineDiffRaw(from, to)
            assertEquals(
                expected,
                diff.tracksAdded.single().kind,
                "track kind for ${track::class.simpleName}",
            )
        }
    }

    // ── 2. Cross-track clip move ────────────────────────────

    @Test fun crossTrackClipMoveIsChangeNotRemoveAdd() {
        // Marquee pin: a clip with the same id moving between tracks
        // shows up in clipsChanged with `changedFields=["track"]`,
        // NOT as remove + add.
        val from = project(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = listOf(videoClip("c1"))),
                Track.Video(id = TrackId("v2"), clips = emptyList()),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = emptyList()),
                Track.Video(id = TrackId("v2"), clips = listOf(videoClip("c1"))),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        assertEquals(0, diff.clipsAdded.size, "moved clip is NOT added")
        assertEquals(0, diff.clipsRemoved.size, "moved clip is NOT removed")
        assertEquals(1, diff.clipsChanged.size)
        val change = diff.clipsChanged[0]
        assertEquals("c1", change.clipId)
        assertEquals("v2", change.trackId, "trackId reflects the destination")
        assertEquals(listOf("track"), change.changedFields)
    }

    // ── 3. Subtype change → "kind" catch-all ───────────────

    @Test fun subtypeChangeEmitsKindFieldOnly() {
        // Pin: when the SAME id flips Video → Text, only "kind" is
        // emitted (NOT "assetId", "text", etc.). The kdoc says
        // "treat as one catch-all 'kind' change rather than
        // enumerating subtype fields".
        val from = project(
            tracks = listOf(
                Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c1"))),
                Track.Video(id = TrackId("v"), clips = emptyList()),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        // The subtitle track is added; the video clip changed kind+track.
        assertEquals(1, diff.clipsChanged.size)
        val change = diff.clipsChanged[0]
        assertEquals("c1", change.clipId)
        // Track changed (v → s) AND kind changed; both reported.
        // Per impl: track is added FIRST, then if subtype mismatches
        // we add "kind" and RETURN (no per-subtype enumeration).
        assertEquals(
            listOf("track", "kind"),
            change.changedFields,
            "track + kind reported; per-subtype fields suppressed",
        )
    }

    // ── 4. Per-subtype field comparison ─────────────────────

    @Test fun videoTimeRangeChangeReported() {
        val from = project(
            tracks = listOf(
                Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", start = 0, duration = 5))),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", start = 10, duration = 5))),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        assertEquals(listOf("timeRange"), diff.clipsChanged[0].changedFields)
    }

    @Test fun videoAssetAndFiltersChangedFields() {
        // Pin: Video-specific fields = assetId + filters. Other
        // common fields untouched.
        val from = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        videoClip("c1", assetId = "old-asset", filters = emptyList()),
                    ),
                ),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        videoClip(
                            "c1",
                            assetId = "new-asset",
                            filters = listOf(Filter(name = "blur")),
                        ),
                    ),
                ),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        val fields = diff.clipsChanged[0].changedFields
        assertTrue("assetId" in fields, "assetId reported; got: $fields")
        assertTrue("filters" in fields, "filters reported; got: $fields")
        assertFalse("volume" in fields, "Audio-specific field NOT reported on Video")
        assertFalse("text" in fields, "Text-specific field NOT reported on Video")
    }

    @Test fun audioVolumeChangedField() {
        val from = project(
            tracks = listOf(
                Track.Audio(id = TrackId("a"), clips = listOf(audioClip("c1", volume = 1.0f))),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Audio(id = TrackId("a"), clips = listOf(audioClip("c1", volume = 0.5f))),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        assertEquals(listOf("volume"), diff.clipsChanged[0].changedFields)
    }

    @Test fun textTextAndStyleChangedFields() {
        val from = project(
            tracks = listOf(
                Track.Subtitle(
                    id = TrackId("s"),
                    clips = listOf(textClip("c1", text = "old", style = TextStyle(fontSize = 24f))),
                ),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Subtitle(
                    id = TrackId("s"),
                    clips = listOf(textClip("c1", text = "new", style = TextStyle(fontSize = 32f))),
                ),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        val fields = diff.clipsChanged[0].changedFields
        assertTrue("text" in fields)
        assertTrue("style" in fields)
        assertFalse("assetId" in fields, "Text has no assetId field")
    }

    @Test fun transformsAndSourceBindingComparedForAllVariants() {
        // Pin: transforms + sourceBinding are common across all
        // three variants (compared in the same `if` block before
        // the per-subtype `when`).
        val from = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        videoClip(
                            "c1",
                            transforms = emptyList(),
                            sourceBinding = setOf("style-1"),
                        ),
                    ),
                ),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        videoClip(
                            "c1",
                            transforms = listOf(Transform(opacity = 0.5f)),
                            sourceBinding = setOf("style-2"),
                        ),
                    ),
                ),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        val fields = diff.clipsChanged[0].changedFields
        assertTrue("transforms" in fields)
        assertTrue("sourceBinding" in fields)
    }

    @Test fun sourceRangeChangeReported() {
        val from = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("a"),
                        ),
                    ),
                ),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(10.seconds, 5.seconds),
                            assetId = AssetId("a"),
                        ),
                    ),
                ),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        assertEquals(listOf("sourceRange"), diff.clipsChanged[0].changedFields)
    }

    // ── 5. No-change clips filtered out ────────────────────

    @Test fun unchangedClipProducesNoRow() {
        // Marquee pin: a clip identical in both projects produces
        // ZERO changed-rows (the `mapNotNull` on empty fields drops
        // it). Drift to "always emit row" would let totalChanges
        // diverge from "actually changed" semantically.
        val from = project(
            tracks = listOf(
                Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        assertEquals(0, diff.clipsChanged.size)
        assertEquals(0, diff.clipsAdded.size)
        assertEquals(0, diff.clipsRemoved.size)
        assertEquals(0, diff.tracksAdded.size)
        assertEquals(0, diff.tracksRemoved.size)
        assertEquals(0, diff.totalChanges)
    }

    @Test fun identicalProjectsProduceEmptyDiff() {
        val p = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        videoClip("c1"),
                        videoClip("c2", start = 5),
                    ),
                ),
                Track.Audio(id = TrackId("a"), clips = listOf(audioClip("ac"))),
            ),
        )
        val diff = computeTimelineDiffRaw(p, p)
        assertEquals(0, diff.totalChanges)
    }

    // ── 6. totalChanges + capTimelineDiff ──────────────────

    @Test fun totalChangesIsExactPreCapSum() {
        // Pin: totalChanges = sum of all 5 lists. Drift to "min
        // (sum, max)" or similar would lie about the true
        // change count.
        val from = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(videoClip("c1"), videoClip("c2", start = 5)),
                ),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Audio(id = TrackId("a"), clips = listOf(audioClip("ac"))),
                // c1 changed (start), c2 removed.
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(videoClip("c1", start = 99)),
                ),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        // tracksAdded=1 (a), tracksRemoved=0, clipsAdded=1 (ac),
        // clipsRemoved=1 (c2), clipsChanged=1 (c1).
        assertEquals(1, diff.tracksAdded.size)
        assertEquals(0, diff.tracksRemoved.size)
        assertEquals(1, diff.clipsAdded.size)
        assertEquals(1, diff.clipsRemoved.size)
        assertEquals(1, diff.clipsChanged.size)
        assertEquals(4, diff.totalChanges, "1+0+1+1+1 = 4")
    }

    @Test fun capTimelineDiffReturnsThisWhenSizeWithinMax() {
        // Pin: per impl `if (size <= max) this`. The original list
        // is returned (NOT a copy) when within budget.
        val list = listOf("a", "b", "c")
        val capped = list.capTimelineDiff(max = 5)
        assertTrue(capped === list, "returns same instance when within max")
    }

    @Test fun capTimelineDiffTakesFirstNWhenSizeExceedsMax() {
        val list = listOf("a", "b", "c", "d", "e")
        val capped = list.capTimelineDiff(max = 3)
        assertEquals(listOf("a", "b", "c"), capped)
    }

    @Test fun capTimelineDiffDefaultMaxIsFifty() {
        // Pin: default max = TIMELINE_DIFF_MAX_DETAIL = 50. A list
        // of 51 caps to 50; 50 returns identity.
        val fifty = (1..50).map { "$it" }
        assertTrue(fifty.capTimelineDiff() === fifty)
        val fiftyOne = (1..51).map { "$it" }
        val capped = fiftyOne.capTimelineDiff()
        assertEquals(50, capped.size)
        assertEquals("1", capped.first())
        assertEquals("50", capped.last())
    }

    @Test fun timelineDiffMaxDetailConstantIsFifty() {
        // Pin: the constant value matches the documented kdoc.
        // Drift would silently change cap behavior site-wide.
        assertEquals(50, TIMELINE_DIFF_MAX_DETAIL)
    }

    // ── Clip kindString matrix ──────────────────────────────

    @Test fun clipKindStringMatrix() {
        // Pin: per impl `Clip.kindString` over the 3 variants. Tested
        // via the `clipsAdded` row for each variant (the kindString
        // is exposed via the row's `kind` field).
        val from = project()
        val toVideo = project(
            tracks = listOf(Track.Video(id = TrackId("v"), clips = listOf(videoClip("c")))),
        )
        val toAudio = project(
            tracks = listOf(Track.Audio(id = TrackId("a"), clips = listOf(audioClip("c")))),
        )
        val toText = project(
            tracks = listOf(Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c")))),
        )
        assertEquals("video", computeTimelineDiffRaw(from, toVideo).clipsAdded.single().kind)
        assertEquals("audio", computeTimelineDiffRaw(from, toAudio).clipsAdded.single().kind)
        assertEquals("text", computeTimelineDiffRaw(from, toText).clipsAdded.single().kind)
    }

    // ── End-to-end: complex multi-track, multi-change ──────

    @Test fun complexDiffAcrossMultipleTracksAndChangeTypes() {
        // Realistic: from has 2 tracks and 3 clips; to has 2 tracks
        // (one renamed = one added + one removed), 4 clips (2
        // unchanged in shared track, 1 moved track, 1 added).
        val from = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClip("c1"), videoClip("c2", start = 5)),
                ),
                Track.Audio(id = TrackId("a1"), clips = listOf(audioClip("ac"))),
            ),
        )
        val to = project(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(
                        videoClip("c1"),
                        // c2 moved out
                        videoClip("c3"), // new
                    ),
                ),
                Track.Subtitle(
                    id = TrackId("s1"),
                    clips = listOf(textClip("ac")), // ac is now Text on a Subtitle track
                ),
            ),
        )
        val diff = computeTimelineDiffRaw(from, to)
        // Tracks: a1 removed, s1 added; v1 unchanged in identity.
        assertEquals(setOf("s1"), diff.tracksAdded.map { it.trackId }.toSet())
        assertEquals(setOf("a1"), diff.tracksRemoved.map { it.trackId }.toSet())
        // Clips: c2 removed; c3 added; c1 unchanged; ac changed (track + kind).
        assertEquals(setOf("c3"), diff.clipsAdded.map { it.clipId }.toSet())
        assertEquals(setOf("c2"), diff.clipsRemoved.map { it.clipId }.toSet())
        val acChange = diff.clipsChanged.first { it.clipId == "ac" }
        assertEquals("s1", acChange.trackId, "trackId reflects new track")
        assertTrue("track" in acChange.changedFields)
        assertTrue("kind" in acChange.changedFields)
        // c1 → no change row.
        assertFalse(diff.clipsChanged.any { it.clipId == "c1" })
    }
}
