package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProxyAsset
import io.talevia.core.domain.ProxyPurpose
import io.talevia.core.domain.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Direct tests for [deduplicateProxies] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/MediaImportProbe.kt:134`.
 * Cycle 291 audit: 0 prior test refs across both
 * commonTest and jvmTest (verified via the duplicate-check
 * idiom banked cycle 289).
 *
 * Same audit-pattern fallback as cycles 207-290.
 *
 * `deduplicateProxies` is the pure function `MediaImportProbe`
 * uses to ensure a repeated import doesn't accumulate stale
 * thumbnails when the generator re-runs on an already-imported
 * asset. Pin documents three semantics from the source:
 *
 *   1. Empty list returned as-is (early-return identity).
 *   2. Dedupe key is `(purpose, source)` tuple.
 *   3. Order semantic: associateBy{...}.values preserves
 *      insertion order; newer same-key entries overwrite
 *      older ones (last-wins).
 *
 * Drift signals:
 *   - **Drift in dedupe key** (e.g. dedupe by `purpose` only,
 *     or include `resolution`) silently changes which
 *     entries collide.
 *   - **Order-loss drift** (e.g. switch to a `Set<ProxyAsset>`
 *     dedup) silently breaks downstream consumers that read
 *     proxies in insertion order.
 *   - **First-wins drift** (associateBy returns last; drift
 *     to `distinctBy` returns first) — generator-rerun stale
 *     thumbnails would WIN the dedupe, defeating the purpose.
 */
class DeduplicateProxiesTest {

    private val fileA = MediaSource.File("/tmp/a.mp4")
    private val fileB = MediaSource.File("/tmp/b.mp4")

    private fun proxy(
        purpose: ProxyPurpose,
        source: MediaSource = fileA,
        resolution: Resolution? = null,
    ): ProxyAsset = ProxyAsset(source = source, purpose = purpose, resolution = resolution)

    // ── Empty / single ──────────────────────────────────────

    @Test fun emptyListReturnedAsSameInstance() {
        // Pin: per source line 135, `if (isEmpty()) return this`.
        // Drift to building a fresh empty list would lose
        // referential identity and add an allocation.
        val empty = emptyList<ProxyAsset>()
        val result = empty.deduplicateProxies()
        assertSame(empty, result, "empty input MUST return SAME instance (early return)")
    }

    @Test fun singleElementListReturnsTheSingleEntry() {
        val one = listOf(proxy(ProxyPurpose.THUMBNAIL))
        val result = one.deduplicateProxies()
        assertEquals(one, result)
    }

    // ── Dedup key: (purpose, source) tuple ──────────────────

    @Test fun samePurposeSameSourceCollapsesToOne() {
        // Marquee dedupe-key pin: same purpose + same source
        // → single entry retained.
        val a = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(100, 100))
        val b = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(200, 200))
        val result = listOf(a, b).deduplicateProxies()
        assertEquals(1, result.size, "same (purpose, source) MUST collapse to one entry")
    }

    @Test fun differentPurposeSameSourceKept() {
        // Pin: differing purpose alone is enough to keep
        // both. Drift to dedupe by source-only would silently
        // collapse THUMBNAIL + LOW_RES from the same source.
        val a = proxy(ProxyPurpose.THUMBNAIL, fileA)
        val b = proxy(ProxyPurpose.LOW_RES, fileA)
        val result = listOf(a, b).deduplicateProxies()
        assertEquals(2, result.size, "differing purpose MUST keep both entries")
    }

    @Test fun samePurposeDifferentSourceKept() {
        // Pin: differing source alone is enough to keep both.
        // Drift to dedupe by purpose-only would silently
        // collapse two THUMBNAIL proxies from different
        // sources.
        val a = proxy(ProxyPurpose.THUMBNAIL, fileA)
        val b = proxy(ProxyPurpose.THUMBNAIL, fileB)
        val result = listOf(a, b).deduplicateProxies()
        assertEquals(2, result.size, "differing source MUST keep both entries")
    }

    @Test fun resolutionAloneDoesNotChangeDedupKey() {
        // Marquee key pin: resolution is NOT part of the
        // dedupe key (only purpose + source). Drift to
        // include resolution would let stale thumbnails
        // accumulate when the proxy generator changes its
        // output dimensions.
        val a = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(100, 100))
        val b = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(200, 200))
        val result = listOf(a, b).deduplicateProxies()
        assertEquals(
            1,
            result.size,
            "differing resolution alone MUST collapse (resolution NOT in dedupe key)",
        )
    }

    // ── Last-wins semantic ──────────────────────────────────

    @Test fun lastEntryWinsOnSameKey() {
        // Marquee last-wins pin: per the doc-comment,
        // associateBy{...}.values gives last-wins (drift to
        // distinctBy / Set would give first-wins, defeating
        // the "stale thumbnails should be replaced" purpose).
        val older = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(100, 100))
        val newer = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(200, 200))
        val result = listOf(older, newer).deduplicateProxies()
        assertEquals(
            1,
            result.size,
            "same key collapses to one",
        )
        assertEquals(
            Resolution(200, 200),
            result.single().resolution,
            "LAST entry MUST win (newer overwrites older — generator-rerun replaces stale)",
        )
    }

    @Test fun lastWinsAcrossNonAdjacentDuplicates() {
        // Sister last-wins pin: duplicates not next to each
        // other still resolve to last entry.
        val first = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(100, 100))
        val middle = proxy(ProxyPurpose.LOW_RES, fileA)
        val last = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(300, 300))
        val result = listOf(first, middle, last).deduplicateProxies()
        assertEquals(2, result.size, "2 unique keys")
        // The THUMBNAIL entry MUST be the LAST (Resolution=300x300).
        val thumb = result.single { it.purpose == ProxyPurpose.THUMBNAIL }
        assertEquals(
            Resolution(300, 300),
            thumb.resolution,
            "non-adjacent duplicate MUST also surface as last-wins",
        )
    }

    // ── Insertion order preservation ───────────────────────

    @Test fun insertionOrderPreservedAcrossUniqueKeys() {
        // Marquee order pin: per the doc-comment,
        // associateBy{...}.values preserves insertion order.
        // Drift to a non-ordered Map (e.g. HashMap) would
        // randomize output order across JVM versions.
        val proxies = listOf(
            proxy(ProxyPurpose.AUDIO_WAVEFORM, fileA),
            proxy(ProxyPurpose.THUMBNAIL, fileA),
            proxy(ProxyPurpose.LOW_RES, fileA),
        )
        val result = proxies.deduplicateProxies()
        assertEquals(3, result.size)
        assertEquals(
            listOf(ProxyPurpose.AUDIO_WAVEFORM, ProxyPurpose.THUMBNAIL, ProxyPurpose.LOW_RES),
            result.map { it.purpose },
            "insertion order MUST be preserved (drift to HashMap surfaces here)",
        )
    }

    @Test fun firstOccurrenceSlotPreservedOnDuplicate() {
        // Pin: when a duplicate appears, the surviving entry
        // sits in the FIRST occurrence's slot (per
        // associateBy semantics), but carries the LAST
        // occurrence's value (last-wins). Drift would either
        // re-order or pick the wrong value.
        val first = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(100, 100))
        val middle = proxy(ProxyPurpose.LOW_RES, fileA)
        val late = proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(200, 200))
        val result = listOf(first, middle, late).deduplicateProxies()
        // Slot order: THUMBNAIL (the first occurrence's slot
        // wins for ordering), LOW_RES (its own slot).
        assertEquals(
            listOf(ProxyPurpose.THUMBNAIL, ProxyPurpose.LOW_RES),
            result.map { it.purpose },
            "first-occurrence slot order MUST be preserved",
        )
        // But the THUMBNAIL VALUE is the late one.
        assertEquals(
            Resolution(200, 200),
            result.single { it.purpose == ProxyPurpose.THUMBNAIL }.resolution,
        )
    }

    // ── Multi-key combinations ──────────────────────────────

    @Test fun mixedDuplicatesAndUniquesAcrossPurposesAndSources() {
        // Pin: mixed scenario — all 3 purposes, both sources,
        // some duplicates. Final size = 6 (3 purposes × 2
        // sources, no resolution-collisions in key space).
        val all = listOf(
            proxy(ProxyPurpose.THUMBNAIL, fileA),
            proxy(ProxyPurpose.LOW_RES, fileA),
            proxy(ProxyPurpose.AUDIO_WAVEFORM, fileA),
            proxy(ProxyPurpose.THUMBNAIL, fileB),
            proxy(ProxyPurpose.LOW_RES, fileB),
            proxy(ProxyPurpose.AUDIO_WAVEFORM, fileB),
            // duplicates of every key:
            proxy(ProxyPurpose.THUMBNAIL, fileA, Resolution(99, 99)),
            proxy(ProxyPurpose.AUDIO_WAVEFORM, fileB, Resolution(50, 50)),
        )
        val result = all.deduplicateProxies()
        assertEquals(6, result.size, "6 unique (purpose, source) pairs survive")
        // Spot-check: the THUMBNAIL+fileA entry has
        // resolution=99 (last-wins), not the original null.
        val thumbA = result.single {
            it.purpose == ProxyPurpose.THUMBNAIL && it.source == fileA
        }
        assertEquals(
            Resolution(99, 99),
            thumbA.resolution,
            "duplicate fileA THUMBNAIL MUST be last-wins (99x99 not null)",
        )
        val wavB = result.single {
            it.purpose == ProxyPurpose.AUDIO_WAVEFORM && it.source == fileB
        }
        assertEquals(
            Resolution(50, 50),
            wavB.resolution,
            "duplicate fileB AUDIO_WAVEFORM MUST be last-wins",
        )
    }

    // ── Cross-source-type dedupe ────────────────────────────

    @Test fun differentSourceTypesAreSeparateKeys() {
        // Pin: MediaSource is sealed; File / BundleFile / Http /
        // Platform with same "value" semantics (e.g.
        // Http("/x") vs File("/x")) are still distinct dedupe
        // keys. Drift to compare via toString would silently
        // collapse them.
        val viaFile = proxy(ProxyPurpose.THUMBNAIL, MediaSource.File("/x.png"))
        val viaHttp = proxy(ProxyPurpose.THUMBNAIL, MediaSource.Http("https://x.png"))
        val viaBundle = proxy(ProxyPurpose.THUMBNAIL, MediaSource.BundleFile("media/x.png"))
        val result = listOf(viaFile, viaHttp, viaBundle).deduplicateProxies()
        assertEquals(
            3,
            result.size,
            "different MediaSource subtypes MUST be distinct keys (drift to toString-compare surfaces here)",
        )
    }

    @Test fun listWithoutDuplicatesReturnedUnchanged() {
        // Pin: when input has no duplicates, the function is
        // idempotent at the (size, content) level. Drift to
        // re-order or re-allocate would surface here.
        val unique = listOf(
            proxy(ProxyPurpose.THUMBNAIL, fileA),
            proxy(ProxyPurpose.LOW_RES, fileA),
            proxy(ProxyPurpose.AUDIO_WAVEFORM, fileB),
        )
        val result = unique.deduplicateProxies()
        assertEquals(unique, result, "unique input MUST be returned with same content + order")
    }
}
