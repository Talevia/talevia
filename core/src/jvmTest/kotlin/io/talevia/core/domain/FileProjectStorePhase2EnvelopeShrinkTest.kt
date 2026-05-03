package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `lockfile-extract-jsonl-phase2-envelope-shrink` (cycle 28).
 *
 * Phase 1 (cycle 24, 3020ddd3) introduced dual-write: every `mutate(...)`
 * persisted the lockfile both into `lockfile.jsonl` and into the
 * `talevia.json` envelope. Phase 2 keeps JSONL but stops encoding the
 * envelope's `lockfile` field — the per-mutate cost no longer scales with
 * lockfile size (was O(N): every project-level edit re-encoded the entire
 * `Lockfile.entries` JSON into the envelope; now O(1): the envelope has
 * an empty Lockfile placeholder).
 *
 * The follow-up `debt-lockfile-lazy-interface-O1-open` (deferred to
 * P1) is the larger structural refactor that drops the in-memory full
 * materialisation of `Project.lockfile.entries` so `openAt(...)` itself
 * becomes O(1). This test pins the envelope-shrink invariant alone:
 *   - talevia.json's serialised `lockfile.entries` is always `[]` after
 *     mutate, regardless of how many entries the in-memory Lockfile has.
 *   - lockfile.jsonl is the actual storage — entries round-trip through
 *     it on `openAt(...)`.
 *   - Mutating a project (e.g. updating its title) does NOT re-encode
 *     the entries into talevia.json — the envelope size is independent
 *     of N.
 */
class FileProjectStorePhase2EnvelopeShrinkTest {

    private val provenance = GenerationProvenance(
        providerId = "fake",
        modelId = "m",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private fun entry(hash: String, asset: String): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(asset),
        provenance = provenance,
    )

    @Test fun envelopeOmitsLockfileEntriesAfterMutate() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/p-shrink".toPath()
        val pid = store.createAt(path = bundleRoot, title = "shrink").id

        // Land 12 lockfile entries via mutate.
        store.mutate(pid) { project ->
            val lf = (1..12).fold(Lockfile.EMPTY) { acc, i -> acc.append(entry("h$i", "a-$i")) }
            project.copy(lockfile = lf)
        }

        // Direct talevia.json read — bypasses the FileProjectStore reload
        // path so we see exactly what was written. The phase 2 invariant:
        // the envelope encodes [Lockfile.EMPTY], which under
        // [JsonConfig]'s `encodeDefaults = false` means the `lockfile`
        // key is omitted from the JSON entirely. Either form ("field
        // absent" or "field present with entries=[]") satisfies the
        // invariant — we accept both rather than tying the test to the
        // current Json config flag.
        val taleviaJsonText = fs.read(bundleRoot.resolve(FileProjectStore.TALEVIA_JSON)) { readUtf8() }
        val parsed = Json.parseToJsonElement(taleviaJsonText).jsonObject
        val envelopeProject = parsed["project"]!!.jsonObject
        val envelopeLockfile = envelopeProject["lockfile"]?.jsonObject
        val envelopeEntryCount = envelopeLockfile?.get("entries")?.jsonArray?.size ?: 0
        assertTrue(
            envelopeEntryCount == 0,
            "talevia.json envelope must not carry lockfile entries after phase 2; " +
                "got $envelopeEntryCount — phase 1 dual-write regression. " +
                "Envelope lockfile field: $envelopeLockfile",
        )

        // jsonl is the canonical storage — read it back, assert all 12.
        val jsonl = readLockfileJsonl(fs, bundleRoot, JsonConfig.prettyPrint)
        assertEquals(12, jsonl?.entries?.size, "all 12 entries must persist via lockfile.jsonl")
    }

    @Test fun openAtMaterialisesEntriesFromJsonlNotEnvelope() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/p-open".toPath()
        val pid = store.createAt(path = bundleRoot, title = "open").id

        // Seed entries.
        store.mutate(pid) { project ->
            project.copy(lockfile = EagerLockfile(entries = listOf(entry("hA", "asset-A"), entry("hB", "asset-B"))))
        }

        // Verify pure round-trip via the public API: get() should reload
        // entries from jsonl even though the envelope no longer carries
        // them. (No direct fs poke — the FileProjectStore.openAt path is
        // what production exercises.)
        val reloaded = store.get(pid)!!
        assertEquals(2, reloaded.lockfile.entries.size)
        assertEquals(setOf("hA", "hB"), reloaded.lockfile.entries.map { it.inputHash }.toSet())
        assertEquals(setOf("asset-A", "asset-B"), reloaded.lockfile.entries.map { it.assetId.value }.toSet())
    }

    @Test fun unrelatedMutateDoesNotReencodeLockfileIntoEnvelope() = runTest {
        // The actual perf invariant: a project edit that does NOT touch
        // the lockfile must keep talevia.json's size independent of N
        // lockfile entries. Pre-phase-2 every mutate re-serialised the
        // whole entries list into the envelope; phase 2 makes the envelope
        // size independent of N.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/p-decoupled".toPath()
        val pid = store.createAt(path = bundleRoot, title = "decoupled").id

        // Seed 50 entries.
        store.mutate(pid) { project ->
            val lf = (1..50).fold(Lockfile.EMPTY) { acc, i -> acc.append(entry("h$i", "a-$i")) }
            project.copy(lockfile = lf)
        }
        val sizeAfterSeed = fs.metadata(bundleRoot.resolve(FileProjectStore.TALEVIA_JSON))
            .size ?: error("size unknown")

        // Mutate via a non-lockfile field. talevia.json size should not
        // grow with the seeded 50 entries — the envelope encodes an empty
        // Lockfile regardless of in-memory N.
        store.mutate(pid) { project -> project.copy(timeline = Timeline()) }
        val sizeAfterUnrelated = fs.metadata(bundleRoot.resolve(FileProjectStore.TALEVIA_JSON))
            .size ?: error("size unknown")

        // Same exact size: the envelope is byte-stable across non-lockfile
        // mutates because the lockfile slot is empty in both writes.
        assertEquals(
            sizeAfterSeed,
            sizeAfterUnrelated,
            "talevia.json size must be byte-stable across non-lockfile mutates after phase 2; " +
                "seed=$sizeAfterSeed unrelated=$sizeAfterUnrelated — envelope is leaking lockfile bytes",
        )
    }
}
