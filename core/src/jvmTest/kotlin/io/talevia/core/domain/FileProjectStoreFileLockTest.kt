package io.talevia.core.domain

import io.talevia.core.platform.BundleLocker
import io.talevia.core.platform.JvmBundleLocker
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-process write-lock contract for [FileProjectStore].
 *
 * We can't easily fork a second JVM inside a unit test, but
 * [java.nio.channels.FileChannel.tryLock] throws
 * [java.nio.channels.OverlappingFileLockException] on a second same-JVM
 * acquire of the same file — [JvmBundleLocker] catches that and returns
 * `null`, which matches the cross-process "another process holds the
 * lock" outcome. So two locker instances in one JVM exercise the same
 * code path a second Talevia process would.
 */
class FileProjectStoreFileLockTest {

    private fun storeWithLocker(locker: BundleLocker): Pair<FileProjectStore, okio.Path> {
        val tmpHome = createTempDirectory("filelock-home")
        val registry = RecentsRegistry(tmpHome.resolve("recents.json").toString().toPath())
        val store = FileProjectStore(
            registry = registry,
            defaultProjectsHome = tmpHome.toString().toPath(),
            locker = locker,
        )
        return store to tmpHome.resolve("p").toString().toPath()
    }

    @Test fun secondLockerFailsLoudWhenFirstHoldsLock() = runTest {
        val locker1 = JvmBundleLocker()
        val locker2 = JvmBundleLocker()
        val (store1, bundlePath) = storeWithLocker(locker1)
        // Seed the bundle via store1 (this acquires + releases the lock once).
        val pid = store1.createAt(path = bundlePath, title = "demo").id

        // Hold the lock from locker1 directly — simulates "process 1 is
        // mid-write".
        val lockFile = bundlePath.resolve(".talevia-cache").resolve(".lock")
        val held = locker1.tryAcquire(lockFile)
        assertTrue(held != null, "first acquire must succeed")

        // Second store (different locker instance = different channel in
        // this JVM = same code path as another process in production).
        val registry2 = RecentsRegistry(
            createTempDirectory("filelock-home2").resolve("recents.json").toString().toPath(),
        )
        val store2 = FileProjectStore(
            registry = registry2,
            defaultProjectsHome = bundlePath.parent!!,
            locker = locker2,
        )
        // Point store2's registry at the existing bundle so openAt / mutate can find it.
        store2.openAt(bundlePath)

        val ex = assertFailsWith<IllegalStateException> {
            store2.mutate(pid) { it }
        }
        assertTrue(
            ex.message!!.contains("locked by another Talevia process"),
            "expected fail-loud; got: ${ex.message}",
        )

        // Release: second mutate should now succeed.
        held.release()
        val mutated = store2.mutate(pid) { it }
        assertEquals(pid, mutated.id)
    }

    @Test fun noopLockerNeverFailsEvenUnderContention() = runTest {
        // The default [BundleLocker.Noop] keeps iOS / Android / tests
        // from paying the cross-process tax. Verify it never surfaces
        // "locked by another process" even when an external RAF holds
        // a real OS lock on the sidecar.
        val (store, bundlePath) = storeWithLocker(BundleLocker.Noop)
        val pid = store.createAt(path = bundlePath, title = "demo").id

        val lockFile = bundlePath.resolve(".talevia-cache").resolve(".lock")
        val raf = RandomAccessFile(lockFile.toString(), "rw")
        val externalLock = raf.channel.tryLock()
        try {
            // Noop locker ignores the externally-held lock; mutate succeeds.
            store.mutate(pid) { it }
        } finally {
            externalLock?.release()
            raf.close()
        }
    }

    @Test fun lockReleasedAfterSuccessfulWrite() = runTest {
        // A single-threaded sequence of mutations must not leak the lock
        // between calls — each write releases on exit, so the next write
        // re-acquires cleanly.
        val locker = JvmBundleLocker()
        val (store, bundlePath) = storeWithLocker(locker)
        val pid = store.createAt(path = bundlePath, title = "demo").id
        repeat(5) {
            store.mutate(pid) { it }
        }
    }

    @Test fun lockReleasedEvenWhenMutateBlockThrows() = runTest {
        // If a tool's mutation block throws, the lock must still release —
        // otherwise a subsequent mutate would fail-loud with a stale-lock
        // error that the user can't resolve short of restarting the app.
        val locker = JvmBundleLocker()
        val (store, bundlePath) = storeWithLocker(locker)
        val pid = store.createAt(path = bundlePath, title = "demo").id
        assertFailsWith<RuntimeException> {
            store.mutate(pid) { error("tool-level failure") }
        }
        // Next mutate must succeed — previous lock handle was released in
        // the finally block, so we can acquire again.
        store.mutate(pid) { it }
    }
}
