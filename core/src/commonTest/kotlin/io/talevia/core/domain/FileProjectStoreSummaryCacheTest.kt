package io.talevia.core.domain

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract guard for the `RecentsEntry.createdAtEpochMs` cache.
 *
 *  1. **Fast path**: entries freshly created / opened / upserted carry
 *     the bundle envelope's `createdAtEpochMs`, and `listSummaries` /
 *     `summary` serve it without decoding `talevia.json`.
 *  2. **Backward-compat fallback**: a registry entry from a pre-cache
 *     `recents.json` schema (no `createdAtEpochMs` field → defaults to
 *     0L on deserialize) falls back to the bundle decode path so the
 *     returned `ProjectSummary.createdAtEpochMs` is still correct.
 *  3. **Heal on next open**: once an old-schema entry is re-opened via
 *     `openAt`, the registry caches the field and the fast path kicks
 *     in on subsequent calls.
 */
class FileProjectStoreSummaryCacheTest {

    @Test fun createAtStampsCreatedAtOnRegistryEntry() = runTest {
        val (store, _) = ProjectStoreTestKit.createWithFs()
        val project = store.createAt("/projects/demo".toPath(), title = "demo")

        val summary = store.summary(project.id)!!
        assertTrue(summary.createdAtEpochMs > 0L, "createAt must stamp createdAtEpochMs, got ${summary.createdAtEpochMs}")

        // listSummaries should agree — it reads the same registry entry.
        val listed = store.listSummaries().single()
        assertEquals(summary.createdAtEpochMs, listed.createdAtEpochMs)
    }

    @Test fun openAtCachesCreatedAtFromBundleEnvelope() = runTest {
        val (storeA, fs) = ProjectStoreTestKit.createWithFs()
        val project = storeA.createAt("/projects/persisted".toPath(), title = "persisted")
        val expectedCreatedAt = storeA.summary(project.id)!!.createdAtEpochMs

        // Simulate a fresh-process open: new store instance over the same fs.
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        val storeB = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
        )
        val reopened = storeB.openAt("/projects/persisted".toPath())
        assertEquals(project.id, reopened.id)

        val cached = storeB.summary(reopened.id)!!
        assertEquals(expectedCreatedAt, cached.createdAtEpochMs, "openAt must populate registry cache from bundle envelope")
    }

    @Test fun legacyZeroCreatedAtFallsBackToBundleDecode() = runTest {
        // Seed the bundle via createAt (which stamps the envelope), but then
        // overwrite the registry entry's `createdAtEpochMs` to 0L to simulate
        // a pre-cache `recents.json` schema. summary / listSummaries must
        // still return a correct timestamp via the bundle-decode fallback.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val project = store.createAt("/projects/legacy".toPath(), title = "legacy")
        val envelopeCreatedAt = store.summary(project.id)!!.createdAtEpochMs

        // Reach into the registry and rewrite the entry with zero cache.
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        val entry = registry.get(project.id)!!
        registry.upsert(
            id = project.id,
            path = entry.path.toPath(),
            title = entry.title,
            lastOpenedAtEpochMs = entry.lastOpenedAtEpochMs,
            createdAtEpochMs = 0L,
        )

        val summary = store.summary(project.id)!!
        assertEquals(
            envelopeCreatedAt,
            summary.createdAtEpochMs,
            "legacy zero cache must fall back to bundle decode and produce the same timestamp",
        )
        val listed = store.listSummaries().single()
        assertEquals(envelopeCreatedAt, listed.createdAtEpochMs)
    }
}
