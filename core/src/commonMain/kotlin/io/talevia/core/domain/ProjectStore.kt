package io.talevia.core.domain

import io.talevia.core.ProjectId
import kotlinx.serialization.Serializable

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
 * provides a simple read-modify-write API guarded by an in-process [kotlinx.coroutines.sync.Mutex]
 * so two concurrent tool dispatches in the same Agent turn cannot interleave.
 *
 * The only production implementation is [FileProjectStore], which persists each
 * project as an on-disk bundle (`talevia.json` + `media/` + `.talevia-cache/`).
 * The SQL-backed `SqlDelightProjectStore` was removed in favour of file-bundles
 * (see `docs/decisions/2026-04-22-delete-sqldelight-project-store.md`); the
 * previous project tables are dropped by migration `3.sqm`.
 */
interface ProjectStore {
    suspend fun get(id: ProjectId): Project?
    suspend fun upsert(title: String, project: Project)
    suspend fun list(): List<Project>

    /**
     * Remove the project from the store. When [deleteFiles] is true the on-disk
     * bundle is removed as well; otherwise only the in-memory catalog entry goes
     * and the bundle remains on disk. Defaults to false so accidentally invoking
     * [delete] never destroys user files.
     */
    suspend fun delete(id: ProjectId, deleteFiles: Boolean = false)

    /**
     * Rename a project — updates only the catalog `title` (and the envelope's
     * `updatedAtEpochMs`), leaving the [Project] model untouched. Throws if the
     * project is not registered.
     */
    suspend fun setTitle(id: ProjectId, title: String)

    /** Catalog metadata for a single project, or null if no row exists. */
    suspend fun summary(id: ProjectId): ProjectSummary?

    /** Catalog metadata for every project — cheaper than [list] for orientation. */
    suspend fun listSummaries(): List<ProjectSummary>

    /**
     * Atomic read-mutate-write. Callers return the new [Project]; the store persists
     * it and updates the `updatedAtEpochMs` timestamp.
     */
    suspend fun mutate(id: ProjectId, block: suspend (Project) -> Project): Project

    /**
     * Open an existing project bundle at the given path and register it in the
     * recents catalog. The directory must contain a valid `talevia.json`.
     *
     * Default throws so lightweight test fakes don't have to implement it; the
     * one production implementation [FileProjectStore] overrides it.
     */
    suspend fun openAt(path: okio.Path): Project =
        throw UnsupportedOperationException("openAt is only implemented by FileProjectStore")

    /**
     * Create a new project bundle at the given path with the given title.
     * The directory must not already contain a `talevia.json`.
     *
     * Default throws for the same reason as [openAt].
     */
    suspend fun createAt(
        path: okio.Path,
        title: String,
        timeline: Timeline = Timeline(),
        outputProfile: OutputProfile = OutputProfile.DEFAULT_1080P,
    ): Project = throw UnsupportedOperationException("createAt is only implemented by FileProjectStore")

    /**
     * Filesystem path of the bundle for the given project, or null if not
     * registered. Default returns null so test fakes that don't have a
     * filesystem can stay silent.
     */
    suspend fun pathOf(id: ProjectId): okio.Path? = null
}
