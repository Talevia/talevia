package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
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
 * Capture a named, restorable point-in-time of the project (VISION §3.4 — "可版本化").
 *
 * Distinct from `revert_timeline`, which scrubs through *session-scoped* timeline
 * snapshots emitted as side effects of every mutating tool. Those die with the
 * chat session; this tool persists snapshots inside the project itself, so
 * "before re-color" or "final cut v1" survives across chat sessions, app
 * restarts, and even device migrations.
 *
 * The captured payload includes the full Project (timeline, source DAG, lockfile,
 * render cache, asset catalog ids, output profile) — but the snapshot list itself
 * is emptied before storage to avoid quadratic blow-up when the user takes
 * snapshots-of-snapshots-of-snapshots.
 *
 * Asset bytes are *not* copied. Snapshots reference [io.talevia.core.AssetId]s in
 * the shared MediaStorage. This is the same trade-off git makes vs. LFS: keeping
 * the manifest is cheap; copying every blob would balloon storage. If a user
 * deletes the underlying file, restore will succeed but downstream renders may
 * miss the asset — that's a future "snapshot integrity" tool's problem, not a
 * promise we make here.
 */
class SaveProjectSnapshotTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<SaveProjectSnapshotTool.Input, SaveProjectSnapshotTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val label: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val snapshotId: String,
        val label: String,
        val capturedAtEpochMs: Long,
        val totalSnapshotCount: Int,
    )

    override val id: String = "save_project_snapshot"
    override val helpText: String =
        "Capture a named, restorable point-in-time of the project. Survives across chat sessions " +
            "(unlike `revert_timeline`, which only sees in-session snapshots). label is free-form " +
            "(e.g. \"final cut v1\", \"before re-color\"); defaults to the capture timestamp when " +
            "omitted. Asset bytes are not copied — the snapshot references AssetIds in shared media " +
            "storage. Use list_project_snapshots to enumerate, restore_project_snapshot to roll back."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("label") {
                put("type", "string")
                put("description", "Optional human handle. Defaults to a timestamp string.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val capturedAt = clock.now().toEpochMilliseconds()
        val snapshotId = ProjectSnapshotId("snap-$capturedAt-${ctx.callId.value}")
        val label = input.label?.takeIf { it.isNotBlank() } ?: "snapshot @ $capturedAt"

        val updated = projects.mutate(pid) { project ->
            // Clear nested snapshots before capture so snapshots-of-snapshots don't
            // grow quadratically. Restore will preserve the (current) snapshots list,
            // not whatever was nested inside the captured payload.
            val payload = project.copy(snapshots = emptyList())
            project.copy(
                snapshots = project.snapshots + ProjectSnapshot(
                    id = snapshotId,
                    label = label,
                    capturedAtEpochMs = capturedAt,
                    project = payload,
                ),
            )
        }

        val out = Output(
            projectId = pid.value,
            snapshotId = snapshotId.value,
            label = label,
            capturedAtEpochMs = capturedAt,
            totalSnapshotCount = updated.snapshots.size,
        )
        return ToolResult(
            title = "save snapshot \"$label\"",
            outputForLlm = "Saved snapshot ${snapshotId.value} (\"$label\") for project ${pid.value}. " +
                "Project now has ${updated.snapshots.size} snapshot(s). " +
                "Pass snapshotId=${snapshotId.value} to restore_project_snapshot to roll back.",
            data = out,
        )
    }
}
