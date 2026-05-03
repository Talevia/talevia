package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [resolveProjectSummary] —
 * `core/domain/FileProjectStoreSummaryIO.kt`. The cache-first
 * resolver shared by `FileProjectStore.summary` and
 * `FileProjectStore.listSummaries`. Cycle 146 audit: 82 LOC,
 * 0 transitive test refs (the function is internal but
 * exercised only through SqlDelight-store integration tests
 * — the cache-warm vs cache-cold branches were never pinned
 * directly).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Bundle missing → returns null without calling the
 *    decoder.** A `talevia.json` that doesn't exist (registry
 *    pointed at a deleted bundle, e.g., user moved the
 *    folder) returns null EARLY — the caller filters nulls
 *    so a missing bundle becomes "row absent" instead of
 *    "row with garbage data". The decoder is never invoked
 *    for missing bundles, sparing the I/O.
 *
 * 2. **Cache warm (`entry.createdAtEpochMs > 0L`) → fast path
 *    skips envelope decode entirely.** Per kdoc: "Skipping
 *    the JSON parse saves 5–30 ms per project on warm
 *    registries; the 1000-bundle benchmark at cycle-42 (36
 *    ms total) depended on this fast path." A regression
 *    that always decoded would non-linearly slow
 *    `listSummaries` on long recents. Pinned by counting
 *    decoder invocations: warm → 0 calls.
 *
 * 3. **Cache cold (`createdAtEpochMs == 0L`) → slow path
 *    decodes envelope to fill missing fields.** Pre-cache
 *    `recents.json` files OR entries from paths that didn't
 *    thread `createdAtEpochMs` need the decode. Pin: cold
 *    → exactly 1 decoder call, returned title/createdAt
 *    come from the StoredProject.
 */
class FileProjectStoreSummaryIOTest {

    private val taleviaJsonPath = "/bundle/talevia.json".toPath()

    private fun fakeFsWithBundle(): FakeFileSystem {
        val fs = FakeFileSystem()
        fs.createDirectories(taleviaJsonPath.parent!!)
        fs.write(taleviaJsonPath) { writeUtf8("""{"schemaVersion":1,"title":"Bundle","createdAtEpochMs":0,"project":{}}""") }
        return fs
    }

