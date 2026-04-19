package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.db.TaleviaDb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

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

    override suspend fun delete(id: ProjectId) {
        db.projectsQueries.delete(id.value)
    }

    override suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project = mutex.withLock {
        val current = get(id) ?: error("Project $id does not exist")
        val updated = block(current)
        val title = db.projectsQueries.selectById(id.value).executeAsOneOrNull()?.title ?: id.value
        upsert(title, updated)
        updated
    }
}
