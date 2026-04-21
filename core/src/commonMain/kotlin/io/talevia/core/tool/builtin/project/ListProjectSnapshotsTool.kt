package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
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
 * Enumerate saved snapshots on a project, newest-first. Read-only;
 * `project.read` permission. Returns a compact summary (id / label / captured-at /
 * clip + asset counts) — the full Project payload is not surfaced because it would
 * blow up the tool-result and the LLM doesn't need it to plan a restore.
 *
 * Scope narrows via two orthogonal optional inputs:
 * - `maxAgeDays` — drop snapshots older than `now - maxAgeDays` (strict). Useful
 *   when a long-running project has 30+ snapshots and only the recent ones matter.
 * - `limit` — cap on returned rows (default 50, max 500). Applied *after* the age
 *   filter so "the 5 most recent" is a `limit=5` call.
 *
 * Pair with `save_project_snapshot` (capture) and `restore_project_snapshot`
 * (rollback) — VISION §3.4 "可版本化".
 */
class ListProjectSnapshotsTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<ListProjectSnapshotsTool.Input, ListProjectSnapshotsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val maxAgeDays: Int? = null,
        val limit: Int? = null,
    )

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
        "List saved snapshots on a project, newest-first. Returns id + label + " +
            "captured-at-epoch-ms + clip/track/asset counts so the agent can pick which one to " +
            "restore. Optional maxAgeDays drops snapshots older than now - maxAgeDays days; " +
            "optional limit caps the returned rows (default 50, max 500, applied after the age " +
            "filter). The Project payload itself is not returned — call get_project_state for " +
            "live state."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("maxAgeDays") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "Drop snapshots captured strictly earlier than now - maxAgeDays. Omit to keep all ages.",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", MAX_LIMIT)
                put(
                    "description",
                    "Cap on returned rows (default $DEFAULT_LIMIT, max $MAX_LIMIT). Applied after the age filter.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        input.maxAgeDays?.let {
            require(it >= 0) { "maxAgeDays must be >= 0 (got $it)" }
        }
        input.limit?.let {
            require(it in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT (got $it)" }
        }
        val cap = input.limit ?: DEFAULT_LIMIT
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        val cutoffEpochMs = input.maxAgeDays?.let { days ->
            clock.now().toEpochMilliseconds() - days.toLong() * MS_PER_DAY
        }

        val filtered = project.snapshots
            .asSequence()
            .filter { cutoffEpochMs == null || it.capturedAtEpochMs >= cutoffEpochMs }
            .sortedByDescending { it.capturedAtEpochMs }
            .take(cap)
            .toList()

        val summaries = filtered.map { snap ->
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
            "Project ${pid.value} has no matching snapshots."
        } else {
            summaries.joinToString("; ") { "${it.snapshotId} \"${it.label}\" (${it.clipCount} clip(s))" }
        }
        return ToolResult(
            title = "list snapshots",
            outputForLlm = summary,
            data = out,
        )
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 500
        private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}
