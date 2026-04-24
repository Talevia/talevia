package io.talevia.core.domain.render

import io.talevia.core.JsonConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sibling to [ClipRenderCacheTest]. Same three load-bearing behaviours pinned
 * on the `byFingerprint` map lookup that replaced the old `entries.lastOrNull
 * { … }` scan:
 *
 *  1. Hit returns the exact entry.
 *  2. Miss returns null.
 *  3. Duplicate fingerprint → later entry wins (the append-only ledger's
 *     "latest wins" contract survives the index swap).
 */
class RenderCacheTest {

    private fun entry(fp: String, path: String, at: Long = 1_700_000_000_000L) = RenderCacheEntry(
        fingerprint = fp,
        outputPath = path,
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        durationSeconds = 12.0,
        createdAtEpochMs = at,
    )

    @Test fun emptyCacheReturnsNull() {
        assertNull(RenderCache.EMPTY.findByFingerprint("anything"))
    }

    @Test fun lookupHitsExactFingerprint() {
        val cache = RenderCache().append(entry("fp-a", "/out/a.mp4"))
        val hit = cache.findByFingerprint("fp-a")!!
        assertEquals("/out/a.mp4", hit.outputPath)
    }

    @Test fun lookupMissReturnsNullEvenWithNeighbourEntries() {
        val cache = RenderCache()
            .append(entry("fp-a", "/out/a.mp4"))
            .append(entry("fp-b", "/out/b.mp4"))
        assertNull(cache.findByFingerprint("fp-c"))
    }

    @Test fun duplicateFingerprintResolvesToLastWrite() {
        val cache = RenderCache()
            .append(entry("fp-a", "/out/a-v1.mp4", at = 1_700_000_000_000L))
            .append(entry("fp-a", "/out/a-v2.mp4", at = 1_700_000_000_500L))
            .append(entry("fp-a", "/out/a-v3.mp4", at = 1_700_000_001_000L))
        val hit = cache.findByFingerprint("fp-a")!!
        assertEquals("/out/a-v3.mp4", hit.outputPath, "last-wins across three re-exports of the same fingerprint")
    }

    @Test fun mapIsRebuiltAfterDeserialize() {
        val original = RenderCache()
            .append(entry("fp-a", "/out/a.mp4"))
            .append(entry("fp-b", "/out/b.mp4"))
        val json = JsonConfig.default.encodeToString(RenderCache.serializer(), original)
        val revived = JsonConfig.default.decodeFromString(RenderCache.serializer(), json)

        assertEquals("/out/a.mp4", revived.findByFingerprint("fp-a")?.outputPath)
        assertEquals("/out/b.mp4", revived.findByFingerprint("fp-b")?.outputPath)
        assertNull(revived.findByFingerprint("fp-absent"))
    }
}
