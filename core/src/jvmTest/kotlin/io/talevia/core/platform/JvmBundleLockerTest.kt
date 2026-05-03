package io.talevia.core.platform

import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [JvmBundleLocker] — the JVM-side per-bundle file
 * lock backing `FileProjectStore`'s "only one process owns a bundle
 * at a time" invariant. Cycle 86 audit found this class had no
 * direct test (1 transitive reference).
 *
 * The lock semantics matter for cross-process safety: two JVMs
 * (Desktop + Server) can't accidentally write to the same talevia
 * bundle without this lock. A regression that always returns a
 * Handle (forgetting the null path) would silently let concurrent
 * writers corrupt the bundle.
 */
class JvmBundleLockerTest {

    private val tmpDir = Files.createTempDirectory("jvm-bundle-locker-").toFile()

    @AfterTest fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun lockFile(): okio.Path {
        val f = java.io.File(tmpDir, "bundle.lock")
        // The lock file may not exist before tryAcquire; tryAcquire creates
        // it via RandomAccessFile("rw").
        return f.absolutePath.toPath()
    }

    @Test fun acquireOnFreshFileReturnsHandle() {
        val locker = JvmBundleLocker()
        val handle = locker.tryAcquire(lockFile())
        assertNotNull(handle, "fresh lock must succeed")
        handle.release()
    }

    @Test fun concurrentAcquireSameProcessReturnsNullForSecond() {
        // Pin: kdoc commits to "a second `tryAcquire` within the same
        // process on the same file throws OverlappingFileLockException
        // [which we] catch and treat as 'already held' → null".
        val locker = JvmBundleLocker()
        val path = lockFile()
        val first = locker.tryAcquire(path)
        assertNotNull(first, "first acquire succeeds")
        try {
            val second = locker.tryAcquire(path)
            assertNull(second, "concurrent same-process acquire returns null, not Handle")
        } finally {
            first.release()
        }
    }

    @Test fun acquireAgainAfterReleaseSucceeds() {
        // Pin: release() makes the lock available again. The release
        // ordering (lock first, then RAF close) matters; this test
        // verifies the visible behaviour.
        val locker = JvmBundleLocker()
        val path = lockFile()
        val first = locker.tryAcquire(path)
        assertNotNull(first)
        first.release()

        val second = locker.tryAcquire(path)
        assertNotNull(second, "post-release acquire succeeds")
        second.release()
    }

    @Test fun multipleReleaseDoesNotCorruptState() {
        // Defensive contract: calling release() twice on the same handle
        // shouldn't crash. The release() impl uses try/finally, so a
        // double-release exercises the finally-only path on the second
        // call (lock.release() throws but raf.close() still runs).
        val locker = JvmBundleLocker()
        val path = lockFile()
        val first = locker.tryAcquire(path)
        assertNotNull(first)
        first.release()
        // Second release: java.nio.channels.FileLock.release() throws
        // ClosedChannelException; the finally still closes raf. The
        // exception propagates per impl, but the state is recoverable
        // (next tryAcquire works).
        try {
            first.release()
        } catch (_: Throwable) {
            // Acceptable: double-release on FileLock throws. The pin
            // is "next acquire still works", not "double-release is
            // silent".
        }
        val second = locker.tryAcquire(path)
        assertNotNull(second, "double-release didn't break the lock file's recoverability")
        second.release()
    }

    @Test fun differentLockFilesAreIndependent() {
        val locker = JvmBundleLocker()
        val a = locker.tryAcquire(java.io.File(tmpDir, "a.lock").absolutePath.toPath())
        val b = locker.tryAcquire(java.io.File(tmpDir, "b.lock").absolutePath.toPath())
        assertNotNull(a)
        assertNotNull(b, "different lock files don't conflict")
        a.release()
        b.release()
    }

    @Test fun lockFileIsCreatedIfMissing() {
        // The tryAcquire impl uses RandomAccessFile("rw") which creates
        // the file. Pin so a future refactor accidentally requiring an
        // existing file would catch this assertion.
        val path = java.io.File(tmpDir, "nonexistent.lock")
        assertTrue(!path.exists(), "precondition: file does not exist")
        val handle = JvmBundleLocker().tryAcquire(path.absolutePath.toPath())
        assertNotNull(handle, "acquire must auto-create the lock file")
        assertTrue(path.exists(), "lock file is auto-created on tryAcquire")
        handle.release()
    }
}
