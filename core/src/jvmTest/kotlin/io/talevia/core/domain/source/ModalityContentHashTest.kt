package io.talevia.core.domain.source

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [SourceNode.contentHashFor] /
 * [Source.deepContentHashOfFor] —
 * `core/domain/source/ModalityContentHash.kt`. The
 * modality-aware hashing pair that lets a visual-only
 * clip's deep fingerprint stay stable when audio-only
 * fields edit (and vice versa). Cycle 173 audit: 90 LOC,
 * 0 direct test refs (the function is exercised
 * transitively in staleness tests but the per-modality
 * slicing contracts were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`character_ref` body slices: Visual drops
 *    `voiceId` only; Audio drops every visual-only
 *    field BUT keeps `name` and `voiceId`.** The marquee
 *    modality-aware staleness invariant — flipping
 *    voiceId on a character_ref must NOT stale a video
 *    clip bound to that character; flipping
 *    visualDescription must NOT stale a TTS clip.
 *    `name` participates in BOTH because TTS prompts
 *    that reference the character by name are sensitive
 *    to it. Drift to "drop name on Audio" would silently
 *    miss name-driven TTS staleness; drift to "keep
 *    voiceId on Visual" would over-invalidate every
 *    image clip on every voice change.
 *
 * 2. **Decode failure falls back to plain
 *    `contentHash` — never throws.** Per kdoc: "Falls
 *    back to [SourceNode.contentHash] on decode failure
 *    (e.g. partially filled / malformed character_ref
 *    bodies on disk) so the staleness detector never
 *    throws — at worst it loses modality awareness for
 *    that one entry." Drift to "throw on decode" would
 *    crash every staleness pass on legacy / malformed
 *    bodies.
 *
 * 3. **`deepContentHashOfFor` includes modality.name in
 *    the fold AND uses contentHashFor at each step.**
 *    Two consequences pinned: (a) Visual vs Audio deep
 *    hashes for the same node are DIFFERENT even when
 *    the modality slices are identical, because the
 *    fold mixes in `modality=Visual` vs `modality=Audio`
 *    discriminator strings; (b) ancestor edits propagate
 *    only when they touch the modality's slice — a
 *    `voiceId` edit on a grandparent character_ref does
 *    NOT change the grandchild's Visual deep hash.
 */
class ModalityContentHashTest {

    private fun nodeId(s: String) = SourceNodeId(s)

    private fun characterRefBody(
        name: String = "Mei",
        visual: String = "tall, brown hair",
        voice: String? = "voice-1",
        refs: List<String> = emptyList(),
    ): JsonObject {
        val body = CharacterRefBody(
            name = name,
            visualDescription = visual,
            referenceAssetIds = refs.map { AssetId(it) },
            loraPin = null,
            voiceId = voice,
        )
        return JsonConfig.default.encodeToJsonElement(
            CharacterRefBody.serializer(),
            body,
        ) as JsonObject
    }

    private fun characterRefNode(
        id: String,
        name: String = "Mei",
        visual: String = "tall, brown hair",
        voice: String? = "voice-1",
        refs: List<String> = emptyList(),
        parents: List<String> = emptyList(),
    ): SourceNode = SourceNode.create(
        id = nodeId(id),
        kind = ConsistencyKinds.CHARACTER_REF,
        body = characterRefBody(name, visual, voice, refs),
        parents = parents.map { SourceRef(nodeId(it)) },
    )

    private fun nonCharacterNode(
        id: String,
        body: JsonElement = JsonObject(emptyMap()),
        parents: List<String> = emptyList(),
    ): SourceNode = SourceNode.create(
        id = nodeId(id),
        kind = "test.opaque",
        body = body,
        parents = parents.map { SourceRef(nodeId(it)) },
    )

    private fun source(vararg nodes: SourceNode): Source = Source(nodes = nodes.toList())

    // ── Non-character_ref kind: modality is irrelevant ────────

    @Test fun nonCharacterKindHashIsModalityIndependent() {
        // Pin: for every kind that's NOT character_ref,
        // contentHashFor returns the plain contentHash —
        // identical for both modalities. Drift to "always
        // slice" would diverge them and break the
        // documented "modality awareness only matters for
        // mixed-modality bodies" semantics.
        val node = nonCharacterNode(
            id = "n",
            body = JsonObject(mapOf("foo" to JsonPrimitive("bar"))),
        )
        val visualHash = node.contentHashFor(Modality.Visual)
        val audioHash = node.contentHashFor(Modality.Audio)
        assertEquals(visualHash, audioHash, "non-character kind: same hash for both modalities")
        assertEquals(node.contentHash, visualHash, "delegates to plain contentHash")
    }

    // ── character_ref Visual: drops voiceId only ──────────────

    @Test fun visualHashIsUnchangedByVoiceIdEdit() {
        // Marquee Visual-stays-stable-on-audio-edit pin:
        // a character_ref with voiceId="A" vs voiceId="B"
        // produces the SAME Visual hash. Drift would
        // re-stale every image clip on voice changes.
        val nodeA = characterRefNode("char", voice = "voice-A")
        val nodeB = characterRefNode("char", voice = "voice-B")
        assertEquals(
            nodeA.contentHashFor(Modality.Visual),
            nodeB.contentHashFor(Modality.Visual),
            "visual hash unchanged by voiceId edit",
        )
    }

    @Test fun visualHashIsChangedByVisualDescriptionEdit() {
        // Pin: visual fields DO change Visual hash.
        val nodeA = characterRefNode("char", visual = "tall, brown hair")
        val nodeB = characterRefNode("char", visual = "short, blonde hair")
        assertNotEquals(
            nodeA.contentHashFor(Modality.Visual),
            nodeB.contentHashFor(Modality.Visual),
            "visual hash changes on visualDescription edit",
        )
    }

    @Test fun visualHashIsChangedByReferenceAssetEdit() {
        val nodeA = characterRefNode("char", refs = listOf("asset-1"))
        val nodeB = characterRefNode("char", refs = listOf("asset-2"))
        assertNotEquals(
            nodeA.contentHashFor(Modality.Visual),
            nodeB.contentHashFor(Modality.Visual),
            "visual hash changes on referenceAssetIds edit",
        )
    }

    @Test fun visualHashIsChangedByNameEdit() {
        // Pin: name participates in BOTH modalities, so
        // it changes Visual too.
        val nodeA = characterRefNode("char", name = "Mei")
        val nodeB = characterRefNode("char", name = "Lin")
        assertNotEquals(
            nodeA.contentHashFor(Modality.Visual),
            nodeB.contentHashFor(Modality.Visual),
            "visual hash changes on name edit (name is in both modalities)",
        )
    }

    // ── character_ref Audio: drops visual-only fields ────────

    @Test fun audioHashIsUnchangedByVisualDescriptionEdit() {
        // Marquee Audio-stays-stable-on-visual-edit pin:
        // flipping visualDescription must NOT stale a TTS
        // clip. Drift would re-stale every audio clip on
        // visual prompt edits.
        val nodeA = characterRefNode("char", visual = "tall, brown hair")
        val nodeB = characterRefNode("char", visual = "short, blonde hair")
        assertEquals(
            nodeA.contentHashFor(Modality.Audio),
            nodeB.contentHashFor(Modality.Audio),
            "audio hash unchanged by visualDescription edit",
        )
    }

    @Test fun audioHashIsUnchangedByReferenceAssetEdit() {
        // Pin: referenceAssetIds is visual-only.
        val nodeA = characterRefNode("char", refs = listOf("asset-1"))
        val nodeB = characterRefNode("char", refs = listOf("asset-1", "asset-2"))
        assertEquals(
            nodeA.contentHashFor(Modality.Audio),
            nodeB.contentHashFor(Modality.Audio),
            "audio hash unchanged by referenceAssetIds edit",
        )
    }

    @Test fun audioHashIsChangedByVoiceIdEdit() {
        // Pin: voiceId is the audio-defining field.
        val nodeA = characterRefNode("char", voice = "voice-A")
        val nodeB = characterRefNode("char", voice = "voice-B")
        assertNotEquals(
            nodeA.contentHashFor(Modality.Audio),
            nodeB.contentHashFor(Modality.Audio),
            "audio hash changes on voiceId edit",
        )
    }

    @Test fun audioHashIsChangedByNameEdit() {
        // Marquee "name participates in both" pin: drift
        // to "drop name on Audio" would silently miss
        // name-driven TTS staleness (a TTS prompt that
        // says "Mei smiles" depends on the name).
        val nodeA = characterRefNode("char", name = "Mei")
        val nodeB = characterRefNode("char", name = "Lin")
        assertNotEquals(
            nodeA.contentHashFor(Modality.Audio),
            nodeB.contentHashFor(Modality.Audio),
            "audio hash changes on name edit (name is in both modalities)",
        )
    }

    // ── Visual vs Audio hash differ when both fields present ──

    @Test fun visualAndAudioHashesDifferForSameCharacterRefBody() {
        // Pin: even on the same body, Visual and Audio
        // produce different shallow hashes (because the
        // slice projections differ — Visual omits voiceId,
        // Audio strips visualDescription/refs/loraPin).
        // Drift to "same hash for same body" would
        // collapse modality-aware staleness back to
        // pre-cycle-37 behavior.
        val node = characterRefNode("char")
        assertNotEquals(
            node.contentHashFor(Modality.Visual),
            node.contentHashFor(Modality.Audio),
            "visual + audio hashes are distinct projections",
        )
    }

    // ── Decode-failure fallback ──────────────────────────────

    @Test fun malformedCharacterRefBodyFallsBackToContentHash() {
        // Marquee fallback pin: a node tagged
        // CHARACTER_REF but with a body that doesn't
        // decode as CharacterRefBody (legacy schema,
        // hand-edited talevia.json) returns the plain
        // contentHash via the runCatching/getOrNull
        // branch — never throws. Drift to "throw on
        // decode" would crash every staleness pass on
        // such bodies.
        val malformed = SourceNode.create(
            id = nodeId("char-bad"),
            kind = ConsistencyKinds.CHARACTER_REF,
            body = JsonObject(mapOf("not_a_field" to JsonPrimitive("oops"))),
        )
        // Decode fails because the body has none of the
        // required fields → both modalities fall back to
        // shallow contentHash.
        val visual = malformed.contentHashFor(Modality.Visual)
        val audio = malformed.contentHashFor(Modality.Audio)
        assertEquals(
            malformed.contentHash,
            visual,
            "decode-fail Visual falls back to contentHash",
        )
        assertEquals(
            malformed.contentHash,
            audio,
            "decode-fail Audio falls back to contentHash",
        )
        // And both are the SAME hash — modality awareness
        // is lost on fallback (per kdoc: "at worst it
        // loses modality awareness for that one entry").
        assertEquals(visual, audio, "fallback collapses to single hash")
    }

    // ── deepContentHashOfFor: modality-aware deep walk ────────

    @Test fun deepHashFolds_modalityIntoDiscriminator() {
        // Pin: `modality=${modality.name}` is part of the
        // fold. So even when a non-character node has the
        // SAME contentHashFor across modalities (because
        // contentHashFor falls back to contentHash for
        // non-character kinds), the DEEP hashes still
        // differ because `modality=Visual` vs
        // `modality=Audio` is in the fold string.
        val src = source(nonCharacterNode("n"))
        val deepVisual = src.deepContentHashOfFor(nodeId("n"), Modality.Visual)
        val deepAudio = src.deepContentHashOfFor(nodeId("n"), Modality.Audio)
        assertNotEquals(
            deepVisual,
            deepAudio,
            "deep hash includes modality.name in fold (drift would let visual + audio caches collide)",
        )
    }

    @Test fun visualDeepHashOfClipBoundToCharacterRefIsStableUnderVoiceIdEdit() {
        // The marquee end-to-end propagation pin: a
        // grandchild "clip-binding" with a
        // grandparent character_ref. Editing voiceId on
        // the grandparent must NOT change the grandchild's
        // VISUAL deep hash. Drift to "use plain shallow
        // contentHash inside deep walk" would regress to
        // the over-invalidating baseline this lane was
        // built to avoid.
        val srcA = source(
            characterRefNode("char", voice = "voice-A"),
            nonCharacterNode("clip-binding", parents = listOf("char")),
        )
        val srcB = source(
            characterRefNode("char", voice = "voice-B"),
            nonCharacterNode("clip-binding", parents = listOf("char")),
        )
        assertEquals(
            srcA.deepContentHashOfFor(nodeId("clip-binding"), Modality.Visual),
            srcB.deepContentHashOfFor(nodeId("clip-binding"), Modality.Visual),
            "voice change does NOT propagate to Visual deep hash",
        )
    }

    @Test fun audioDeepHashChangesUnderVoiceIdEditOnAncestor() {
        // Pin: the symmetric direction. The same
        // grandchild's AUDIO deep hash DOES change on
        // voiceId edit.
        val srcA = source(
            characterRefNode("char", voice = "voice-A"),
            nonCharacterNode("clip-binding", parents = listOf("char")),
        )
        val srcB = source(
            characterRefNode("char", voice = "voice-B"),
            nonCharacterNode("clip-binding", parents = listOf("char")),
        )
        assertNotEquals(
            srcA.deepContentHashOfFor(nodeId("clip-binding"), Modality.Audio),
            srcB.deepContentHashOfFor(nodeId("clip-binding"), Modality.Audio),
            "voice change DOES propagate to Audio deep hash",
        )
    }

    @Test fun audioDeepHashIsStableUnderVisualDescriptionEditOnAncestor() {
        // Pin: edit visualDescription on grandparent —
        // grandchild's Audio deep hash stays put.
        val srcA = source(
            characterRefNode("char", visual = "tall"),
            nonCharacterNode("clip-binding", parents = listOf("char")),
        )
        val srcB = source(
            characterRefNode("char", visual = "short"),
            nonCharacterNode("clip-binding", parents = listOf("char")),
        )
        assertEquals(
            srcA.deepContentHashOfFor(nodeId("clip-binding"), Modality.Audio),
            srcB.deepContentHashOfFor(nodeId("clip-binding"), Modality.Audio),
            "visualDescription change does NOT propagate to Audio deep hash",
        )
    }

    // ── deepContentHashOfFor: sentinels ──────────────────────

    @Test fun missingNodeReturnsMissingSentinel() {
        // Pin: same `missing:<id>` sentinel as
        // deepContentHashOf — drift would diverge the two
        // functions' partial-DAG handling.
        val src = Source.EMPTY
        assertEquals(
            "missing:ghost",
            src.deepContentHashOfFor(nodeId("ghost"), Modality.Visual),
        )
    }

    @Test fun selfLoopReturnsRealHashNotSentinel() {
        // Pin: same cycle defense as deepContentHashOf —
        // top-level call returns a real (folded) hash, not
        // the sentinel. Drift would crash on any
        // hand-edited talevia.json with a self-loop.
        val src = source(nonCharacterNode("a", parents = listOf("a")))
        val hash = src.deepContentHashOfFor(nodeId("a"), Modality.Visual)
        assertEquals(16, hash.length, "real folded hash returned")
        assertTrue(
            hash.all { it in "0123456789abcdef" },
            "non-sentinel hex; got: $hash",
        )
    }

    @Test fun cacheHitReturnsCachedValueVerbatim() {
        // Pin: same memoisation as deepContentHashOf —
        // pre-populated cache short-circuits.
        val src = source(nonCharacterNode("a"))
        val cache = mutableMapOf<SourceNodeId, String>()
        cache[nodeId("a")] = "fake-precomputed-modality-hash"
        val out = src.deepContentHashOfFor(
            nodeId = nodeId("a"),
            modality = Modality.Visual,
            cache = cache,
        )
        assertEquals("fake-precomputed-modality-hash", out)
    }
}
