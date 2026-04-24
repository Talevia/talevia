package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Create-verb handlers extracted from [SourceNodeActionTool] — the two
 * actions that mint fresh [SourceNodeId] values (`add`, `fork`).
 *
 * **Axis mirrors [io.talevia.core.tool.builtin.video.ClipCreateHandlers]**:
 * the split established in the `ClipActionTool` refactor (commit
 * `9264c1a2`) separates verbs that produce new ids from verbs that
 * mutate existing ids. Here `add` / `fork` share the
 * `require(newId !in source.byId)` collision guard + the
 * `SourceNode.create` / `addNode` call. Counterpart
 * [SourceNodeMutateHandlers] holds `remove` / `rename`, both of which
 * operate on pre-existing ids.
 */

internal suspend fun executeSourceAdd(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
): ToolResult<SourceNodeActionTool.Output> {
    val nodeIdRaw = input.nodeId
        ?: error("action=add requires `nodeId` (omit `sourceNodeId` / `newNodeId`)")
    val kind = input.kind
        ?: error("action=add requires `kind`")
    require(input.sourceNodeId == null && input.newNodeId == null) {
        "action=add rejects `sourceNodeId` / `newNodeId` — those are action=fork fields"
    }
    require(input.oldId == null && input.newId == null) {
        "action=add rejects `oldId` / `newId` — those are action=rename fields"
    }
    require(kind.isNotBlank()) { "kind must not be blank" }
    require(nodeIdRaw.isNotBlank()) { "nodeId must not be blank" }

    val body = input.body ?: JsonObject(emptyMap())
    val parentIds = input.parentIds ?: emptyList()

    val pid = ProjectId(input.projectId)
    val newId = SourceNodeId(nodeIdRaw)
    val parentRefs = parentIds.map { SourceRef(SourceNodeId(it)) }

    var finalHash = ""
    projects.mutateSource(pid) { source ->
        require(newId !in source.byId) {
            "Source node $nodeIdRaw already exists in project ${input.projectId}. " +
                "Use update_source_node_body to edit its body, or pick a fresh id."
        }
        val missing = parentRefs.map { it.nodeId }.filter { it !in source.byId }
        require(missing.isEmpty()) {
            "Parent node(s) not found in project ${input.projectId}: " +
                "${missing.joinToString(", ") { it.value }}. Create them first or pass an empty parentIds."
        }
        val node = SourceNode.create(
            id = newId,
            kind = kind,
            body = body,
            parents = parentRefs,
        )
        val next = source.addNode(node)
        finalHash = next.byId[newId]!!.contentHash
        next
    }

    val parentNote = if (parentRefs.isEmpty()) "" else " parents=[${parentIds.joinToString(",")}]"
    return ToolResult(
        title = "add source $kind $nodeIdRaw",
        outputForLlm = "Added $kind node $nodeIdRaw to ${input.projectId}$parentNote. " +
            "contentHash=$finalHash. Edit via update_source_node_body.",
        data = SourceNodeActionTool.Output(
            projectId = input.projectId,
            action = "add",
            added = listOf(
                SourceNodeActionTool.AddResult(
                    nodeId = nodeIdRaw,
                    kind = kind,
                    contentHash = finalHash,
                    parentIds = parentIds,
                ),
            ),
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeSourceFork(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
): ToolResult<SourceNodeActionTool.Output> {
    val sourceNodeIdRaw = input.sourceNodeId
        ?: error("action=fork requires `sourceNodeId`")
    require(
        input.nodeId == null &&
            input.kind == null &&
            input.body == null &&
            input.parentIds == null &&
            input.oldId == null &&
            input.newId == null,
    ) {
        "action=fork rejects add/remove/rename payload fields — use `sourceNodeId` + optional `newNodeId`"
    }

    val pid = ProjectId(input.projectId)
    projects.get(pid) ?: error("Project ${input.projectId} not found")

    val requestedNewId = input.newNodeId?.trim().takeIf { !it.isNullOrBlank() }
    require(requestedNewId != sourceNodeIdRaw) {
        "newNodeId ($sourceNodeIdRaw) equals sourceNodeId — fork must produce a distinct id"
    }
    val forkedId = SourceNodeId(requestedNewId ?: Uuid.random().toString())

    var kindOut: String? = null
    var hashOut: String? = null

    projects.mutateSource(pid) { source ->
        val original = source.nodes.firstOrNull { it.id.value == sourceNodeIdRaw }
            ?: error("Source node $sourceNodeIdRaw not found in project ${input.projectId}")
        if (source.nodes.any { it.id == forkedId }) {
            error(
                "Source node ${forkedId.value} already exists in project ${input.projectId}. " +
                    "Pick a different newNodeId (or source_node_action(action=remove) first).",
            )
        }
        kindOut = original.kind
        val forked = SourceNode.create(
            id = forkedId,
            kind = original.kind,
            body = original.body,
            parents = original.parents,
        )
        hashOut = forked.contentHash
        source.addNode(forked)
    }

    return ToolResult(
        title = "fork ${kindOut} ${forkedId.value}",
        outputForLlm = "Forked source node $sourceNodeIdRaw -> ${forkedId.value} " +
            "(kind=$kindOut, contentHash=$hashOut). Parents preserved; body copied verbatim. " +
            "Tweak with update_source_node_body to diverge from the original.",
        data = SourceNodeActionTool.Output(
            projectId = input.projectId,
            action = "fork",
            forked = listOf(
                SourceNodeActionTool.ForkResult(
                    sourceNodeId = sourceNodeIdRaw,
                    forkedNodeId = forkedId.value,
                    kind = kindOut!!,
                    contentHash = hashOut!!,
                ),
            ),
        ),
    )
}
