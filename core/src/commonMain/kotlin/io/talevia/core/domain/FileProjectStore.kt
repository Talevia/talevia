package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import io.talevia.core.platform.BundleLocker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * On-disk shape (the "project bundle"):
 * ```
 * <bundleRoot>/
 *   talevia.json              # StoredProject envelope: title + slim Project
 *   .gitignore                # ".talevia-cache/\n"
 *   media/                    # bundle-local assets — AIGC products + small imports
 *   source-history/           # per-node body audit JSONL (cycle-45)
 *   .talevia-cache/           # machine-local — gitignored
 *     clip-render-cache.json  # ClipRenderCache (mezzanine paths are local)
 * ```
 *
 * The bundle is a directory the user owns. Talevia writes only `talevia.json`,
 * `.gitignore`, `media/`, `source-history/`, and `.talevia-cache/`; users may
 * add their own files (e.g. README.md). [delete] with `deleteFiles = true`
 * removes only the Talevia-owned files and rmdirs the directory only if empty
 * afterwards.
 *
 * Identity: each bundle has a stable [ProjectId] inside its `talevia.json`.
 * Path is per-machine (registered in the [RecentsRegistry]). Cloning the
 * bundle to another machine preserves the id; that machine's registry will
 * pick up the new path on first [openAt].
 *
 * Concurrency: a process-level [Mutex] serialises all writes within a
 * single process. A [BundleLocker] layered on top extends that guarantee
 * *across* processes — every write path (`createAt` / `upsert` / `setTitle` /
 * `mutate` / `delete`) acquires an OS file lock on `<bundle>/.talevia-cache/.lock`
 * before touching `talevia.json`, so two concurrent Talevia processes on
 * the same bundle can't lose a write. Lock acquisition failure is fail-loud:
 * the second process sees `IllegalStateException("Bundle is locked by another
 * Talevia process")` rather than silently racing. Default [BundleLocker.Noop]
 * keeps this store usable in single-process mobile + test rigs.
 *
 * Code layout: this class is the `ProjectStore` facade. Internal I/O helpers
 * live in sibling files — envelope read/write in `FileProjectStoreEnvelopeIO.kt`,
 * source-history persistence in `FileProjectStoreSourceHistoryIO.kt`. Split
 * landed cycle-49 per `debt-split-file-project-store` decision (per-
 * persistence-role axis).
 */
