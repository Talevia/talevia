package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.LoraPin
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
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
 * Create or replace a `core.consistency.character_ref` source node (VISION §3.3).
 *
 * The agent calls this when the user introduces a named character whose visual
 * identity should stay stable across shots. The returned `nodeId` is what gets
 * passed in `consistencyBindingIds` of subsequent AIGC tool calls so the
 * character description folds into every prompt that features this person.
 *
 * Idempotent on `nodeId`: if a node with the same id exists we replace it via
 * [replaceNode] (preserves the id, bumps revision + recomputes contentHash). A
 * fresh `nodeId` defaults to a slugged variant of `name` so the LLM rarely needs
 * to invent ids.
 */
class DefineCharacterRefTool(
    private val projects: ProjectStore,
) : Tool<DefineCharacterRefTool.Input, DefineCharacterRefTool.Output> {

    @Serializable data class LoraPinInput(
        val adapterId: String,
        val weight: Float = 1.0f,
        val triggerTokens: List<String> = emptyList(),
    )

    @Serializable data class Input(
        val projectId: String,
        val name: String,
        val visualDescription: String,
        val nodeId: String? = null,
        val referenceAssetIds: List<String> = emptyList(),
        val loraPin: LoraPinInput? = null,
        val voiceId: String? = null,
        val parentIds: List<String> = emptyList(),
    )

    @Serializable data class Output(
        val nodeId: String,
        val name: String,
        val replaced: Boolean,
    )

    override val id: String = "define_character_ref"
    override val helpText: String =
        "Create or replace a character reference node (core.consistency.character_ref). " +
            "Returns the nodeId — pass it in consistencyBindingIds of generate_image / future AIGC " +
            "tools so the character's identity folds into every prompt that features them."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("name") { put("type", "string"); put("description", "Human-readable handle, e.g. 'Mei'.") }
            putJsonObject("visualDescription") {
                put("type", "string")
                put("description", "Natural-language description of look / age / costume / vibe — folded into AIGC prompts.")
            }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Optional explicit id; defaults to a slugged variant of name.")
            }
            putJsonObject("referenceAssetIds") {
                put("type", "array")
                put("description", "Optional project asset ids of canonical reference images.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("loraPin") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("adapterId") { put("type", "string") }
                    putJsonObject("weight") { put("type", "number") }
                    putJsonObject("triggerTokens") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                }
                put("required", JsonArray(listOf(JsonPrimitive("adapterId"))))
                put("additionalProperties", false)
            }
            putJsonObject("voiceId") {
                put("type", "string")
                put("description", "Optional provider-scoped voice id (e.g. OpenAI 'alloy', ElevenLabs voice uuid). When set, binding this character_ref in synthesize_speech's consistencyBindingIds will override the caller's explicit voice input.")
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put("description", "Optional source-node ids this character_ref depends on (e.g. a style_bible that defines the world). Editing any parent cascades contentHash changes so downstream AIGC renders go stale automatically.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("name"), JsonPrimitive("visualDescription"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val nodeId = SourceNodeId(input.nodeId?.takeIf { it.isNotBlank() } ?: slugifyId(input.name, "character"))
        val body = CharacterRefBody(
            name = input.name,
            visualDescription = input.visualDescription,
            referenceAssetIds = input.referenceAssetIds.map(::AssetId),
            loraPin = input.loraPin?.let {
                LoraPin(adapterId = it.adapterId, weight = it.weight, triggerTokens = it.triggerTokens)
            },
            voiceId = input.voiceId?.takeIf { it.isNotBlank() },
        )
        val pid = ProjectId(input.projectId)
        var replaced = false
        projects.mutateSource(pid) { source ->
            val parents = resolveParentRefs(input.parentIds, source, nodeId)
            val existing = source.byId[nodeId]
            if (existing != null) {
                require(existing.asCharacterRef() != null) {
                    "node ${nodeId.value} exists but has kind ${existing.kind}; use a different nodeId or remove the existing node first"
                }
                replaced = true
                source.replaceNode(nodeId) { node ->
                    node.copy(
                        body = io.talevia.core.JsonConfig.default.encodeToJsonElement(
                            CharacterRefBody.serializer(),
                            body,
                        ),
                        parents = parents,
                    )
                }
            } else {
                source.addCharacterRef(nodeId, body, parents)
            }
        }
        val out = Output(nodeId.value, input.name, replaced)
        val verb = if (replaced) "Replaced" else "Defined"
        return ToolResult(
            title = if (replaced) "replace character_ref ${input.name}" else "define character_ref ${input.name}",
            outputForLlm = "$verb character_ref node ${out.nodeId} (\"${input.name}\"). " +
                "Pass it via consistencyBindingIds when generating shots that feature ${input.name}.",
            data = out,
        )
    }
}
