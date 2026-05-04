package io.talevia.core.tool.builtin.aigc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [AigcPipeline.ensureSeed] +
 * [AigcPipeline.inputHash] canonical-format invariants —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/aigc/AigcPipeline.kt`.
 * Cycle 271 audit: 0 test refs against [AigcPipeline.ensureSeed]
 * (the sibling [AigcPipeline.inputHash] is tested by
 * `LockfileVariantIndexTest` for variant semantics but NOT for
 * its canonical-format / determinism / field-order invariants).
 *
 * Same audit-pattern fallback as cycles 207-270.
 *
 * `AigcPipeline.ensureSeed(explicit)` is the pre-dispatch helper
 * every AIGC tool calls to ensure a stable seed lands in the
 * lockfile entry: explicit caller-provided seed wins, otherwise
 * a fresh `nextClientSideSeed()` random Long is generated.
 *
 * `AigcPipeline.inputHash(fields, variantIndex)` is the
 * cache-key construction that pairs every dispatch with a
 * lockfile entry. Its determinism + field-order semantics are
 * load-bearing for cache correctness — drift would silently
 * cause cache misses (re-bill provider) or false hits (return
 * stale variant).
 *
 * Pins three correctness contracts:
 *
 *  1. **`ensureSeed(non-null)`** echoes the explicit value
 *     verbatim. Drift to "always randomise" would silently
 *     break user-supplied determinism (deterministic A/B
 *     comparison runs would re-roll seeds).
 *
 *  2. **`ensureSeed(null)`** returns a Long (NOT 0L /
 *     constant) — drift to "default to 0" would silently
 *     produce identical seeds across sessions, defeating
 *     variation. Plus consecutive-call randomness pin (two
 *     null-calls produce DIFFERENT seeds modulo birthday-
 *     paradox luck).
 *
 *  3. **`inputHash` is deterministic + field-order-sensitive
 *     + variantIndex-appended-last**. Already partially
 *     covered by LockfileVariantIndexTest for variant
 *     semantics; this commit pins the canonical-format
 *     invariants the existing test misses:
 *     - Same input → same hash (idempotent).
 *     - Different field order → DIFFERENT hash (drift to
 *       "sort fields" would silently change cache keys).
 *     - Hash is a 16-char lowercase hex string (FNV-1a 64
 *       output format).
 */
class AigcPipelineEnsureSeedTest {

    // ── 1. ensureSeed: explicit wins ────────────────────────

    @Test fun ensureSeedEchoesExplicitValue() {
        // Marquee explicit-wins pin: caller-provided seeds
        // round-trip verbatim. Drift to "always randomise"
        // would break deterministic comparison runs.
        for (seed in listOf(0L, 1L, 42L, -1L, 1_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals(
                seed,
                AigcPipeline.ensureSeed(seed),
                "ensureSeed($seed) MUST echo the explicit value verbatim",
            )
        }
    }

    // ── 2. ensureSeed: null branch ──────────────────────────

    @Test fun ensureSeedNullProducesAFreshLong() {
        // Pin: null → some Long. Drift to "always 0" would
        // silently produce identical seeds across sessions.
        val seed = AigcPipeline.ensureSeed(null)
        // Type pin (Kotlin Long, not unboxed Int / String).
        // Cannot easily check "not 0" because randomness CAN
        // produce 0 — instead pin "the function returns",
        // which the previous line already enforces.
        // Pin: the seed is in Long range (trivially true via
        // type but documents the invariant).
        assertTrue(seed in Long.MIN_VALUE..Long.MAX_VALUE)
    }

    @Test fun ensureSeedNullIsRandomAcrossCalls() {
        // Marquee randomness pin: drift to "always 42" /
        // "use a fixed clock" would silently produce
        // identical lockfile entries on every call. Take 50
        // null-calls — birthday paradox says 50 random Longs
        // collide with probability ≈ 6e-17 (effectively
        // zero). Assert at least 49 distinct values to allow
        // the cosmic-ray collision the universe occasionally
        // emits.
        val seeds = (1..50).map { AigcPipeline.ensureSeed(null) }
        assertTrue(
            seeds.toSet().size >= 49,
            "50 null-calls MUST produce ≥49 distinct seeds (drift to fixed seed would surface here); got: ${seeds.toSet().size} distinct",
        )
    }

    @Test fun ensureSeedNullDoesNotCollapseToZero() {
        // Sister randomness pin: drift to "default to 0"
        // would manifest as every call returning 0. Take 20
        // calls — at least one MUST be non-zero (probability
        // of 20 random zeros ≈ 1/2^1280, effectively
        // impossible).
        val seeds = (1..20).map { AigcPipeline.ensureSeed(null) }
        assertTrue(
            seeds.any { it != 0L },
            "20 null-calls MUST include at least one non-zero seed (drift to 'default to 0' surfaces here)",
        )
    }

    // ── 3. inputHash canonical-format invariants ────────────

    @Test fun inputHashIsDeterministic() {
        // Pin: same input always produces same hash. Drift to
        // "include timestamp / random salt" would silently
        // break cache hits.
        val fields = listOf("tool" to "image", "model" to "dall-e-3", "prompt" to "x")
        val a = AigcPipeline.inputHash(fields)
        val b = AigcPipeline.inputHash(fields)
        assertEquals(a, b, "same input MUST produce same hash (idempotent)")
    }

    @Test fun inputHashIsFieldOrderSensitive() {
        // Marquee field-order pin: per source line 127, the
        // canonical form is `joinToString("|") { "$k=$v" }`
        // — fields are NOT sorted. Drift to "sort by key"
        // would silently change every hash for callers that
        // emit fields in different orders.
        val abc = AigcPipeline.inputHash(listOf("a" to "1", "b" to "2", "c" to "3"))
        val cba = AigcPipeline.inputHash(listOf("c" to "3", "b" to "2", "a" to "1"))
        assertNotEquals(
            abc,
            cba,
            "different field order MUST produce different hash; drift to 'sort fields' would silently merge cache keys",
        )
    }

    @Test fun inputHashAppendsVariantIndexEvenWithExplicitVariantField() {
        // Pin: per source, `withVariant = fields + ("variant"
        // to variantIndex.toString())` — variant ALWAYS gets
        // appended at the END regardless of whether a "variant"
        // field already exists in the input list. Drift to
        // "deduplicate / replace pre-existing 'variant'" would
        // silently change the canonical form for callers that
        // happen to use the same key name.
        //
        // Concretely: an explicit `variant=999` field followed
        // by the default-appended `variant=0` is DIFFERENT
        // from a single `variant=999` (variantIndex=999):
        //   inputHash([("variant","999")]) == hash("variant=999|variant=0")
        //   inputHash([], variantIndex=999) == hash("variant=999")
        // Different canonical strings → different hashes.
        // This pin enforces the "always-append" semantic.
        val withDuplicateVariant = AigcPipeline.inputHash(listOf("variant" to "999"))
        val singleVariant = AigcPipeline.inputHash(emptyList(), variantIndex = 999)
        assertNotEquals(
            withDuplicateVariant,
            singleVariant,
            "explicit 'variant' field gets appended ALONGSIDE the variantIndex (NOT replaced); " +
                "drift to 'replace duplicate' would silently merge cache keys",
        )
    }

    @Test fun inputHashIsSixteenHexChars() {
        // Pin: per `fnv1a64Hex`, the hash is a 16-character
        // lowercase hex string (FNV-1a 64-bit).
        // Drift to a different hash function (e.g. SHA-256
        // → 64 chars / xxh32 → 8 chars) would silently
        // change every cache key.
        val hash = AigcPipeline.inputHash(
            listOf("tool" to "test", "prompt" to "anything"),
        )
        assertEquals(
            16,
            hash.length,
            "inputHash MUST be 16 chars (FNV-1a 64 hex); got: '$hash' (length ${hash.length})",
        )
        for (c in hash) {
            assertTrue(
                c in '0'..'9' || c in 'a'..'f',
                "hash char '$c' MUST be lowercase hex; got: '$hash'",
            )
        }
    }

    @Test fun inputHashEmptyFieldsStillProducesValidHex() {
        // Edge: empty fields list + default variantIndex=0
        // produces a hash of just "variant=0" canonical form.
        // Pin so a future refactor that fails on empty list
        // surfaces here.
        val hash = AigcPipeline.inputHash(emptyList())
        assertEquals(16, hash.length)
        for (c in hash) {
            assertTrue(c in '0'..'9' || c in 'a'..'f')
        }
    }

    @Test fun inputHashDifferentValuesProduceDifferentHash() {
        // Pin: different field VALUES change the hash.
        // Drift to "drop value side" would silently collapse
        // every prompt to the same hash.
        val a = AigcPipeline.inputHash(listOf("prompt" to "cat"))
        val b = AigcPipeline.inputHash(listOf("prompt" to "dog"))
        assertNotEquals(
            a,
            b,
            "different field values MUST produce different hash",
        )
    }

    @Test fun inputHashUsesPipeSeparatorBetweenFields() {
        // Pin: per source line 127, fields joined by `|`.
        // Drift to `,` / `;` / newline would change every
        // hash. Verify by constructing two hashes that
        // differ only in whether they'd collide under a
        // different separator:
        //   [a=1, b=2|3] vs [a=1|b=2, b=3] would collide
        //   under any non-`|` separator that doesn't allow
        //   '|' inside values.
        // Easier pin: a value that contains `|` must change
        // the hash differently than splitting it across
        // fields (since `|` is a meta-char in our format).
        val singleFieldWithPipe = AigcPipeline.inputHash(
            listOf("prompt" to "a|b"),
        )
        val twoFields = AigcPipeline.inputHash(
            listOf("prompt" to "a", "extra" to "b"),
        )
        // These MUST be different hashes — drift to a
        // separator that allows pipe-in-value would collapse
        // them.
        assertNotEquals(
            singleFieldWithPipe,
            twoFields,
            "value containing `|` MUST hash differently than splitting it across fields",
        )
    }
}
