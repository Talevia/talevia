package io.talevia.core.tool.builtin.source.query

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Shared row type for every `source_query` select that returns SourceNode
 * projections — `nodes`, `descendants`, `ancestors`, and
 * `scope=all_projects` node enumeration. Per-select optional fields
 * ([body], [snippet], [matchOffset], [projectId], [depthFromRoot]) stay
 * null when the select doesn't populate them, so downstream decoders stay
 * forward-compatible (§3a-7).
 */
@Serializable
data class NodeRow(
    val id: String,
    val kind: String,
    val revision: Long,
    val contentHash: String,
    val parentIds: List<String>,
    /** Short human-readable summary (name + clip-description for typed nodes, key list for opaque). */
    val summary: String,
    /** Full JSON body — populated only when `Input.includeBody` is true. */
    val body: JsonElement? = null,
    /** Excerpt around the first `contentSubstring` hit. Populated only when that filter is set. */
    val snippet: String? = null,
    /** Character offset of the `contentSubstring` match. Populated only when that filter is set. */
    val matchOffset: Int? = null,
    /**
     * Owning project id — populated only when `scope=all_projects` so the
     * cross-project caller can pinpoint each hit. Null on single-project
     * queries because the owning project is already in the Input echo.
     */
    val projectId: String? = null,
    /**
     * Hop count from `Input.root` to this row. Populated only by
     * `select=descendants` / `select=ancestors` — null for all other
     * selects so old decoders stay forward-compatible (§3a-7). `0` is the
     * root itself, `1` the immediate neighbors, and so on.
     */
    val depthFromRoot: Int? = null,
)