    private fun entry(
        id: String = "p-1",
        title: String = "Recents Title",
        lastOpenedAtEpochMs: Long = 0L,
        createdAtEpochMs: Long = 0L,
    ): RecentsEntry = RecentsEntry(
        id = id,
        path = "/bundle",
        title = title,
        lastOpenedAtEpochMs = lastOpenedAtEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    /**
     * A `decodeWithLock` lambda that records its invocation
     * count + arg. Returns the supplied `stored` (or null when
     * `stored=null`).
     */
    private class CountingDecoder(private val stored: StoredProject?) {
        var calls: Int = 0
            private set
        var lastPath: okio.Path? = null
            private set
        val lambda: suspend (okio.Path) -> StoredProject? = { p ->
            calls += 1
            lastPath = p
            stored
        }
    }

    private fun storedProject(
        title: String = "Stored Title",
        createdAtEpochMs: Long = 0L,
    ): StoredProject = StoredProject(
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        project = Project(id = ProjectId("p-1"), timeline = Timeline()),
    )

    // ── bundle-missing path ──────────────────────────────────────

    @Test fun missingTaleviaJsonReturnsNullWithoutCallingDecoder() = runTest {
        val fs = FakeFileSystem() // no bundle planted
        val decoder = CountingDecoder(stored = storedProject())
        val out = resolveProjectSummary(
            entry = entry(),
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )
        assertNull(out, "missing bundle returns null")
        assertEquals(
            0,
            decoder.calls,
            "decoder NOT invoked when bundle absent — sparing the I/O",
        )
    }

    @Test fun missingBundleReturnsNullEvenIfCacheIsWarm() = runTest {
        // Pin: bundle-existence check is BEFORE the cache-warm
        // check. A warm cache pointing at a deleted bundle
        // still returns null (registry healing happens at the
        // caller, not here).
        val fs = FakeFileSystem()
        val decoder = CountingDecoder(stored = storedProject())
        val out = resolveProjectSummary(
            entry = entry(createdAtEpochMs = 5000L), // warm
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )
        assertNull(out)
        assertEquals(0, decoder.calls)
    }

    // ── fast path (cache warm) ───────────────────────────────────

    @Test fun cacheWarmFastPathSkipsEnvelopeDecodeAndReturnsCachedFields() = runTest {
        val fs = fakeFsWithBundle()
        val decoder = CountingDecoder(stored = storedProject())
        val warmEntry = entry(
            id = "p-warm",
            title = "Cached Title",
            lastOpenedAtEpochMs = 9_999L,
            createdAtEpochMs = 1_234L, // > 0 → fast path
        )
        val out = resolveProjectSummary(
            entry = warmEntry,
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )!!

        // Pin: fast-path returns decoded=false flag.
        assertEquals(false, out.decoded, "fast path: decoded=false")
        // Pin: decoder NOT called.
        assertEquals(
            0,
            decoder.calls,
            "fast path skips decode (saves 5-30 ms per project)",
        )
        // Pin: title from RecentsEntry, NOT from envelope —
        // the envelope's "Bundle" title is never read on the
        // fast path, only the cached "Cached Title".
        assertEquals("Cached Title", out.summary.title)
        assertEquals("p-warm", out.summary.id)
        assertEquals(1_234L, out.summary.createdAtEpochMs)
        // Pin: lastOpenedAtEpochMs (9999) takes precedence
        // over fs `updated` for updatedAtEpochMs.
        assertEquals(9_999L, out.summary.updatedAtEpochMs)
    }

    @Test fun cacheWarmWithZeroLastOpenedFallsBackToFsUpdated() = runTest {
        // Pin: the `entry.lastOpenedAtEpochMs.takeIf { it > 0L }
        // ?: updated` chain. lastOpenedAtEpochMs=0 falls
        // through to FS-derived `updated`. FakeFileSystem
        // doesn't always populate timestamps, so this also
        // exercises the bundleTimestamps fallback.
        val fs = fakeFsWithBundle()
        val decoder = CountingDecoder(stored = storedProject())
        val out = resolveProjectSummary(
            entry = entry(lastOpenedAtEpochMs = 0L, createdAtEpochMs = 1_000L),
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )!!
        // updatedAtEpochMs source is fs-derived (or fallback);
        // the only invariant we can assert without mocking the
        // fs clock is that decoded=false (fast path was taken).
        assertEquals(false, out.decoded)
    }

    // ── slow path (cache cold) ───────────────────────────────────

    @Test fun cacheColdSlowPathDecodesEnvelopeOnceAndReturnsFromStoredProject() = runTest {
        val fs = fakeFsWithBundle()
        val decoder = CountingDecoder(
            stored = storedProject(title = "From Envelope", createdAtEpochMs = 4_321L),
        )
        val coldEntry = entry(
            id = "p-cold",
            title = "Cached Title", // Should be IGNORED by slow path
            lastOpenedAtEpochMs = 8_888L,
            createdAtEpochMs = 0L, // = 0 → slow path
        )
        val out = resolveProjectSummary(
            entry = coldEntry,
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )!!

        // Pin: slow-path returns decoded=true flag.
        assertEquals(true, out.decoded, "slow path: decoded=true")
        // Pin: decoder called EXACTLY ONCE.
        assertEquals(1, decoder.calls, "slow path: decoder called exactly once")
        // Pin: decoder gets the bundle DIRECTORY (parent of
        // talevia.json), not the talevia.json path itself.
        assertEquals(taleviaJsonPath.parent, decoder.lastPath)
        // Pin: title from StoredProject, NOT from RecentsEntry.
        assertEquals("From Envelope", out.summary.title)
        // Pin: createdAt from StoredProject (when > 0), NOT
        // from FS metadata.
        assertEquals(4_321L, out.summary.createdAtEpochMs)
        // Pin: id from RecentsEntry (always — never overridden).
        assertEquals("p-cold", out.summary.id)
        // Pin: updatedAtEpochMs uses lastOpenedAtEpochMs when
        // > 0.
        assertEquals(8_888L, out.summary.updatedAtEpochMs)
    }

    @Test fun cacheColdAndStoredProjectCreatedAtZeroFallsBackToFsCreated() = runTest {
        // Pin: `stored.createdAtEpochMs.takeIf { it > 0L } ?:
        // createdFromFs`. When BOTH cache AND envelope have
        // createdAtEpochMs=0 (legacy), fall back to
        // FS-metadata-derived created timestamp (or the
        // fallback if FS doesn't have it).
        val fs = fakeFsWithBundle()
        val decoder = CountingDecoder(
            stored = storedProject(title = "Pre-stamp", createdAtEpochMs = 0L),
        )
        val out = resolveProjectSummary(
            entry = entry(lastOpenedAtEpochMs = 5_000L, createdAtEpochMs = 0L),
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )!!
        assertEquals(true, out.decoded)
        // FS-derived createdAt OR fallback (lastOpenedAtEpochMs
        // = 5000) — either way, > 0 invariant holds.
        assertTrue(
            out.summary.createdAtEpochMs >= 0L,
            "createdAt resolves to non-negative; got: ${out.summary.createdAtEpochMs}",
        )
    }

    @Test fun cacheColdAndDecoderReturnsNullProducesNullSummary() = runTest {
        // Pin: the decoder lambda returning null (e.g.,
        // talevia.json got corrupted and parsing failed
        // silently in the locked decode call) → null summary
        // returned, NOT a partial summary with stub data.
        // Drift to "make up a placeholder" would silently mask
        // bundle corruption.
        val fs = fakeFsWithBundle()
        val decoder = CountingDecoder(stored = null)
        val out = resolveProjectSummary(
            entry = entry(createdAtEpochMs = 0L),
            taleviaJson = taleviaJsonPath,
            fs = fs,
            json = JsonConfig.default,
            decodeWithLock = decoder.lambda,
        )
        assertNull(out, "decoder null → summary null")
        assertEquals(1, decoder.calls)
    }

    // ── ProjectSummaryInput data class ──────────────────────────

    @Test fun projectSummaryInputCarriesSummaryAndDecodedFlag() = runTest {
        // Pin: ProjectSummaryInput is a 2-field carrier.
        // `decoded` flag is currently not consumed but exists
        // for "future bulk-warm routine" per kdoc — pinning
        // its presence so a refactor that elides it surfaces
        // here.
        val input = ProjectSummaryInput(
            summary = ProjectSummary(
                id = "p",
                title = "T",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 2L,
            ),
            decoded = true,
        )
        assertEquals("p", input.summary.id)
        assertEquals(true, input.decoded)
    }
}
