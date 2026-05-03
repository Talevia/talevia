package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.logging.LogLevel
import io.talevia.core.logging.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the two best-effort load-time audits in
 * `core/domain/FileProjectStoreValidation.kt`:
 * [emitValidationWarningIfAny] and [emitMissingAssetsIfAny].
 * Both fire on every `FileProjectStore.openAt` /
 * `FileProjectStore.get` so a UI / CLI consumer can show the
 * "your bundle has issues" panel without running an explicit
 * validate command. Cycle 145 audit: 78 LOC, 0 transitive
 * test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Both functions are best-effort: `bus = null` short-
 *    circuits cleanly without NPE.** Per kdoc: "Both
 *    operations are best-effort and short-circuit fast when
 *    no event bus is wired (pure-persistence test rigs)."
 *    A regression that NPE'd here would crash every test
 *    rig that doesn't wire a bus — invisible until someone
 *    runs the production path.
 *
 * 2. **Validation: zero issues → ZERO side effects (no log,
 *    no event); ≥1 issue → log warn AND publish
 *    `ProjectValidationWarning`.** Both side effects must
 *    fire together — the log alone strands UI subscribers,
 *    the event alone strands operators reading CLI logs.
 *
 * 3. **Missing-assets: only `MediaSource.File` paths are
 *    filesystem-checked.** Per kdoc: "Only [MediaSource.File]
 *    (absolute host paths) is checked. [MediaSource.BundleFile]
 *    paths resolve inside the bundle … [MediaSource.Http] /
 *    [MediaSource.Platform] sources aren't filesystem-
 *    checkable." Drift to checking BundleFile would error
 *    on every successfully-loaded bundle (those paths are
 *    bundle-relative, not host-absolute).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileProjectStoreValidationTest {

    /** Captures every log invocation for assertion. */
    private class CapturingLogger : Logger {
        val entries: MutableList<Triple<LogLevel, String, Map<String, Any?>>> = mutableListOf()
        override fun log(
            level: LogLevel,
            message: String,
            fields: Map<String, Any?>,
            cause: Throwable?,
        ) {
            entries += Triple(level, message, fields)
        }
    }

    private fun project(
        id: String = "p1",
        source: Source = Source.EMPTY,
        assets: List<MediaAsset> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = Timeline(),
        source = source,
        assets = assets,
    )

    private fun fileAsset(id: String, path: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File(path),
        metadata = MediaMetadata(duration = kotlin.time.Duration.ZERO),
    )

    private fun bundleAsset(id: String, relativePath: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.BundleFile(relativePath),
        metadata = MediaMetadata(duration = kotlin.time.Duration.ZERO),
    )

    private fun httpAsset(id: String, url: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.Http(url),
        metadata = MediaMetadata(duration = kotlin.time.Duration.ZERO),
    )

    // ── emitValidationWarningIfAny ────────────────────────────────

    @Test fun emptySourceProducesNoSideEffects() = runTest {
        val logger = CapturingLogger()
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitValidationWarningIfAny(project(), bus, logger)
        advanceUntilIdle()
        yield()

        assertTrue(logger.entries.isEmpty(), "no log on empty source")
        assertTrue(
            collected.none { it is BusEvent.ProjectValidationWarning },
            "no event on empty source",
        )
    }

    @Test fun cleanSourceDagProducesNoSideEffects() = runTest {
        // Pin: source with nodes but no dangling parents / cycles
        // → validator returns []. No log, no event.
        val logger = CapturingLogger()
        val bus = EventBus()
        val nodes = listOf(
            SourceNode.create(SourceNodeId("a"), kind = "k"),
            SourceNode.create(SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a")))),
        )
        emitValidationWarningIfAny(project(source = Source(nodes = nodes)), bus, logger)

        assertTrue(logger.entries.isEmpty(), "no log on clean DAG")
    }

    @Test fun danglingParentTriggersWarningLogAndEvent() = runTest {
        // Marquee dual-side-effect pin: validator reports one
        // issue → BOTH log and event fire.
        val logger = CapturingLogger()
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        // Node with parent that doesn't exist in the DAG.
        val nodes = listOf(
            SourceNode.create(
                id = SourceNodeId("orphan"),
                kind = "k",
                parents = listOf(SourceRef(SourceNodeId("ghost"))),
            ),
        )
        emitValidationWarningIfAny(
            project(id = "p-bad", source = Source(nodes = nodes)),
            bus,
            logger,
        )
        advanceUntilIdle()
        yield()

        // Pin: log warn fires.
        val warns = logger.entries.filter { it.first == LogLevel.WARN }
        assertEquals(1, warns.size, "exactly one warn entry; got: ${logger.entries}")
        val (_, msg, fields) = warns.single()
        assertTrue("p-bad" in msg, "project id in warn message; got: $msg")
        assertEquals("p-bad", fields["projectId"], "projectId field surfaces")
        assertTrue(
            fields["issueCount"].toString() == "1",
            "issueCount = 1 (one dangling); got: $fields",
        )

        // Pin: event publishes too.
        val events = collected.filterIsInstance<BusEvent.ProjectValidationWarning>()
        assertEquals(1, events.size, "exactly one validation event")
        val event = events.single()
        assertEquals(ProjectId("p-bad"), event.projectId)
        assertEquals(1, event.issues.size)
    }

    @Test fun nullBusValidationWarnsLogButEmitsNoEvent() = runTest {
        // Pin: bus=null → no event publish, log still happens.
        // Difference from the missing-assets path which
        // short-circuits ENTIRELY when bus=null.
        val logger = CapturingLogger()
        val nodes = listOf(
            SourceNode.create(
                SourceNodeId("orphan"),
                kind = "k",
                parents = listOf(SourceRef(SourceNodeId("ghost"))),
            ),
        )
        emitValidationWarningIfAny(
            project(source = Source(nodes = nodes)),
            bus = null,
            logger = logger,
        )

        assertEquals(
            1,
            logger.entries.filter { it.first == LogLevel.WARN }.size,
            "log still fires even with null bus",
        )
        // No bus to assert against (null).
    }

    // ── emitMissingAssetsIfAny ────────────────────────────────────

    @Test fun nullBusMissingAssetsShortCircuitsBeforeAnyScan() = runTest {
        // Pin: `val publisher = bus ?: return` — null bus
        // bypasses the entire scan AND the log. Distinct from
        // validation which still logs without bus. Pure-store
        // tests don't pay the I/O for the scan.
        val logger = CapturingLogger()
        val fs = FakeFileSystem()
        val asset = fileAsset("a1", "/nonexistent/path.mp4")
        emitMissingAssetsIfAny(
            project(assets = listOf(asset)),
            bus = null,
            fs = fs,
            logger = logger,
        )

        assertTrue(
            logger.entries.isEmpty(),
            "null bus skips ALL — including the log; got: ${logger.entries}",
        )
    }

    @Test fun emptyAssetsListNoEventNoLog() = runTest {
        val logger = CapturingLogger()
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitMissingAssetsIfAny(project(), bus, FakeFileSystem(), logger)
        advanceUntilIdle()
        yield()

        assertTrue(logger.entries.isEmpty())
        assertTrue(collected.none { it is BusEvent.AssetsMissing })
    }

    @Test fun allFileAssetsPresentNoEventNoLog() = runTest {
        val logger = CapturingLogger()
        val bus = EventBus()
        val fs = FakeFileSystem()
        // Plant the file the asset references.
        val path = "/raw/clip1.mp4".toPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("fake bytes") }

        val asset = fileAsset("a1", "/raw/clip1.mp4")
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitMissingAssetsIfAny(project(assets = listOf(asset)), bus, fs, logger)
        advanceUntilIdle()
        yield()

        assertTrue(logger.entries.isEmpty(), "no log when all present")
        assertTrue(
            collected.none { it is BusEvent.AssetsMissing },
            "no event when all present",
        )
    }

    @Test fun missingFileAssetTriggersWarnLogAndAssetsMissingEvent() = runTest {
        val logger = CapturingLogger()
        val bus = EventBus()
        val fs = FakeFileSystem() // empty, /missing/clip.mp4 does not exist
        val asset = fileAsset("a-missing", "/missing/clip.mp4")
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitMissingAssetsIfAny(
            project(id = "p1", assets = listOf(asset)),
            bus,
            fs,
            logger,
        )
        advanceUntilIdle()
        yield()

        // Pin: log warn fires.
        val warns = logger.entries.filter { it.first == LogLevel.WARN }
        assertEquals(1, warns.size)
        val (_, msg, fields) = warns.single()
        assertTrue("p1" in msg, "project id in msg; got: $msg")
        assertEquals("1", fields["missingCount"].toString())

        // Pin: event publishes.
        val events = collected.filterIsInstance<BusEvent.AssetsMissing>()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ProjectId("p1"), event.projectId)
        assertEquals(1, event.missing.size)
        val missing = event.missing.single()
        assertEquals("a-missing", missing.assetId)
        assertEquals("/missing/clip.mp4", missing.originalPath)
    }

    @Test fun bundleFileSourceIsSkippedNotChecked() = runTest {
        // The marquee scope pin: BundleFile paths are bundle-
        // relative and resolve inside the bundle (different
        // failure mode = corruption). The validator skips
        // them. Drift to checking BundleFile would error on
        // every successfully-loaded bundle (because the
        // bundle-relative path doesn't exist on the test FS
        // root either).
        val logger = CapturingLogger()
        val bus = EventBus()
        val fs = FakeFileSystem()
        val asset = bundleAsset("a-bundle", "media/clip.mp4")
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitMissingAssetsIfAny(
            project(assets = listOf(asset)),
            bus,
            fs,
            logger,
        )
        advanceUntilIdle()
        yield()

        assertTrue(
            collected.none { it is BusEvent.AssetsMissing },
            "BundleFile not flagged as missing; got: $collected",
        )
        assertTrue(
            logger.entries.isEmpty(),
            "no log for BundleFile-only asset list",
        )
    }

    @Test fun httpSourceIsSkippedNotChecked() = runTest {
        // Pin: Http sources can't be filesystem-checked.
        // Drift to flagging them would either (a) error on
        // every cloud-backed asset or (b) try to do a network
        // probe at load time (hard rule violation —
        // `fs.exists` is filesystem only).
        val logger = CapturingLogger()
        val bus = EventBus()
        val fs = FakeFileSystem()
        val asset = httpAsset("a-http", "https://example.com/clip.mp4")
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitMissingAssetsIfAny(
            project(assets = listOf(asset)),
            bus,
            fs,
            logger,
        )
        advanceUntilIdle()
        yield()

        assertTrue(collected.none { it is BusEvent.AssetsMissing })
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun mixedSourcesOnlyMissingFileAssetsReported() = runTest {
        // Pin: walk filters with `if (src !is MediaSource.File) return@mapNotNull null`
        // — only File sources participate. Mix planted
        // present-File + missing-File + BundleFile + Http;
        // event carries ONLY the missing File.
        val logger = CapturingLogger()
        val bus = EventBus()
        val fs = FakeFileSystem()
        // Plant the existing file.
        val present = "/raw/present.mp4".toPath()
        fs.createDirectories(present.parent!!)
        fs.write(present) { writeUtf8("ok") }

        val assets = listOf(
            fileAsset("a-present", "/raw/present.mp4"),
            fileAsset("a-missing", "/raw/missing.mp4"),
            bundleAsset("a-bundle", "media/x.mp4"),
            httpAsset("a-http", "https://example.com/y.mp4"),
        )
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        emitMissingAssetsIfAny(project(assets = assets), bus, fs, logger)
        advanceUntilIdle()
        yield()

        val events = collected.filterIsInstance<BusEvent.AssetsMissing>()
        assertEquals(1, events.size)
        val missing = events.single().missing
        assertEquals(1, missing.size, "only the absent File-source asset")
        assertEquals("a-missing", missing.single().assetId)
    }
}
