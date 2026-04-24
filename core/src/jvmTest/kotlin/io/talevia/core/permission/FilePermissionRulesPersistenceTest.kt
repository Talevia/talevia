package io.talevia.core.permission

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for [FilePermissionRulesPersistence] — the per-user
 * storage for interactive "Always" permission grants that survive
 * process restart (closes `permission-persistent-rules`, cycle-53).
 *
 * Edges (§3a #9):
 *  - missing file → empty list (new install / first launch).
 *  - save + load round-trip preserves rule shape.
 *  - malformed JSON → empty list (corrupt config doesn't brick CLI).
 *  - save is atomic (tmp-file + rename, mirroring FileProjectStore).
 *  - save on read-only fs → swallowed error (in-memory list still has rule).
 */
class FilePermissionRulesPersistenceTest {

    private val path = "/.talevia/permission-rules.json".toPath()

    @Test fun missingFileLoadsEmpty() = runTest {
        val fs = FakeFileSystem()
        val persistence = FilePermissionRulesPersistence(path, fs)
        assertEquals(emptyList(), persistence.load())
    }

    @Test fun saveThenLoadRoundTripsRuleList() = runTest {
        val fs = FakeFileSystem()
        val persistence = FilePermissionRulesPersistence(path, fs)
        val rules = listOf(
            PermissionRule("fs.write", "/tmp/**", PermissionAction.ALLOW),
            PermissionRule("web.fetch", "https://api.example.com/*", PermissionAction.ALLOW),
            PermissionRule("shell.exec", "*", PermissionAction.DENY),
        )
        persistence.save(rules)
        assertEquals(rules, persistence.load())
    }

    @Test fun malformedFileLoadsEmpty() = runTest {
        // §3a #9 bounded-edge: a corrupt permission-rules.json must NOT
        // throw during CLI init. Worst case the user re-grants rules they
        // care about; best case the file's been hand-edited by the
        // operator and the corruption is intentional (debug / revoke).
        val fs = FakeFileSystem()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("not json at all, just random text") }
        val persistence = FilePermissionRulesPersistence(path, fs)
        assertEquals(
            emptyList(),
            persistence.load(),
            "corrupt JSON must degrade to empty, not throw",
        )
    }

    @Test fun saveOverwritesExistingFile() = runTest {
        val fs = FakeFileSystem()
        val persistence = FilePermissionRulesPersistence(path, fs)
        persistence.save(listOf(PermissionRule("p1", "*", PermissionAction.ALLOW)))
        persistence.save(listOf(PermissionRule("p2", "*", PermissionAction.DENY)))
        val loaded = persistence.load()
        assertEquals(
            listOf(PermissionRule("p2", "*", PermissionAction.DENY)),
            loaded,
            "second save must fully replace first (whole-file rewrite)",
        )
    }

    @Test fun saveIsAtomicViaTmpRename() = runTest {
        // The implementation writes to a `.tmp.*` sibling then atomicMoves
        // in place. After a successful save, the tmp file must be gone
        // (proof the rename happened; a crashed write would leave a
        // straggler).
        val fs = FakeFileSystem()
        val persistence = FilePermissionRulesPersistence(path, fs)
        persistence.save(listOf(PermissionRule("p", "*", PermissionAction.ALLOW)))

        val siblings = fs.list(path.parent!!)
        val tmps = siblings.filter { it.name.startsWith("permission-rules.json.tmp.") }
        assertTrue(tmps.isEmpty(), "no .tmp.* stragglers after successful save; got $tmps")
        assertTrue(path in siblings, "target file must exist after save")
    }

    @Test fun saveErrorIsSwallowed() = runTest {
        // §3a #9 bounded-edge: a save failure (read-only fs, quota hit,
        // whatever) must NOT throw into the interactive prompt path. The
        // in-memory rules list still has the freshly-appended rule; the
        // user just re-grants on next launch.
        //
        // We simulate by asking the persistence to write to a path whose
        // parent can't be created — FakeFileSystem treats a file path as
        // a non-directory, so createDirectories on a path that IS a file
        // throws. The runCatching in save() should swallow.
        val fs = FakeFileSystem()
        val blockingFile = "/readonly-blob".toPath()
        fs.write(blockingFile) { writeUtf8("existing-file") }
        val trappedPath = blockingFile.resolve("permission-rules.json")
        val persistence = FilePermissionRulesPersistence(trappedPath, fs)
        // Should NOT throw.
        persistence.save(listOf(PermissionRule("p", "*", PermissionAction.ALLOW)))
        // And load should still return empty (no write happened).
        assertEquals(emptyList(), persistence.load())
    }

    @Test fun noopPersistenceIsInertButCallable() = runTest {
        // Guard on the Noop sentinel — platforms / tests that don't want
        // file persistence use this; it must load empty + save without
        // touching anything.
        val persistence = PermissionRulesPersistence.Noop
        assertEquals(emptyList(), persistence.load())
        persistence.save(listOf(PermissionRule("whatever", "*", PermissionAction.ALLOW)))
        // Still empty on reload — no state.
        assertEquals(emptyList(), persistence.load())
    }
}
