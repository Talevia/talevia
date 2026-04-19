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
 * Roll the project back to a previously-saved [io.talevia.core.domain.ProjectSnapshot]
 * (VISION §3.4 — "可版本化, 可回滚").
 *
 * Restore replaces every restorable Project field — timeline, source DAG, lockfile,
 * render cache, asset catalog ids, output profile — with the snapshot's payload, but
 * **preserves the project's snapshots list itself and the project id**. Without
 * that rule restore would forget every prior snapshot and become a one-way trapdoor;
 * with it, restore behaves like `git checkout <snapshot>` from the user's
 * perspective: state changes, history stays.
 *
 * Permission `project.destructive` because the live timeline / source DAG / lockfile
 * are overwritten in place. The user is asked to confirm just like with delete_project
 * — the agent should suggest `save_project_snapshot` first if the live state hasn't
 * been captured.
 */
class RestoreProjectSnapshotTool(
    private val projects: ProjectStore,
) : Tool<RestoreProjectSnapshotTool.Input, RestoreProjectSnapshotTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val snapshotId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val snapshotId: String,
        val label: String,
        val capturedAtEpochMs: Long,
        val clipCount: Int,
        val trackCount: Int,
    )

    override val id: String = "restore_project_snapshot"
    override val helpText: String =
        "Roll the project back to a previously-saved snapshot. Replaces timeline / source / " +
            "lockfile / render cache / assets with the snapshot's payload but preserves the " +
            "snapshots list itself (so restore is reversible — call save_project_snapshot first " +
            "if you want to keep the current state). Confirms with the user before applying."
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

        val updated = projects.mutate(pid) { project ->
            val snap = project.snapshots.firstOrNull { it.id == targetId }
                ?: error(
                    "Snapshot ${input.snapshotId} not found on project ${input.projectId}. " +
                        "Call list_project_snapshots to enumerate.",
                )
            val captured = snap.project
            // Replace restorable fields; preserve the snapshots list itself + the project id
            // so history isn't a one-way trapdoor.
            captured.copy(
                id = project.id,
                snapshots = project.snapshots,
            )
        }

        // Re-read the snapshot off the (now-updated) project to populate the response.
        val snap = updated.snapshots.first { it.id == targetId }
        val clipCount = updated.timeline.tracks.sumOf { it.clips.size }
        val trackCount = updated.timeline.tracks.size
        val out = Output(
            projectId = pid.value,
            snapshotId = snap.id.value,
            label = snap.label,
            capturedAtEpochMs = snap.capturedAtEpochMs,
            clipCount = clipCount,
            trackCount = trackCount,
        )
        return ToolResult(
            title = "restore snapshot \"${snap.label}\"",
            outputForLlm = "Restored project ${pid.value} to snapshot ${snap.id.value} " +
                "(\"${snap.label}\", $clipCount clip(s), $trackCount track(s)). " +
                "Snapshot history preserved — call list_project_snapshots to see all ${updated.snapshots.size}.",
            data = out,
        )
    }
}
