package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [LockfileEntry] field defaults + 3-state
 * `costCents` semantics + [ModalityHashes] dispatch. Cycle 306
 * audit: no direct `LockfileEntryFieldDefaultsTest.kt` (verified
 * via cycle 289-banked duplicate-check idiom). The 14-field shape
 * is exercised across many lockfile tests but defaults +
 * forward-compat invariants have no dedicated pin.
 *
 * Same audit-pattern fallback as cycles 207-305. Continues the
 * field-defaults sprint (Session/Project/Clip/Timeline/MediaAsset
 * cycles 302-305).
 *
 * Why this matters: LockfileEntry is the VISION §3.1 lockfile row
 * persisted in every `talevia.json` for every AIGC production.
 * Drift in defaults silently changes:
 *   - **pinned default false** — drift to true silently pins every
 *     fresh entry, blocking GC sweeps.
 *   - **costCents three-state** (null = unknown, 0 = free, positive
 *     = cost) — drift to default 0L silently flags every entry as
 *     "free" when cost wasn't measured.
 *   - **variantIndex default 0** — drift to default-non-zero
 *     silently mis-keys cache lookups.
 *   - **sourceContentHashesByModality default empty** — forward-
 *     compat fallback to legacy whole-body hash comparison; drift
 *     would silently break legacy bundle decode.
 *
 * Pinned via direct construction + JSON round-trip on
 * JsonConfig.default.
 */
class LockfileEntryFieldDefaultsTest {

    private val json: Json = JsonConfig.default

