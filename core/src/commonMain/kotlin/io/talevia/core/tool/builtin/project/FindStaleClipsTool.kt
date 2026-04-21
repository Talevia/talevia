package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.staleClipsFromLockfile
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
 * Surface the lockfile-driven stale-clip lane (VISION §3.2) as an agent-callable
 * tool. Joins each clip on the timeline against its lockfile entry, compares the
 * snapshotted `sourceContentHashes` to the project's *current* hashes, and
 * returns every clip whose conditioning source nodes have changed since
 * generation.
 *
 * Without this tool the DAG infrastructure is theoretical — the agent has no way
 * to answer "I just changed Mei's hair, what needs regenerating?" except by
 * reading the entire project JSON and reasoning manually. With it, the planning
 * pattern becomes:
 *
 *   1. User: "make Mei's hair red instead of teal"
 *   2. Agent: define_character_ref (idempotent on `mei` → revision++)
 *   3. Agent: find_stale_clips → list of clip ids + which source ids changed
 *   4. Agent: regenerate each (calling generate_image with the same
 *      `consistencyBindingIds=["mei"]`) and replace via add_clip / future
 *      `replace_clip`.
 *
 * Read-only: permission `project.read`. Imported (non-AIGC) media is excluded
 * from the report — there's no lockfile entry to compare against, so we can't
 * meaningfully call it stale.
 *
 * Bounded output: reports are sorted by `clipId` ASC (deterministic across
 * repeated calls against the same project state) and capped at `limit` rows
 * (default 50, max 500). `staleClipCount` always reflects the *true* total
 * even when `reports` is truncated, so the agent can tell when it's looking
 * at a partial view.
 */
class FindStaleClipsTool(
    private val projects: ProjectStore,
) : Tool<FindStaleClipsTool.Input, FindStaleClipsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val limit: Int? = null,
    )

    @Serializable data class Report(
        val clipId: String,
        val assetId: String,
        val changedSourceIds: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val staleClipCount: Int,
        val totalClipCount: Int,
        val reports: List<Report>,
    )

    override val id: String = "find_stale_clips"
    override val helpText: String =
        "List clips whose conditioning source nodes have changed since the asset was generated. " +
            "Use after editing a character_ref / style_bible / brand_palette to plan which AIGC " +
            "clips to regenerate. Returns one report per stale clip with the source-node ids that " +
            "drifted, sorted by clipId (ascending) so repeated calls are reproducible. Optional " +
            "limit caps returned reports (default $DEFAULT_LIMIT, max $MAX_LIMIT); staleClipCount " +
            "always reports the true total even when the reports list is truncated."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", MAX_LIMIT)
                put(
                    "description",
                    "Cap on returned reports (default $DEFAULT_LIMIT, max $MAX_LIMIT). " +
                        "staleClipCount stays the true count even when reports is truncated.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        input.limit?.let {
            require(it in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT (got $it)" }
        }
        val cap = input.limit ?: DEFAULT_LIMIT
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val allReports = project.staleClipsFromLockfile()
            .map { r ->
                Report(
                    clipId = r.clipId.value,
                    assetId = r.assetId.value,
                    changedSourceIds = r.changedSourceIds.map { it.value },
                )
            }
            .sortedBy { it.clipId }
        val totalStale = allReports.size
        val truncated = totalStale > cap
        val trimmed = if (truncated) allReports.take(cap) else allReports
        val totalClips = project.timeline.tracks.sumOf { it.clips.size }
        val out = Output(
            projectId = pid.value,
            staleClipCount = totalStale,
            totalClipCount = totalClips,
            reports = trimmed,
        )
        val summary = if (totalStale == 0) {
            "All AIGC clips fresh ($totalClips clip(s) total; nothing to regenerate)."
        } else {
            val head = "$totalStale of $totalClips clip(s) stale"
            val truncNote = if (truncated) " (showing first $cap of $totalStale — raise limit to see more)" else ""
            val preview = trimmed.take(5).joinToString("; ") {
                "${it.clipId} (changed: ${it.changedSourceIds.joinToString(",")})"
            }
            val tail = if (trimmed.size > 5) "; …" else ""
            "$head$truncNote. $preview$tail"
        }
        return ToolResult(
            title = "find stale clips",
            outputForLlm = summary,
            data = out,
        )
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 500
    }
}
