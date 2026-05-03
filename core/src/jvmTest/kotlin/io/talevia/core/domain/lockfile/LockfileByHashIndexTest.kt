package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage for the O(1) `byInputHash` / `byAssetId` transient indexes on [Lockfile].
 *
 * The critical invariant these tests protect is the **last-wins** semantic: when
 * multiple entries share the same `inputHash` (the append-only ledger permits that
 * when a provider re-runs and happens to produce the same hash), `findByInputHash`
 * must return the most recently appended entry — matching the original
 * `entries.lastOrNull { it.inputHash == hash }` behaviour. A naive change to
 * `associateBy` that accidentally becomes first-wins would regress stale-clip
 * detection / cache lookups silently, so we shuffle and re-check.
 */
class LockfileByHashIndexTest {

    private val json = JsonConfig.default

    private fun entry(hash: String, assetId: String, seed: Long = 0L): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = "v1",
            seed = seed,
            parameters = buildJsonObject { put("prompt", "p$seed") },
            createdAtEpochMs = 1_700_000_000_000L + seed,
        ),
        sourceBinding = setOf(SourceNodeId("mei")),
    )

    @Test fun byInputHashIsPopulatedAtConstruction() {
        val lf = Lockfile.EMPTY
            .append(entry("h1", "asset-1"))
            .append(entry("h2", "asset-2"))
        assertEquals(2, lf.byInputHash.size)
        assertEquals(AssetId("asset-1"), lf.byInputHash["h1"]?.assetId)
        assertEquals(AssetId("asset-2"), lf.byInputHash["h2"]?.assetId)
        assertNull(lf.byInputHash["nope"])
    }

    @Test fun byAssetIdIsPopulatedAtConstruction() {
        val lf = Lockfile.EMPTY
            .append(entry("h1", "asset-1"))
            .append(entry("h2", "asset-2"))
        assertEquals(2, lf.byAssetId.size)
        assertEquals("h1", lf.byAssetId[AssetId("asset-1")]?.inputHash)
        assertEquals("h2", lf.byAssetId[AssetId("asset-2")]?.inputHash)
        assertNull(lf.byAssetId[AssetId("nope")])
    }

    @Test fun findByInputHashConsultsIndexAndReturnsLastAppended() {
        val lf = Lockfile.EMPTY
            .append(entry("h1", "asset-1", seed = 1L))
            .append(entry("h1", "asset-2", seed = 2L)) // duplicate hash — later wins
            .append(entry("h1", "asset-3", seed = 3L)) // duplicate hash — latest wins
            .append(entry("h2", "asset-4", seed = 4L))

        val h1 = assertNotNull(lf.findByInputHash("h1"))
        assertEquals(AssetId("asset-3"), h1.assetId, "expected most recent entry for duplicate hash")
        assertEquals(AssetId("asset-4"), lf.findByInputHash("h2")?.assetId)
        assertNull(lf.findByInputHash("missing"))
    }

    @Test fun findByAssetIdConsultsIndexAndReturnsLastAppended() {
        val lf = Lockfile.EMPTY
            .append(entry("h1", "asset-dup", seed = 1L))
            .append(entry("h2", "asset-dup", seed = 2L)) // same asset, newer hash
            .append(entry("h3", "asset-other", seed = 3L))

        val hit = assertNotNull(lf.findByAssetId(AssetId("asset-dup")))
        assertEquals("h2", hit.inputHash, "expected most recent entry for duplicate assetId")
        assertEquals("h3", lf.findByAssetId(AssetId("asset-other"))?.inputHash)
        assertNull(lf.findByAssetId(AssetId("none")))
    }

    /**
     * Property-style check: for 10 randomly-shuffled insertion sequences, the last
     * appended entry for a given hash is what `findByInputHash` returns. Catches
     * any accidental first-wins regression (e.g. switching to `groupBy` + `first`).
     */
    @Test fun lastAppendedWinsForSameHash() {
        val rng = Random(seed = 0xC0DEL)
        repeat(10) { trial ->
            val hashes = listOf("a", "b", "c")
            // Build a pool of N entries per hash, each tagged with a monotonically
            // increasing assetId suffix so "last appended" is unambiguous.
            val pool = mutableListOf<LockfileEntry>()
            var counter = 0
            hashes.forEach { h ->
                repeat(rng.nextInt(2, 5)) {
                    pool += entry(h, "asset-${h}-${counter++}", seed = counter.toLong())
                }
            }
            val shuffled = pool.shuffled(rng)
            val lf = shuffled.fold(Lockfile.EMPTY) { acc, e -> acc.append(e) }

            hashes.forEach { h ->
                val expectedLast = shuffled.last { it.inputHash == h }
                val found = assertNotNull(lf.findByInputHash(h), "trial=$trial hash=$h")
                assertSame(
                    expectedLast,
                    found,
                    "trial=$trial hash=$h: expected last-appended entry but got earlier one",
                )
            }
        }
    }

    @Test fun serializationRoundTripPreservesIndexLookup() {
        val original = Lockfile.EMPTY
            .append(entry("h1", "asset-1", seed = 1L))
            .append(entry("h1", "asset-2", seed = 2L)) // duplicate hash
            .append(entry("h2", "asset-3", seed = 3L))

        // Cast: append() returns the Lockfile interface; EagerLockfile is the
        // only impl today, so the round-trip target is materialized as eager.
        val encoded = json.encodeToString(EagerLockfile.serializer(), original as EagerLockfile)
        val decoded = json.decodeFromString(EagerLockfile.serializer(), encoded)

        // The transient maps are rebuilt on deserialize.
        assertEquals(2, decoded.byInputHash.size, "byInputHash must be rebuilt on decode")
        assertEquals(3, decoded.byAssetId.size, "byAssetId must be rebuilt on decode")
        assertEquals(AssetId("asset-2"), decoded.findByInputHash("h1")?.assetId)
        assertEquals(AssetId("asset-3"), decoded.findByInputHash("h2")?.assetId)
        assertEquals("h1", decoded.findByAssetId(AssetId("asset-1"))?.inputHash)
        assertEquals("h1", decoded.findByAssetId(AssetId("asset-2"))?.inputHash)
        assertEquals("h2", decoded.findByAssetId(AssetId("asset-3"))?.inputHash)

        // The @Transient fields must not bloat the serialized shape.
        assertTrue("byInputHash" !in encoded, "transient index must not be serialized")
        assertTrue("byAssetId" !in encoded, "transient index must not be serialized")
    }

    @Test fun emptyLockfileHasEmptyIndexes() {
        assertEquals(0, Lockfile.EMPTY.byInputHash.size)
        assertEquals(0, Lockfile.EMPTY.byAssetId.size)
        assertNull(Lockfile.EMPTY.findByInputHash("anything"))
        assertNull(Lockfile.EMPTY.findByAssetId(AssetId("anything")))
    }
}
