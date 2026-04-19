package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.LoraPin
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
 * Surgical field-level update for a `core.consistency.character_ref` node
 * (VISION §5.4 professional path). Sibling of [DefineCharacterRefTool]:
 * `define_character_ref` asserts full identity (requires name +
 * visualDescription), this patches one or more fields on an existing node
 * without re-asserting the rest.
 *
 * Typical intents: "change Mei's hair color to red", "add a second
 * reference image for Mei", "pin Mei to the alloy voice", "unpin the LoRA
 * for this character".
 *
 * Every body field is optional; at least one must be set. Unspecified
 * fields inherit from the current node. Bumps `contentHash` via
 * `replaceNode` so downstream clips go stale and `find_stale_clips`
 * surfaces them — the refactor-propagation path VISION §3.2 requires.
 *
 * Semantics of the optional fields:
 *  - `name` / `visualDescription`: null → keep, non-blank → replace.
 *  - `referenceAssetIds`: null → keep, `[]` → clear, non-empty → replace
 *    the full list (assets are identified, order is meaningful).
 *  - `voiceId`: null → keep, `""` → clear (unset voice pin), non-blank →
 *    set. Matches [DefineCharacterRefTool]'s "blank = unset" shape.
 *  - `loraPin`: null → keep current pin. A provided body replaces the
 *    pin wholesale (adapterId required when provided). Use `clearLoraPin
 *    = true` to remove the pin explicitly.
 *  - `parentIds`: null → keep, `[]` → clear, non-empty → replace (same
 *    resolution rules as [DefineCharacterRefTool] — every id must
 *    resolve, no self-reference).
 */
class UpdateCharacterRefTool(
    private val projects: ProjectStore,
) : Tool<UpdateCharacterRefTool.Input, UpdateCharacterRefTool.Output> {

    @Serializable data class LoraPinInput(
        val adapterId: String,
        val weight: Float = 1.0f,
        val triggerTokens: List<String> = emptyList(),
    )

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        val name: String? = null,
        val visualDescription: String? = null,
        val referenceAssetIds: List<String>? = null,
        val voiceId: String? = null,
        val loraPin: LoraPinInput? = null,
        val clearLoraPin: Boolean = false,
        val parentIds: List<String>? = null,
    )

    @Serializable data class Output(
        val nodeId: String,
        val updatedFields: List<String>,
    )

    override val id: String = "update_character_ref"
    override val helpText: String =
        "Patch one or more fields on an existing character_ref node without re-asserting the " +
            "whole body. Every body field is optional; at least one must be set. Unspecified " +
            "fields inherit from the current node. Use voiceId=\"\" to clear a voice pin; " +
            "clearLoraPin=true to drop a LoRA pin. Bumps contentHash so downstream clips go " +
            "stale — run find_stale_clips + replace_clip to regenerate."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Id of the existing character_ref node. Must already be defined.")
            }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("visualDescription") { put("type", "string") }
            putJsonObject("referenceAssetIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional replacement list. null → keep, [] → clear, non-empty → replace the full list.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("voiceId") {
                put("type", "string")
                put(
                    "description",
                    "Optional voice pin. null → keep, \"\" → clear, non-blank → set (e.g. 'alloy').",
                )
            }
            putJsonObject("loraPin") {
                put("type", "object")
                put("description", "Optional LoRA pin. If provided, replaces the existing pin wholesale.")
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
            putJsonObject("clearLoraPin") {
                put("type", "boolean")
                put("description", "If true, drop any existing LoRA pin. Incompatible with setting loraPin in the same call.")
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional replacement parent list. null → keep, [] → clear, non-empty → replace.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("nodeId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val touched = mutableListOf<String>()
        input.name?.let { touched += "name" }
        input.visualDescription?.let { touched += "visualDescription" }
        input.referenceAssetIds?.let { touched += "referenceAssetIds" }
        input.voiceId?.let { touched += "voiceId" }
        if (input.loraPin != null) touched += "loraPin"
        if (input.clearLoraPin) touched += "loraPin"
        input.parentIds?.let { touched += "parentIds" }
        require(touched.isNotEmpty()) {
            "update_character_ref requires at least one body field to be set."
        }
        require(!(input.loraPin != null && input.clearLoraPin)) {
            "update_character_ref: loraPin and clearLoraPin=true are mutually exclusive."
        }
        input.name?.let {
            require(it.isNotBlank()) { "name, when provided, must not be blank." }
        }
        input.visualDescription?.let {
            require(it.isNotBlank()) { "visualDescription, when provided, must not be blank." }
        }

        val nodeId = SourceNodeId(input.nodeId)
        val pid = ProjectId(input.projectId)
        projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error(
                    "node ${nodeId.value} not found in project ${input.projectId}; " +
                        "call define_character_ref first or list_source_nodes to find the id.",
                )
            val current = existing.asCharacterRef()
                ?: error(
                    "node ${nodeId.value} exists but has kind ${existing.kind}; " +
                        "update_character_ref only works on core.consistency.character_ref nodes.",
                )
            val merged = CharacterRefBody(
                name = input.name ?: current.name,
                visualDescription = input.visualDescription ?: current.visualDescription,
                referenceAssetIds = input.referenceAssetIds?.map(::AssetId)
                    ?: current.referenceAssetIds,
                loraPin = when {
                    input.clearLoraPin -> null
                    input.loraPin != null -> LoraPin(
                        adapterId = input.loraPin.adapterId,
                        weight = input.loraPin.weight,
                        triggerTokens = input.loraPin.triggerTokens,
                    )
                    else -> current.loraPin
                },
                voiceId = when {
                    input.voiceId == null -> current.voiceId
                    input.voiceId.isBlank() -> null
                    else -> input.voiceId
                },
            )
            val parents = if (input.parentIds != null) {
                resolveParentRefs(input.parentIds, source, nodeId)
            } else {
                existing.parents
            }
            source.replaceNode(nodeId) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        merged,
                    ),
                    parents = parents,
                )
            }
        }
        val out = Output(nodeId.value, touched.distinct())
        return ToolResult(
            title = "update character_ref ${nodeId.value}",
            outputForLlm = "Updated character_ref ${nodeId.value}: fields=${out.updatedFields}. " +
                "contentHash bumped — run find_stale_clips to see which clips need regeneration.",
            data = out,
        )
    }
}
