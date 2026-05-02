package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.aigc.AigcPipeline
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `aigc-multi-variant-phase1-schema-field` (cycle 26).
 *
 * Phase 1 of `aigc-result-multi-variant`: every [LockfileEntry] carries a
 * `variantIndex: Int` (default 0) and [AigcPipeline.inputHash] folds it into
 * the canonical hash string. Phase 2 will surface `variantCount` on AIGC
 * tool inputs; phase 1 just makes sure the schema + hash math don't have to
 * change again when that lands.
 *
 * The four pinned behaviours below are exactly what phase 2's dispatch loop
 * relies on: every variant gets a distinct hash (so the cache doesn't
 * clobber), serialised entries round-trip the index, missing-field decode
 * defaults to 0 (so Project blobs from earlier in the same cycle still
 * parse), and the default-0 path matches an explicit 0 (so single-variant
 * tools that haven't been migrated yet keep the same hashes as multi-variant
 * tools that pass `variantIndex = 0` for their first variant).
 */
class LockfileVariantIndexTest {

    private val json = JsonConfig.default

    private fun fields() = listOf(
        "tool" to "generate_image",
        "model" to "gpt-image-1",
        "seed" to "42",
        "prompt" to "cyberpunk street at night",
    )

    @Test fun differentVariantIndicesProduceDifferentHashes() {
        // Two variants of the same prompt + seed must not collide — that's
        // the whole point of folding variantIndex into the canonical string.
        val h0 = AigcPipeline.inputHash(fields(), variantIndex = 0)
        val h1 = AigcPipeline.inputHash(fields(), variantIndex = 1)
        val h2 = AigcPipeline.inputHash(fields(), variantIndex = 2)
        assertNotEquals(h0, h1, "variant 0 and 1 must hash distinctly")
        assertNotEquals(h1, h2, "variant 1 and 2 must hash distinctly")
        assertNotEquals(h0, h2, "variant 0 and 2 must hash distinctly")
    }

    @Test fun defaultVariantIndexMatchesExplicitZero() {
        // Single-variant tools (today: every AIGC tool) call inputHash
        // without passing variantIndex. Phase 2 multi-variant tools will
        // pass `variantIndex = 0` for the first variant. The two paths must
        // produce identical hashes so the cache stays warm across the
        // single→multi migration.
        val implicit = AigcPipeline.inputHash(fields())
        val explicit = AigcPipeline.inputHash(fields(), variantIndex = 0)
        assertEquals(implicit, explicit)
    }

    @Test fun entryWithVariantIndexRoundTrips() {
        val original = LockfileEntry(
            inputHash = "h-variant-1",
            toolId = "generate_image",
            assetId = AssetId("asset-v1"),
            provenance = GenerationProvenance(
                providerId = "fake",
                modelId = "m",
                modelVersion = "v1",
                seed = 42L,
                parameters = buildJsonObject { put("prompt", "p") },
                createdAtEpochMs = 1_700_000_000_000L,
            ),
            sourceBinding = setOf(SourceNodeId("mei")),
            variantIndex = 3,
        )
        val roundTripped = json.decodeFromString(
            LockfileEntry.serializer(),
            json.encodeToString(LockfileEntry.serializer(), original),
        )
        assertEquals(3, roundTripped.variantIndex)
    }

    @Test fun entryMissingVariantIndexDecodesAsZero() {
        // Project blobs written before this field landed — i.e. every blob
        // on disk today — must still decode. The missing field falls back
        // to the data-class default `0`, which is the right value for a
        // pre-multi-variant single-variant generation.
        val legacy = """
            {
              "inputHash": "h1",
              "toolId": "generate_image",
              "assetId": "asset-1",
              "provenance": {
                "providerId": "fake",
                "modelId": "m",
                "modelVersion": "v1",
                "seed": 42,
                "parameters": { "prompt": "p" },
                "createdAtEpochMs": 1700000000000
              }
            }
        """.trimIndent()
        val entry = json.decodeFromString(LockfileEntry.serializer(), legacy)
        assertEquals(0, entry.variantIndex)
    }
}
