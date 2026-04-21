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
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Ingest a portable JSON envelope (produced by [ExportSourceNodeTool]) into a
 * target project. Closes the VISION §5.1 "跨 project 复用" loop across Talevia
 * instances / backups / version control.
 *
 * Semantics match [ImportSourceNodeTool] exactly so the agent can reason about
 * either flavor interchangeably:
 *  - Content-addressed dedup: a node whose contentHash matches a pre-existing
 *    target node is **not** re-added; the importer remaps references and flags
 *    `skippedDuplicate=true`. AIGC lockfile cache hits transfer automatically
 *    because cache keys are content-addressed.
 *  - Id-collision without contentHash match fails loudly with a rename hint —
 *    silently overwriting would invalidate downstream bindings.
 *  - Parents are imported first (topologically); the envelope is required to be
 *    in that order, matching the export tool's output contract.
 *
 * Rejects payloads whose `formatVersion` ≠ the current importer version —
 * version drift is a breaking schema change, not an errata we silently paper
 * over. The exporter only emits one version today; the check becomes load-bearing
 * the first time the schema grows a field with non-defaultable semantics.
 */
class ImportSourceNodeFromJsonTool(
    private val projects: ProjectStore,
) : Tool<ImportSourceNodeFromJsonTool.Input, ImportSourceNodeFromJsonTool.Output> {

    private val json: Json get() = JsonConfig.default

    @Serializable data class Input(
        val toProjectId: String,
        /** The exact JSON string produced by `export_source_node`. */
        val envelope: String,
        /**
         * Optional rename for the root (leaf) node only — matches
         * [ImportSourceNodeTool.Input.newNodeId]. Parents keep their original ids;
         * re-run with a fresh envelope if those collide.
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
        val toProjectId: String,
        val formatVersion: String,
        val nodes: List<ImportedNode>,
    )

    override val id: String = "import_source_node_from_json"
    override val helpText: String =
        "Ingest a portable source-node envelope (produced by export_source_node) into a project. " +
            "Dedup, collision handling, and parent-walk semantics match import_source_node. Reject " +
            "payloads whose formatVersion doesn't match the current importer."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("toProjectId") { put("type", "string") }
            putJsonObject("envelope") {
                put("type", "string")
                put(
                    "description",
                    "JSON envelope string produced by export_source_node (data.envelope output).",
                )
            }
            putJsonObject("newNodeId") {
                put("type", "string")
                put(
                    "description",
                    "Optional rename for the root (leaf) node only. Use when the original id " +
                        "would collide with a different-content node in the target.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("toProjectId"), JsonPrimitive("envelope"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val decoded: SourceNodeEnvelope = try {
            json.decodeFromString(SourceNodeEnvelope.serializer(), input.envelope)
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

        val toPid = ProjectId(input.toProjectId)
        projects.get(toPid) ?: error("Target project ${input.toProjectId} not found")

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
                        "Target project ${input.toProjectId} already has a node ${proposedId.value} " +
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
                "${input.toProjectId} as ${leaf.importedId}$parentNote.$dedupNote " +
                "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls.",
            data = Output(
                toProjectId = input.toProjectId,
                formatVersion = decoded.formatVersion,
                nodes = imported,
            ),
        )
    }
}
