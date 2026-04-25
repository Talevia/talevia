package io.talevia.core.tool.builtin.source

import kotlinx.serialization.Serializable

/**
 * Per-node outcome of a source-node import (live cross-project or
 * portable envelope). Each row reports what landed: original id from
 * the source side, the id under which the node now lives in the
 * target project, the kind, and whether the import skipped a write
 * because a content-hash-equivalent node already existed.
 *
 * Top-level type (not a nested member of `ImportSourceNodeTool`)
 * since cycle 136 folded that standalone tool into
 * `source_node_action(action="import")`. Lifted here so the live /
 * envelope handlers and the [SourceNodeActionTool.Output] echo path
 * can share one definition without coupling to a defunct dispatcher.
 */
@Serializable
data class SourceNodeImportedNode(
    val originalId: String,
    val importedId: String,
    val kind: String,
    val skippedDuplicate: Boolean,
)

/**
 * Adapter shape passed from `source_node_action(action="import")`
 * dispatch to the live / envelope handlers — exactly the fields the
 * handlers previously read off `ImportSourceNodeTool.Input`. Keeping
 * a dedicated request type means the handlers stay decoupled from
 * the dispatcher's full Input data class (which carries unrelated
 * fields like `body` / `oldId` / `restoreFromRevisionIndex`).
 *
 * Internal: only the `import` dispatch path constructs this; LLMs
 * never see it.
 */
internal data class SourceNodeImportRequest(
    val toProjectId: String?,
    val fromProjectId: String?,
    val fromNodeId: String?,
    val envelope: String?,
    val newNodeId: String?,
)

/**
 * Result returned by the live / envelope import handlers. The
 * dispatcher folds these into the canonical [SourceNodeActionTool.Output]
 * by populating [SourceNodeActionTool.Output.imported] +
 * [SourceNodeActionTool.Output.importFromProjectId] /
 * [SourceNodeActionTool.Output.importedFormatVersion] and producing
 * the human `outputForLlm` string.
 */
internal data class SourceNodeImportOutcome(
    val fromProjectId: String?,
    val toProjectId: String,
    val formatVersion: String?,
    val nodes: List<SourceNodeImportedNode>,
    val outputForLlm: String,
    val title: String,
)
