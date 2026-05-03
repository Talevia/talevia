package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TextStyle
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runTimelineClipsQuery] —
 * `project_query(select=timeline_clips)`. The bread-and-butter
 * clip-listing primitive — every "show me what's on the
 * timeline" turn pulls it. Cycle 140 audit: 181 LOC, 0 transitive
 * test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`fromSeconds` / `toSeconds` are an inclusive overlap
 *    window, not a containment window.** A clip is kept iff its
 *    timeRange intersects `[fromSeconds, toSeconds]`. Per code:
 *    excluded only when `clip.end < fromDuration` OR
 *    `clip.start > toDuration`. A regression flipping to
 *    "fully contained" semantics would silently hide clips that
 *    straddle the window edge — invisible to the LLM. Pinned
 *    by a clip [0..2s] queried with `from=1s,to=1.5s` →
 *    visible (overlaps even though not contained).
 *
 * 2. **`onlyPinned` is tri-state via the lockfile.** Pinned ⇔
 *    `lockfile.findByAssetId(clip.assetId)?.pinned == true`.
 *    Text clips and clips whose asset has no lockfile row
 *    (imported media) are NEVER pinned. Pinned by 3-clip
 *    project: pinned-video, unpinned-video (no lockfile), text
 *    — `onlyPinned=true` returns just the pinned-video,
 *    `onlyPinned=false` returns the other two.
 *
 * 3. **`trackKind` and `sortBy` validate against fixed sets and
 *    fail loud.** Unknown `trackKind` ∉ {video, audio, subtitle,
 *    effect} or `sortBy` ∉ {startseconds, durationseconds,
 *    recent} produces an `error()` listing the legal values.
 *    A regression silently coercing to default would mask LLM
 *    typos (`startsec` → falls back to startseconds) — exactly
 *    the silent-misroute the codebase removed describe_project
 *    to avoid.
 */
class TimelineClipsQueryTest {

