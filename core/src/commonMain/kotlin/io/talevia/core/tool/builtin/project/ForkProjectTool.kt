package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Branch a project into a new one (VISION §3.4 — "可分支").
 *
 * Snapshots cover save+restore on a single trunk; forking is the third leg —
 * "let me try a different cut without losing the original". The new project gets
 * a fresh [ProjectId] and a clean (empty) snapshots list, but inherits the source
 * payload — timeline, source DAG, lockfile, render cache, asset catalog ids,
 * output profile — from either the *current* state of the source project (when
 * `snapshotId` is null) or from a specific captured snapshot.
 *
 * Asset bytes are not duplicated. Both projects reference the same `AssetId`s in
 * shared MediaStorage — same trade-off as snapshots themselves. If the user later
 * mutates one project's assets in place we'll need refcounting; for now the
 * canonical mutation pattern is "produce a new asset and replace_clip", so the
 * shared-id model is safe.
 *
 * Fails loud on duplicate `newProjectId` so the agent reconciles via
 * `list_projects` rather than silently stomping a project the user already cares
 * about — same discipline as `create_project`.
 */
class ForkProjectTool(
    private val projects: ProjectStore,
) : Tool<ForkProjectTool.Input, ForkProjectTool.Output> {

    @Serializable data class Input(
        val sourceProjectId: String,
        val newTitle: String,
        val newProjectId: String? = null,
        /**
         * If null → fork from the source project's *current* live state.
         * If set → fork from the captured snapshot with this id. The snapshot
         * must exist on the source project; we fail loudly otherwise.
         */
        val snapshotId: String? = null,
    )

    @Serializable data class Output(
        val sourceProjectId: String,
        val newProjectId: String,
        val newTitle: String,
        val branchedFromSnapshotId: String?,
        val clipCount: Int,
        val trackCount: Int,
    )

    override val id: String = "fork_project"
    override val helpText: String =
        "Branch a project into a new one (closes VISION \u00a73.4 \"\u53ef\u5206\u652f\"). " +
            "By default forks from the source project's current state; pass snapshotId to fork " +
            "from a previously-saved snapshot instead. The new project has a fresh id, an empty " +
            "snapshots list, and otherwise inherits everything (timeline, source, lockfile, " +
            "render cache, assets, output profile). Asset bytes are not duplicated — both " +
            "projects reference the same AssetIds in shared media storage."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sourceProjectId") { put("type", "string") }
            putJsonObject("newTitle") {
                put("type", "string")
                put("description", "Title for the forked project (also drives the default newProjectId).")
            }
            putJsonObject("newProjectId") {
                put("type", "string")
                put("description", "Optional explicit id for the fork; defaults to a slug of newTitle.")
            }
            putJsonObject("snapshotId") {
                put("type", "string")
                put(
                    "description",
                    "Optional snapshot to fork from; defaults to the source project's current state.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("sourceProjectId"), JsonPrimitive("newTitle"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.newTitle.isNotBlank()) { "newTitle must not be blank" }
        val sourcePid = ProjectId(input.sourceProjectId)
        val source = projects.get(sourcePid)
            ?: error("Source project ${input.sourceProjectId} not found")

        val payload: Project = if (input.snapshotId == null) {
            source
        } else {
            val targetId = ProjectSnapshotId(input.snapshotId)
            source.snapshots.firstOrNull { it.id == targetId }?.project
                ?: error(
                    "Snapshot ${input.snapshotId} not found on project ${input.sourceProjectId}. " +
                        "Call list_project_snapshots to enumerate.",
                )
        }

        val rawId = input.newProjectId?.takeIf { it.isNotBlank() }
            ?: slugifyProjectId(input.newTitle)
        val newPid = ProjectId(rawId)
        require(projects.get(newPid) == null) {
            "project ${newPid.value} already exists; pick a different newProjectId or call list_projects to find an unused id"
        }

        // Fresh id, empty snapshots list. Everything else carries over from the
        // chosen payload (current state or a captured snapshot).
        val forked = payload.copy(id = newPid, snapshots = emptyList())
        projects.upsert(input.newTitle, forked)

        val clipCount = forked.timeline.tracks.sumOf { it.clips.size }
        val trackCount = forked.timeline.tracks.size
        val out = Output(
            sourceProjectId = sourcePid.value,
            newProjectId = newPid.value,
            newTitle = input.newTitle,
            branchedFromSnapshotId = input.snapshotId,
            clipCount = clipCount,
            trackCount = trackCount,
        )
        val from = input.snapshotId?.let { "snapshot $it" } ?: "current state"
        return ToolResult(
            title = "fork project ${input.newTitle}",
            outputForLlm = "Forked project ${sourcePid.value} → ${newPid.value} " +
                "(\"${input.newTitle}\", from $from, $clipCount clip(s), $trackCount track(s)). " +
                "Pass projectId=${newPid.value} to subsequent tool calls on the fork.",
            data = out,
        )
    }
}
