package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Import a source node (and any parents it references) into a project —
 * unified entry for both cross-project live reuse and portable-envelope
 * ingestion. Closes the VISION §3.4 "可组合" leg.
 *
 * Two mutually-exclusive input shapes:
 *   - **Live cross-project**: pass `fromProjectId` + `fromNodeId` to copy
 *     a node out of another open project in this Talevia instance.
 *   - **Portable envelope**: pass `envelope` (a JSON string produced by
 *     `export_source_node`) to ingest a node from a backup / version-
 *     controlled source / another Talevia instance.
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
 * order so the leaf's [SourceRef]s point at nodes that actually exist in the
 * target. The envelope path is already topologically ordered by the exporter's
 * contract; the live path runs [topoCollect] to achieve the same ordering.
 *
 * **Collision policy.** If the target already has a node at the same id:
 *  - same contentHash → reuse (idempotent, returns `skippedDuplicate=true`)
 *  - different contentHash → fail loudly with rename hint
 *
 * **Self-import is rejected** on the live path (`fromProjectId == toProjectId`);
 * within-project copies belong to `set_character_ref` / `set_style_bible` etc
 * with a fresh id. The envelope path doesn't need this check — by construction
 * the envelope was produced outside the target project.
 *
 * **Envelope version check.** The envelope path rejects payloads whose
 * `formatVersion` ≠ [ExportSourceNodeTool.FORMAT_VERSION]; version drift is a
 * breaking schema change, not an errata we silently paper over.
 */
class ImportSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<ImportSourceNodeTool.Input, ImportSourceNodeTool.Output> {

