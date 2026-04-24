package io.talevia.core.domain.render

import io.talevia.core.JsonConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract guard for the O(1) map lookup rebuilt from [ClipRenderCache.entries]
 * on deserialize. Pins the three load-bearing behaviours of the swap from the
 * old `entries.lastOrNull { it.fingerprint == fp }` scan to the new
 * `byFingerprint[fp]` lookup:
 *
 *  1. Hit returns the exact entry.
 *  2. Miss returns null (no silent match on a neighbouring entry).
 *  3. Duplicate fingerprint → **later** entry wins (same "latest wins"
 *     invariant the append-only ledger depends on).
 */
class ClipRenderCacheTest {

    private fun entry(fp: String, mezz: String, at: Long = 1_700_000_000_000L) = ClipRenderCacheEntry(
        fingerprint = fp,
        mezzaninePath = mezz,
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        durationSeconds = 4.0,
        createdAtEpochMs = at,
    )

    @Test fun emptyCacheReturnsNull() {
        assertNull(ClipRenderCache.EMPTY.findByFingerprint("anything"))
    }

    @Test fun lookupHitsExactFingerprint() {
        val cache = ClipRenderCache().append(entry("fp-a", "/mezz/a.mp4"))
        val hit = cache.findByFingerprint("fp-a")!!
        assertEquals("/mezz/a.mp4", hit.mezzaninePath)
    }

    @Test fun lookupMissReturnsNullEvenWithNeighbourEntries() {
        val cache = ClipRenderCache()
            .append(entry("fp-a", "/mezz/a.mp4"))
            .append(entry("fp-b", "/mezz/b.mp4"))
        assertNull(cache.findByFingerprint("fp-c"))
    }

    @Test fun duplicateFingerprintResolvesToLastWrite() {
        // The append-only ledger allows duplicates (e.g. a re-render that
        // happened to hash identically). The map lookup must return the
        // latest entry, matching the pre-refactor `entries.lastOrNull` contract.
        val cache = ClipRenderCache()
            .append(entry("fp-a", "/mezz/a-v1.mp4", at = 1_700_000_000_000L))
            .append(entry("fp-a", "/mezz/a-v2.mp4", at = 1_700_000_000_500L))
            .append(entry("fp-a", "/mezz/a-v3.mp4", at = 1_700_000_001_000L))
        val hit = cache.findByFingerprint("fp-a")!!
        assertEquals("/mezz/a-v3.mp4", hit.mezzaninePath, "last-wins across three re-renders of the same fingerprint")
    }

    @Test fun mapIsRebuiltAfterDeserialize() {
        // @Transient is recomputed on construction; a round-trip through
        // kotlinx.serialization must land the lookup working again without
        // the caller touching anything. Regression lock.
        val original = ClipRenderCache()
            .append(entry("fp-a", "/mezz/a.mp4"))
            .append(entry("fp-b", "/mezz/b.mp4"))
        val json = JsonConfig.default.encodeToString(ClipRenderCache.serializer(), original)
        val revived = JsonConfig.default.decodeFromString(ClipRenderCache.serializer(), json)

        assertEquals("/mezz/a.mp4", revived.findByFingerprint("fp-a")?.mezzaninePath)
        assertEquals("/mezz/b.mp4", revived.findByFingerprint("fp-b")?.mezzaninePath)
        assertNull(revived.findByFingerprint("fp-absent"))
    }

    @Test fun retainByFingerprintRebuildsLookupMap() {
        // `retainByFingerprint` returns a `copy(entries = …)`; the @Transient
        // map must see the filtered entries, not the pre-filter ones.
        val cache = ClipRenderCache()
            .append(entry("fp-a", "/mezz/a.mp4"))
            .append(entry("fp-b", "/mezz/b.mp4"))
            .append(entry("fp-c", "/mezz/c.mp4"))
        val pruned = cache.retainByFingerprint(setOf("fp-a", "fp-c"))
        assertEquals("/mezz/a.mp4", pruned.findByFingerprint("fp-a")?.mezzaninePath)
        assertNull(pruned.findByFingerprint("fp-b"), "dropped entry must not leak back via the lookup map")
        assertEquals("/mezz/c.mp4", pruned.findByFingerprint("fp-c")?.mezzaninePath)
    }
}
