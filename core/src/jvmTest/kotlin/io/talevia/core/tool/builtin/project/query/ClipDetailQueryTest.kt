package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
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
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runClipDetailQuery] — `project_query(select=
 * clip)`. Single-row drill-down replacing the deleted
 * `describe_clip` tool. Cycle 128 audit: 158 LOC, **zero**
 * transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Kind-discriminated field exposure: video=filters, audio=
 *    volume/fade, text=text/textStyle.** Each variant exposes
 *    only its own fields with sister fields null. A regression
 *    populating Audio fields on a Video clip would silently
 *    misleadingly show volume/fade on visual clips; conversely
 *    losing variant fields would force LLM follow-up queries.
 *
 * 2. **Lockfile staleness via source hash drift.** Per code:
 *    `entry.sourceContentHashes` snapshot is compared against
 *    each node's CURRENT contentHash; mismatches go into
 *    `driftedSourceNodeIds`. `currentlyStale = drifted.isNotEmpty()`.
 *    A regression in either the comparison direction or the
 *    drift list construction would silently flip every
 *    "is this clip stale?" answer the LLM gives.
 *
 * 3. **Summary tail: " — stale" / " — fresh" / " — pinned"
 *    composition.** Three discrete suffixes that compose
 *    independently. The LLM uses these to triage which
 *    clips need attention; silent drift in suffix logic
 *    would mislabel clip status in every drill-down.
 */
class ClipDetailQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(
        id: String,
        assetId: String,
        binding: Set<SourceNodeId> = emptySet(),
        transforms: List<Transform> = emptyList(),
        filters: List<Filter> = emptyList(),
    ) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = binding,
        transforms = transforms,
        filters = filters,
    )

    private fun audioClip(
        id: String,
        assetId: String,
        volume: Float = 1.0f,
        fadeInSeconds: Float = 0.0f,
        fadeOutSeconds: Float = 0.0f,
    ) = Clip.Audio(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        volume = volume,
        fadeInSeconds = fadeInSeconds,
        fadeOutSeconds = fadeOutSeconds,
    )

    private fun textClip(
        id: String,
        text: String = "subtitle",
        style: TextStyle = TextStyle(),
    ) = Clip.Text(
        id = ClipId(id),
        timeRange = timeRange,
        text = text,
        style = style,
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        nodes: List<SourceNode> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes),
            lockfile = EagerLockfile(entries = entries),
        )
    }

    private fun input(clipId: String?) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_CLIP,
        clipId = clipId,
    )

    private fun decodeRow(out: ProjectQueryTool.Output): ClipDetailRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ClipDetailRow.serializer()),
            out.rows,
        ).single()

    private fun lockEntry(
        assetId: String,
        sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
        pinned: Boolean = false,
    ) = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "gpt-image-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        sourceContentHashes = sourceContentHashes,
        pinned = pinned,
        originatingMessageId = MessageId("m"),
    )

    // ── input validation ──────────────────────────────────────────

    @Test fun missingClipIdErrorsLoudWithRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runClipDetailQuery(project(), input(null))
        }
        val msg = ex.message ?: ""
        assertTrue("requires clipId" in msg, "got: $msg")
        assertTrue("project_query(select=timeline_clips)" in msg, "recovery; got: $msg")
    }

    @Test fun unknownClipIdErrorsWithProjectIdAndRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runClipDetailQuery(project(), input("ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "got: $msg")
        assertTrue("not found in project p" in msg, "got: $msg")
        assertTrue("project_query(select=timeline_clips)" in msg, "got: $msg")
    }

    // ── kind-discriminated field exposure ────────────────────────

    @Test fun videoClipExposesAssetIdFiltersButNullAudioAndTextFields() {
        val filter = Filter(name = "lut", assetId = AssetId("lut-1"))
        val clip = videoClip("c1", "asset-v", filters = listOf(filter))
        val row = decodeRow(runClipDetailQuery(project(listOf(clip)), input("c1")).data)
        assertEquals("video", row.clipType)
        assertEquals("asset-v", row.assetId)
        assertEquals(listOf(filter), row.filters, "filters round-trip")
        // Audio + Text fields null on Video.
        assertNull(row.volume, "video has no volume")
        assertNull(row.fadeInSeconds)
        assertNull(row.fadeOutSeconds)
        assertNull(row.text)
        assertNull(row.textStyle)
    }

    @Test fun audioClipExposesAssetIdVolumeFadeButNullVideoAndTextFields() {
        val clip = audioClip(
            id = "c1",
            assetId = "asset-a",
            volume = 0.7f,
            fadeInSeconds = 0.5f,
            fadeOutSeconds = 1.5f,
        )
        // Need an audio track for an Audio clip; rebuild project.
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Audio(TrackId("at"), listOf(clip)))),
        )
        val row = decodeRow(runClipDetailQuery(proj, input("c1")).data)
        assertEquals("audio", row.clipType)
        assertEquals("asset-a", row.assetId)
        assertEquals(0.7f, row.volume)
        assertEquals(0.5f, row.fadeInSeconds)
        assertEquals(1.5f, row.fadeOutSeconds)
        // Video + Text fields null on Audio.
        assertNull(row.filters, "audio has no filters")
        assertNull(row.text)
        assertNull(row.textStyle)
    }

    @Test fun textClipExposesTextAndTextStyleButNullAssetAndOtherFields() {
        val style = TextStyle(fontFamily = "Helvetica", fontSize = 64f, color = "#ff0000")
        val clip = textClip(id = "t1", text = "Hello world", style = style)
        // Text clips go on Subtitle tracks.
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("st"), listOf(clip)))),
        )
        val row = decodeRow(runClipDetailQuery(proj, input("t1")).data)
        assertEquals("text", row.clipType)
        assertEquals("Hello world", row.text)
        assertEquals(style, row.textStyle)
        // Other fields null.
        assertNull(row.assetId, "text has no assetId")
        assertNull(row.filters)
        assertNull(row.volume)
        assertNull(row.fadeInSeconds)
        assertNull(row.fadeOutSeconds)
    }

    // ── timeRange / sourceRange ───────────────────────────────────

    @Test fun timeRangeRoundTripsAsMilliseconds() {
        val tr = TimeRange(start = 2.seconds, duration = 3.seconds)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = tr,
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
        )
        val row = decodeRow(runClipDetailQuery(project(listOf(clip)), input("c1")).data)
        assertEquals(2_000L, row.timeRange.startMs)
        assertEquals(3_000L, row.timeRange.durationMs)
        assertEquals(5_000L, row.timeRange.endMs, "end = start + duration")
    }

    @Test fun textClipHasNullSourceRange() {
        // Text clips have sourceRange=null per the domain model
        // (no source media to track). Pin: this null surfaces in
        // the row; it's NOT auto-defaulted.
        val clip = textClip("t1")
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("st"), listOf(clip)))),
        )
        val row = decodeRow(runClipDetailQuery(proj, input("t1")).data)
        assertNull(row.sourceRange, "text clip has null sourceRange")
    }

    @Test fun videoClipHasSourceRange() {
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(start = 1.seconds, duration = 5.seconds),
            assetId = AssetId("a"),
        )
        val row = decodeRow(runClipDetailQuery(project(listOf(clip)), input("c1")).data)
        assertNotNull(row.sourceRange)
        assertEquals(1_000L, row.sourceRange!!.startMs)
        assertEquals(5_000L, row.sourceRange!!.durationMs)
    }

    // ── sourceBindingIds sorted ───────────────────────────────────

    @Test fun sourceBindingIdsAreSortedAlphabetically() {
        // Pin diff stability: input order z, a, m → output [a, m, z].
        val clip = videoClip(
            "c1",
            "a",
            binding = setOf(SourceNodeId("z"), SourceNodeId("a"), SourceNodeId("m")),
        )
        val row = decodeRow(runClipDetailQuery(project(listOf(clip)), input("c1")).data)
        assertEquals(listOf("a", "m", "z"), row.sourceBindingIds)
    }

    // ── transforms round-trip ─────────────────────────────────────

    @Test fun transformsRoundTripFromClip() {
        val transform = Transform(translateX = 10f, scaleY = 2f)
        val clip = videoClip("c1", "a", transforms = listOf(transform))
        val row = decodeRow(runClipDetailQuery(project(listOf(clip)), input("c1")).data)
        assertEquals(listOf(transform), row.transforms)
    }

    // ── lockfile ref + staleness ──────────────────────────────────

    @Test fun lockfileRefNullWhenNoLockfileEntryExists() {
        // Pin: clip with no matching lockfile entry → lockfile=null
        // (NOT a defaulted ClipDetailLockfileRef with empty fields).
        val clip = videoClip("c1", "no-entry-asset")
        val row = decodeRow(runClipDetailQuery(project(listOf(clip)), input("c1")).data)
        assertNull(row.lockfile)
    }

    @Test fun lockfileRefFreshWhenSourceHashesMatch() {
        // Pin: when entry's snapshotted contentHashes match the
        // current node hashes, currentlyStale=false +
        // driftedSourceNodeIds=[].
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("char")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("char") to node.contentHash),
        )
        val row = decodeRow(
            runClipDetailQuery(
                project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
                input("c1"),
            ).data,
        )
        assertNotNull(row.lockfile)
        assertEquals(false, row.lockfile!!.currentlyStale)
        assertEquals(emptyList(), row.lockfile!!.driftedSourceNodeIds)
    }

    @Test fun lockfileRefStaleWhenAnySourceHashDrifts() {
        // Pin: entry's snapshot has one mismatching hash → currently
        // Stale=true + drifted listed.
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("char")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("char") to "stale-hash"),
        )
        val row = decodeRow(
            runClipDetailQuery(
                project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
                input("c1"),
            ).data,
        )
        assertEquals(true, row.lockfile!!.currentlyStale)
        assertEquals(listOf("char"), row.lockfile!!.driftedSourceNodeIds)
    }

    @Test fun lockfileRefStaleWhenSnapshottedNodeMissingFromCurrentDag() {
        // Pin: entry's snapshot includes a node that no longer
        // exists in the source DAG → also classified as drift.
        val clip = videoClip("c1", "asset-1")
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("deleted-node") to "old-hash"),
        )
        val row = decodeRow(
            runClipDetailQuery(
                project(clips = listOf(clip), entries = listOf(entry)),
                input("c1"),
            ).data,
        )
        assertEquals(true, row.lockfile!!.currentlyStale)
        assertEquals(listOf("deleted-node"), row.lockfile!!.driftedSourceNodeIds)
    }

    @Test fun driftedSourceNodeIdsSortedAlphabetically() {
        // Pin diff stability: when 3 nodes drift, output is
        // alphabetic.
        val clip = videoClip("c1", "asset-1")
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(
                SourceNodeId("z") to "old",
                SourceNodeId("a") to "old",
                SourceNodeId("m") to "old",
            ),
        )
        val row = decodeRow(
            runClipDetailQuery(
                project(clips = listOf(clip), entries = listOf(entry)),
                input("c1"),
            ).data,
        )
        assertEquals(listOf("a", "m", "z"), row.lockfile!!.driftedSourceNodeIds)
    }

    @Test fun lockfileRefFieldsRoundTripFromEntry() {
        val clip = videoClip("c1", "asset-1")
        val entry = lockEntry("asset-1", pinned = true)
        val row = decodeRow(
            runClipDetailQuery(
                project(clips = listOf(clip), entries = listOf(entry)),
                input("c1"),
            ).data,
        )
        val ref = row.lockfile!!
        assertEquals("h-asset-1", ref.inputHash)
        assertEquals("generate_image", ref.toolId)
        assertEquals(true, ref.pinned)
    }

    // ── summary text suffix composition ──────────────────────────

    @Test fun summaryNoLockfileHasBareDescription() {
        // Pin: no lockfile entry → no " — stale/fresh/pinned"
        // suffix, just bare clip description.
        val clip = videoClip("c1", "asset-1")
        val out = runClipDetailQuery(project(listOf(clip)), input("c1")).outputForLlm
        // Bare format: "<kind> clip <id> on track <trackId>
        // (<seconds>s)."
        assertTrue("video clip c1 on track vt" in out, "core; got: $out")
        assertTrue(out.endsWith("s)."), "no suffix; got: $out")
        assertTrue("stale" !in out && "fresh" !in out && "pinned" !in out, "no suffix; got: $out")
    }

    @Test fun summaryFreshLockfileGetsFreshSuffix() {
        val node = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("c")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("c") to node.contentHash),
        )
        val out = runClipDetailQuery(
            project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
            input("c1"),
        ).outputForLlm
        assertTrue(" — fresh" in out, "fresh suffix; got: $out")
        assertTrue("pinned" !in out)
    }

    @Test fun summaryStaleLockfileGetsStaleSuffix() {
        val node = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("c")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("c") to "stale-hash"),
        )
        val out = runClipDetailQuery(
            project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
            input("c1"),
        ).outputForLlm
        assertTrue(" — stale" in out, "stale suffix; got: $out")
    }

    @Test fun summaryPinnedFreshGetsBothSuffixes() {
        // Pin: stale/fresh and pinned compose. Fresh + pinned →
        // " — fresh — pinned".
        val node = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("c")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("c") to node.contentHash),
            pinned = true,
        )
        val out = runClipDetailQuery(
            project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
            input("c1"),
        ).outputForLlm
        assertTrue(" — fresh" in out, "got: $out")
        assertTrue(" — pinned" in out, "got: $out")
    }

    @Test fun summaryStalePinnedGetsBothSuffixes() {
        val node = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("c")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("c") to "stale-hash"),
            pinned = true,
        )
        val out = runClipDetailQuery(
            project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
            input("c1"),
        ).outputForLlm
        assertTrue(" — stale" in out)
        assertTrue(" — pinned" in out, "stale-pinned (the canonical 'should I drop pin?' state); got: $out")
    }

    @Test fun summaryDurationFormattedAsSecondsDecimal() {
        // Pin: duration in summary is durationMs / 1000.0 (NOT
        // truncated to int seconds). 5500ms → 5.5s.
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(start = Duration.ZERO, duration = 5_500.toLong().milliseconds()),
            sourceRange = TimeRange(start = Duration.ZERO, duration = 5_500.toLong().milliseconds()),
            assetId = AssetId("a"),
        )
        val out = runClipDetailQuery(project(listOf(clip)), input("c1")).outputForLlm
        assertTrue("(5.5s)" in out, "decimal seconds; got: $out")
    }

    private fun Long.milliseconds(): Duration = kotlin.time.Duration.parseIsoString("PT${this / 1000.0}S")

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndSingleRowFraming() {
        val clip = videoClip("c1", "a")
        val result = runClipDetailQuery(project(listOf(clip)), input("c1"))
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_CLIP, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleIncludesClipId() {
        val clip = videoClip("alpha-clip", "a")
        val result = runClipDetailQuery(project(listOf(clip)), input("alpha-clip"))
        assertTrue(
            "project_query clip alpha-clip" in (result.title ?: ""),
            "title; got: ${result.title}",
        )
    }

    @Test fun trackIdIsResolvedFromClipsContainingTrack() {
        // Pin: trackId field comes from the track that holds the
        // clip, not from the input. A regression returning the
        // first track or a default would silently misroute UI
        // updates.
        val clip = videoClip("c1", "a")
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("first-track"), emptyList()),
                    Track.Video(TrackId("clip-host"), listOf(clip)),
                ),
            ),
        )
        val row = decodeRow(runClipDetailQuery(proj, input("c1")).data)
        assertEquals("clip-host", row.trackId, "trackId from actual host, not first track")
    }
}
