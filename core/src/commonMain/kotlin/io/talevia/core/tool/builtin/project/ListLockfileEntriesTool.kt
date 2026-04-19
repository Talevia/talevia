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
 * Enumerate the lockfile (VISION §3.1 — "package-lock.json for the random compiler").
 *
 * The agent already has [FindStaleClipsTool] for "what needs regenerating?", but
 * no way to answer the more basic "what AIGC have I produced in this project so
 * far?". Without that orientation, the agent can't help the user audit what's
 * been generated, plan reuse decisions ("do we have a Mei portrait we can crop
 * instead of re-generating?"), or notice runaway generation costs.
 *
 * Read-only — permission `project.read`. Returns lockfile entries in insertion
 * order (most recent last) so the natural "show me the last 5 generations" call
 * is `limit=5` from the *tail*. We invert to most-recent-first in the response
 * so the LLM doesn't have to re-sort, but the underlying append-only ordering
 * is preserved on disk.
 *
 * Filterable by [Input.toolId] — useful for "show me only the image generations"
 * vs. "show me the TTS calls". Pair with `get_project_state` (which only returns
 * a count) when the agent needs detail beyond "how many entries are there".
 */
class ListLockfileEntriesTool(
    private val projects: ProjectStore,
) : Tool<ListLockfileEntriesTool.Input, ListLockfileEntriesTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** Optional filter — only entries with this `toolId` (e.g. "generate_image"). */
        val toolId: String? = null,
        /** Cap on returned entries (most recent first). Defaults to 20. */
        val limit: Int? = null,
    )

    @Serializable data class Entry(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val providerId: String,
        val modelId: String,
        val seed: Long,
        val createdAtEpochMs: Long,
        val sourceBindingIds: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalEntries: Int,
        val returnedEntries: Int,
        val entries: List<Entry>,
    )

    override val id: String = "list_lockfile_entries"
    override val helpText: String =
        "List AIGC lockfile entries on a project, most recent first. Use this for orientation " +
            "(\"what have I generated?\") and reuse decisions (\"do we have a Mei portrait already?\"). " +
            "Filter by toolId to scope to one modality (e.g. \"generate_image\", \"synthesize_speech\"). " +
            "Defaults to 20 most recent. For staleness use find_stale_clips instead."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("toolId") {
                put("type", "string")
                put(
                    "description",
                    "Optional filter on the producing tool id (e.g. generate_image, synthesize_speech).",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned entries (default 20, max 200).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val all = project.lockfile.entries
        val filtered = if (input.toolId.isNullOrBlank()) all else all.filter { it.toolId == input.toolId }
        // entries is append-only / insertion-ordered; reverse so most-recent is first,
        // *then* take the cap so a "show me the last 5" call gets the actual tail.
        val recent = filtered.asReversed().take(limit)

        val mapped = recent.map { e ->
            Entry(
                inputHash = e.inputHash,
                toolId = e.toolId,
                assetId = e.assetId.value,
                providerId = e.provenance.providerId,
                modelId = e.provenance.modelId,
                seed = e.provenance.seed,
                createdAtEpochMs = e.provenance.createdAtEpochMs,
                sourceBindingIds = e.sourceBinding.map { it.value }.sorted(),
            )
        }

        val out = Output(
            projectId = pid.value,
            totalEntries = filtered.size,
            returnedEntries = mapped.size,
            entries = mapped,
        )

        val summary = if (mapped.isEmpty()) {
            val scope = input.toolId?.let { " (toolId=$it)" } ?: ""
            "No lockfile entries on project ${pid.value}$scope."
        } else {
            val scope = input.toolId?.let { " toolId=$it," } ?: ""
            "${mapped.size} of ${filtered.size} entries$scope most recent first: " +
                mapped.take(5).joinToString("; ") { "${it.toolId}/${it.assetId} (model=${it.modelId})" } +
                if (mapped.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list lockfile entries",
            outputForLlm = summary,
            data = out,
        )
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
    }
}
