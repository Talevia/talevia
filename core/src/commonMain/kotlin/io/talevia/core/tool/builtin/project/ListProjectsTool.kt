package io.talevia.core.tool.builtin.project

import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
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
 * Catalog read for orientation. Returns lightweight metadata only — no Source DAG
 * or Timeline JSON decode. Pair with `get_project_state` for a single-project deep
 * dive once the LLM has picked which one to operate on.
 *
 * Input extensions ([Input.sortBy], [Input.limit]) exist so common agent orientation
 * questions — "show me the 5 most recently updated projects", "sort alphabetically
 * by title" — can be answered without pulling the full catalog and sorting
 * client-side. Mirrors the `list_assets` `sortBy` pattern (see
 * `docs/decisions/2026-04-21-list-assets-sort-by.md`).
 */
class ListProjectsTool(
    private val projects: ProjectStore,
) : Tool<ListProjectsTool.Input, ListProjectsTool.Output> {

    @Serializable data class Input(
        /**
         * Deterministic ordering applied before [limit] so the page reflects the
         * sorted-top-N, not a sorted slice of an unsorted head. Case-insensitive,
         * trimmed + lowercased before match. Accepted values:
         *   - `"updated-desc"` (default) — most-recently-updated first.
         *   - `"created-desc"` — most-recently-created first.
         *   - `"title"` — ascending by title, case-insensitive.
         *   - `"id"` — ascending by project id (lexicographic).
         * Invalid values raise `IllegalArgumentException` listing the accepted set.
         */
        val sortBy: String? = null,
        /**
         * Cap on returned projects. Default 50, silently clamped to [1, 500] —
         * mirrors `project_query(select=lockfile_entries)` / `list_assets`. No exception on
         * overflow; orientation tools shouldn't fail on a too-generous limit.
         */
        val limit: Int? = null,
    )

    @Serializable data class Output(
        /**
         * Full project count in the store — kept as `totalCount` for backward
         * compat with the pre-sort/limit schema (when no knob existed,
         * `totalCount == projects.size` trivially).
         */
        val totalCount: Int,
        /** Number of entries in [projects] after sort + limit. */
        val returnedCount: Int,
        val projects: List<ProjectSummary>,
    )

    override val id: String = "list_projects"
    override val helpText: String =
        "List every project in the store with id / title / created+updated timestamps. " +
            "Optional sortBy (updated-desc default | created-desc | title | id) applies a " +
            "deterministic ordering; optional limit caps the response (default 50, max 500). " +
            "Use this for orientation; call get_project_state for full per-project details."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sortBy") {
                put("type", "string")
                put(
                    "description",
                    "Ordering applied before limit. Default updated-desc.",
                )
                put(
                    "enum",
                    JsonArray(SORT_BY_ALLOWED.map { JsonPrimitive(it) }),
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned projects (default 50, max 500, silently clamped).")
            }
        }
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sortKey = (input.sortBy ?: DEFAULT_SORT_BY).trim().lowercase()
        require(sortKey in SORT_BY_ALLOWED) {
            "sortBy must be one of ${SORT_BY_ALLOWED.joinToString("|")} (got '${input.sortBy}')"
        }
        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val summaries = projects.listSummaries()
        val sorted = sortedSummaries(summaries, sortKey)
        val page = sorted.take(limit)

        val totalCount = summaries.size
        val returnedCount = page.size
        val scoped = returnedCount != totalCount
        val tail = if (page.isEmpty()) {
            "no projects yet — call create_project to bootstrap one"
        } else {
            page.joinToString("\n") { "- ${it.id} (\"${it.title}\")" }
        }
        val headline = if (scoped) {
            "$returnedCount of $totalCount project(s), sorted by $sortKey"
        } else {
            "$totalCount project(s), sorted by $sortKey"
        }
        return ToolResult(
            title = "list projects ($returnedCount/$totalCount)",
            outputForLlm = "$headline:\n$tail",
            data = Output(
                totalCount = totalCount,
                returnedCount = returnedCount,
                projects = page,
            ),
        )
    }

    private fun sortedSummaries(summaries: List<ProjectSummary>, sortKey: String): List<ProjectSummary> =
        when (sortKey) {
            "updated-desc" -> summaries.sortedByDescending { it.updatedAtEpochMs }
            "created-desc" -> summaries.sortedByDescending { it.createdAtEpochMs }
            "title" -> summaries.sortedBy { it.title.lowercase() }
            "id" -> summaries.sortedBy { it.id }
            else -> error("unreachable — validated above: '$sortKey'")
        }

    companion object {
        private const val DEFAULT_SORT_BY = "updated-desc"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
        private val SORT_BY_ALLOWED = setOf("updated-desc", "created-desc", "title", "id")
    }
}
