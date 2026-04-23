package io.talevia.core.platform

import okio.Path
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * JVM [BundleLocker] backed by [java.nio.channels.FileChannel.tryLock].
 *
 * Lock semantics:
 * - Per-process: the JVM keeps a lock on a channel, so a second
 *   `tryAcquire` within the same process on the same file throws
 *   [OverlappingFileLockException]. We catch that and treat it as "already
 *   held" → null, mirroring cross-process semantics.
 * - Cross-process: `FileChannel.tryLock()` maps to advisory `flock` on
 *   POSIX and `LockFileEx` on Windows. A second JVM process calling
 *   `tryLock` on the same file returns null → we return null → caller
 *   fails loud.
 * - On crash: OS releases the lock when the process exits; the `.lock`
 *   file itself persists (gitignored via `.talevia-cache/`) but poses no
 *   hazard — next acquire succeeds on whichever process arrives first.
 *
 * The sidecar file path is chosen by the caller ([FileProjectStore])
 * inside the bundle's `.talevia-cache/` directory.
 */
class JvmBundleLocker : BundleLocker {

    override fun tryAcquire(lockFile: Path): BundleLocker.Handle? {
        val raf = RandomAccessFile(lockFile.toString(), "rw")
        val lock: FileLock? = try {
            raf.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
        if (lock == null) {
            raf.close()
            return null
        }
        return object : BundleLocker.Handle {
            override fun release() {
                try {
                    lock.release()
                } finally {
                    raf.close()
                }
            }
        }
    }
}
