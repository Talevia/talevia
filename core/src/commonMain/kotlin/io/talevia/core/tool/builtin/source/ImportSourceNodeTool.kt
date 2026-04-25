package io.talevia.core.tool.builtin.source

import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Import a source node (and any parents it references) into a project —
 * unified entry for both cross-project live reuse and portable-envelope
 * ingestion. Closes the VISION §3.4 "可组合" leg.
 *
 * Two mutually-exclusive input shapes:
 *   - **Live cross-project**: pass `fromProjectId` + `fromNodeId` to copy
 *     a node out of another open project in this Talevia instance —
 *     handled by [executeLiveImport].
 *   - **Portable envelope**: pass `envelope` (a JSON string produced by
 *     `export_source_node`) to ingest a node from a backup / version-
 *     controlled source / another Talevia instance — handled by
 *     [executeEnvelopeImport].
 *
 * Exactly one of those two sources must be set; otherwise the call fails
 * loud. Everything else is shared:
 *
 * **Content-addressed dedup is the load-bearing trick.** A SourceNode's
 * [SourceNode.contentHash] is a deterministic fingerprint over `(kind, body,
 * parents)`. The AIGC lockfile keys cache entries on the bound nodes' content
 * hashes (not on their ids). So the moment an imported node lands in the target
 * project with the *same* contentHash as the source, every previous AIGC
 * generation that was bound to that node automatically becomes a cache hit on
 * the target side too — zero extra work.
 *
 * **Recursive parent walk.** Source nodes may reference upstream parents
 * ([SourceNode.parents]). We walk the parent chain and import in topological
 * order so the leaf's [io.talevia.core.domain.source.SourceRef]s point at nodes
 * that actually exist in the target. The envelope path is already topologically
 * ordered by the exporter's contract; the live path runs
 * [topoCollectForLiveImport] to achieve the same ordering.
 *
 * **Collision policy.** If the target already has a node at the same id:
 *  - same contentHash → reuse (idempotent, returns `skippedDuplicate=true`)
 *  - different contentHash → fail loudly with rename hint
 *
 * **Self-import is rejected** on the live path (`fromProjectId == toProjectId`);
 * within-project copies belong to `source_node_action(action="add")` /
 * `source_node_action(action="fork")` with a fresh id. The
 * envelope path doesn't need this check — by construction the envelope was
 * produced outside the target project.
 *
 * **Envelope version check.** The envelope path rejects payloads whose
 * `formatVersion` ≠ [ExportSourceNodeTool.FORMAT_VERSION]; version drift is a
 * breaking schema change, not an errata we silently paper over.
 *
 * **Structure (post split, debt-split-import-source-node-tool).** The class
 * itself carries the LLM-facing surface (Input / Output / ImportedNode data
 * classes, JSON schema, tool metadata, dispatch). The two strategy bodies
 * live in sibling files:
 *  - [executeLiveImport] — `ImportSourceNodeLiveHandler.kt`
 *  - [executeEnvelopeImport] — `ImportSourceNodeEnvelopeHandler.kt`
 *
 * No behaviour change; the tool id / helpText / JSON schema are byte-
 * identical to pre-split.
 */
class ImportSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<ImportSourceNodeTool.Input, ImportSourceNodeTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val toProjectId: String? = null,
        /** Live cross-project source project id. Must be paired with `fromNodeId`. */
        val fromProjectId: String? = null,
        /** Live cross-project source node id. Must be paired with `fromProjectId`. */
        val fromNodeId: String? = null,
        /**
         * Portable JSON envelope string produced by `export_source_node`.
         * Mutually exclusive with the live (`fromProjectId` + `fromNodeId`) pair.
         */
        val envelope: String? = null,
        /**
         * Optional rename for the *requested leaf only*. Parents keep their
         * original ids; rerun with explicit `newNodeId` per node if those collide.
         */
        val newNodeId: String? = null,
    )

    @Serializable data class ImportedNode(
        val originalId: String,
        val importedId: String,
        val kind: String,
        val skippedDuplicate: Boolean,
    )

    @Serializable data class Output(
        /** Null on the envelope path (the envelope has no source project id). */
        val fromProjectId: String? = null,
        val toProjectId: String,
        /** Non-null on the envelope path — echoes [SourceNodeEnvelope.formatVersion]. */
        val formatVersion: String? = null,
        val nodes: List<ImportedNode>,
    )

    override val id: String = "import_source_node"
    override val helpText: String =
        "Import a source node (and any parents it references) into a project. Two input shapes — " +
            "exactly one must be set: (a) `fromProjectId + fromNodeId` copies a node from another open " +
            "project in this instance; (b) `envelope` ingests a portable JSON envelope produced by " +
            "export_source_node (backup / cross-instance / version control). Idempotent on contentHash: " +
            "re-importing the same node is a no-op that returns the existing target id. AIGC lockfile " +
            "cache hits transfer automatically because cache keys are content-addressed. Pass " +
            "`newNodeId` only when the original id collides with a different-content node in the target."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = IMPORT_SOURCE_NODE_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val livePair = !input.fromProjectId.isNullOrBlank() && !input.fromNodeId.isNullOrBlank()
        val envelopeSet = !input.envelope.isNullOrBlank()
        require(livePair xor envelopeSet) {
            "exactly one input shape must be set: either (fromProjectId + fromNodeId) for live " +
                "cross-project reuse, or envelope for portable JSON ingestion " +
                "(livePair=$livePair, envelopeSet=$envelopeSet)"
        }

        val toPid = ctx.resolveProjectId(input.toProjectId)
        projects.get(toPid) ?: error("Target project ${toPid.value} not found")

        return if (livePair) {
            executeLiveImport(projects, input, toPid)
        } else {
            executeEnvelopeImport(projects, input, toPid)
        }
    }
}
