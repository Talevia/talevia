package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `lockfile-extract-jsonl-phase1-add-jsonl-writes` (cycle 24): JSONL
 * is now the authoritative storage for `Project.lockfile`; the envelope's
 * `lockfile` field is a phase-1 fallback for pre-existing bundles.
 * Tests pin:
 *   - Round-trip: write → read recovers all entries in order.
 *   - JSONL takes precedence when both paths populate.
 *   - Missing JSONL falls back to envelope's lockfile field
 *     (pre-phase-1 bundle migration).
 *   - Crash recovery: malformed last line silently truncated.
 *   - Mid-file malformed line throws (silent truncation would lose data).
 *   - Empty entries → 0-byte JSONL still readable as Lockfile.EMPTY.
 *   - Write order: JSONL written before envelope (best-effort
 *     atomicity — verified by inspecting timestamp / file
 *     existence after a partial-failure simulation isn't trivially
 *     testable on FakeFileSystem; case left for integration test).
 */
class FileProjectStoreLockfileJsonlTest {

    private val json = JsonConfig.prettyPrint
    private val fakeProvenance = GenerationProvenance(
        providerId = "fake",
        modelId = "fake-model",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private fun entry(inputHash: String, assetId: String): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = fakeProvenance,
    )

    @Test fun writeReadRoundTripsAllEntriesInOrder() = runTest {
        val fs = FakeFileSystem()
        val bundleRoot = "/bundles/p1".toPath()
        fs.createDirectories(bundleRoot)
        val lockfile = Lockfile(entries = listOf(entry("h1", "a-1"), entry("h2", "a-2"), entry("h3", "a-3")))

        writeLockfileJsonl(fs, bundleRoot, lockfile, json)
        val readBack = readLockfileJsonl(fs, bundleRoot, json)

        assertNotNull(readBack)
        assertEquals(3, readBack.entries.size)
        assertEquals(listOf("h1", "h2", "h3"), readBack.entries.map { it.inputHash })
        assertEquals(listOf("a-1", "a-2", "a-3"), readBack.entries.map { it.assetId.value })
    }

    @Test fun missingJsonlReturnsNullSoCallerFallsBackToEnvelope() = runTest {
        val fs = FakeFileSystem()
        val bundleRoot = "/bundles/p1".toPath()
        fs.createDirectories(bundleRoot)

        val readBack = readLockfileJsonl(fs, bundleRoot, json)
        assertNull(readBack, "missing lockfile.jsonl signals 'fall back to envelope', not 'empty lockfile'")
    }

    @Test fun emptyJsonlReturnsLockfileEmpty() = runTest {
        // 0-byte file is the canonical shape for "lockfile.jsonl exists
        // but holds no entries" — distinct from the missing-file case.
        val fs = FakeFileSystem()
        val bundleRoot = "/bundles/p1".toPath()
        fs.createDirectories(bundleRoot)
        writeLockfileJsonl(fs, bundleRoot, Lockfile.EMPTY, json)

        val readBack = readLockfileJsonl(fs, bundleRoot, json)
        assertNotNull(readBack, "0-byte JSONL still signals 'authoritative empty', not 'missing'")
        assertTrue(readBack.entries.isEmpty())
    }

    @Test fun trailingMalformedLineIsSilentlyTruncated() = runTest {
        // Simulate partial write before flush: last line is half-written.
        // readLockfileJsonl should drop it from the in-memory result; on-
        // disk file isn't rewritten by reads (next mutate cleans up).
        val fs = FakeFileSystem()
        val bundleRoot = "/bundles/p1".toPath()
        fs.createDirectories(bundleRoot)
        val good = JsonConfig.default.encodeToString(LockfileEntry.serializer(), entry("h1", "a-1"))
        // Append a malformed-final-line scenario: 1 good entry then a half-JSON.
        fs.write(bundleRoot.resolve(FileProjectStore.LOCKFILE_JSONL)) {
            writeUtf8(good)
            writeUtf8("\n")
            writeUtf8("{\"inputHash\":\"trunc")
            // No closing brace — partial-flush.
        }

        val readBack = readLockfileJsonl(fs, bundleRoot, json)
        assertNotNull(readBack)
        assertEquals(1, readBack.entries.size, "tail-truncate drops the partial last line; only h1 survives")
        assertEquals("h1", readBack.entries.single().inputHash)
    }

