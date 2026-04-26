package io.talevia.core.provider.openai.codex

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOpenAiCodexCredentialStoreTest {

    private val tmpDir = Files.createTempDirectory("codex-store-test")
    private val tmpFile = tmpDir.resolve("auth.json")

    @AfterTest
    fun cleanup() {
        runCatching { Files.deleteIfExists(tmpFile) }
        runCatching { Files.deleteIfExists(tmpDir) }
    }

    @Test
    fun roundTripsCredentials() = runTest {
        val store = FileOpenAiCodexCredentialStore(path = tmpFile)
        assertNull(store.load(), "fresh store must report no credentials")

        val creds = OpenAiCodexCredentials(
            accessToken = "access-jwt",
            refreshToken = "refresh-opaque",
            idToken = "id-jwt",
            accountId = "acct-test",
            lastRefreshEpochMs = 1_735_689_600_000,
        )
        store.save(creds)
        assertEquals(creds, store.load())
    }

    @Test
    fun setsRestrictedPermissions() = runTest {
        val store = FileOpenAiCodexCredentialStore(path = tmpFile)
        store.save(
            OpenAiCodexCredentials(
                accessToken = "a",
                refreshToken = "r",
                idToken = "i",
                accountId = "acct",
                lastRefreshEpochMs = 0,
            ),
        )

        // Skip on filesystems without POSIX permission support (e.g. Windows).
        val view = runCatching { Files.getPosixFilePermissions(tmpFile) }.getOrNull() ?: return@runTest
        assertEquals(PosixFilePermissions.fromString("rw-------"), view)
    }

    @Test
    fun clearRemovesFile() = runTest {
        val store = FileOpenAiCodexCredentialStore(path = tmpFile)
        store.save(
            OpenAiCodexCredentials(
                accessToken = "a",
                refreshToken = "r",
                idToken = "i",
                accountId = "acct",
                lastRefreshEpochMs = 0,
            ),
        )
        assertTrue(tmpFile.exists())
        store.clear()
        assertFalse(tmpFile.exists())
        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullForCorruptFile() = runTest {
        Files.writeString(tmpFile, "{not-json")
        val store = FileOpenAiCodexCredentialStore(path = tmpFile)
        assertNull(store.load())
    }
}
