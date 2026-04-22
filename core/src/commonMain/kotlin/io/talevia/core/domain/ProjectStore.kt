package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
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
    suspend fun delete(id: ProjectId)

    /**
     * Rename a project — updates only the catalog `title` column (and `time_updated`),
     * leaving the JSON-blobbed [Project] model untouched. Throws if no row exists.
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
}

/**
 * SQLDelight-backed [ProjectStore].
 *
 * Storage shape (schema v2):
 *  - `Projects.data` — JSON of the [Project] with `snapshots` and `lockfile.entries`
 *    emptied. The "hot path" slice that the agent re-reads / re-writes on every tool
 *    call: timeline, source DAG, assets, outputProfile, renderCache.
 *  - `ProjectSnapshots` — one row per [ProjectSnapshot], keyed by `(projectId, snapshotId)`.
 *  - `ProjectLockfileEntries` — one row per [LockfileEntry], keyed by `(projectId, ordinal)`.
 *    `ordinal` preserves the append-only order [Lockfile.findByInputHash] relies on
 *    (most-recent match wins).
 *
 * Write shape: [upsert] always writes the split form — blob with empty lists plus
 * fresh rows in the two sibling tables (delete-all + re-insert, one atomic cycle).
 * [delete] explicitly clears both sibling tables because the tables don't have
 * FK cascade (SQLite's `foreign_keys` pragma is off project-wide).
 *
 * Read shape with legacy fallback: [get] decodes the blob, queries both sibling
 * tables, and overlays the table rows on top of whatever the blob carries.
 * If the tables are empty **and** the blob still has inline snapshots / lockfile
 * entries, those blob values are returned — this is the pre-migration ("legacy")
 * case where a v1 project hasn't been mutated since the v2 upgrade. The next
 * [upsert] migrates automatically (blob lists are emptied, tables are populated).
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

        // Legacy fallback: when v2 sibling tables are empty but the v1 blob still
        // carries inline lists, keep serving the blob's lists. On the next upsert
        // the split form takes over (blob lists emptied, sibling rows populated).
        val snapshots = if (snapshotsFromTable.isEmpty()) base.snapshots else snapshotsFromTable
        val lockfile = if (entriesFromTable.isEmpty()) base.lockfile else Lockfile(entriesFromTable)
        return base.copy(snapshots = snapshots, lockfile = lockfile)
    }

    override suspend fun upsert(title: String, project: Project) {
        val now = clock.now().toEpochMilliseconds()
        val existing = db.projectsQueries.selectById(project.id.value).executeAsOneOrNull()

        // Stamp `updatedAtEpochMs` on tracks / clips / assets based on a
        // structural diff against the prior blob. Done here (not in tools)
        // so 14+ mutation tools don't each need recency bookkeeping — the
        // one centralised hook covers every write path.
        val stamped = stampRecency(project, existing?.data_, now)

        // Blob carries only the "always re-written together" fields. The two
        // append-only lists are stored in sibling tables so `add_clip` doesn't
        // pay the write-amplification of re-encoding all history.
        val slim = stamped.copy(snapshots = emptyList(), lockfile = Lockfile.EMPTY)

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

    override suspend fun delete(id: ProjectId) {
        db.transaction {
            db.projectsQueries.delete(id.value)
            db.projectSnapshotsQueries.deleteAllForProject(id.value)
            db.projectLockfileEntriesQueries.deleteAllForProject(id.value)
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

    /**
     * Produce a copy of [project] with `updatedAtEpochMs` stamped on each
     * clip / track / asset per the rule:
     *  - brand-new entity (id absent in the prior blob) → `now`,
     *  - structurally unchanged entity (all non-recency fields equal) →
     *    preserve old stamp (or `now` if old was null / blob predated recency),
     *  - structurally changed entity → `now`.
     *
     * Track stamps cascade from clips: a track whose own fields AND clips
     * list membership are unchanged but whose individual clip content
     * changed still counts as "touched". That matches how agents use
     * `sortBy="recent"` for orientation ("what did I just edit?").
     *
     * The prior blob is the slim form (snapshots / lockfile emptied), but
     * recency only looks at timeline + assets so that's fine. A decoding
     * error (e.g. schema drift) degrades to "treat everything as new"
     * — strictly more conservative than losing the feature entirely.
     */
    private fun stampRecency(project: Project, existingBlob: String?, now: Long): Project {
        val old = existingBlob?.let {
            runCatching { json.decodeFromString(Project.serializer(), it) }.getOrNull()
        }
        val oldClipsById: Map<String, Clip> = old?.timeline?.tracks
            ?.flatMap { it.clips }
            ?.associateBy { it.id.value }
            ?: emptyMap()
        val oldTracksById: Map<String, Track> = old?.timeline?.tracks
            ?.associateBy { it.id.value }
            ?: emptyMap()
        val oldAssetsById: Map<String, MediaAsset> = old?.assets?.associateBy { it.id.value } ?: emptyMap()

        val newTracks = project.timeline.tracks.map { newTrack ->
            val stampedClips = newTrack.clips.map { stampClip(it, oldClipsById[it.id.value], now) }
            val trackWithClips = withClips(newTrack, stampedClips)
            stampTrack(trackWithClips, oldTracksById[newTrack.id.value], now)
        }
        val newAssets = project.assets.map { stampAsset(it, oldAssetsById[it.id.value], now) }
        return project.copy(
            timeline = project.timeline.copy(tracks = newTracks),
            assets = newAssets,
        )
    }

    private fun stampClip(new: Clip, old: Clip?, now: Long): Clip {
        val stamp = when {
            old == null -> now
            clipStructure(new) == clipStructure(old) -> old.updatedAtEpochMs ?: now
            else -> now
        }
        return applyClipStamp(new, stamp)
    }

    private fun stampTrack(new: Track, old: Track?, now: Long): Track {
        val stamp = when {
            old == null -> now
            trackStructure(new) == trackStructure(old) -> old.updatedAtEpochMs ?: now
            else -> now
        }
        return applyTrackStamp(new, stamp)
    }

    private fun stampAsset(new: MediaAsset, old: MediaAsset?, now: Long): MediaAsset {
        val stamp = when {
            old == null -> now
            new.copy(updatedAtEpochMs = null) == old.copy(updatedAtEpochMs = null) -> old.updatedAtEpochMs ?: now
            else -> now
        }
        return new.copy(updatedAtEpochMs = stamp)
    }

    private fun clipStructure(c: Clip): Clip = applyClipStamp(c, null)

    private fun trackStructure(t: Track): Track = applyTrackStamp(
        withClips(t, t.clips.map(::clipStructure)),
        null,
    )

    private fun applyClipStamp(c: Clip, stamp: Long?): Clip = when (c) {
        is Clip.Video -> c.copy(updatedAtEpochMs = stamp)
        is Clip.Audio -> c.copy(updatedAtEpochMs = stamp)
        is Clip.Text -> c.copy(updatedAtEpochMs = stamp)
    }

    private fun applyTrackStamp(t: Track, stamp: Long?): Track = when (t) {
        is Track.Video -> t.copy(updatedAtEpochMs = stamp)
        is Track.Audio -> t.copy(updatedAtEpochMs = stamp)
        is Track.Subtitle -> t.copy(updatedAtEpochMs = stamp)
        is Track.Effect -> t.copy(updatedAtEpochMs = stamp)
    }

    private fun withClips(t: Track, clips: List<Clip>): Track = when (t) {
        is Track.Video -> t.copy(clips = clips)
        is Track.Audio -> t.copy(clips = clips)
        is Track.Subtitle -> t.copy(clips = clips)
        is Track.Effect -> t.copy(clips = clips)
    }
}
