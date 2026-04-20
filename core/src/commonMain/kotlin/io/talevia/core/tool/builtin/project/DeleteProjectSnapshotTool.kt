package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
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
 * Remove one snapshot from the project's [io.talevia.core.domain.Project.snapshots]
 * list (VISION §3.4 — "可版本化" lifecycle completeness).
 *
 * Snapshots accumulate linearly inside the Project JSON blob; at ~hundreds the
 * blob read/write cost starts to matter. `save_project_snapshot` +
 * `list_project_snapshots` + `restore_project_snapshot` cover capture / browse /
 * rollback, but there was no way to drop an individual obsolete snapshot
 * without nuking the whole project via `delete_project`. That's the gap this
 * tool fills — "prune the v1-draft I don't need anymore, keep v2-final".
 *
 * Permission `project.destructive` — same tier as `delete_project` /
 * `restore_project_snapshot`. Dropping a snapshot is irreversible (the payload
 * is gone once the blob is rewritten) and the user should consciously confirm.
 *
 * No-op-on-missing is rejected (throws) instead of silently succeeding. Rationale:
 * the agent asked for a specific id, a silent success hides typos and stale ids.
 * `list_project_snapshots` is the one-call-cheap way to see what's actually there.
 */
class DeleteProjectSnapshotTool(
    private val projects: ProjectStore,
) : Tool<DeleteProjectSnapshotTool.Input, DeleteProjectSnapshotTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val snapshotId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val snapshotId: String,
        val label: String,
        val remainingSnapshotCount: Int,
    )

    override val id: String = "delete_project_snapshot"
    override val helpText: String =
        "Remove one snapshot from the project by id. Irreversible — the snapshot payload is " +
            "dropped from the project blob on next save. Use list_project_snapshots to enumerate " +
            "candidates; other snapshots and the live project state are untouched."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.destructive")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("snapshotId") {
                put("type", "string")
                put("description", "Id from save_project_snapshot / list_project_snapshots.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("snapshotId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val targetId = ProjectSnapshotId(input.snapshotId)
        var removedLabel = ""

        val updated = projects.mutate(pid) { project ->
            val snap = project.snapshots.firstOrNull { it.id == targetId }
                ?: error(
                    "Snapshot ${input.snapshotId} not found on project ${input.projectId}. " +
                        "Call list_project_snapshots to enumerate.",
                )
            removedLabel = snap.label
            project.copy(snapshots = project.snapshots.filterNot { it.id == targetId })
        }

        val out = Output(
            projectId = pid.value,
            snapshotId = targetId.value,
            label = removedLabel,
            remainingSnapshotCount = updated.snapshots.size,
        )
        return ToolResult(
            title = "delete snapshot \"$removedLabel\"",
            outputForLlm = "Deleted snapshot ${targetId.value} (\"$removedLabel\") from project ${pid.value}. " +
                "Remaining: ${updated.snapshots.size} snapshot(s).",
            data = out,
        )
    }
}
