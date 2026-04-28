package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.lockfile.ModalityHashes
import io.talevia.core.domain.source.Modality
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.deepContentHashOfFor
import io.talevia.core.platform.GenerationProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-modal staleness — the rule the legacy whole-body comparison violates:
 * a `character_ref` carries both visual fields (`visualDescription`,
 * `referenceAssetIds`, `loraPin`) and audio fields (`voiceId`). Without
 * modality awareness, flipping `voiceId` re-hashes the whole node and stales
 * every visual clip bound to the character (and the symmetric case stales
 * audio clips on visual edits). After this lane lands, only clips whose
 * modality actually consumes the changed slice are reported stale.
 *
 * Pairs with [ProjectStalenessTest] — that one covers the topology lane
 * (`staleClips(changed)`), this covers the snapshot lane
 * (`staleClipsFromLockfile()`), which is the only place modality information
 * flows in.
 */
class ProjectStalenessModalityTest {

    private fun characterRefNode(id: String, body: CharacterRefBody): SourceNode =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = ConsistencyKinds.CHARACTER_REF,
            body = JsonConfig.default.encodeToJsonElement(CharacterRefBody.serializer(), body),
        )

    private fun videoClipBoundTo(id: String, vararg nodeIds: String, start: Long = 0): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun audioClipBoundTo(id: String, vararg nodeIds: String, start: Long = 0): Clip.Audio =
        Clip.Audio(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    /** Lockfile entry that captures both modality slices (the new lane). */
    private fun modalityEntry(
        clipAssetId: AssetId,
        nodeId: String,
        source: Source,
        toolId: String = "generate_image",
    ): LockfileEntry = LockfileEntry(
        inputHash = "h-${clipAssetId.value}",
        toolId = toolId,
        assetId = clipAssetId,
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        sourceBinding = setOf(SourceNodeId(nodeId)),
        sourceContentHashes = mapOf(
            SourceNodeId(nodeId) to source.deepContentHashOf(SourceNodeId(nodeId)),
        ),
        sourceContentHashesByModality = mapOf(
            SourceNodeId(nodeId) to ModalityHashes(
                visual = source.deepContentHashOfFor(SourceNodeId(nodeId), Modality.Visual),
                audio = source.deepContentHashOfFor(SourceNodeId(nodeId), Modality.Audio),
            ),
        ),
    )

    private fun mei(visual: String = "tall, dark hair", voice: String? = "alloy"): CharacterRefBody =
        CharacterRefBody(name = "Mei", visualDescription = visual, voiceId = voice)

    @Test fun visualClipNotStaleWhenOnlyVoiceIdChanges() {
        // The headline bug. Pre-lane, the deep hash folded voiceId into the
        // character_ref's whole-body hash, and any video clip bound to the
        // character was reported stale on a TTS swap.
        val before = Source(nodes = listOf(characterRefNode("mei", mei(voice = "alloy"))))
        val clip = videoClipBoundTo("c1", "mei")
        val entry = modalityEntry(clip.assetId, "mei", before)
        val now = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("v"), listOf(clip)))),
            source = Source(nodes = listOf(characterRefNode("mei", mei(voice = "nova")))),
            lockfile = Lockfile(entries = listOf(entry)),
        )

        val stale = now.staleClipsFromLockfile()
        assertTrue(stale.isEmpty(), "video clip must be fresh when only voiceId changes — got $stale")
    }

    @Test fun audioClipNotStaleWhenOnlyVisualDescriptionChanges() {
        // Symmetric case: TTS clip bound to a character_ref must not stale
        // when the visual description (used for image-gen) is swapped.
        val before = Source(nodes = listOf(characterRefNode("mei", mei(visual = "tall, dark hair"))))
        val clip = audioClipBoundTo("c1", "mei")
        val entry = modalityEntry(clip.assetId, "mei", before, toolId = "synthesize_speech")
        val now = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a"), listOf(clip)))),
            source = Source(nodes = listOf(characterRefNode("mei", mei(visual = "short, blonde")))),
            lockfile = Lockfile(entries = listOf(entry)),
        )

        val stale = now.staleClipsFromLockfile()
        assertTrue(stale.isEmpty(), "audio clip must be fresh when only visualDescription changes — got $stale")
    }

    @Test fun visualClipStaleWhenVisualDescriptionChanges() {
        // Confirms the modality lane still flags the cases it should:
        // changing the visual slice of the character_ref must stale a video
        // clip bound to it.
        val before = Source(nodes = listOf(characterRefNode("mei", mei(visual = "tall"))))
        val clip = videoClipBoundTo("c1", "mei")
        val entry = modalityEntry(clip.assetId, "mei", before)
        val now = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("v"), listOf(clip)))),
            source = Source(nodes = listOf(characterRefNode("mei", mei(visual = "short")))),
            lockfile = Lockfile(entries = listOf(entry)),
        )

        val stale = now.staleClipsFromLockfile()
        assertEquals(1, stale.size)
        assertEquals(ClipId("c1"), stale.single().clipId)
        assertEquals(setOf(SourceNodeId("mei")), stale.single().changedSourceIds)
    }

    @Test fun audioClipStaleWhenVoiceIdChanges() {
        val before = Source(nodes = listOf(characterRefNode("mei", mei(voice = "alloy"))))
        val clip = audioClipBoundTo("c1", "mei")
        val entry = modalityEntry(clip.assetId, "mei", before, toolId = "synthesize_speech")
        val now = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a"), listOf(clip)))),
            source = Source(nodes = listOf(characterRefNode("mei", mei(voice = "nova")))),
            lockfile = Lockfile(entries = listOf(entry)),
        )

        val stale = now.staleClipsFromLockfile()
        assertEquals(1, stale.size)
        assertEquals(ClipId("c1"), stale.single().clipId)
    }

    @Test fun bothModalityClipsStaleWhenSharedFieldChanges() {
        // The character `name` is referenced by both visual prompts ("a
        // photo of Mei in the kitchen") and TTS prompts ("Mei narrates").
        // Renaming the character must therefore stale clips on both sides.
        val before = Source(nodes = listOf(characterRefNode("mei", mei(visual = "tall", voice = "alloy"))))
        val videoClip = videoClipBoundTo("v1", "mei")
        val audioClip = audioClipBoundTo("a1", "mei")
        val videoEntry = modalityEntry(videoClip.assetId, "mei", before)
        val audioEntry = modalityEntry(audioClip.assetId, "mei", before, toolId = "synthesize_speech")
        val renamed = CharacterRefBody(name = "Lin", visualDescription = "tall", voiceId = "alloy")
        val now = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("v"), listOf(videoClip)),
                    Track.Audio(TrackId("a"), listOf(audioClip)),
                ),
            ),
            source = Source(nodes = listOf(characterRefNode("mei", renamed))),
            lockfile = Lockfile(entries = listOf(videoEntry, audioEntry)),
        )

        val stale = now.staleClipsFromLockfile()
        assertEquals(setOf(ClipId("v1"), ClipId("a1")), stale.map { it.clipId }.toSet())
    }

    @Test fun legacyEntryWithoutModalityHashesFallsBackToWholeBodyCompare() {
        // Pre-lane lockfile entries (or any entry written by code that hasn't
        // been threaded through the modality snapshot lane) must keep
        // working: the detector falls back to the legacy whole-body
        // comparison so behavior is preserved — even if that means flagging
        // a video clip stale on a voiceId edit. This is exactly the bug the
        // new lane fixes; the test pins the fallback path so a future
        // refactor of the legacy field doesn't silently regress it.
        val before = Source(nodes = listOf(characterRefNode("mei", mei(voice = "alloy"))))
        val clip = videoClipBoundTo("c1", "mei")
        val legacyEntry = LockfileEntry(
            inputHash = "h",
            toolId = "generate_image",
            assetId = clip.assetId,
            provenance = GenerationProvenance(
                providerId = "fake",
                modelId = "m",
                modelVersion = null,
                seed = 0,
                parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
                createdAtEpochMs = 0,
            ),
            sourceBinding = setOf(SourceNodeId("mei")),
            sourceContentHashes = mapOf(
                SourceNodeId("mei") to before.deepContentHashOf(SourceNodeId("mei")),
            ),
            // sourceContentHashesByModality intentionally absent (legacy entry).
        )
        val now = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("v"), listOf(clip)))),
            source = Source(nodes = listOf(characterRefNode("mei", mei(voice = "nova")))),
            lockfile = Lockfile(entries = listOf(legacyEntry)),
        )

        val stale = now.staleClipsFromLockfile()
        assertEquals(1, stale.size, "legacy entry must fall back to whole-body comparison")
    }
}
