package io.talevia.core.domain

import io.talevia.core.bus.EventBus
import kotlinx.datetime.Clock
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Helper for tests that just need a working [ProjectStore] fixture without
 * having to stand up a real filesystem. Returns a [FileProjectStore] wired
 * over an Okio [FakeFileSystem], so tests are sandboxed and don't leak state
 * across runs. Tests asserting raw catalog state should use `store.summary(id)`
 * / `store.listSummaries()`.
 */
object ProjectStoreTestKit {

    /**
     * Build a fresh in-memory [FileProjectStore]. The default-projects-home
     * is `/projects` on the fake filesystem; pass an explicit path to
     * `createAt` if a test wants a known location.
     */
    fun create(
        bus: EventBus? = null,
        clock: Clock = Clock.System,
    ): FileProjectStore {
        val fs = FakeFileSystem(clock = clock)
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        return FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
            clock = clock,
            bus = bus,
        )
    }

    /**
     * Like [create] but also returns the underlying [FakeFileSystem] for
     * tests that want to assert about on-disk artefacts (talevia.json
     * present, .gitignore content, media/ dir populated, etc.).
     */
    fun createWithFs(
        bus: EventBus? = null,
        clock: Clock = Clock.System,
    ): Pair<FileProjectStore, FakeFileSystem> {
        val fs = FakeFileSystem(clock = clock)
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        val store = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
            clock = clock,
            bus = bus,
        )
        return store to fs
    }
}
