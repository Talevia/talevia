package io.talevia.core.platform

import okio.Path

/**
 * Cross-process exclusive lock on a project bundle.
 *
 * The in-process [kotlinx.coroutines.sync.Mutex] inside `FileProjectStore`
 * serialises writes from one process but can't see writes from a concurrent
 * Talevia process (CLI + Desktop + Server may all run against the same
 * `~/.talevia/projects/<id>/` bundle on a developer's machine).
 * [BundleLocker.tryAcquire] asks the OS for an exclusive lock on a sidecar
 * file inside `<bundle>/.talevia-cache/` (always gitignored, machine-local);
 * a non-null [Handle] means we hold it and every other Talevia process will
 * see `tryAcquire` fail until [Handle.release] runs.
 *
 * Returning `null` is the "fail-loud" signal — caller throws so the user
 * sees "another Talevia process holds this bundle" rather than silently
 * losing a write.
 *
 * Default ([Noop]) acquires nothing and never blocks; used on platforms
 * where single-process is the norm (Android app sandbox, iOS app sandbox)
 * and in pure-store tests that don't need concurrency semantics. JVM
 * containers (CLI / Desktop / Server) inject `JvmBundleLocker` from
 * `core/src/jvmMain` which uses `FileChannel.tryLock`.
 */
interface BundleLocker {

    /**
     * @return non-null [Handle] if we now hold the lock; null if another
     * process already holds it.
     */
    fun tryAcquire(lockFile: Path): Handle?

    interface Handle {
        fun release()
    }

    /** No-op default. Every [tryAcquire] succeeds; [release] is a no-op. */
    object Noop : BundleLocker {
        private val NOOP_HANDLE = object : Handle {
            override fun release() {}
        }

        override fun tryAcquire(lockFile: Path): Handle = NOOP_HANDLE
    }
}