    private fun videoClip(
        id: String,
        start: Duration = Duration.ZERO,
        duration: Duration = 1.seconds,
        binding: Set<SourceNodeId> = emptySet(),
        updatedAt: Long? = null,
        filters: List<io.talevia.core.domain.Filter> = emptyList(),
    ) = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start = start, duration = duration),
        sourceRange = TimeRange(start = Duration.ZERO, duration = duration),
        assetId = AssetId("a-$id"),
        sourceBinding = binding,
        updatedAtEpochMs = updatedAt,
        filters = filters,
    )

    private fun audioClip(
        id: String,
        start: Duration = Duration.ZERO,
        duration: Duration = 1.seconds,
        volume: Float = 1.0f,
        fadeIn: Float = 0.0f,
        fadeOut: Float = 0.0f,
    ) = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(start = start, duration = duration),
        sourceRange = TimeRange(start = Duration.ZERO, duration = duration),
        assetId = AssetId("a-$id"),
        volume = volume,
        fadeInSeconds = fadeIn,
        fadeOutSeconds = fadeOut,
    )

    private fun textClip(
        id: String,
        text: String,
        start: Duration = Duration.ZERO,
        duration: Duration = 1.seconds,
    ) = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(start = start, duration = duration),
        text = text,
        style = TextStyle(),
    )

    private fun project(
        videoClips: List<Clip> = emptyList(),
        audioClips: List<Clip> = emptyList(),
        subtitleClips: List<Clip> = emptyList(),
        lockfile: EagerLockfile = EagerLockfile(),
    ): Project {
        val tracks = mutableListOf<Track>()
        if (videoClips.isNotEmpty()) tracks += Track.Video(TrackId("vt"), videoClips)
        if (audioClips.isNotEmpty()) tracks += Track.Audio(TrackId("at"), audioClips)
        if (subtitleClips.isNotEmpty()) tracks += Track.Subtitle(TrackId("st"), subtitleClips)
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            lockfile = lockfile,
        )
    }

    private fun input(
        trackKind: String? = null,
        trackId: String? = null,
        fromSeconds: Double? = null,
        toSeconds: Double? = null,
        onlySourceBound: Boolean? = null,
        onlyPinned: Boolean? = null,
        sortBy: String? = null,
    ) = ProjectQueryTool.Input(
        select = "timeline_clips",
        trackKind = trackKind,
        trackId = trackId,
        fromSeconds = fromSeconds,
        toSeconds = toSeconds,
        onlySourceBound = onlySourceBound,
        onlyPinned = onlyPinned,
        sortBy = sortBy,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<ClipRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ClipRow.serializer()),
            out.rows,
        )

    private fun pinnedLockfileEntry(asset: String, pinned: Boolean) = LockfileEntry(
        inputHash = "h-$asset",
        toolId = "generate_video",
        assetId = AssetId(asset),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        pinned = pinned,
    )

    // ── input validation ──────────────────────────────────────────

    @Test fun unknownTrackKindFailsLoudWithLegalValuesListed() {
        val ex = assertFailsWith<IllegalStateException> {
            runTimelineClipsQuery(project(), input(trackKind = "unknown"), 100, 0)
        }
        // Pin: legal set should appear in error so the LLM can
        // self-correct without re-reading the schema.
        val msg = ex.message.orEmpty()
        assertTrue("video" in msg, "got: $msg")
        assertTrue("audio" in msg, "got: $msg")
        assertTrue("subtitle" in msg, "got: $msg")
        assertTrue("effect" in msg, "got: $msg")
        assertTrue("unknown" in msg, "input value echoed; got: $msg")
    }

    @Test fun unknownSortByFailsLoudWithLegalValuesListed() {
        val ex = assertFailsWith<IllegalStateException> {
            runTimelineClipsQuery(project(), input(sortBy = "alphabet"), 100, 0)
        }
        val msg = ex.message.orEmpty()
        assertTrue("startseconds" in msg, "got: $msg")
        assertTrue("durationseconds" in msg, "got: $msg")
        assertTrue("recent" in msg, "got: $msg")
        assertTrue("alphabet" in msg, "input value echoed; got: $msg")
    }

    @Test fun trackKindCaseInsensitiveAndTrimmed() {
        // Pin: input.trackKind?.trim()?.lowercase() — leading
        // whitespace and uppercase normalised.
        val proj = project(videoClips = listOf(videoClip("v")))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(trackKind = "  VIDEO  "), 100, 0).data,
        )
        assertEquals(1, rows.size)
        assertEquals("video", rows.single().trackKind)
    }

    // ── inclusive-overlap window ─────────────────────────────────

    @Test fun fromToOverlapKeepsStraddlingClipsNotJustContained() {
        // The marquee precedence pin: overlap ≠ containment.
        // Clip lives [0..2s]. Window [1.0,1.5]. Containment
        // semantics would exclude (clip extends past 1.5);
        // overlap semantics keep (clip's [0..2] intersects
        // [1.0..1.5]).
        val proj = project(
            videoClips = listOf(videoClip("v", duration = 2.seconds)),
        )
        val rows = decodeRows(
            runTimelineClipsQuery(
                proj,
                input(fromSeconds = 1.0, toSeconds = 1.5),
                100,
                0,
            ).data,
        )
        assertEquals(1, rows.size, "straddling clip kept under overlap semantics")
    }

    @Test fun fromExcludesClipEndingBeforeWindow() {
        // Pin: `clip.end < fromDuration` excludes. Clip [0..1s],
        // fromSeconds=2 → exclude.
        val proj = project(videoClips = listOf(videoClip("v", duration = 1.seconds)))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(fromSeconds = 2.0), 100, 0).data,
        )
        assertEquals(0, rows.size)
    }

    @Test fun toExcludesClipStartingAfterWindow() {
        // Pin: `clip.start > toDuration` excludes. Clip starts
        // at 5s, toSeconds=1 → exclude.
        val proj = project(videoClips = listOf(videoClip("v", start = 5.seconds)))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(toSeconds = 1.0), 100, 0).data,
        )
        assertEquals(0, rows.size)
    }

    @Test fun negativeFromCoercedToZero() {
        // Pin: `fromSeconds?.coerceAtLeast(0.0)`. Negative
        // → 0. Clip [0..1] kept.
        val proj = project(videoClips = listOf(videoClip("v")))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(fromSeconds = -100.0), 100, 0).data,
        )
        assertEquals(1, rows.size, "negative coerced to 0; clip [0..1] kept")
    }

    // ── onlyPinned tri-state ─────────────────────────────────────

    @Test fun onlyPinnedSeparatesByLockfilePinnedFlag() {
        val pinned = videoClip("p1")
        val unpinnedNoLockfile = videoClip("u1")
        val text = textClip("t1", text = "hi")
        val lockfile = EagerLockfile(
            entries = listOf(
                pinnedLockfileEntry("a-p1", pinned = true),
                pinnedLockfileEntry("a-u1", pinned = false),
            ),
        )
        val proj = project(
            videoClips = listOf(pinned, unpinnedNoLockfile),
            subtitleClips = listOf(text),
            lockfile = lockfile,
        )
        val onlyPinnedTrue = decodeRows(
            runTimelineClipsQuery(proj, input(onlyPinned = true), 100, 0).data,
        )
        assertEquals(listOf("p1"), onlyPinnedTrue.map { it.clipId })

        val onlyPinnedFalse = decodeRows(
            runTimelineClipsQuery(proj, input(onlyPinned = false), 100, 0).data,
        )
        // Pin: text clip is NEVER pinned (asset = null, so
        // matchesPinned returns false). With onlyPinned=false
        // both the unpinned video and the text clip pass.
        assertEquals(setOf("u1", "t1"), onlyPinnedFalse.map { it.clipId }.toSet())
    }

    @Test fun textClipNeverPinnedRegardlessOfLockfile() {
        // Pin: matchesPinned → text clips have assetId=null
        // → isPinned=false, so onlyPinned=true never returns
        // text clips even if no lockfile rows were planted at
        // all.
        val text = textClip("t1", text = "subtitle")
        val proj = project(subtitleClips = listOf(text))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(onlyPinned = true), 100, 0).data,
        )
        assertEquals(0, rows.size)
    }

    // ── trackId / trackKind / onlySourceBound filters ───────────

    @Test fun trackIdFiltersToExactTrackOnly() {
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt-a"), listOf(videoClip("v1"))),
                    Track.Video(TrackId("vt-b"), listOf(videoClip("v2"))),
                ),
            ),
        )
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(trackId = "vt-b"), 100, 0).data,
        )
        assertEquals(listOf("v2"), rows.map { it.clipId })
    }

    @Test fun onlySourceBoundDropsClipsWithEmptyBinding() {
        val bound = videoClip("b1", binding = setOf(SourceNodeId("n1")))
        val unbound = videoClip("u1")
        val proj = project(videoClips = listOf(bound, unbound))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(onlySourceBound = true), 100, 0).data,
        )
        assertEquals(listOf("b1"), rows.map { it.clipId })
    }

    @Test fun onlySourceBoundFalseIsNoOpEvenIfBoolean() {
        // Pin: per code `if (input.onlySourceBound == true)`,
        // so onlySourceBound=false is a no-op (keeps both bound
        // and unbound). The semantics are "true = enable
        // filter, anything else = no filter".
        val bound = videoClip("b1", binding = setOf(SourceNodeId("n1")))
        val unbound = videoClip("u1")
        val proj = project(videoClips = listOf(bound, unbound))
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(onlySourceBound = false), 100, 0).data,
        )
        assertEquals(setOf("b1", "u1"), rows.map { it.clipId }.toSet())
    }

    // ── per-track sort-by-start ──────────────────────────────────

    @Test fun clipsWithinTrackSortedByStartAscending() {
        // Pin: `track.clips.sortedBy { it.timeRange.start }`
        // — even if the track lists clips out of order in the
        // domain object, the row order respects start time.
        val proj = project(
            videoClips = listOf(
                videoClip("late", start = 5.seconds),
                videoClip("early", start = 1.seconds),
                videoClip("middle", start = 3.seconds),
            ),
        )
        val rows = decodeRows(runTimelineClipsQuery(proj, input(), 100, 0).data)
        assertEquals(listOf("early", "middle", "late"), rows.map { it.clipId })
    }

    // ── sortBy variants ──────────────────────────────────────────

    @Test fun sortByDurationsecondsDescending() {
        val proj = project(
            videoClips = listOf(
                videoClip("short", duration = 1.seconds),
                videoClip("long", start = 2.seconds, duration = 5.seconds),
                videoClip("med", start = 8.seconds, duration = 2.seconds),
            ),
        )
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(sortBy = "durationseconds"), 100, 0).data,
        )
        assertEquals(listOf("long", "med", "short"), rows.map { it.clipId })
    }

    @Test fun sortByRecentTailsNullStampedClips() {
        // Pin: recentComparator → DESC by stamp, nulls last,
        // tie-break by id.
        val proj = project(
            videoClips = listOf(
                videoClip("a", updatedAt = null),
                videoClip("b", start = 2.seconds, updatedAt = 200L),
                videoClip("c", start = 4.seconds, updatedAt = 100L),
            ),
        )
        val rows = decodeRows(
            runTimelineClipsQuery(proj, input(sortBy = "recent"), 100, 0).data,
        )
        // b (200) → c (100) → a (null tailed).
        assertEquals(listOf("b", "c", "a"), rows.map { it.clipId })
    }

    // ── per-kind row shape ──────────────────────────────────────

    @Test fun videoClipRowExposesAssetSourceRangeAndFilterCount() {
        val v = videoClip(
            "v1",
            duration = 3.seconds,
            binding = setOf(SourceNodeId("n2"), SourceNodeId("n1")),
            filters = listOf(io.talevia.core.domain.Filter("vignette", emptyMap())),
        )
        val rows = decodeRows(
            runTimelineClipsQuery(project(videoClips = listOf(v)), input(), 100, 0).data,
        )
        val r = rows.single()
        assertEquals("video", r.clipKind)
        assertEquals("a-v1", r.assetId)
        assertEquals(0.0, r.sourceStartSeconds)
        assertEquals(3.0, r.sourceDurationSeconds)
        assertEquals(1, r.filterCount, "filter count surfaces")
        assertNull(r.volume, "audio-only field absent")
        assertNull(r.textPreview, "text-only field absent")
        // Pin: sourceBindingNodeIds sorted ascending for diff
        // stability.
        assertEquals(listOf("n1", "n2"), r.sourceBindingNodeIds)
    }

    @Test fun audioClipRowExposesVolumeAndFades() {
        val a = audioClip("a1", volume = 0.5f, fadeIn = 0.25f, fadeOut = 0.5f)
        val rows = decodeRows(
            runTimelineClipsQuery(project(audioClips = listOf(a)), input(), 100, 0).data,
        )
        val r = rows.single()
        assertEquals("audio", r.clipKind)
        assertEquals(0.5f, r.volume)
        assertEquals(0.25f, r.fadeInSeconds)
        assertEquals(0.5f, r.fadeOutSeconds)
        assertEquals(0, r.filterCount, "video-only field defaults to 0")
        assertNull(r.textPreview)
    }

    @Test fun textClipRowCapsTextPreviewAtEightyChars() {
        // Pin: per buildClipRow, `textPreview = clip.text.take(80)`.
        // Distinct from narrative cap (40) — row keeps more
        // context for replays / UI.
        val long = "x".repeat(120)
        val t = textClip("t1", text = long)
        val rows = decodeRows(
            runTimelineClipsQuery(project(subtitleClips = listOf(t)), input(), 100, 0).data,
        )
        val r = rows.single()
        assertEquals("text", r.clipKind)
        assertEquals(80, r.textPreview!!.length, "row preview cap = 80")
        assertNull(r.assetId, "text has no asset")
        assertNull(r.sourceStartSeconds)
    }

    // ── pagination + narrative ───────────────────────────────────

    @Test fun paginationSplitsRowsAndReportsHiddenTail() {
        // Pin: hiddenByPage = filtered.size - page.size (NOT
        // total minus offset minus returned). With 5 clips,
        // offset=1, limit=2: page = [v2, v3]; hiddenByPage =
        // 5 - 2 = 3. The narrative reports the full hidden
        // count including the offset-skipped row, since the
        // LLM's view is "you asked for a slice; here's what
        // exists outside it."
        val proj = project(
            videoClips = (1..5).map { videoClip("v$it", start = it.seconds) },
        )
        val result = runTimelineClipsQuery(proj, input(), 2, 1)
        val rows = decodeRows(result.data)
        assertEquals(2, rows.size, "limit=2")
        assertEquals(listOf("v2", "v3"), rows.map { it.clipId })
        assertEquals(5, result.data.total, "total ignores pagination")
        assertEquals(2, result.data.returned)
        assertTrue(
            "(3 more not shown)" in result.outputForLlm,
            "hidden-tail count = filtered - page.size; got: ${result.outputForLlm}",
        )
    }

    @Test fun emptyResultUsesNoMatchSentinelInsteadOfBlankOutput() {
        // Pin: empty page → "No clips match the given filters."
        // Drift to "" or "0 clips" would break LLM response
        // template that quotes outputForLlm verbatim.
        val out = runTimelineClipsQuery(project(), input(), 100, 0).outputForLlm
        assertEquals("No clips match the given filters.", out)
    }

    @Test fun narrativeTextPreviewCappedAtFortyWithEllipsis() {
        // Pin: narrative cap (40) ≠ row cap (80). Long text gets
        // "…" suffix in narrative only.
        val long = "x".repeat(60)
        val t = textClip("t1", text = long)
        val out = runTimelineClipsQuery(
            project(subtitleClips = listOf(t)),
            input(),
            100,
            0,
        ).outputForLlm
        // narrative renders text="..." with up-to-40 chars + …
        assertTrue("…" in out, "ellipsis surfaces; got: $out")
        // Find the inline text block.
        val xs = "x".repeat(40)
        assertTrue(
            "text=\"$xs…\"" in out,
            "exact 40-char + ellipsis; got: $out",
        )
    }

    // ── output framing ─────────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndCounts() {
        val proj = project(videoClips = listOf(videoClip("v")))
        val result = runTimelineClipsQuery(proj, input(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_TIMELINE_CLIPS, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val proj = project(
            videoClips = (1..5).map { videoClip("v$it", start = it.seconds) },
        )
        val result = runTimelineClipsQuery(proj, input(), 2, 0)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
