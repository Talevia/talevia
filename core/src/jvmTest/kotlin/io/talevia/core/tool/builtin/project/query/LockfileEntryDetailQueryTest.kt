package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runLockfileEntryDetailQuery] —
 * `project_query(select=lockfile_entry)`. Single-row drill-down
 * for one lockfile entry, with bidirectional lookup
 * (`inputHash` forward / `assetId` reverse). Cycle 130 audit:
 * 187 LOC, **zero** transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Bidirectional lookup with mutual exclusion enforced.**
 *    Per kdoc: "Looks up by either `inputHash` (forward
 *    lookup) or `assetId` (reverse lookup) ... Exactly one of
 *    the two must be supplied — both-set or neither-set fails
 *    loud." Three error paths pinned (both / neither / unknown
 *    on each direction). A regression accepting both with one
 *    side silently winning would create non-deterministic
 *    behavior depending on hash collision.
 *
 * 2. **`driftedNodes` carries snapshot AND current hashes,
 *    with `currentContentHash=null` when node deleted.** Per
 *    kdoc: "Null when the bound node has since been deleted
 *    from the project." A regression collapsing the deletion
 *    case into "drift" without the null sentinel would make
 *    "node was edited" indistinguishable from "node was
 *    deleted" in audit views.
 *
 * 3. **`clipReferences` enumerates clips on current timeline
 *    using the entry's asset.** Text clips are skipped (no
 *    assetId). A regression including text clips would surface
 *    nonsense matches; missing clip refs would force LLM
 *    follow-up queries to find consumers.
 */
class LockfileEntryDetailQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
    )

    private fun audioClip(id: String, assetId: String) = Clip.Audio(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
    )

    private fun textClip(id: String) = Clip.Text(
        id = ClipId(id),
        timeRange = timeRange,
        text = "subtitle",
    )

    private fun entry(
        hash: String,
        assetId: String,
        toolId: String = "generate_image",
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
        modelVersion: String? = null,
        seed: Long = 0,
        createdAtEpochMs: Long = 0,
        sourceBinding: Set<SourceNodeId> = emptySet(),
        sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
        baseInputs: JsonObject = JsonObject(emptyMap()),
        pinned: Boolean = false,
        resolvedPrompt: String? = null,
        originatingMessageId: MessageId? = null,
    ) = LockfileEntry(
        inputHash = hash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = modelVersion,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        sourceBinding = sourceBinding,
        sourceContentHashes = sourceContentHashes,
        baseInputs = baseInputs,
        pinned = pinned,
        resolvedPrompt = resolvedPrompt,
        originatingMessageId = originatingMessageId,
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        tracks: List<Track>? = null,
        nodes: List<SourceNode> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
    ): Project {
        val resolvedTracks = tracks
            ?: if (clips.isEmpty()) emptyList()
            else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = resolvedTracks),
            source = Source(nodes = nodes),
            lockfile = EagerLockfile(entries = entries),
        )
    }

    private fun input(
        inputHash: String? = null,
        assetId: String? = null,
    ) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_LOCKFILE_ENTRY,
        inputHash = inputHash,
        assetId = assetId,
    )

    private fun decodeRow(out: ProjectQueryTool.Output): LockfileEntryDetailRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(LockfileEntryDetailRow.serializer()),
            out.rows,
        ).single()

    // ── input validation: bidirectional lookup ────────────────────

    @Test fun bothInputHashAndAssetIdSetErrorsLoud() {
        val ex = assertFailsWith<IllegalStateException> {
            runLockfileEntryDetailQuery(
                project(),
                input(inputHash = "h1", assetId = "a1"),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("takes exactly one" in msg, "got: $msg")
        assertTrue("inputHash" in msg && "assetId" in msg, "lists both; got: $msg")
        assertTrue("Drop the one you don't need" in msg, "recovery; got: $msg")
    }

    @Test fun neitherInputHashNorAssetIdSetErrorsLoud() {
        val ex = assertFailsWith<IllegalStateException> {
            runLockfileEntryDetailQuery(project(), input())
        }
        val msg = ex.message ?: ""
        assertTrue("requires one of" in msg, "got: $msg")
        assertTrue("forward lookup" in msg && "reverse lookup" in msg, "explains both modes; got: $msg")
        assertTrue("project_query(select=lockfile_entries)" in msg, "recovery; got: $msg")
    }

    @Test fun unknownInputHashErrorsWithRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runLockfileEntryDetailQuery(project(), input(inputHash = "ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "got: $msg")
        assertTrue("not found" in msg, "got: $msg")
        assertTrue("project_query(select=lockfile_entries)" in msg)
    }

    @Test fun unknownAssetIdReverseLookupErrorsWithImportedHint() {
        // Pin: reverse-lookup miss explanation explicitly hints
        // at "imported (not generated) or pre-dates lockfile
        // recording" — distinct narrative from forward-lookup
        // miss, since the user might be looking up an imported
        // asset.
        val ex = assertFailsWith<IllegalStateException> {
            runLockfileEntryDetailQuery(project(), input(assetId = "imported-asset"))
        }
        val msg = ex.message ?: ""
        assertTrue("No lockfile entry produced asset 'imported-asset'" in msg, "got: $msg")
        assertTrue("imported (not generated)" in msg, "imported hint; got: $msg")
        assertTrue("pre-dates lockfile" in msg, "legacy hint; got: $msg")
    }

    // ── lookup happy paths ────────────────────────────────────────

    @Test fun forwardLookupByInputHashFindsEntry() {
        val e = entry("h1", "asset-1")
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals("h1", row.inputHash)
        assertEquals("asset-1", row.assetId)
    }

    @Test fun reverseLookupByAssetIdFindsEntry() {
        val e = entry("h1", "asset-1")
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(assetId = "asset-1")).data,
        )
        assertEquals("h1", row.inputHash)
        assertEquals("asset-1", row.assetId)
    }

    // ── provenance + scalar fields ────────────────────────────────

    @Test fun provenanceFieldsRoundTripFromEntry() {
        val e = entry(
            hash = "h1",
            assetId = "a1",
            toolId = "generate_video",
            providerId = "openai",
            modelId = "sora-2",
            modelVersion = "2024-09",
            seed = 42,
            createdAtEpochMs = 9000L,
            pinned = true,
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals("generate_video", row.toolId)
        assertEquals(true, row.pinned)
        val p = row.provenance
        assertEquals("openai", p.providerId)
        assertEquals("sora-2", p.modelId)
        assertEquals("2024-09", p.modelVersion)
        assertEquals(42L, p.seed)
        assertEquals(9000L, p.createdAtEpochMs)
    }

    @Test fun resolvedPromptAndOriginatingMessageIdRoundTrip() {
        val e = entry(
            hash = "h1",
            assetId = "a",
            resolvedPrompt = "Mei walks down the alley",
            originatingMessageId = MessageId("msg-42"),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals("Mei walks down the alley", row.resolvedPrompt)
        assertEquals("msg-42", row.originatingMessageId)
    }

    @Test fun resolvedPromptNullForLegacyEntries() {
        // Pin: legacy entries (pre-cycle-7) have null resolvedPrompt.
        val e = entry("h1", "a", resolvedPrompt = null)
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertNull(row.resolvedPrompt)
    }

    // ── sourceBinding / sourceContentHashes ───────────────────────

    @Test fun sourceBindingIdsSortedAlphabetically() {
        val e = entry(
            "h1",
            "a",
            sourceBinding = setOf(SourceNodeId("z"), SourceNodeId("a"), SourceNodeId("m")),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals(listOf("a", "m", "z"), row.sourceBindingIds)
    }

    @Test fun sourceContentHashesMapKeysAreStringified() {
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(
                SourceNodeId("char") to "hash-c",
                SourceNodeId("style") to "hash-s",
            ),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        // Pin: keys converted to plain String for JSON encoding.
        assertEquals(setOf("char", "style"), row.sourceContentHashes.keys)
        assertEquals("hash-c", row.sourceContentHashes["char"])
        assertEquals("hash-s", row.sourceContentHashes["style"])
    }

    // ── driftedNodes + currentlyStale ─────────────────────────────

    @Test fun freshEntryHasZeroDriftedNodesAndStaleFalse() {
        // Per code: drift only when `current == null OR current
        // != snapshot`. Match → no drift.
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(SourceNodeId("char") to node.contentHash),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(nodes = listOf(node), entries = listOf(e)),
                input(inputHash = "h1"),
            ).data,
        )
        assertEquals(false, row.currentlyStale)
        assertEquals(emptyList(), row.driftedNodes)
    }

    @Test fun driftedNodeWithMismatchedHashCarriesBothSnapshotAndCurrentHashes() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(SourceNodeId("char") to "snap-hash"),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(nodes = listOf(node), entries = listOf(e)),
                input(inputHash = "h1"),
            ).data,
        )
        assertEquals(true, row.currentlyStale)
        val drift = row.driftedNodes.single()
        assertEquals("char", drift.nodeId)
        assertEquals("snap-hash", drift.snapshotContentHash)
        // Current hash is the live node's contentHash (NOT null —
        // node still exists, just drifted).
        assertEquals(node.contentHash, drift.currentContentHash)
    }

    @Test fun deletedNodeSurfacesAsDriftWithNullCurrentContentHash() {
        // Marquee pin: snapshotted node no longer in DAG →
        // drift with currentContentHash=null. The kdoc explicitly
        // commits to this distinction.
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(SourceNodeId("deleted") to "ghost-hash"),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(entries = listOf(e)), // no source nodes
                input(inputHash = "h1"),
            ).data,
        )
        assertEquals(true, row.currentlyStale)
        val drift = row.driftedNodes.single()
        assertEquals("deleted", drift.nodeId)
        assertEquals("ghost-hash", drift.snapshotContentHash)
        // Pin null sentinel — distinguishes "deleted" from "edited".
        assertNull(drift.currentContentHash, "deleted node has null currentContentHash")
    }

    @Test fun multipleDriftedNodesAllSurface() {
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(
                SourceNodeId("char") to "old-c",
                SourceNodeId("style") to "old-s",
            ),
        )
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(entries = listOf(e)),
                input(inputHash = "h1"),
            ).data,
        )
        assertEquals(2, row.driftedNodes.size)
        assertEquals(
            setOf("char", "style"),
            row.driftedNodes.map { it.nodeId }.toSet(),
        )
    }

    // ── clipReferences ────────────────────────────────────────────

    @Test fun clipReferencesEmptyWhenNoClipUsesAsset() {
        val e = entry("h1", "asset-1")
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals(emptyList(), row.clipReferences)
    }

    @Test fun clipReferencesIncludesVideoAndAudioClipsWithKindLabels() {
        // Pin: clip refs span multiple tracks; each row gets the
        // correct kind label.
        val v = videoClip("v1", "asset-1")
        val au = audioClip("a1", "asset-1")
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(v)),
            Track.Audio(TrackId("at"), listOf(au)),
        )
        val e = entry("h1", "asset-1")
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(tracks = tracks, entries = listOf(e)),
                input(inputHash = "h1"),
            ).data,
        )
        assertEquals(2, row.clipReferences.size)
        val byClip = row.clipReferences.associateBy { it.clipId }
        assertEquals("video", byClip.getValue("v1").clipType)
        assertEquals("vt", byClip.getValue("v1").trackId)
        assertEquals("audio", byClip.getValue("a1").clipType)
        assertEquals("at", byClip.getValue("a1").trackId)
    }

    @Test fun textClipsAreSkippedFromClipReferences() {
        // Pin: text clips have no assetId in domain model →
        // continue past them. A regression including them
        // would surface nonsense matches.
        val v = videoClip("v1", "asset-1")
        val t = textClip("t1") // no asset
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(v)),
            Track.Subtitle(TrackId("st"), listOf(t)),
        )
        val e = entry("h1", "asset-1")
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(tracks = tracks, entries = listOf(e)),
                input(inputHash = "h1"),
            ).data,
        )
        // Only video clip surfaces.
        assertEquals(listOf("v1"), row.clipReferences.map { it.clipId })
    }

    @Test fun clipReferencesRestrictedToEntryAssetId() {
        // Pin: only clips using THIS entry's assetId surface;
        // clips using other assets are filtered out.
        val v1 = videoClip("v1", "asset-mine")
        val v2 = videoClip("v2", "asset-other")
        val e = entry("h1", "asset-mine")
        val row = decodeRow(
            runLockfileEntryDetailQuery(
                project(clips = listOf(v1, v2), entries = listOf(e)),
                input(inputHash = "h1"),
            ).data,
        )
        assertEquals(listOf("v1"), row.clipReferences.map { it.clipId })
    }

    // ── baseInputs ────────────────────────────────────────────────

    @Test fun baseInputsRoundTripWithEmptyFlag() {
        val inputs = buildJsonObject { put("prompt", "hello") }
        val e = entry("h1", "a", baseInputs = inputs)
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals(inputs, row.baseInputs)
        assertEquals(false, row.baseInputsEmpty)
    }

    @Test fun emptyBaseInputsHaveBaseInputsEmptyTrue() {
        val e = entry("h1", "a", baseInputs = JsonObject(emptyMap()))
        val row = decodeRow(
            runLockfileEntryDetailQuery(project(entries = listOf(e)), input(inputHash = "h1")).data,
        )
        assertEquals(true, row.baseInputsEmpty)
        assertEquals(JsonObject(emptyMap()), row.baseInputs)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun summaryIncludesEntryHashToolIdAssetIdAndZeroClipsWhenUnreferenced() {
        val e = entry("h1", "asset-1")
        val out = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        // Pin: "Entry h1 (generate_image) → asset asset-1: no
        // clip references."
        assertTrue("Entry h1 (generate_image) → asset asset-1" in out, "header; got: $out")
        assertTrue("no clip references" in out, "ref note; got: $out")
        // No stale / pinned / legacy suffixes.
        assertTrue("stale" !in out)
        assertTrue("pinned" !in out)
    }

    @Test fun summaryUsesOneClipSingularNote() {
        val v = videoClip("v1", "asset-1")
        val e = entry("h1", "asset-1")
        val out = runLockfileEntryDetailQuery(
            project(clips = listOf(v), entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        assertTrue("1 clip references its asset" in out, "singular; got: $out")
    }

    @Test fun summaryUsesPluralForMultipleClips() {
        val v1 = videoClip("v1", "asset-1")
        val v2 = videoClip("v2", "asset-1")
        val e = entry("h1", "asset-1")
        val out = runLockfileEntryDetailQuery(
            project(clips = listOf(v1, v2), entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        assertTrue("2 clips reference its asset" in out, "plural; got: $out")
    }

    @Test fun summaryStaleSuffixWhenDriftedNodes() {
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(SourceNodeId("ghost") to "old"),
        )
        val out = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        // Pin: "currently stale (1 drifted)".
        assertTrue("currently stale (1 drifted)" in out, "stale suffix; got: $out")
    }

    @Test fun summaryPinnedSuffixWhenPinned() {
        val e = entry("h1", "a", pinned = true)
        val out = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        assertTrue(" — pinned" in out, "pinned suffix; got: $out")
    }

    @Test fun summaryLegacySuffixWhenBaseInputsEmpty() {
        val e = entry("h1", "a", baseInputs = JsonObject(emptyMap()))
        val out = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        // Pin: "legacy entry (no baseInputs)" — the LLM signal
        // that pre-cycle-7 entries can't be replayed.
        assertTrue(" — legacy entry (no baseInputs)" in out, "legacy suffix; got: $out")
    }

    @Test fun summarySuffixesComposeStalePinnedLegacy() {
        // Pin all 3 suffixes compose independently. Stale +
        // pinned + legacy → triple suffix.
        val e = entry(
            "h1",
            "a",
            sourceContentHashes = mapOf(SourceNodeId("g") to "old"),
            pinned = true,
            baseInputs = JsonObject(emptyMap()),
        )
        val out = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        ).outputForLlm
        assertTrue("currently stale" in out)
        assertTrue(" — pinned" in out)
        assertTrue(" — legacy entry" in out)
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndSingleRowFraming() {
        val e = entry("h1", "a")
        val result = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        )
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_LOCKFILE_ENTRY, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleIncludesToolIdAndAssetId() {
        val e = entry("h1", "asset-x", toolId = "generate_video")
        val result = runLockfileEntryDetailQuery(
            project(entries = listOf(e)),
            input(inputHash = "h1"),
        )
        assertTrue(
            "lockfile_entry generate_video/asset-x" in (result.title ?: ""),
            "title; got: ${result.title}",
        )
    }
}
