package io.talevia.core.tool.builtin.source

import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `source_node_action(action="import", …)` dispatch handler.
 *
 * Two mutually-exclusive input shapes — exactly one must be set:
 *   - **Live cross-project**: `fromProjectId` + `fromNodeId` copies a
 *     node from another open project in this Talevia instance —
 *     handled by [executeLiveImport].
 *   - **Portable envelope**: `envelope` (a JSON string produced by
 *     `export_source_node`) ingests a node from a backup /
 *     version-controlled source / another Talevia instance — handled
 *     by [executeEnvelopeImport].
 *
 * Cycle 136: this handler folded the standalone `ImportSourceNodeTool`
 * into the `source_node_action` action dispatcher (see commit body
 * for `source-action-import-fold-and-split`). Routes the input shape
 * → existing handler, then maps the [SourceNodeImportOutcome] back
 * onto the canonical [SourceNodeActionTool.Output] envelope.
 *
 * Content-addressed dedup is the load-bearing trick (preserved
 * verbatim from the pre-fold handlers): a `SourceNode.contentHash`
 * is a deterministic fingerprint over `(kind, body, parents)`, and
 * the AIGC lockfile keys cache entries on bound nodes' content
 * hashes. The moment an imported node lands in the target project
 * with the same `contentHash` as the source, every previous AIGC
 * generation that was bound to that node automatically becomes a
 * cache hit on the target side too — zero extra work.
 *
 * Self-import (`fromProjectId == toProjectId`) is rejected on the
 * live path; within-project copies belong to
 * `source_node_action(action="add")` / `(action="fork")` with a
 * fresh id. The envelope path doesn't need this check — by
 * construction the envelope was produced outside the target project.
 */
internal suspend fun executeSourceImport(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
    ctx: ToolContext,
): ToolResult<SourceNodeActionTool.Output> {
    val livePair = !input.fromProjectId.isNullOrBlank() && !input.fromNodeId.isNullOrBlank()
    val envelopeSet = !input.envelope.isNullOrBlank()
    require(livePair xor envelopeSet) {
        "action=import requires exactly one input shape: either (fromProjectId + fromNodeId) for live " +
            "cross-project reuse, or envelope for portable JSON ingestion " +
            "(livePair=$livePair, envelopeSet=$envelopeSet)."
    }

    // The `source_node_action` dispatcher accepts `projectId` as the
    // target — `import_source_node`'s pre-fold input had a separate
    // `toProjectId`. Map the dispatcher's target into the request type.
    val request = SourceNodeImportRequest(
        toProjectId = input.projectId,
        fromProjectId = input.fromProjectId,
        fromNodeId = input.fromNodeId,
        envelope = input.envelope,
        newNodeId = input.newNodeId,
    )

    val toPid = ctx.resolveProjectId(input.projectId)
    projects.get(toPid) ?: error("Target project ${toPid.value} not found")

    val outcome = if (livePair) {
        executeLiveImport(projects, request, toPid)
    } else {
        executeEnvelopeImport(projects, request, toPid)
    }

    return ToolResult(
        title = outcome.title,
        outputForLlm = outcome.outputForLlm,
        data = SourceNodeActionTool.Output(
            projectId = outcome.toProjectId,
            action = "import",
            imported = outcome.nodes,
            importFromProjectId = outcome.fromProjectId,
            importedFormatVersion = outcome.formatVersion,
        ),
    )
}
