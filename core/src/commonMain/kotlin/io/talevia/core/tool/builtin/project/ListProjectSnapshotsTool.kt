package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
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
 * Enumerate every saved snapshot on a project, most recent first. Read-only;
 * `project.read` permission. Returns a compact summary (id / label / captured-at /
 * clip + asset counts) — the full Project payload is not surfaced because it would
 * blow up the tool-result and the LLM doesn't need it to plan a restore.
 *
 * Pair with `save_project_snapshot` (capture) and `restore_project_snapshot`
 * (rollback) — VISION §3.4 "可版本化".
 */
class ListProjectSnapshotsTool(
    private val projects: ProjectStore,
) : Tool<ListProjectSnapshotsTool.Input, ListProjectSnapshotsTool.Output> {

    @Serializable data class Input(val projectId: String)

    @Serializable data class Summary(
        val snapshotId: String,
        val label: String,
        val capturedAtEpochMs: Long,
        val clipCount: Int,
        val trackCount: Int,
        val assetCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val snapshotCount: Int,
        val snapshots: List<Summary>,
    )

    override val id: String = "list_project_snapshots"
    override val helpText: String =
        "List every saved snapshot on a project (most recent first). Returns id + label + " +
            "captured-at-epoch-ms + clip/track/asset counts so the agent can pick which one to " +
            "restore. The Project payload itself is not returned — call get_project_state for " +
            "live state."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val sorted = project.snapshots.sortedByDescending { it.capturedAtEpochMs }
        val summaries = sorted.map { snap ->
            val captured = snap.project
            Summary(
                snapshotId = snap.id.value,
                label = snap.label,
                capturedAtEpochMs = snap.capturedAtEpochMs,
                clipCount = captured.timeline.tracks.sumOf { it.clips.size },
                trackCount = captured.timeline.tracks.size,
                assetCount = captured.assets.size,
            )
        }
        val out = Output(
            projectId = pid.value,
            snapshotCount = summaries.size,
            snapshots = summaries,
        )
        val summary = if (summaries.isEmpty()) {
            "Project ${pid.value} has no saved snapshots."
        } else {
            summaries.joinToString("; ") { "${it.snapshotId} \"${it.label}\" (${it.clipCount} clip(s))" }
        }
        return ToolResult(
            title = "list snapshots",
            outputForLlm = summary,
            data = out,
        )
    }
}
