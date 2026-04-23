package io.talevia.platform.ffmpeg

import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.RecentsRegistry
import kotlinx.datetime.Clock
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Local in-memory [FileProjectStore] factory for FFmpeg E2E tests — the
 * FFmpeg test module depends on `:core` main only, so it can't see the
 * commonTest-scoped `core.domain.ProjectStoreTestKit`. Duplicating the ~15
 * lines here is lighter than exposing core's jvmTest classes as a fixture
 * configuration. Matches the core helper's behaviour (in-memory
 * FakeFileSystem, recents registry under `/.talevia/recents.json`).
 */
internal object ProjectStoreTestKit {
    fun create(clock: Clock = Clock.System): FileProjectStore {
        val fs = FakeFileSystem(clock = clock)
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        return FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
            clock = clock,
            bus = null,
        )
    }
}
