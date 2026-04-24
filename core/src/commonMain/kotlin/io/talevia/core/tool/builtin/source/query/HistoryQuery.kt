package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * One past body snapshot for `select=history`. [body] is the body as it
 * existed at the time of overwriting — full snapshot, not a diff — and
 * [overwrittenAtEpochMs] is the wall-clock timestamp when
 * `update_source_node_body` replaced it with a newer one.
 *
 * Sequence contract: rows come newest-first from [runHistoryQuery], so
 * `rows.first()` is the most-recent overwritten state and `rows.last()` is
 * the oldest one in the returned window. The current body is NOT included
 * — it lives on the `SourceNode` itself (`describe_source_node` or
 * `select=nodes` with `includeBody=true`).
 */
@Serializable
data class BodyRevisionRow(
    val body: JsonElement,
    val overwrittenAtEpochMs: Long,
)

/**
 * `select=history` — past body snapshots for a single source node,
 * newest-first.
 *
 * Reads `<bundle>/source-history/<root>.jsonl` via
 * [ProjectStore.listSourceNodeHistory]. JSONL is append-order (oldest-
 * first); we reverse + cap at [SourceQueryTool.Input.limit] (default 20,
 * clamped `[1, 100]` — the per-file JSONL is already small so a pagination
 * cap past that is rarely useful). A node that was never updated (history
 * file absent) returns an empty set legitimately — distinct from "unknown
 * node id" which is not an error here (history's raison d'être is
 * surfacing lost drafts; erroring on "no drafts" would cost an extra
 * source_query(select=nodes) roundtrip for every audit call).
 *
 * The handler re-validates the node exists on the current DAG before
 * reading — if the caller passed a stale id (node was deleted + a
 * replacement with the same id never existed), we surface that clearly in
 * the outputForLlm narrative so the agent doesn't chase ghost state.
 */
internal suspend fun runHistoryQuery(
    store: ProjectStore,
    project: Project,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> {
    val rootStr = input.root
        ?: error(
            "select=history requires `root` (the source node id whose past bodies to read). " +
                "Call source_query(select=nodes) to discover available ids.",
        )
    val nodeId = SourceNodeId(rootStr)
    val nodeExists = project.source.byId[nodeId] != null
    val rawLimit = input.limit ?: DEFAULT_HISTORY_LIMIT
    val limit = rawLimit.coerceIn(1, MAX_HISTORY_LIMIT)

    val revisions = store.listSourceNodeHistory(project.id, nodeId, limit)
    val rows = revisions.map { rev ->
        BodyRevisionRow(
            body = rev.body,
            overwrittenAtEpochMs = rev.overwrittenAtEpochMs,
        )
    }
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(BodyRevisionRow.serializer()),
        rows,
    ) as JsonArray

    val narrative = when {
        !nodeExists && rows.isEmpty() ->
            "Source node '$rootStr' is not on the current DAG, and no history file exists for it " +
                "either. Call source_query(select=nodes) to discover valid ids."
        !nodeExists ->
            "Source node '$rootStr' is no longer on the current DAG, but ${rows.size} past revision(s) " +
                "are preserved in the bundle. (Deleted nodes keep their audit trail.)"
        rows.isEmpty() ->
            "Source node '$rootStr' exists but has no body-history entries — either no " +
                "update_source_node_body call has landed on it, or this bundle was created on a " +
                "Talevia version that predates body-history tracking."
        else ->
            "${rows.size} past revision(s) of source node '$rootStr' (newest first). Most recent " +
                "was overwritten at epoch-ms ${rows.first().overwrittenAtEpochMs}."
    }

    return ToolResult(
        title = "source_query history $rootStr (${rows.size})",
        outputForLlm = narrative,
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_HISTORY,
            total = rows.size,
            returned = rows.size,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}

private const val DEFAULT_HISTORY_LIMIT = 20
private const val MAX_HISTORY_LIMIT = 100
