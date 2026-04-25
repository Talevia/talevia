package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.autoRegenHint
import io.talevia.core.domain.source.BodyRevision
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * `source_node_action(action="update_body")` handler — replace a source
 * node's [io.talevia.core.domain.source.SourceNode.body] wholesale,
 * preserving the legacy [io.talevia.core.tool.builtin.source.UpdateSourceNodeBodyTool]
 * semantics verbatim:
 *
 * - kind / parents / id are not editable here (use the matching action
 *   verbs);
 * - three input modes (mutually exclusive): full-replacement [body],
 *   `restoreFromRevisionIndex`, `mergeFromRevisionIndex` +
 *   `mergeFieldPaths`. Mode validation lives in
 *   [resolveBodyEdit] (`UpdateSourceNodeBodyResolver.kt`);
 * - bumps `contentHash` so bound clips go stale; the per-node history
 *   log captures the pre-edit body via
 *   [ProjectStore.appendSourceNodeHistory] when the body actually
 *   changed (no-op edits skip the log entry).
 *
 * The handler does not emit a `Part.TimelineSnapshot` — body edits
 * touch zero `Clip.sourceBinding` fields, so `revert_timeline` would
 * be a no-op anyway. Project-level undo for source edits stays in
 * `project_snapshot_action`.
 */
internal suspend fun executeSourceUpdateBody(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
    clock: Clock,
): ToolResult<SourceNodeActionTool.Output> {
    val rawNodeId = input.nodeId?.takeIf { it.isNotBlank() }
        ?: error("action=update_body requires `nodeId`")
    val pid = ProjectId(input.projectId)
    val nodeId = SourceNodeId(rawNodeId)

    val resolution = resolveBodyEdit(projects, pid, nodeId, input)

    var previousHash = ""
    var previousBody: JsonElement = JsonObject(emptyMap())
    var resolvedNewBody: JsonObject = JsonObject(emptyMap())
    var kindSeen = ""

    val updated = projects.mutateSource(pid) { source ->
        val existing = source.byId[nodeId]
            ?: error(
                "Source node ${nodeId.value} not found in project ${input.projectId}. " +
                    "Call source_query(select=nodes) or source_query(select=node_detail) to discover available ids.",
            )
        previousHash = existing.contentHash
        previousBody = existing.body
        kindSeen = existing.kind
        val bodyAsObject: JsonObject = when (resolution) {
            is BodyResolution.Replace -> resolution.body as? JsonObject
                ?: error(
                    "restored body is not a JSON object (got ${resolution.body::class.simpleName}). " +
                        "History entry is corrupt or was written by an older schema.",
                )
            is BodyResolution.Merge -> {
                val current = (existing.body as? JsonObject)
                    ?: error(
                        "current body of ${nodeId.value} is not a JSON object " +
                            "(got ${existing.body::class.simpleName}); cannot per-field " +
                            "merge over a non-object body.",
                    )
                buildJsonObject {
                    current.forEach { (k, v) -> put(k, v) }
                    resolution.fieldPaths.forEach { key ->
                        put(key, resolution.historicalBody[key]!!)
                    }
                }
            }
        }
        resolvedNewBody = bodyAsObject
        source.replaceNode(nodeId) { node -> node.copy(body = bodyAsObject) }
    }

    if (previousBody != (resolvedNewBody as JsonElement)) {
        projects.appendSourceNodeHistory(
            pid,
            nodeId,
            BodyRevision(
                body = previousBody,
                overwrittenAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    val newHash = updated.source.byId[nodeId]!!.contentHash
    val boundClipCount = updated.timeline.tracks.sumOf { track ->
        track.clips.count { nodeId in it.sourceBinding }
    }

    val hint = updated.autoRegenHint()
    val staleHint = if (boundClipCount == 0) {
        " No clips bind this node directly; DAG descendants unchanged (body edits don't rewrite " +
            "parent refs). Call project_query(select=stale_clips) if you want to surface transitively-stale clips."
    } else {
        " $boundClipCount clip(s) bind this node directly — they will show up as stale in " +
            "project_query(select=stale_clips) until re-rendered."
    }
    val regenNudge = if (hint != null) {
        " autoRegenHint: ${hint.staleClipCount} clip(s) need regeneration — suggested next: ${hint.suggestedTool}."
    } else {
        ""
    }

    return ToolResult(
        title = "update source body for ${nodeId.value}",
        outputForLlm = "Replaced body of ${nodeId.value} ($kindSeen). " +
            "contentHash $previousHash → $newHash.$staleHint$regenNudge",
        data = SourceNodeActionTool.Output(
            projectId = input.projectId,
            action = "update_body",
            updatedBody = listOf(
                SourceNodeActionTool.UpdateBodyResult(
                    nodeId = nodeId.value,
                    kind = kindSeen,
                    previousContentHash = previousHash,
                    newContentHash = newHash,
                    boundClipCount = boundClipCount,
                ),
            ),
            autoRegenHint = hint,
        ),
    )
}