    private val json: Json get() = JsonConfig.default

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

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("toProjectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
            putJsonObject("fromProjectId") {
                put("type", "string")
                put(
                    "description",
                    "Live cross-project source project id (pair with fromNodeId). Mutually exclusive with envelope.",
                )
            }
            putJsonObject("fromNodeId") {
                put("type", "string")
                put("description", "Live cross-project source node id. Required when fromProjectId is set.")
            }
            putJsonObject("envelope") {
                put("type", "string")
                put(
                    "description",
                    "JSON envelope string produced by export_source_node (data.envelope output). " +
                        "Mutually exclusive with fromProjectId / fromNodeId.",
                )
            }
            putJsonObject("newNodeId") {
                put("type", "string")
                put(
                    "description",
                    "Optional rename for the imported leaf node only (parents keep their original ids). " +
                        "Use when the original id would collide with a different-content node in the target.",
                )
            }
        }
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

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
            executeLive(input, toPid)
        } else {
            executeEnvelope(input, toPid)
        }
    }

    private suspend fun executeLive(input: Input, toPid: ProjectId): ToolResult<Output> {
        val fromProjectIdStr = input.fromProjectId!!
        val fromNodeIdStr = input.fromNodeId!!
        require(fromProjectIdStr != toPid.value) {
            "fromProjectId and toProjectId are the same ($fromProjectIdStr); " +
                "use set_character_ref / set_style_bible / set_brand_palette with a fresh nodeId " +
                "for within-project copies."
        }
        val fromPid = ProjectId(fromProjectIdStr)
        val fromProject = projects.get(fromPid)
            ?: error("Source project $fromProjectIdStr not found")

        val leafId = SourceNodeId(fromNodeIdStr)
        val ordered = topoCollect(fromProject.source.nodes.associateBy { it.id }, leafId)
        val renamed = input.newNodeId?.takeIf { it.isNotBlank() }?.let { SourceNodeId(it) }

        val imported = mutableListOf<ImportedNode>()
        projects.mutateSource(toPid) { source ->
            var working = source
            val remap = mutableMapOf<SourceNodeId, SourceNodeId>()
            for (node in ordered) {
                val proposedId = if (node.id == leafId && renamed != null) renamed else node.id
                val remappedParents = node.parents.map { ref ->
                    SourceRef(remap[ref.nodeId] ?: ref.nodeId)
                }
                val effectiveHash = if (remappedParents == node.parents) {
                    node.contentHash
                } else {
                    SourceNode.create(node.id, node.kind, node.body, remappedParents).contentHash
                }
                val existingByHash = working.nodes.firstOrNull { it.contentHash == effectiveHash }
                val existingAtId = working.nodes.firstOrNull { it.id == proposedId }

                when {
                    existingByHash != null -> {
                        remap[node.id] = existingByHash.id
                        imported += ImportedNode(
                            originalId = node.id.value,
                            importedId = existingByHash.id.value,
                            kind = node.kind,
                            skippedDuplicate = true,
                        )
                    }
                    existingAtId != null -> error(
                        "Target project ${toPid.value} already has a node ${proposedId.value} " +
                            "with a different contentHash (kind=${existingAtId.kind}). Pick a fresh " +
                            "newNodeId or remove_source_node first.",
                    )
                    else -> {
                        working = working.addNode(
                            SourceNode.create(
                                id = proposedId,
                                kind = node.kind,
                                body = node.body,
                                parents = remappedParents,
                            ),
                        )
                        remap[node.id] = proposedId
                        imported += ImportedNode(
                            originalId = node.id.value,
                            importedId = proposedId.value,
                            kind = node.kind,
                            skippedDuplicate = false,
                        )
                    }
                }
            }
            working
        }

        val leaf = imported.last()
        val parentNote = if (imported.size > 1) " (with ${imported.size - 1} parent node(s))" else ""
        val dedupNote = imported.count { it.skippedDuplicate }.takeIf { it > 0 }
            ?.let { " — $it already-present node(s) reused" }
            .orEmpty()
        return ToolResult(
            title = "import ${leaf.kind} ${leaf.importedId}",
            outputForLlm = "Imported $fromNodeIdStr from $fromProjectIdStr into " +
                "${toPid.value} as ${leaf.importedId}$parentNote.$dedupNote " +
                "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls on the target project.",
            data = Output(
                fromProjectId = fromProjectIdStr,
                toProjectId = toPid.value,
                nodes = imported,
            ),
        )
    }

    private suspend fun executeEnvelope(input: Input, toPid: ProjectId): ToolResult<Output> {
        val decoded: SourceNodeEnvelope = try {
            json.decodeFromString(SourceNodeEnvelope.serializer(), input.envelope!!)
        } catch (e: SerializationException) {
            error("Envelope is not valid JSON for the source-export schema: ${e.message}")
        }
        require(decoded.formatVersion == ExportSourceNodeTool.FORMAT_VERSION) {
            "Envelope formatVersion='${decoded.formatVersion}' is not understood by this importer " +
                "(expected ${ExportSourceNodeTool.FORMAT_VERSION}). Re-export from a compatible Talevia build."
        }
        require(decoded.nodes.isNotEmpty()) {
            "Envelope contains no nodes; nothing to import."
        }
        require(decoded.nodes.any { it.id == decoded.rootNodeId }) {
            "Envelope rootNodeId='${decoded.rootNodeId}' not present in its own nodes list — envelope corrupt."
        }

        val rootRename = input.newNodeId?.takeIf { it.isNotBlank() }
        val imported = mutableListOf<ImportedNode>()
        projects.mutateSource(toPid) { source ->
            var working = source
            val remap = mutableMapOf<SourceNodeId, SourceNodeId>()
            for (exported in decoded.nodes) {
                val originalId = SourceNodeId(exported.id)
                val proposedId = if (exported.id == decoded.rootNodeId && rootRename != null) {
                    SourceNodeId(rootRename)
                } else {
                    originalId
                }
                val remappedParents = exported.parents.map { parentId ->
                    val mapped = remap[SourceNodeId(parentId)] ?: SourceNodeId(parentId)
                    SourceRef(mapped)
                }
                val candidate = SourceNode.create(
                    id = proposedId,
                    kind = exported.kind,
                    body = exported.body,
                    parents = remappedParents,
                )
                val existingByHash = working.nodes.firstOrNull { it.contentHash == candidate.contentHash }
                val existingAtId = working.nodes.firstOrNull { it.id == proposedId }

                when {
                    existingByHash != null -> {
                        remap[originalId] = existingByHash.id
                        imported += ImportedNode(
                            originalId = exported.id,
                            importedId = existingByHash.id.value,
                            kind = exported.kind,
                            skippedDuplicate = true,
                        )
                    }
                    existingAtId != null -> error(
                        "Target project ${toPid.value} already has a node ${proposedId.value} " +
                            "with a different contentHash (kind=${existingAtId.kind}). Pick a fresh " +
                            "newNodeId or remove_source_node first.",
                    )
                    else -> {
                        working = working.addNode(candidate)
                        remap[originalId] = proposedId
                        imported += ImportedNode(
                            originalId = exported.id,
                            importedId = proposedId.value,
                            kind = exported.kind,
                            skippedDuplicate = false,
                        )
                    }
                }
            }
            working
        }

        val leaf = imported.last()
        val parentNote = if (imported.size > 1) " (with ${imported.size - 1} parent node(s))" else ""
        val dedupNote = imported.count { it.skippedDuplicate }.takeIf { it > 0 }
            ?.let { " — $it already-present node(s) reused" }
            .orEmpty()
        return ToolResult(
            title = "import envelope ${leaf.kind} ${leaf.importedId}",
            outputForLlm = "Ingested ${decoded.rootNodeId} (${decoded.formatVersion}) into " +
                "${toPid.value} as ${leaf.importedId}$parentNote.$dedupNote " +
                "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls.",
            data = Output(
                toProjectId = toPid.value,
                formatVersion = decoded.formatVersion,
                nodes = imported,
            ),
        )
    }

    /**
     * Collect [leafId] and every transitive parent in topological (parents-first) order.
     * Throws if the leaf or any referenced parent is missing — incomplete imports
     * would leave dangling [SourceRef]s on the target project.
     */
    internal fun topoCollect(byId: Map<SourceNodeId, SourceNode>, leafId: SourceNodeId): List<SourceNode> {
        val visited = mutableSetOf<SourceNodeId>()
        val result = mutableListOf<SourceNode>()
        fun visit(id: SourceNodeId) {
            if (id in visited) return
            visited += id
            val node = byId[id] ?: error(
                "Source node ${id.value} not found on the source project (referenced from import chain).",
            )
            for (parent in node.parents) visit(parent.nodeId)
            result += node
        }
        visit(leafId)
        return result
    }
}
