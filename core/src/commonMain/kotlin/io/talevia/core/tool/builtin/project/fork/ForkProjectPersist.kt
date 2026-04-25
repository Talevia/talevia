package io.talevia.core.tool.builtin.project.fork

import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.resolveDefaultHomeProjectId
import okio.Path.Companion.toPath

/**
 * The two persistence shapes a fork can land in:
 *
 *  - **explicit `path`** — call `projects.createAt(path, ...)` to register
 *    a fresh bundle on disk, then `mutate` to splat the (variant-reshaped)
 *    fork body in. The store assigns the new ProjectId from the bundle.
 *  - **default-home (no path)** — derive a candidate id from
 *    [ForkProjectTool.Input.newProjectId] / a slug of `newTitle`, fail
 *    loud on collision, then `upsert`.
 *
 * Both branches apply [applyVariantSpec] to the base fork before the
 * write so the dropped/truncated counters are accurate (replaying after
 * persist would always count zero — the body is already trimmed).
 *
 * Returned [ForkPersistResult] carries the new id, the optional
 * reshape outcome, and the as-persisted Project (re-read from the
 * store so subsequent steps see post-store stamping).
 */
internal data class ForkPersistResult(
    val pid: ProjectId,
    val reshape: VariantReshape?,
    val forked: Project,
)

internal suspend fun persistFork(
    projects: ProjectStore,
    sourcePid: ProjectId,
    payload: Project,
    input: ForkProjectTool.Input,
): ForkPersistResult {
    val pid: ProjectId
    val reshape: VariantReshape?
    if (input.path != null && input.path.isNotBlank()) {
        val created = projects.createAt(
            path = input.path.toPath(),
            title = input.newTitle,
            timeline = payload.timeline,
            outputProfile = payload.outputProfile,
        )
        val baseFork = payload.copy(
            id = created.id,
            snapshots = emptyList(),
            parentProjectId = sourcePid,
        )
        reshape = input.variantSpec?.let { spec -> applyVariantSpec(baseFork, spec) }
        val forkBody = reshape?.project ?: baseFork
        projects.mutate(created.id) { forkBody }
        pid = created.id
    } else {
        val rawId = resolveDefaultHomeProjectId(input.newProjectId, input.newTitle)
        val candidate = ProjectId(rawId)
        require(projects.get(candidate) == null) {
            "project ${candidate.value} already exists; pick a different newProjectId or call list_projects to find an unused id"
        }
        val baseFork = payload.copy(
            id = candidate,
            snapshots = emptyList(),
            parentProjectId = sourcePid,
        )
        reshape = input.variantSpec?.let { spec -> applyVariantSpec(baseFork, spec) }
        val forkBody = reshape?.project ?: baseFork
        projects.upsert(input.newTitle, forkBody)
        pid = candidate
    }

    val forked = projects.get(pid) ?: error("Fork ${pid.value} not found after persist")
    return ForkPersistResult(pid, reshape, forked)
}