    private fun provenance() = GenerationProvenance(
        providerId = "openai",
        modelId = "gpt-image-1",
        modelVersion = null,
        seed = 42L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private fun minimalEntry(): LockfileEntry = LockfileEntry(
        inputHash = "0000000000000000",
        toolId = "generate_image",
        assetId = AssetId("a1"),
        provenance = provenance(),
    )

    // ── Default values for 10 optional fields ───────────────

    @Test fun sourceBindingDefaultsToEmptySet() {
        // Pin: empty binding marks the entry as "no
        // consistency nodes folded in".
        assertEquals(emptySet(), minimalEntry().sourceBinding)
    }

    @Test fun sourceContentHashesDefaultsToEmptyMap() {
        // Pin: empty hash map = no source-graph snapshot
        // (legacy / unbound entries).
        assertEquals(emptyMap(), minimalEntry().sourceContentHashes)
    }

    @Test fun baseInputsDefaultsToEmptyJsonObject() {
        // Pin: empty {}. Drift to populating defaults
        // would silently corrupt audit trail / replay.
        assertEquals(JsonObject(emptyMap()), minimalEntry().baseInputs)
    }

    @Test fun pinnedDefaultsToFalse() {
        // Marquee default-false pin: drift to true would
        // silently pin every fresh entry, blocking GC sweeps
        // and inflating bundle size on long-running projects.
        assertEquals(
            false,
            minimalEntry().pinned,
            "pinned MUST default to false (drift to true silently blocks GC)",
        )
    }

    @Test fun costCentsDefaultsToNull() {
        // Marquee 3-state pin: per source line 297, null =
        // unknown (cost not measured for this entry). Drift
        // to 0L would silently flag every entry as "free"
        // when cost wasn't measured.
        assertNull(
            minimalEntry().costCents,
            "costCents MUST default to null (NOT 0L which would flag entry as 'free')",
        )
    }

    @Test fun sessionIdDefaultsToNull() {
        // Pin: null = "not attributed to a session".
        assertNull(minimalEntry().sessionId)
    }

    @Test fun resolvedPromptDefaultsToNull() {
        // Pin: null = no prompt audit captured.
        assertNull(minimalEntry().resolvedPrompt)
    }

    @Test fun originatingMessageIdDefaultsToNull() {
        // Pin: null = entry not produced via Agent turn
        // (e.g. test fixture, manual import).
        assertNull(minimalEntry().originatingMessageId)
    }

    @Test fun sourceContentHashesByModalityDefaultsToEmptyMap() {
        // Marquee forward-compat pin: per source lines 308-313,
        // empty default = legacy entries fall back to
        // sourceContentHashes whole-body comparison. Drift to
        // populating defaults would silently break legacy
        // bundle decode.
        assertEquals(
            emptyMap(),
            minimalEntry().sourceContentHashesByModality,
            "sourceContentHashesByModality MUST default to empty (forward-compat fallback)",
        )
    }

    @Test fun variantIndexDefaultsToZero() {
        // Marquee variant-index pin: per source line 342, 0
        // = single-variant. Drift to non-zero default would
        // silently mis-key cache lookups for every entry
        // produced before phase 2 surfaces variantCount.
        assertEquals(
            0,
            minimalEntry().variantIndex,
            "variantIndex MUST default to 0 (single-variant baseline)",
        )
    }

    // ── 3-state distinct interpretations ────────────────────

    @Test fun costCentsNullVsZeroAreSemanticallyDistinct() {
        // Marquee distinction pin: drift to treat them as
        // equivalent loses the "unknown" vs "explicitly
        // free" 3-state distinction.
        val unknown = minimalEntry()
        val explicitlyFree = minimalEntry().copy(costCents = 0L)
        assertNull(unknown.costCents, "unknown-cost entry has null")
        assertEquals(0L, explicitlyFree.costCents, "free entry has 0L")
        assertNotEquals(unknown, explicitlyFree, "null and 0L MUST produce distinct entries")
    }

    @Test fun pinnedToggleProducesDistinctEntries() {
        val unpinned = minimalEntry()
        val pinned = minimalEntry().copy(pinned = true)
        assertNotEquals(unpinned, pinned)
    }

    // ── ModalityHashes dispatch ─────────────────────────────

    @Test fun modalityHashesForModalityDispatchesByEnum() {
        // Marquee dispatch pin: per ModalityHashes.forModality,
        // Visual maps to .visual, Audio maps to .audio. Drift
        // to swap dispatch silently breaks modality-aware
        // staleness detection.
        val mh = ModalityHashes(visual = "v-hash", audio = "a-hash")
        assertEquals(
            "v-hash",
            mh.forModality(io.talevia.core.domain.source.Modality.Visual),
            "Visual modality MUST dispatch to visual hash",
        )
        assertEquals(
            "a-hash",
            mh.forModality(io.talevia.core.domain.source.Modality.Audio),
            "Audio modality MUST dispatch to audio hash",
        )
    }

    @Test fun modalityHashesAllowsDistinctValues() {
        // Pin: visual + audio fields are independent — same
        // node can have different per-modality hashes (e.g.
        // a character_ref's visualDescription change bumps
        // visual hash but not audio hash).
        val mh = ModalityHashes(visual = "x", audio = "y")
        assertNotEquals(mh.visual, mh.audio)
    }

    // ── Serialization round-trip ───────────────────────────

    @Test fun roundTripPreservesAllFieldDefaults() {
        val original = minimalEntry()
        val encoded = json.encodeToString(LockfileEntry.serializer(), original)
        val decoded = json.decodeFromString(LockfileEntry.serializer(), encoded)
        assertEquals(original, decoded)
        // Defaults preserved.
        assertEquals(emptySet(), decoded.sourceBinding)
        assertEquals(false, decoded.pinned)
        assertNull(decoded.costCents)
        assertEquals(0, decoded.variantIndex)
    }

    @Test fun encodeOmitsDefaultedFields() {
        // Marquee encodeDefaults=false back-compat pin: per
        // JsonConfig.default, default values are omitted from
        // the encoded JSON. Drift to encode defaults bloats
        // every bundle's serialised state.
        val encoded = json.encodeToString(LockfileEntry.serializer(), minimalEntry())
        assertTrue(
            "pinned" !in encoded,
            "default pinned (false) MUST be omitted from encoded JSON",
        )
        assertTrue(
            "variantIndex" !in encoded,
            "default variantIndex (0) MUST be omitted",
        )
        assertTrue(
            "costCents" !in encoded,
            "default costCents (null) MUST be omitted",
        )
        assertTrue(
            "sessionId" !in encoded,
            "default sessionId (null) MUST be omitted",
        )
    }

    @Test fun minimalJsonDecodesWithEveryDefaultHonored() {
        // Marquee back-compat pin: encoding minimal entry +
        // decoding produces identical defaults. Sister of the
        // round-trip test but explicit on each default.
        val encoded = json.encodeToString(LockfileEntry.serializer(), minimalEntry())
        val decoded = json.decodeFromString(LockfileEntry.serializer(), encoded)
        // Every optional field at its default.
        assertEquals(emptySet(), decoded.sourceBinding)
        assertEquals(emptyMap(), decoded.sourceContentHashes)
        assertEquals(JsonObject(emptyMap()), decoded.baseInputs)
        assertEquals(false, decoded.pinned)
        assertNull(decoded.costCents)
        assertNull(decoded.sessionId)
        assertNull(decoded.resolvedPrompt)
        assertNull(decoded.originatingMessageId)
        assertEquals(emptyMap(), decoded.sourceContentHashesByModality)
        assertEquals(0, decoded.variantIndex)
    }

    @Test fun roundTripPreservesAllExplicitlySetFields() {
        // Sister round-trip pin: every populated field round-
        // trips correctly.
        val original = LockfileEntry(
            inputHash = "abc1234567890def",
            toolId = "generate_image",
            assetId = AssetId("a-explicit"),
            provenance = provenance(),
            pinned = true,
            costCents = 42L,
            sessionId = "s-1",
            resolvedPrompt = "a sunset over the mountains",
            variantIndex = 3,
        )
        val encoded = json.encodeToString(LockfileEntry.serializer(), original)
        val decoded = json.decodeFromString(LockfileEntry.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(true, decoded.pinned)
        assertEquals(42L, decoded.costCents)
        assertEquals("s-1", decoded.sessionId)
        assertEquals(3, decoded.variantIndex)
    }

    // ── Identity / equality ────────────────────────────────

    @Test fun twoEntriesWithSameFieldsAreEqual() {
        val a = minimalEntry()
        val b = minimalEntry()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun differentVariantIndexProducesDistinctEntries() {
        // Pin: variantIndex is part of equality (and inputHash)
        // so 2 variants of the same prompt land as distinct
        // entries — load-bearing for cache-uniqueness.
        val a = minimalEntry()
        val b = minimalEntry().copy(variantIndex = 1)
        assertNotEquals(
            a,
            b,
            "different variantIndex MUST produce distinct entries (cache-uniqueness contract)",
        )
    }
}
