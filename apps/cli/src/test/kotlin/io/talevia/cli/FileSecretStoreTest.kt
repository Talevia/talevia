package io.talevia.cli

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [FileSecretStore] — the CLI's local-filesystem
 * SecretStore for API keys / OAuth tokens. Cycle 88 audit found
 * this class had no direct test (zero references in any CLI test).
 *
 * The store is shared with the Desktop app (same
 * `~/.talevia/secrets.properties` path) so a regression here
 * affects both surfaces.
 */
class FileSecretStoreTest {

    private val tmpDir = Files.createTempDirectory("file-secret-store-").toFile()

    @AfterTest fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun store(): FileSecretStore {
        // Custom path inside the tmp dir so we don't pollute the
        // user's real ~/.talevia/secrets.properties.
        val path = java.nio.file.Path.of(tmpDir.absolutePath, "secrets.properties")
        return FileSecretStore(path = path)
    }

    @Test fun getReturnsNullWhenFileDoesNotExist() = runTest {
        val s = store()
        assertNull(s.get("any-key"), "fresh store has no values")
    }

    @Test fun keysReturnsEmptySetWhenFileDoesNotExist() = runTest {
        val s = store()
        assertEquals(emptySet(), s.keys(), "fresh store has no keys")
    }

    @Test fun putThenGetRoundTripsValue() = runTest {
        val s = store()
        s.put("openai", "sk-test-123")
        assertEquals("sk-test-123", s.get("openai"))
    }

    @Test fun putCreatesParentDirectoryIfMissing() = runTest {
        // The default path is ~/.talevia/secrets.properties; the
        // ~/.talevia directory may not exist on first use.
        // FileSecretStore's `write()` calls `Files.createDirectories
        // (path.parent)` to handle this. Pin so a refactor accidentally
        // dropping that wouldn't break first-time-use silently.
        val deepPath = java.nio.file.Path.of(
            tmpDir.absolutePath,
            "deep",
            "nested",
            "secrets.properties",
        )
        val s = FileSecretStore(path = deepPath)
        s.put("k", "v")
        assertTrue(java.nio.file.Files.exists(deepPath), "parent dirs auto-created")
        assertEquals("v", s.get("k"))
    }

    @Test fun putOverwritesExistingValue() = runTest {
        val s = store()
        s.put("k", "v1")
        s.put("k", "v2")
        assertEquals("v2", s.get("k"), "second put overwrites first")
    }

    @Test fun removeDeletesValue() = runTest {
        val s = store()
        s.put("k", "v")
        s.remove("k")
        assertNull(s.get("k"), "post-remove key returns null")
    }

    @Test fun removeNonExistentKeyIsNoOp() = runTest {
        // Properties.remove on absent key is a no-op (returns null);
        // the file is still rewritten harmlessly. Pin: no exception.
        val s = store()
        s.remove("never-set") // should not throw
        assertNull(s.get("never-set"))
    }

    @Test fun keysReflectsAllSetKeys() = runTest {
        val s = store()
        s.put("a", "1")
        s.put("b", "2")
        s.put("c", "3")
        assertEquals(setOf("a", "b", "c"), s.keys())
    }

    @Test fun keysAfterRemoveOmitsRemoved() = runTest {
        val s = store()
        s.put("a", "1")
        s.put("b", "2")
        s.remove("a")
        assertEquals(setOf("b"), s.keys())
    }

    @Test fun multipleStoresShareUnderlyingFile() = runTest {
        // Two FileSecretStore instances pointing at the same path see
        // each other's writes — the file IS the shared state. Pin so
        // a refactor caching values in-memory wouldn't silently lose
        // cross-process semantics (CLI + Desktop sharing
        // ~/.talevia/secrets.properties).
        val path = java.nio.file.Path.of(tmpDir.absolutePath, "shared.properties")
        val a = FileSecretStore(path = path)
        val b = FileSecretStore(path = path)
        a.put("k", "from-a")
        assertEquals("from-a", b.get("k"), "second store reads first store's writes from disk")
    }

    @Test fun emptyValueIsValidAndRoundTripsAsEmpty() = runTest {
        // Properties supports empty values. Pin: empty string ≠ null
        // (semantics: "the key is set, with empty value").
        val s = store()
        s.put("k", "")
        assertEquals("", s.get("k"))
        assertTrue("k" in s.keys())
    }
}
