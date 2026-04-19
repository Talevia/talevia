package io.talevia.core.tool.builtin.source

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Copy a source node from one project into another (VISION §3.4 — "可组合").
 *
 * Snapshots / fork / diff handle a single project's lifecycle; this tool closes
 * the fourth §3.4 leg — *cross-project reuse*. Typical use: the user defined a
 * "Mei" character_ref in their narrative project and now wants to use the same
 * character in a vlog project without re-typing the visualDescription / voiceId
 * / LoRA pin.
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
 * target. Today's consistency nodes are leaves, so the recursion is usually a
 * no-op — but the moment a richer source schema lands (e.g. a "scene" node
 * referencing several "character_ref" parents), this generalises without
 * change.
 *
 * **Collision policy.** If the target already has a node at the same id:
 *  - same contentHash → reuse (idempotent, returns `skippedDuplicate=true`)
 *  - different contentHash → fail loudly with rename hint
 * The agent must explicitly resolve the conflict (pick a `newNodeId`,
 * `remove_source_node` first, etc.) — silently overwriting would invalidate
 * every downstream binding on the existing node.
 *
 * **Self-import is rejected.** `from == to` is almost certainly a mistake;
 * within-project copies belong to a different tool (`define_*` with a fresh
 * id), so we fail loudly rather than silently no-op.
 */
class ImportSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<ImportSourceNodeTool.Input, ImportSourceNodeTool.Output> {

    @Serializable data class Input(
        val fromProjectId: String,
        val fromNodeId: String,
        val toProjectId: String,
        /**
         * Optional rename for the *requested leaf only*. Parents (if any) are
         * imported with their original ids — caller can rerun with explicit
         * `newNodeId` per node if those collide.
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
        val fromProjectId: String,
        val toProjectId: String,
        val nodes: List<ImportedNode>,
    )

    override val id: String = "import_source_node"
    override val helpText: String =
        "Copy a source node (and any parent nodes it references) from one project into another. " +
            "Idempotent on contentHash — re-importing the same node is a no-op that returns the " +
            "existing target id. Use this to share a character_ref / style_bible / brand_palette " +
            "across projects without re-typing the body. AIGC lockfile cache hits transfer " +
            "automatically because cache keys are content-addressed, not id-addressed."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("fromProjectId") { put("type", "string") }
            putJsonObject("fromNodeId") { put("type", "string") }
            putJsonObject("toProjectId") { put("type", "string") }
            putJsonObject("newNodeId") {
                put("type", "string")
                put(
                    "description",
                    "Optional rename for the imported leaf node only (parents keep their original ids). " +
                        "Use when the original id would collide with a different-content node in the target.",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("fromProjectId"),
                    JsonPrimitive("fromNodeId"),
                    JsonPrimitive("toProjectId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.fromProjectId != input.toProjectId) {
            "fromProjectId and toProjectId are the same (${input.fromProjectId}); " +
                "use define_character_ref / define_style_bible / define_brand_palette with a fresh nodeId " +
                "for within-project copies."
        }
        val fromPid = ProjectId(input.fromProjectId)
        val toPid = ProjectId(input.toProjectId)
        val fromProject = projects.get(fromPid)
            ?: error("Source project ${input.fromProjectId} not found")
        projects.get(toPid)
            ?: error("Target project ${input.toProjectId} not found")

        val leafId = SourceNodeId(input.fromNodeId)
        val ordered = topoCollect(fromProject.source.nodes.associateBy { it.id }, leafId)
        val renamed = input.newNodeId?.takeIf { it.isNotBlank() }?.let { SourceNodeId(it) }

        val imported = mutableListOf<ImportedNode>()
        projects.mutateSource(toPid) { source ->
            var working = source
            // originalId → targetId. Populated as we walk topologically so a
            // child's SourceRefs can be remapped if a parent was deduped to a
            // pre-existing target node under a different id.
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
                        "Target project ${input.toProjectId} already has a node ${proposedId.value} " +
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
            outputForLlm = "Imported ${input.fromNodeId} from ${input.fromProjectId} into " +
                "${input.toProjectId} as ${leaf.importedId}$parentNote.$dedupNote " +
                "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls on the target project.",
            data = Output(
                fromProjectId = input.fromProjectId,
                toProjectId = input.toProjectId,
                nodes = imported,
            ),
        )
    }

    /**
     * Collect [leafId] and every transitive parent in topological (parents-first) order.
     * Throws if the leaf or any referenced parent is missing — incomplete imports
     * would leave dangling [SourceRef]s on the target project.
     */
    private fun topoCollect(byId: Map<SourceNodeId, SourceNode>, leafId: SourceNodeId): List<SourceNode> {
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
