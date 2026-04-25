package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Captures the post-validation outcome of resolving a
 * [SourceNodeActionTool.Input] (action="update_body") to a concrete
 * operation against the source node. Two shapes:
 *
 *  - [Replace] — body / restore modes both end up here. The caller's
 *    [body] field already holds the final JsonElement that should land
 *    on the node. Direct-body passes the input through; restore mode
 *    pulls the historical body out of the per-node history log.
 *
 *  - [Merge] — merge mode. The caller knows the historical revision's
 *    body plus the (validated) [fieldPaths] to splice over the current
 *    body, but the actual current body must be read inside the
 *    `mutateSource` block.
 *
 * Extracted from the dispatcher file alongside the JSON schema so the
 * mode-validation + history-fetch logic — the most complex part of the
 * tool — has its own home. Tests can target it in isolation.
 */
internal sealed class BodyResolution {
    data class Replace(val body: JsonElement) : BodyResolution()
    data class Merge(
        val historicalBody: JsonObject,
        val fieldPaths: List<String>,
    ) : BodyResolution()
}

/**
 * Validate the three-mode mutual-exclusion contract on
 * `source_node_action(action="update_body")` and resolve the
 * historical-body lookup needed for restore / merge modes. Throws with
 * an agent-friendly message on any contract violation; otherwise
 * returns a [BodyResolution] capturing what the mutateSource block
 * should do.
 *
 * Pre-fetches the historical body for restore + merge modes so the IO
 * doesn't sit inside the source mutex.
 *
 * Behaviour byte-identical to the pre-fold inline path.
 */
internal suspend fun resolveBodyEdit(
    projects: ProjectStore,
    pid: ProjectId,
    nodeId: SourceNodeId,
    input: SourceNodeActionTool.Input,
): BodyResolution {
    val hasBody = input.body != null
    val hasRestore = input.restoreFromRevisionIndex != null
    val hasMerge = input.mergeFromRevisionIndex != null
    val modeCount = listOf(hasBody, hasRestore, hasMerge).count { it }
    if (modeCount > 1) {
        error(
            "source_node_action(action=\"update_body\") takes exactly one of `body` / " +
                "`restoreFromRevisionIndex` / `mergeFromRevisionIndex` (got $modeCount). " +
                "Drop the extras; body passes an explicit new state, restore replaces the " +
                "whole body from history, merge copies named fields from history over the " +
                "current body.",
        )
    }
    if (modeCount == 0) {
        error(
            "source_node_action(action=\"update_body\") requires one of `body` (full " +
                "replacement) / `restoreFromRevisionIndex` (whole-body rollback, 0=newest) / " +
                "`mergeFromRevisionIndex` + `mergeFieldPaths` (per-field merge from history). " +
                "Call source_query(select=history, root=${nodeId.value}) to discover " +
                "available indices.",
        )
    }
    if (hasMerge) {
        val paths = input.mergeFieldPaths
        if (paths.isNullOrEmpty()) {
            error(
                "mergeFromRevisionIndex requires non-empty `mergeFieldPaths`. " +
                    "Pass the list of top-level body keys to copy from the historical " +
                    "revision; an empty merge is a no-op.",
            )
        }
    }
    if (input.mergeFieldPaths != null && !hasMerge) {
        error(
            "`mergeFieldPaths` requires `mergeFromRevisionIndex` to be set. Drop one or " +
                "both — `mergeFieldPaths` only applies in merge mode.",
        )
    }

    if (hasBody) {
        return BodyResolution.Replace(input.body!!)
    }

    // Restore + merge share the same window-fetch + bounds-check, then
    // diverge on what they do with the historical body.
    val idx = (input.restoreFromRevisionIndex ?: input.mergeFromRevisionIndex)!!
    val modeLabel = if (hasRestore) "restoreFromRevisionIndex" else "mergeFromRevisionIndex"
    if (idx < 0) {
        error(
            "$modeLabel must be non-negative (got $idx). " +
                "0 = newest historical revision; larger N goes further back.",
        )
    }
    val window = projects.listSourceNodeHistory(pid, nodeId, limit = idx + 1)
    if (window.isEmpty()) {
        error(
            "Source node ${nodeId.value} has no body-history entries — nothing to " +
                "${if (hasRestore) "restore" else "merge from"}. Either this node was " +
                "never updated, or the bundle was created before body-history tracking " +
                "landed. Call source_query(select=history, root=${nodeId.value}) to confirm.",
        )
    }
    if (idx >= window.size) {
        val trueWindow = if (window.size >= idx + 1) window else
            projects.listSourceNodeHistory(pid, nodeId, limit = 100)
        error(
            "$modeLabel=$idx is out of range for node ${nodeId.value} " +
                "(only ${trueWindow.size} historical revision(s) available; valid " +
                "indices 0..${trueWindow.size - 1}). Call source_query(select=history, " +
                "root=${nodeId.value}) to see all revisions.",
        )
    }
    val historical = window[idx].body
    if (hasRestore) {
        return BodyResolution.Replace(historical)
    }
    val historicalObj = historical as? JsonObject
        ?: error(
            "history entry $idx is not a JSON object (got ${historical::class.simpleName}); " +
                "cannot per-field merge from a non-object revision.",
        )
    val missing = input.mergeFieldPaths!!.filter { it !in historicalObj.keys }
    if (missing.isNotEmpty()) {
        error(
            "mergeFieldPaths contains keys not present in historical revision $idx: " +
                "${missing.joinToString(", ")}. The historical body has " +
                "${historicalObj.keys.joinToString(", ")}. If you intend to drop " +
                "these fields, pass an explicit `body` instead.",
        )
    }
    return BodyResolution.Merge(historicalObj, input.mergeFieldPaths)
}
