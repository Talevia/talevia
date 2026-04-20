package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.db.TaleviaDb
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

class SqlDelightProjectStore(
    private val db: TaleviaDb,
    private val clock: Clock = Clock.System,
    private val json: Json = JsonConfig.default,
) : ProjectStore {
    private val mutex = Mutex()

    override suspend fun get(id: ProjectId): Project? =
        db.projectsQueries.selectById(id.value).executeAsOneOrNull()
            ?.let { json.decodeFromString(Project.serializer(), it.data_) }

    override suspend fun upsert(title: String, project: Project) {
        val now = clock.now().toEpochMilliseconds()
        val existing = db.projectsQueries.selectById(project.id.value).executeAsOneOrNull()
        db.projectsQueries.upsert(
            id = project.id.value,
            title = title,
            data_ = json.encodeToString(Project.serializer(), project),
            time_created = existing?.time_created ?: now,
            time_updated = now,
        )
    }

    override suspend fun list(): List<Project> =
        db.projectsQueries.selectAll().executeAsList()
            .map { json.decodeFromString(Project.serializer(), it.data_) }

    override suspend fun summary(id: ProjectId): ProjectSummary? =
        db.projectsQueries.selectById(id.value).executeAsOneOrNull()?.let {
            ProjectSummary(it.id, it.title, it.time_created, it.time_updated)
        }

    override suspend fun listSummaries(): List<ProjectSummary> =
        db.projectsQueries.selectAll().executeAsList().map {
            ProjectSummary(it.id, it.title, it.time_created, it.time_updated)
        }

    override suspend fun delete(id: ProjectId) {
        db.projectsQueries.delete(id.value)
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
