package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.autoRegenHint
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.removeNode
import io.talevia.core.domain.source.rewriteNodeId
import io.talevia.core.domain.source.rewriteSourceBinding
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.video.emitTimelineSnapshot

/**
 * Mutate-verb handlers extracted from [SourceNodeActionTool] — the two
 * actions that rewrite existing [SourceNodeId]s (`remove`, `rename`).
 *
 * Counterpart to [SourceNodeCreateHandlers]; together they replace the
 * four `execute*` methods that used to live directly on
 * [SourceNodeActionTool]. Same axis the `ClipActionTool` refactor
 * (`9264c1a2`) established.
 *
 * Each handler accepts the minimal dependencies explicitly — `(projects,
 * input[, ctx])` — so it reads as a stand-alone procedure rather than
 * reaching into tool instance state. `rename` takes the extra `ctx`
 * because it emits a timeline snapshot when clip bindings change;
 * `remove` doesn't touch the timeline and doesn't need it.
 */

internal suspend fun executeSourceRemove(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
): ToolResult<SourceNodeActionTool.Output> {
    val nodeIdRaw = input.nodeId
        ?: error("action=remove requires `nodeId`")
    require(
        input.kind == null &&
            input.body == null &&
            input.parentIds == null &&
            input.sourceNodeId == null &&
            input.newNodeId == null &&
            input.oldId == null &&
            input.newId == null,
    ) {
        "action=remove rejects add/fork/rename payload fields — only `nodeId` is accepted"
    }

    val pid = ProjectId(input.projectId)
    val nodeId = SourceNodeId(nodeIdRaw)
    var removedKind = ""
    val updated = projects.mutateSource(pid) { source ->
        val existing = source.byId[nodeId]
            ?: error("Source node $nodeIdRaw not found in project ${input.projectId}")
        removedKind = existing.kind
        source.removeNode(nodeId)
    }
    val hint = updated.autoRegenHint()
    val regenNudge = if (hint != null) {
        " autoRegenHint: ${hint.staleClipCount} stale clip(s) — suggested next: ${hint.suggestedTool}."
    } else {
        ""
    }
    return ToolResult(
        title = "remove source node $nodeIdRaw",
        outputForLlm = "Removed $removedKind node $nodeIdRaw. " +
            "Clips that bound this id will be re-rendered next export.$regenNudge",
        data = SourceNodeActionTool.Output(
            projectId = input.projectId,
            action = "remove",
            removed = listOf(SourceNodeActionTool.RemoveResult(nodeId = nodeIdRaw, removedKind = removedKind)),
            autoRegenHint = hint,
        ),
    )
}

internal suspend fun executeSourceRename(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
    ctx: ToolContext,
): ToolResult<SourceNodeActionTool.Output> {
    val oldIdRaw = input.oldId
        ?: error("action=rename requires `oldId`")
    val newIdRaw = input.newId
        ?: error("action=rename requires `newId`")
    require(
        input.nodeId == null &&
            input.kind == null &&
            input.body == null &&
            input.parentIds == null &&
            input.sourceNodeId == null &&
            input.newNodeId == null,
    ) {
        "action=rename rejects add/remove/fork payload fields — only `oldId` + `newId` are accepted"
    }

    val pid = ProjectId(input.projectId)
    val oldId = SourceNodeId(oldIdRaw)
    val newId = SourceNodeId(newIdRaw)

    // No-op — return without mutating (no revision bump, no snapshot).
    if (oldId == newId) {
        return ToolResult(
            title = "rename $oldIdRaw (no-op)",
            outputForLlm = "oldId == newId ($oldIdRaw); no-op, project state untouched.",
            data = SourceNodeActionTool.Output(
                projectId = input.projectId,
                action = "rename",
                renamed = listOf(
                    SourceNodeActionTool.RenameResult(
                        oldId = oldIdRaw,
                        newId = newIdRaw,
                        parentsRewrittenCount = 0,
                        clipsRewrittenCount = 0,
                        lockfileEntriesRewrittenCount = 0,
                    ),
                ),
            ),
        )
    }

    require(isValidSourceNodeIdSlug(newIdRaw)) {
        "newId '$newIdRaw' is not a valid source-node id slug — must be non-empty, " +
            "lowercase ASCII letters / digits / '-' only, and not start or end with '-'."
    }

    var parentsRewritten = 0
    var clipsRewritten = 0
    var lockfileRewritten = 0

    val updated = projects.mutate(pid) { project ->
        val source = project.source
        if (source.byId[oldId] == null) {
            error(
                "Source node $oldIdRaw not found in project ${input.projectId}. " +
                    "Call source_query(select=nodes) to discover available ids.",
            )
        }
        if (source.byId[newId] != null) {
            error(
                "Source node $newIdRaw already exists in project ${input.projectId}; " +
                    "rename would collide. Pick a different newId or " +
                    "source_node_action(action=remove) first.",
            )
        }

        val (newSource, parentsTouched) = source.rewriteNodeId(oldId, newId)
        parentsRewritten = parentsTouched

        val (newTimeline, clipsTouched) = project.timeline.rewriteSourceBinding(oldId, newId)
        clipsRewritten = clipsTouched

        val (newLockfile, entriesTouched) =
            project.lockfile.rewriteSourceBinding(oldId, newId)
        lockfileRewritten = entriesTouched

        project.copy(
            source = newSource,
            timeline = newTimeline,
            lockfile = newLockfile,
        )
    }

    // Only emit a snapshot when the timeline actually changed — otherwise
    // we'd spam `revert_timeline` with a stream of identical snapshots.
    val snapshotIdNote = if (clipsRewritten > 0) {
        val partId = emitTimelineSnapshot(ctx, updated.timeline)
        " Timeline snapshot: ${partId.value}."
    } else {
        ""
    }

    return ToolResult(
        title = "rename source node $oldIdRaw -> $newIdRaw",
        outputForLlm = "Renamed source node $oldIdRaw to $newIdRaw. " +
            "Rewrote $parentsRewritten parent-ref(s), $clipsRewritten clip sourceBinding(s), " +
            "and $lockfileRewritten lockfile entry binding(s).$snapshotIdNote " +
            "Note: string ids inside typed bodies (e.g. narrative.shot.sceneId) are NOT touched — " +
            "update those via the kind-specific update_* tool if needed.",
        data = SourceNodeActionTool.Output(
            projectId = input.projectId,
            action = "rename",
            renamed = listOf(
                SourceNodeActionTool.RenameResult(
                    oldId = oldIdRaw,
                    newId = newIdRaw,
                    parentsRewrittenCount = parentsRewritten,
                    clipsRewrittenCount = clipsRewritten,
                    lockfileEntriesRewrittenCount = lockfileRewritten,
                ),
            ),
        ),
    )
}