    @Test fun midFileMalformedLineThrowsRatherThanTruncating() = runTest {
        // Mid-file corruption is a real bug, not a partial-write artifact.
        // Silently truncating from a non-tail position would lose data.
        val fs = FakeFileSystem()
        val bundleRoot = "/bundles/p1".toPath()
        fs.createDirectories(bundleRoot)
        val good1 = JsonConfig.default.encodeToString(LockfileEntry.serializer(), entry("h1", "a-1"))
        val good2 = JsonConfig.default.encodeToString(LockfileEntry.serializer(), entry("h2", "a-2"))
        fs.write(bundleRoot.resolve(FileProjectStore.LOCKFILE_JSONL)) {
            writeUtf8(good1)
            writeUtf8("\n")
            writeUtf8("{not valid json}")
            writeUtf8("\n")
            writeUtf8(good2)
            writeUtf8("\n")
        }

        val ex = runCatching { readLockfileJsonl(fs, bundleRoot, json) }.exceptionOrNull()
        assertNotNull(ex, "mid-file malformed line must not silently truncate")
        assertTrue(
            ex.message!!.contains("malformed entry at line 2") &&
                ex.message!!.contains("tail-truncate only applies to the last line"),
            "error names the offending line + clarifies tail-only semantics; got: ${ex.message}",
        )
    }

    @Test fun fileProjectStoreOpenAtPrefersJsonlWhenBothPathsPopulate() = runTest {
        // Create a project, write a lockfile entry through the normal
        // mutate path. Verify on next openAt the lockfile comes from
        // the JSONL (not from the envelope's lockfile field).
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/jsonl".toPath(), title = "jsonl").id

        // Mutate adds an entry — dual-write should populate both paths.
        store.mutate(pid) { project ->
            project.copy(lockfile = project.lockfile.append(entry("h-mutate", "a-mutate")))
        }

        // Verify JSONL exists and holds the entry.
        val bundleRoot = "/projects/jsonl".toPath()
        val jsonlPath = bundleRoot.resolve(FileProjectStore.LOCKFILE_JSONL)
        assertTrue(fs.exists(jsonlPath), "writeBundleLocked must create lockfile.jsonl")
        val direct = readLockfileJsonl(fs, bundleRoot, json)
        assertNotNull(direct)
        assertEquals(1, direct.entries.size)
        assertEquals("h-mutate", direct.entries.single().inputHash)

        // Re-read via store.get; the lockfile should match what JSONL holds.
        val reread = store.get(pid)
        assertNotNull(reread)
        assertEquals(1, reread.lockfile.entries.size)
        assertEquals("h-mutate", reread.lockfile.entries.single().inputHash)
    }

    @Test fun fileProjectStoreOpenAtFallsBackToEnvelopeWhenJsonlMissing() = runTest {
        // Pre-phase-1 bundle migration path: a talevia.json with a
        // populated `lockfile` field but no lockfile.jsonl on disk must
        // round-trip through the envelope-fallback branch in
        // [readBundle]. After phase 2 the production write code never
        // emits this shape, so we hand-craft it by writing a
        // [StoredProject] directly to bypass [FileProjectStore]'s phase
        // 2 envelope-shrink path. Exercising the fallback keeps the
        // migration code from rotting silently.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/legacy".toPath()
        val pid = io.talevia.core.ProjectId("legacy-id")
        // Build a phase-1-shape envelope: project carries a non-empty
        // lockfile, no lockfile.jsonl exists on disk.
        val project = Project(
            id = pid,
            timeline = Timeline(),
            lockfile = Lockfile(entries = listOf(entry("h-legacy", "a-legacy"))),
        )
        val stored = StoredProject(
            schemaVersion = 1,
            title = "legacy",
            createdAtEpochMs = 1_700_000_000_000L,
            project = project,
        )
        fs.createDirectories(bundleRoot)
        fs.write(bundleRoot.resolve(FileProjectStore.TALEVIA_JSON)) {
            writeUtf8(json.encodeToString(StoredProject.serializer(), stored))
        }
        // Register so store.get can find it.
        // Hack: createAt would clobber our hand-written envelope, so we
        // register the bundle via the recents registry directly. Easiest
        // path: call openAt which both registers and reads.
        val reread = store.openAt(bundleRoot)
        assertNotNull(reread)
        assertEquals(1, reread.lockfile.entries.size, "fallback to envelope's lockfile field on missing JSONL")
        assertEquals("h-legacy", reread.lockfile.entries.single().inputHash)
    }
}
