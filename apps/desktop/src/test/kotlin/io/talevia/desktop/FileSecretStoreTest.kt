package io.talevia.desktop

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the **desktop** [FileSecretStore] —
 * `apps/desktop/src/main/kotlin/io/talevia/desktop/FileSecretStore.kt`.
 * Cycle 227 audit: 0 direct test refs.
 *
 * The CLI has a structurally-identical `class FileSecretStore` at
 * `apps/cli/src/main/kotlin/io/talevia/cli/FileSecretStore.kt` (also
 * persisting to `~/.talevia/secrets.properties`); cycle-88's
 * `apps/cli/src/test/.../FileSecretStoreTest.kt` pins the CLI variant
 * exhaustively. The desktop variant has been duplicate code without a
 * separate test, which means drift between the two — for example a
 * desktop-side change to "lowercase keys before set" — would not be
 * caught by the CLI test.
 *
 * Same audit-pattern fallback as cycles 207-226. This file mirrors the
 * CLI test cases against the desktop class so a refactor that
 * accidentally diverges either side fails loud here.
 *
 * Three correctness contracts pinned (mirror of CLI variant):
 *
 *  1. **Round-trip per key.** put then get returns the value verbatim;
 *     missing key → null; remove makes get return null again.
 *
 *  2. **Parent directory auto-created.** First-time use against a
 *     deeply-nested path (e.g. `~/.talevia/...` when `~/.talevia` does
 *     not yet exist) must NOT throw — `write()` calls
 *     `Files.createDirectories(path.parent)` to handle this. Drift to
 *     skipping that call would break first-time-use silently.
 *
 *  3. **File IS the shared state across instances.** Two
 *     `FileSecretStore` instances pointing at the same path see each
 *     other's writes through the on-disk file. Pin against drift to
 *     in-memory caching that would silently break cross-process
 *     semantics (CLI + Desktop both read the same `secrets.properties`).
 *
 * Plus pins:
 *   - `keys()` reflects all set keys; reflects post-remove deletion.
 *   - Empty value (`""`) is valid and round-trips as empty (NOT null —
 *     "key is set with empty value" is a distinct state from "key
 *     unset").
 *   - `remove` on a non-existent key is a silent no-op (Properties
 *     contract; the file is rewritten but the operation doesn't
 *     throw).
 *
 * Isolation: each test uses a per-test `tmpDir` so the user's real
 * `~/.talevia/secrets.properties` is never touched.
 */
class FileSecretStoreTest {

    private val tmpDir = Files.createTempDirectory("desktop-file-secret-store-").toFile()

    @AfterTest fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun store(): FileSecretStore {
        // Custom path inside the tmp dir so we don't pollute the user's
        // real ~/.talevia/secrets.properties.
        val path = java.nio.file.Path.of(tmpDir.absolutePath, "secrets.properties")
        return FileSecretStore(path = path)
    }

    @Test fun getReturnsNullWhenFileDoesNotExist() = runBlocking {
        val s = store()
        assertNull(s.get("any-key"), "fresh store has no values")
    }

    @Test fun keysReturnsEmptySetWhenFileDoesNotExist() = runBlocking {
        val s = store()
        assertEquals(emptySet(), s.keys(), "fresh store has no keys")
    }

    @Test fun putThenGetRoundTripsValue() = runBlocking {
        val s = store()
        s.put("openai", "sk-test-123")
        assertEquals("sk-test-123", s.get("openai"))
    }

    @Test fun putCreatesParentDirectoryIfMissing() = runBlocking {
        // Marquee first-time-use pin: the default path is
        // ~/.talevia/secrets.properties; ~/.talevia may not exist on
        // first launch. Drift to skipping `Files.createDirectories
        // (path.parent)` would break first-time-use silently —
        // FileNotFoundException buried under the SecretStore
        // abstraction.
        val deepPath = java.nio.file.Path.of(
            tmpDir.absolutePath,
            "deep",
            "nested",
            "secrets.properties",
        )
        val s = FileSecretStore(path = deepPath)
        s.put("k", "v")
        assertTrue(Files.exists(deepPath), "parent dirs auto-created")
        assertEquals("v", s.get("k"))
    }

    @Test fun putOverwritesExistingValue() = runBlocking {
        val s = store()
        s.put("k", "v1")
        s.put("k", "v2")
        assertEquals("v2", s.get("k"), "second put overwrites first")
    }

    @Test fun removeDeletesValue() = runBlocking {
        val s = store()
        s.put("k", "v")
        s.remove("k")
        assertNull(s.get("k"), "post-remove key returns null")
    }

    @Test fun removeNonExistentKeyIsNoOp() = runBlocking {
        // Properties.remove on absent key returns null (no-op); the
        // file is still rewritten harmlessly. Pin: no exception.
        val s = store()
        s.remove("never-set") // should not throw
        assertNull(s.get("never-set"))
    }

    @Test fun keysReflectsAllSetKeys() = runBlocking {
        val s = store()
        s.put("a", "1")
        s.put("b", "2")
        s.put("c", "3")
        assertEquals(setOf("a", "b", "c"), s.keys())
    }

    @Test fun keysAfterRemoveOmitsRemoved() = runBlocking {
        val s = store()
        s.put("a", "1")
        s.put("b", "2")
        s.remove("a")
        assertEquals(setOf("b"), s.keys())
    }

    @Test fun multipleStoresShareUnderlyingFile() = runBlocking {
        // Marquee shared-file pin: two FileSecretStore instances
        // pointing at the same path see each other's writes via disk.
        // Drift to in-memory caching would silently break cross-
        // process semantics (CLI + Desktop both reading
        // ~/.talevia/secrets.properties from disk on every call).
        val path = java.nio.file.Path.of(tmpDir.absolutePath, "shared.properties")
        val a = FileSecretStore(path = path)
        val b = FileSecretStore(path = path)
        a.put("k", "from-a")
        assertEquals("from-a", b.get("k"), "second store reads first store's writes from disk")
    }

    @Test fun emptyValueIsValidAndRoundTripsAsEmpty() = runBlocking {
        // Pin: empty string ≠ null. Properties supports empty values
        // ("the key IS set, value is empty"). Drift to "treat empty as
        // unset" would conflate two semantically-distinct states.
        val s = store()
        s.put("k", "")
        assertEquals("", s.get("k"))
        assertTrue("k" in s.keys())
    }
}
