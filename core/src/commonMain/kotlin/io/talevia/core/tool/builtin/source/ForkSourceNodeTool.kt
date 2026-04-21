package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Duplicate a source node **within the same project** under a new id —
 * the within-project counterpart to [ImportSourceNodeTool].
 *
 * Intended for cross-shot / cross-variant iteration (VISION §5.5):
 * "try the same scene with a different character" or "clone the style
 * bible so I can tweak mood on the copy without touching the original."
 * Duplicating is cheaper than re-typing a full [body] and preserves the
 * upstream [SourceNode.parents] DAG so the variant still inherits the
 * right reference chain.
 *
 * **Scope.** One level deep. Parents are *referenced*, not cloned —
 * the fork shares the same character_ref / style_bible ancestors as the
 * original. If the user wants an independent subtree, they call
 * `fork_source_node` on each ancestor too, which is the correct
 * composable behaviour (cloning deep would leave no way to *not*
 * clone deep).
 *
 * **Id semantics.**
 *  - `newNodeId` blank or absent → generate a UUID.
 *  - `newNodeId` non-blank → use verbatim; collision with an existing
 *    node is a loud error (same rule as `add_node`).
 *  - The forked node starts at `revision=1` (bumped by `addNode`), with
 *    a fresh [SourceNode.contentHash] derived from the copied body.
 *
 * **contentHash.** Because the forked body is identical to the source's
 * body and the parent chain is identical, the contentHash is identical
 * too. That means downstream AIGC cache hits (lockfile is
 * content-addressed, not id-addressed) transfer automatically — if the
 * user wants a *different* output, they'll tweak the fork via
 * `update_*` tools, which changes the hash and invalidates the cache
 * in the usual way.
 *
 * **Rejected self-references.** `newNodeId == sourceNodeId` is a typo;
 * failing loud beats silently no-op-ing.
 */
@OptIn(ExperimentalUuidApi::class)
class ForkSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<ForkSourceNodeTool.Input, ForkSourceNodeTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val sourceNodeId: String,
        /** Optional; generated UUID when null or blank. */
        val newNodeId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val sourceNodeId: String,
        val forkedNodeId: String,
        val kind: String,
        val contentHash: String,
    )

    override val id: String = "fork_source_node"
    override val helpText: String =
        "Duplicate a source node within the same project under a new id — the within-project " +
            "counterpart to import_source_node. Copies body + parent refs verbatim (so the fork " +
            "shares ancestors) and preserves contentHash, meaning downstream AIGC cache hits " +
            "transfer automatically until the caller tweaks the fork via update_* tools. Use for " +
            "'try the same scene with a different take' / 'alternate character design' flows."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("sourceNodeId") { put("type", "string") }
            putJsonObject("newNodeId") {
                put("type", "string")
                put(
                    "description",
                    "Optional new id for the forked node. Auto-generated UUID if blank. " +
                        "Collides with an existing node id -> loud error.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("sourceNodeId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        projects.get(pid) ?: error("Project ${input.projectId} not found")

        val requestedNewId = input.newNodeId?.trim().takeIf { !it.isNullOrBlank() }
        require(requestedNewId != input.sourceNodeId) {
            "newNodeId (${input.sourceNodeId}) equals sourceNodeId — fork must produce a distinct id"
        }
        val forkedId = SourceNodeId(requestedNewId ?: Uuid.random().toString())

        var kindOut: String? = null
        var hashOut: String? = null

        projects.mutateSource(pid) { source ->
            val original = source.nodes.firstOrNull { it.id.value == input.sourceNodeId }
                ?: error("Source node ${input.sourceNodeId} not found in project ${input.projectId}")
            if (source.nodes.any { it.id == forkedId }) {
                error(
                    "Source node ${forkedId.value} already exists in project ${input.projectId}. " +
                        "Pick a different newNodeId (or remove_source_node first).",
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
            outputForLlm = "Forked source node ${input.sourceNodeId} -> ${forkedId.value} " +
                "(kind=$kindOut, contentHash=$hashOut). Parents preserved; body copied verbatim. " +
                "Tweak with update_* tools to diverge from the original.",
            data = Output(
                projectId = input.projectId,
                sourceNodeId = input.sourceNodeId,
                forkedNodeId = forkedId.value,
                kind = kindOut!!,
                contentHash = hashOut!!,
            ),
        )
    }
}
