package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lightweight catalog row for a [Project] — title + timestamps without forcing the
 * caller to decode the full project JSON. Used by `list_projects` and the project
 * lifecycle tools so an orientation call doesn't deserialize every Source DAG and
 * Timeline in storage.
 */
@Serializable
data class ProjectSummary(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Tools mutate the canonical [Project] (its [Timeline] in particular). The store
 * provides a simple read-modify-write API guarded by an in-process [Mutex] so two
 * concurrent tool dispatches in the same Agent turn cannot interleave.
 */
interface ProjectStore {
    suspend fun get(id: ProjectId): Project?
    suspend fun upsert(title: String, project: Project)
    suspend fun list(): List<Project>

    /**
     * Remove the project from the store. When [deleteFiles] is true the
     * file-backed implementation also removes the on-disk bundle; for the
     * SQL-backed implementation the flag is ignored. Defaults to false so
     * accidentally invoking [delete] never destroys user files.
     */
    suspend fun delete(id: ProjectId, deleteFiles: Boolean = false)

    /**
     * Rename a project — updates only the catalog `title` (and `time_updated`),
     * leaving the [Project] model untouched. Throws if no row exists.
     */
    suspend fun setTitle(id: ProjectId, title: String)

    /** Catalog metadata for a single project, or null if no row exists. */
    suspend fun summary(id: ProjectId): ProjectSummary?

    /** Catalog metadata for every project — cheaper than [list] for orientation. */
    suspend fun listSummaries(): List<ProjectSummary>

    /**
     * Atomic read-mutate-write. Callers return the new [Project]; the store persists
     * it and updates the `time_updated` timestamp.
     */
    suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project

    /**
     * Open an existing project bundle at the given path. Used by file-backed
     * stores; SQL-backed stores throw [UnsupportedOperationException]. The
     * loaded project is registered in the recents registry so subsequent
     * [get] / [list] calls can find it by id.
     */
    suspend fun openAt(path: okio.Path): Project =
        throw UnsupportedOperationException("openAt requires a file-backed ProjectStore")

    /**
     * Create a new project bundle at the given path with the given title.
     * The directory must not already contain a `talevia.json`. Used by
     * file-backed stores; SQL-backed stores throw.
     */
    suspend fun createAt(
        path: okio.Path,
        title: String,
        timeline: Timeline = Timeline(),
        outputProfile: OutputProfile = OutputProfile.DEFAULT_1080P,
    ): Project = throw UnsupportedOperationException("createAt requires a file-backed ProjectStore")

    /**
     * Filesystem path of the bundle for the given project, or null if not
     * currently registered. SQL-backed stores return null.
     */
    suspend fun pathOf(id: ProjectId): okio.Path? = null
}

/**
 * SQLDelight-backed [ProjectStore].
 *
 * Storage shape (schema v3):
 *  - `Projects.data` — JSON of the [Project] with `snapshots`, `lockfile.entries`
 *    and `clipRenderCache.entries` emptied. The "hot path" slice that the agent
 *    re-reads / re-writes on every tool call: timeline, source DAG, assets,
 *    outputProfile, renderCache.
 *  - `ProjectSnapshots` — one row per [ProjectSnapshot], keyed by `(projectId, snapshotId)`.
 *  - `ProjectLockfileEntries` — one row per [LockfileEntry], keyed by `(projectId, ordinal)`.
 *    `ordinal` preserves the append-only order [Lockfile.findByInputHash] relies on
 *    (most-recent match wins).
 *  - `ProjectClipRenderCache` — one row per [ClipRenderCacheEntry], keyed by
 *    `(projectId, fingerprint)`. Append-only semantics but the fingerprint is
 *    already unique per (clip, bound-source hashes, output profile), so the
 *    table is upsert-keyed on fingerprint instead of ordinal.
 *
 * Write shape: [upsert] always writes the split form — blob with empty lists plus
 * fresh rows in the three sibling tables (delete-all + re-insert, one atomic
 * cycle). [delete] explicitly clears all sibling tables because they don't have
 * FK cascade (SQLite's `foreign_keys` pragma is off project-wide).
 *
 * Read shape with legacy fallback: [get] decodes the blob, queries the sibling
 * tables, and overlays the table rows on top of whatever the blob carries.
 * If a sibling table is empty **and** the blob still has inline entries in the
 * corresponding field, those blob values are returned — this is the
 * pre-migration ("legacy") case where a pre-v3 project hasn't been mutated
 * since the upgrade. The next [upsert] migrates automatically (blob lists are
 * emptied, tables are populated).
 */
class SqlDelightProjectStore(
    private val db: TaleviaDb,
    private val clock: Clock = Clock.System,
    private val json: Json = JsonConfig.default,
    /**
     * Optional event bus. When provided, every successful [get] runs a
     * lightweight source-DAG validation (dangling parent refs + parent
     * cycles only — see [ProjectSourceDagValidator]) and publishes
     * [BusEvent.ProjectValidationWarning] if any issues surface. Null
     * keeps the store usable in pure-persistence test rigs; five
     * production AppContainers (CLI / Desktop / Server / Android / iOS)
     * all pass the app's bus.
     */
    private val bus: EventBus? = null,
) : ProjectStore {
    private val logger = Loggers.get("domain.ProjectStore")
    /**
     * Process-scoped Mutex — serialises `mutate` within this JVM. Safe for
     * single-process Desktop / CLI / single-replica server. When multiple JVM
     * processes share the same `TALEVIA_DB_PATH` (unusual), writes can interleave
     * — upgrade to SQLite-level serialisation (`BEGIN IMMEDIATE` or
     * `PRAGMA locking_mode = EXCLUSIVE`) at that point. Triggers + rationale:
     * `docs/decisions/2026-04-21-process-level-project-mutex-recorded.md`.
     */
    private val mutex = Mutex()

    override suspend fun get(id: ProjectId): Project? {
        val row = db.projectsQueries.selectById(id.value).executeAsOneOrNull() ?: return null
        val project = assembleProject(row.data_, id)
        maybeEmitValidationWarning(project)
        return project
    }

    /**
     * Light-weight auto-validation on every project load: checks only
     * source-DAG integrity (dangling parents + parent cycles). Full
     * timeline/asset/audio validation stays in `ValidateProjectTool` —
     * this is specifically the "silent DAG corruption" signal that
     * otherwise goes unnoticed until `find_stale_clips` or an export
     * surfaces it.
     *
     * Non-throwing by design: a warning is logged and (when bus is wired)
     * published as [BusEvent.ProjectValidationWarning]. The project still
     * returns normally so existing buggy blobs don't become unreadable —
     * users can run `validate_project` for the full issue list and fix
     * incrementally.
     */
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

    private fun assembleProject(blobJson: String, id: ProjectId): Project {
        val base = json.decodeFromString(Project.serializer(), blobJson)
        val snapshotsFromTable = db.projectSnapshotsQueries.selectByProject(id.value).executeAsList()
            .map { json.decodeFromString(ProjectSnapshot.serializer(), it.payload) }
        val entriesFromTable = db.projectLockfileEntriesQueries.selectByProject(id.value).executeAsList()
            .map { json.decodeFromString(LockfileEntry.serializer(), it.payload) }
        val clipCacheFromTable = db.projectClipRenderCacheQueries.selectByProject(id.value).executeAsList()
            .map { json.decodeFromString(ClipRenderCacheEntry.serializer(), it.payload) }

        // Legacy fallback: when a sibling table is empty but the blob still
        // carries inline entries in the corresponding field, keep serving the
        // blob's values. On the next upsert the split form takes over (blob
        // lists emptied, sibling rows populated).
        val snapshots = if (snapshotsFromTable.isEmpty()) base.snapshots else snapshotsFromTable
        val lockfile = if (entriesFromTable.isEmpty()) base.lockfile else Lockfile(entriesFromTable)
        val clipRenderCache = if (clipCacheFromTable.isEmpty()) {
            base.clipRenderCache
        } else {
            ClipRenderCache(clipCacheFromTable)
        }
        return base.copy(snapshots = snapshots, lockfile = lockfile, clipRenderCache = clipRenderCache)
    }

    override suspend fun upsert(title: String, project: Project) {
        val now = clock.now().toEpochMilliseconds()
        val existing = db.projectsQueries.selectById(project.id.value).executeAsOneOrNull()

        // Stamp `updatedAtEpochMs` on tracks / clips / assets based on a
        // structural diff against the prior blob. Done here (not in tools)
        // so 14+ mutation tools don't each need recency bookkeeping — the
        // one centralised hook covers every write path. Decode failure on the
        // prior blob (e.g. schema drift) degrades to "treat everything as new",
        // which is strictly more conservative than losing the feature.
        val priorProject = existing?.data_?.let {
            runCatching { json.decodeFromString(Project.serializer(), it) }.getOrNull()
        }
        val stamped = ProjectRecencyStamper.stamp(project, priorProject, now)

        // Blob carries only the "always re-written together" fields. The three
        // append-only lists are stored in sibling tables so `add_clip` doesn't
        // pay the write-amplification of re-encoding all history.
        val slim = stamped.copy(
            snapshots = emptyList(),
            lockfile = Lockfile.EMPTY,
            clipRenderCache = ClipRenderCache.EMPTY,
        )

        db.transaction {
            db.projectsQueries.upsert(
                id = project.id.value,
                title = title,
                data_ = json.encodeToString(Project.serializer(), slim),
                time_created = existing?.time_created ?: now,
                time_updated = now,
            )
            // Full-reset sibling tables — the mutex already serializes upsert
            // calls so this is safe. Cheaper than diffing given typical sizes.
            db.projectSnapshotsQueries.deleteAllForProject(project.id.value)
            stamped.snapshots.forEach { snap ->
                db.projectSnapshotsQueries.insert(
                    project_id = project.id.value,
                    snapshot_id = snap.id.value,
                    label = snap.label,
                    captured_at = snap.capturedAtEpochMs,
                    payload = json.encodeToString(ProjectSnapshot.serializer(), snap),
                )
            }
            db.projectLockfileEntriesQueries.deleteAllForProject(project.id.value)
            stamped.lockfile.entries.forEachIndexed { index, entry ->
                db.projectLockfileEntriesQueries.insert(
                    project_id = project.id.value,
                    ordinal = index.toLong(),
                    input_hash = entry.inputHash,
                    asset_id = entry.assetId.value,
                    tool_id = entry.toolId,
                    payload = json.encodeToString(LockfileEntry.serializer(), entry),
                )
            }
            db.projectClipRenderCacheQueries.deleteAllForProject(project.id.value)
            stamped.clipRenderCache.entries.forEach { entry ->
                db.projectClipRenderCacheQueries.upsert(
                    project_id = project.id.value,
                    fingerprint = entry.fingerprint,
                    mezzanine_path = entry.mezzaninePath,
                    created_at = entry.createdAtEpochMs,
                    payload = json.encodeToString(ClipRenderCacheEntry.serializer(), entry),
                )
            }
        }
    }

    override suspend fun list(): List<Project> =
        db.projectsQueries.selectAll().executeAsList().map {
            assembleProject(it.data_, ProjectId(it.id))
        }

    override suspend fun summary(id: ProjectId): ProjectSummary? =
        db.projectsQueries.selectById(id.value).executeAsOneOrNull()?.let {
            ProjectSummary(it.id, it.title, it.time_created, it.time_updated)
        }

    override suspend fun listSummaries(): List<ProjectSummary> =
        db.projectsQueries.selectAll().executeAsList().map {
            ProjectSummary(it.id, it.title, it.time_created, it.time_updated)
        }

    override suspend fun delete(id: ProjectId, deleteFiles: Boolean) {
        // SQL-backed store has no on-disk bundle; deleteFiles is a no-op here.
        db.transaction {
            db.projectsQueries.delete(id.value)
            db.projectSnapshotsQueries.deleteAllForProject(id.value)
            db.projectLockfileEntriesQueries.deleteAllForProject(id.value)
            db.projectClipRenderCacheQueries.deleteAllForProject(id.value)
        }
    }

    override suspend fun setTitle(id: ProjectId, title: String) = mutex.withLock {
        val existing = db.projectsQueries.selectById(id.value).executeAsOneOrNull()
            ?: error("Project ${id.value} does not exist")
        db.projectsQueries.renameProject(
            title = title,
            time_updated = clock.now().toEpochMilliseconds(),
            id = existing.id,
        )
    }

    override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project = mutex.withLock {
        val current = get(id) ?: error("Project $id does not exist")
        val updated = block(current)
        val title = db.projectsQueries.selectById(id.value).executeAsOneOrNull()?.title ?: id.value
        upsert(title, updated)
        updated
    }

}
