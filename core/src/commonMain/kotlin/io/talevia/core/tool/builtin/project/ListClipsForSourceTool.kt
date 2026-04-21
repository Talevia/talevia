package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.clipsBoundTo
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
 * Forward-index of `sourceBinding` (VISION §5.1): given a source node id,
 * list every clip on the timeline whose `sourceBinding` intersects the
 * transitive-downstream closure of that node in the source DAG. The
 * complement of `find_stale_clips`:
 *   - `list_clips_for_source` — **before** an edit, "if I change X, what
 *     will go stale?"
 *   - `find_stale_clips` — **after** an edit, "what did go stale?"
 *
 * Includes descendants by default because a binding to a descendant (e.g.
 * a scene pinned to a character) means the clip depends on the queried
 * ancestor too. Each report says whether the bind was direct or came via
 * which descendant id(s).
 *
 * Read-only; permission `"project.read"`. Imported clips with empty
 * `sourceBinding` are skipped — they aren't part of the DAG.
 */
class ListClipsForSourceTool(
    private val projects: ProjectStore,
) : Tool<ListClipsForSourceTool.Input, ListClipsForSourceTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val sourceNodeId: String,
    )

    @Serializable data class Report(
        val clipId: String,
        val trackId: String,
        val assetId: String?,
        val directlyBound: Boolean,
        val boundVia: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val sourceNodeId: String,
        val clipCount: Int,
        val reports: List<Report>,
    )

    override val id: String = "list_clips_for_source"
    override val helpText: String =
        "List every clip whose sourceBinding intersects the transitive-downstream " +
            "closure of the given source node id. Use *before* editing a character_ref / " +
            "style_bible / brand_palette to preview what will go stale. Pair with " +
            "find_stale_clips (post-edit) and regenerate_stale_clips (fix) to close the " +
            "VISION §6 loop. Each report says whether the clip binds the node directly " +
            "or via which descendant id(s) it reached."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("sourceNodeId") {
                put("type", "string")
                put(
                    "description",
                    "The source node to query — typically a character_ref / style_bible / " +
                        "brand_palette id. Must exist in the project's source graph.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("sourceNodeId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val nodeId = SourceNodeId(input.sourceNodeId)
        if (nodeId !in project.source.byId) {
            error(
                "Source node ${input.sourceNodeId} not found in project ${input.projectId} — " +
                    "call source_query(select=nodes) to discover valid ids.",
            )
        }
        val reports = project.clipsBoundTo(nodeId).map { r ->
            Report(
                clipId = r.clipId.value,
                trackId = r.trackId.value,
                assetId = r.assetId?.value,
                directlyBound = r.directlyBound,
                boundVia = r.boundVia.map { it.value },
            )
        }
        val summary = if (reports.isEmpty()) {
            "No clips bind source node ${input.sourceNodeId} (directly or transitively)."
        } else {
            val head = reports.take(5).joinToString("; ") {
                "${it.clipId}${if (it.directlyBound) "" else " (via ${it.boundVia.joinToString(",")})"}"
            }
            val tail = if (reports.size > 5) "; …" else ""
            "${reports.size} clip(s) bind source node ${input.sourceNodeId}: $head$tail"
        }
        return ToolResult(
            title = "list clips for source",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                sourceNodeId = input.sourceNodeId,
                clipCount = reports.size,
                reports = reports,
            ),
        )
    }
}