class FileProjectStore(
    private val registry: RecentsRegistry,
    /**
     * Where to put new projects when [createAt] / [upsert] is called without a
     * pre-registered path. Bundles land at `<defaultProjectsHome>/<projectId>/`.
     */
    private val defaultProjectsHome: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val clock: Clock = Clock.System,
    private val json: Json = JsonConfig.prettyPrint,
    /**
     * Optional event bus. When provided, every successful [get] runs a
     * lightweight source-DAG validation and publishes
     * [BusEvent.ProjectValidationWarning] if any issues surface. Null keeps
     * the store usable in pure-persistence test rigs.
     */
    private val bus: EventBus? = null,
    /**
     * Cross-process write lock. [BundleLocker.Noop] disables the check (used
     * on iOS / Android / tests that don't exercise concurrency). JVM apps
     * (CLI / Desktop / Server) inject `JvmBundleLocker` so two Talevia
     * processes on one machine can't silently stomp each other's writes.
     */
    private val locker: BundleLocker = BundleLocker.Noop,
) : ProjectStore {

    private val logger = Loggers.get("domain.FileProjectStore")
    private val mutex = Mutex()

    override suspend fun openAt(path: Path): Project = mutex.withLock {
        val resolved = resolveBundlePath(path)
        val project = readBundle(fs, resolved, json)
        val stored = runCatching { decodeStored(fs, resolved, json) }.getOrNull()
        registry.upsert(
            id = project.id,
            path = resolved,
            title = stored?.title ?: project.id.value,
            lastOpenedAtEpochMs = clock.now().toEpochMilliseconds(),
            createdAtEpochMs = stored?.createdAtEpochMs?.takeIf { it > 0L } ?: 0L,
        )
        maybeEmitValidationWarning(project)
        maybeEmitMissingAssets(project)
        project
    }

    /**
     * Auto-promote `/foo/demo` to `/foo/demo.talevia` when the former has no
     * `talevia.json` but the latter does. Supports the macOS Launch Services
     * flow (Finder treats `demo.talevia` directories as packages via the
     * `io.talevia.project` UTI declared in `apps/desktop`'s `Info.plist`) —
     * users / scripts that type the bare name don't have to remember the
     * extension. Bundle directories without the `.talevia` suffix still work
     * too (the original layout), so nothing regresses; the `.talevia`
     * convention is opt-in at create time.
     */
    private fun resolveBundlePath(path: Path): Path {
        if (fs.exists(path.resolve(TALEVIA_JSON))) return path
        if (!path.name.endsWith(".$BUNDLE_EXTENSION", ignoreCase = true)) {
            val candidate = path.parent?.resolve("${path.name}.$BUNDLE_EXTENSION")
                ?: "${path}.$BUNDLE_EXTENSION".toPath()
            if (fs.exists(candidate.resolve(TALEVIA_JSON))) return candidate
        }
        return path
    }

    override suspend fun createAt(
        path: Path,
        title: String,
        timeline: Timeline,
        outputProfile: OutputProfile,
    ): Project = mutex.withLock {
        val taleviaJson = path.resolve(TALEVIA_JSON)
        require(!fs.exists(taleviaJson)) { "Project already exists at $path" }
        withBundleLock(path) {
            val now = clock.now().toEpochMilliseconds()
            val newId = ProjectId(generateProjectId())
            val project = Project(
                id = newId,
                timeline = timeline,
                outputProfile = outputProfile,
            )
            // Recency stamp the brand-new project so per-clip/track/asset stamps land at `now`.
            val stamped = ProjectRecencyStamper.stamp(project, prior = null, now = now)
            writeBundleLocked(fs, path, title, stamped, createdAtEpochMs = now, json = json)
            registry.upsert(newId, path, title, lastOpenedAtEpochMs = now, createdAtEpochMs = now)
            stamped
        }
    }

    override suspend fun get(id: ProjectId): Project? = mutex.withLock {
        val entry = registry.get(id) ?: return null
        val path = entry.path.toPath()
        if (!fs.exists(path.resolve(TALEVIA_JSON))) {
            return null
        }
        val project = readBundle(fs, path, json)
        maybeEmitValidationWarning(project)
        maybeEmitMissingAssets(project)
        project
    }

    override suspend fun list(): List<Project> = registry.list().mapNotNull { entry ->
        val path = entry.path.toPath()
        runCatching { mutex.withLock { readBundle(fs, path, json) } }.getOrNull()
    }

    override suspend fun upsert(title: String, project: Project): Unit = mutex.withLock {
        val path = registry.get(project.id)?.path?.toPath()
            ?: defaultProjectsHome.resolve(project.id.value.requireSafeFilename())

        withBundleLock(path) {
            val existingStored = if (fs.exists(path.resolve(TALEVIA_JSON))) {
                runCatching { decodeStored(fs, path, json) }.getOrNull()
            } else {
                null
            }
            val now = clock.now().toEpochMilliseconds()
            val stamped = ProjectRecencyStamper.stamp(project, existingStored?.project, now)
            // Preserve original createdAtEpochMs across overwrites; first write
            // stamps it. A read of a pre-field bundle (createdAt == 0) heals on
            // first upsert by adopting `now` rather than perpetuating the zero.
            val createdAt = existingStored?.createdAtEpochMs?.takeIf { it > 0L } ?: now
            writeBundleLocked(fs, path, title, stamped, createdAtEpochMs = createdAt, json = json)
            registry.upsert(project.id, path, title, lastOpenedAtEpochMs = now, createdAtEpochMs = createdAt)
        }
    }

    override suspend fun setTitle(id: ProjectId, title: String): Unit = mutex.withLock {
        val entry = registry.get(id) ?: error("Project ${id.value} does not exist")
        val path = entry.path.toPath()
        require(fs.exists(path.resolve(TALEVIA_JSON))) { "Project ${id.value} bundle missing at $path" }
        withBundleLock(path) {
            // Read envelope, rewrite with new title — project body + createdAt untouched.
            val stored = decodeStored(fs, path, json)
            val now = clock.now().toEpochMilliseconds()
            writeStoredEnvelope(fs, path, stored.copy(title = title), json)
            registry.setTitle(id, title, updatedAtEpochMs = now)
        }
    }

    private fun resolvedCreatedAt(stored: StoredProject, fallback: Long): Long =
        stored.createdAtEpochMs.takeIf { it > 0L } ?: fallback

    override suspend fun summary(id: ProjectId): ProjectSummary? = mutex.withLock {
        val entry = registry.get(id) ?: return null
        val path = entry.path.toPath()
        val taleviaJson = path.resolve(TALEVIA_JSON)
        if (!fs.exists(taleviaJson)) return null
        // Prefer the cached `entry.createdAtEpochMs` (populated by
        // `openAt` / `createAt` / `upsert`) so we can skip the
        // `decodeStored` JSON read entirely when the registry has the
        // field. Only decode when the cache is zero (legacy recents.json
        // schema from before this field existed, or a first-open via
        // `get`/`list` that didn't thread it through).
        if (entry.createdAtEpochMs > 0L) {
            val updated = bundleTimestamps(fs, taleviaJson, fallback = entry.lastOpenedAtEpochMs).second
            return ProjectSummary(
                id = id.value,
                title = entry.title,
                createdAtEpochMs = entry.createdAtEpochMs,
                updatedAtEpochMs = entry.lastOpenedAtEpochMs.takeIf { it > 0L } ?: updated,
            )
        }
        val stored = decodeStored(fs, path, json)
        val (createdFromFs, updated) = bundleTimestamps(fs, taleviaJson, fallback = entry.lastOpenedAtEpochMs)
        ProjectSummary(
            id = id.value,
            title = stored.title,
            createdAtEpochMs = resolvedCreatedAt(stored, createdFromFs),
            updatedAtEpochMs = entry.lastOpenedAtEpochMs.takeIf { it > 0L } ?: updated,
        )
    }

    override suspend fun listSummaries(): List<ProjectSummary> = registry.list().mapNotNull { entry ->
        val path = entry.path.toPath()
        val taleviaJson = path.resolve(TALEVIA_JSON)
        if (!fs.exists(taleviaJson)) return@mapNotNull null
        // Same cache-first fast path as `summary(...)` above. Registry
        // entries created before this field existed fall through to the
        // decode-from-bundle path, and heal on the next `upsert`.
        if (entry.createdAtEpochMs > 0L) {
            val updated = bundleTimestamps(fs, taleviaJson, fallback = entry.lastOpenedAtEpochMs).second
            return@mapNotNull ProjectSummary(
                id = entry.id,
                title = entry.title,
                createdAtEpochMs = entry.createdAtEpochMs,
                updatedAtEpochMs = entry.lastOpenedAtEpochMs.takeIf { it > 0L } ?: updated,
            )
        }
        val stored = runCatching { mutex.withLock { decodeStored(fs, path, json) } }.getOrNull() ?: return@mapNotNull null
        val (createdFromFs, updated) = bundleTimestamps(fs, taleviaJson, fallback = entry.lastOpenedAtEpochMs)
        ProjectSummary(
            id = entry.id,
            title = stored.title,
            createdAtEpochMs = resolvedCreatedAt(stored, createdFromFs),
            updatedAtEpochMs = entry.lastOpenedAtEpochMs.takeIf { it > 0L } ?: updated,
        )
    }.sortedByDescending { it.updatedAtEpochMs }

    override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project = mutex.withLock {
        val entry = registry.get(id) ?: error("Project ${id.value} does not exist")
        val path = entry.path.toPath()
        require(fs.exists(path.resolve(TALEVIA_JSON))) { "Project ${id.value} bundle missing at $path" }
        withBundleLock(path) {
            val stored = decodeStored(fs, path, json)
            val current = readBundle(fs, path, json)
            val updated = block(current)
            val now = clock.now().toEpochMilliseconds()
            val createdAt = resolvedCreatedAt(stored, fallback = now)
            val stamped = ProjectRecencyStamper.stamp(updated, prior = current, now = now)
            writeBundleLocked(fs, path, stored.title, stamped, createdAtEpochMs = createdAt, json = json)
            registry.upsert(id, path, stored.title, lastOpenedAtEpochMs = now)
            stamped
        }
    }

    override suspend fun delete(id: ProjectId, deleteFiles: Boolean): Unit = mutex.withLock {
        val entry = registry.get(id)
        registry.remove(id)
        if (!deleteFiles || entry == null) return@withLock

        val root = entry.path.toPath()
        if (!fs.exists(root)) return@withLock

        withBundleLock(root) {
            // Remove only Talevia-owned content. If the user dropped a README.md
            // inside the bundle, leave it alone (the directory survives).
            val taleviaJson = root.resolve(TALEVIA_JSON)
            if (fs.exists(taleviaJson)) fs.delete(taleviaJson)

            val gitignore = root.resolve(GITIGNORE)
            if (fs.exists(gitignore) && fs.read(gitignore) { readUtf8() }.trim() == AUTO_GITIGNORE_BODY.trim()) {
                fs.delete(gitignore)
            }

            val mediaDir = root.resolve(MEDIA_DIR)
            if (fs.exists(mediaDir)) fs.deleteRecursively(mediaDir)

            val historyDir = root.resolve(SOURCE_HISTORY_DIR)
            if (fs.exists(historyDir)) fs.deleteRecursively(historyDir)
        }
        // Delete the cache dir (including the lock sidecar) outside the lock
        // scope — the handle was released on exit, and a concurrent acquire
        // racing us is fine: we already unlinked talevia.json so the bundle
        // is functionally gone.
        val cacheDir = root.resolve(CACHE_DIR)
        if (fs.exists(cacheDir)) fs.deleteRecursively(cacheDir)

        // Empty? Remove the directory. Otherwise leave it for the user.
        if (fs.list(root).isEmpty()) {
            try {
                fs.delete(root)
            } catch (_: IOException) {
                // Race / OS quirk; non-fatal.
            }
        }
    }

    override suspend fun pathOf(id: ProjectId): Path? = registry.get(id)?.path?.toPath()

    override suspend fun appendSourceNodeHistory(
        id: ProjectId,
        nodeId: io.talevia.core.SourceNodeId,
        revision: io.talevia.core.domain.source.BodyRevision,
    ) {
        // Best-effort: filesystem failure must not block the enclosing
        // update_source_node_body dispatch. History is an audit log; a
        // dropped entry is a soft loss, a dropped body update is hard loss.
        runCatching {
            mutex.withLock {
                val path = registry.get(id)?.path?.toPath() ?: return@withLock
                appendSourceNodeHistoryFile(fs, path, nodeId, revision)
            }
        }
    }

    override suspend fun listSourceNodeHistory(
        id: ProjectId,
        nodeId: io.talevia.core.SourceNodeId,
        limit: Int,
    ): List<io.talevia.core.domain.source.BodyRevision> = mutex.withLock {
        val path = registry.get(id)?.path?.toPath() ?: return@withLock emptyList()
        listSourceNodeHistoryFile(fs, path, nodeId, limit)
    }

    // -- private helpers --

    /**
     * Acquire the OS file lock on `<path>/.talevia-cache/.lock`, run [block],
     * release the lock in a finally. Caller already holds [mutex] so within
     * one process there's only ever one contender for the file lock; the
     * file lock's job is to exclude *other* Talevia processes on the same
     * bundle. Acquisition failure is fail-loud — no retry, no backoff.
     */
    private inline fun <R> withBundleLock(path: Path, block: () -> R): R {
        fs.createDirectories(path.resolve(CACHE_DIR))
        val lockFile = path.resolve(CACHE_DIR).resolve(LOCK_FILE)
        val handle = locker.tryAcquire(lockFile)
            ?: error(
                "Bundle is locked by another Talevia process: $path. " +
                    "Close the other Talevia instance (CLI / Desktop / Server) before retrying.",
            )
        try {
            return block()
        } finally {
            handle.release()
        }
    }

    private suspend fun maybeEmitValidationWarning(project: Project) {
        val issues = ProjectSourceDagValidator.validate(project.source)
        if (issues.isEmpty()) return
        logger.warn(
            "project ${project.id.value} failed source-DAG validation on load",
            "projectId" to project.id.value,
            "issueCount" to issues.size.toString(),
            "firstIssue" to issues.first(),
        )
        bus?.publish(BusEvent.ProjectValidationWarning(project.id, issues))
    }

    /**
     * Scan `project.assets` for `MediaSource.File` paths that don't exist
     * on the current machine. Every cross-machine bundle open flows through
     * `openAt` / `get`; this is where alice's `/Users/alice/raw.mp4` fails
     * to resolve on bob's machine. Publishes one `BusEvent.AssetsMissing`
     * carrying every missing asset so UI / CLI can show a single "relink
     * these before export" panel instead of N independent events.
     *
     * Scope: only `MediaSource.File` (absolute host paths) is checked.
     * `BundleFile` paths resolve inside the bundle (a missing bundle-file
     * is a different failure — bundle corruption). `Http` / `Platform`
     * sources aren't filesystem-checkable. `bus == null` short-circuits
     * so pure-store tests don't pay for the scan.
     */
    private suspend fun maybeEmitMissingAssets(project: Project) {
        val publisher = bus ?: return
        val missing = project.assets.mapNotNull { asset ->
            val src = asset.source
            if (src !is MediaSource.File) return@mapNotNull null
            if (fs.exists(src.path.toPath())) return@mapNotNull null
            BusEvent.MissingAsset(assetId = asset.id.value, originalPath = src.path)
        }
        if (missing.isEmpty()) return
        logger.warn(
            "project ${project.id.value} has ${missing.size} missing file-asset(s) on open",
            "projectId" to project.id.value,
            "missingCount" to missing.size.toString(),
            "firstAsset" to missing.first().assetId,
        )
        publisher.publish(BusEvent.AssetsMissing(project.id, missing))
    }

    /** Bundle id-as-default-dir-name only — accepts arbitrary user-picked paths. */
    private fun String.requireSafeFilename(): String {
        require(matches(SAFE_ID)) {
            "Project id '$this' contains characters unsafe for a default directory name; " +
                "pass an explicit path to createAt instead."
        }
        return this
    }

    companion object {
        const val TALEVIA_JSON = "talevia.json"
        const val GITIGNORE = ".gitignore"
        const val MEDIA_DIR = "media"
        /**
         * macOS package-bundle extension. Directories whose names end in
         * `.talevia` are declared as `io.talevia.project` packages via the
         * desktop app's `Info.plist`, so Finder launches Talevia on double-
         * click instead of expanding them like plain directories. Bundle
         * directories without this suffix still work — the convention is
         * opt-in at `createAt` time; [openAt] auto-promotes bare names to
         * the suffixed variant when the bare path has no `talevia.json`.
         */
        const val BUNDLE_EXTENSION = "talevia"
        const val CACHE_DIR = ".talevia-cache"
        const val CLIP_RENDER_CACHE_FILE = "clip-render-cache.json"
        const val LOCK_FILE = ".lock"
        const val AUTO_GITIGNORE_BODY = ".talevia-cache/\n"
        /**
         * Per-source-node body history — one JSONL file per node, append-only,
         * newest-last. Lives inside the bundle (NOT gitignored) so history
         * travels with `git push`. Read by
         * [listSourceNodeHistory], written by [appendSourceNodeHistory]; the
         * agent surfaces it via `source_query(select=history)`.
         */
        const val SOURCE_HISTORY_DIR = "source-history"
        private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,200}")

        @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
        private fun generateProjectId(): String = kotlin.uuid.Uuid.random().toString()
    }
}
