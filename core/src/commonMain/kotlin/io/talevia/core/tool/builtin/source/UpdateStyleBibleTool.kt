package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.asStyleBible
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
 * Surgical field-level update for a `core.consistency.style_bible` node
 * (VISION §5.4 professional path). Sibling of [DefineStyleBibleTool]
 * and [UpdateCharacterRefTool]: patches one or more fields without
 * re-asserting the whole body.
 *
 * Typical intents: "swap the mood from cinematic to frenetic",
 * "attach a new LUT to the style bible", "clear the negative prompt".
 *
 * Every body field is optional; at least one must be set. Semantics:
 *  - `name` / `description`: null → keep, non-blank → replace.
 *  - `lutReferenceAssetId`: null → keep, `""` → clear, non-blank → set.
 *  - `negativePrompt`: null → keep, `""` → clear, non-blank → set.
 *  - `moodKeywords`: null → keep, `[]` → clear, non-empty → replace.
 *  - `parentIds`: null → keep, `[]` → clear, non-empty → replace.
 *
 * Bumps `contentHash` so downstream clips go stale.
 */
class UpdateStyleBibleTool(
    private val projects: ProjectStore,
) : Tool<UpdateStyleBibleTool.Input, UpdateStyleBibleTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        val name: String? = null,
        val description: String? = null,
        val lutReferenceAssetId: String? = null,
        val negativePrompt: String? = null,
        val moodKeywords: List<String>? = null,
        val parentIds: List<String>? = null,
    )

    @Serializable data class Output(
        val nodeId: String,
        val updatedFields: List<String>,
    )

    override val id: String = "update_style_bible"
    override val helpText: String =
        "Patch one or more fields on an existing style_bible node without re-asserting the " +
            "whole body. Every body field is optional; at least one must be set. Use \"\" on " +
            "string fields to clear them; [] on lists to clear them. Bumps contentHash so " +
            "downstream clips go stale."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Id of the existing style_bible node. Must already be defined.")
            }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("description") { put("type", "string") }
            putJsonObject("lutReferenceAssetId") {
                put("type", "string")
                put("description", "Optional LUT asset pin. null → keep, \"\" → clear, non-blank → set.")
            }
            putJsonObject("negativePrompt") {
                put("type", "string")
                put("description", "Optional negative prompt. null → keep, \"\" → clear, non-blank → set.")
            }
            putJsonObject("moodKeywords") {
                put("type", "array")
                put("description", "null → keep, [] → clear, non-empty → replace the full list.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put("description", "null → keep, [] → clear, non-empty → replace.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("nodeId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val touched = mutableListOf<String>()
        input.name?.let { touched += "name" }
        input.description?.let { touched += "description" }
        input.lutReferenceAssetId?.let { touched += "lutReferenceAssetId" }
        input.negativePrompt?.let { touched += "negativePrompt" }
        input.moodKeywords?.let { touched += "moodKeywords" }
        input.parentIds?.let { touched += "parentIds" }
        require(touched.isNotEmpty()) {
            "update_style_bible requires at least one body field to be set."
        }
        input.name?.let {
            require(it.isNotBlank()) { "name, when provided, must not be blank." }
        }
        input.description?.let {
            require(it.isNotBlank()) { "description, when provided, must not be blank." }
        }

        val nodeId = SourceNodeId(input.nodeId)
        val pid = ProjectId(input.projectId)
        projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error(
                    "node ${nodeId.value} not found in project ${input.projectId}; " +
                        "call define_style_bible first or list_source_nodes to find the id.",
                )
            val current = existing.asStyleBible()
                ?: error(
                    "node ${nodeId.value} exists but has kind ${existing.kind}; " +
                        "update_style_bible only works on core.consistency.style_bible nodes.",
                )
            val merged = StyleBibleBody(
                name = input.name ?: current.name,
                description = input.description ?: current.description,
                lutReference = when {
                    input.lutReferenceAssetId == null -> current.lutReference
                    input.lutReferenceAssetId.isBlank() -> null
                    else -> AssetId(input.lutReferenceAssetId)
                },
                negativePrompt = when {
                    input.negativePrompt == null -> current.negativePrompt
                    input.negativePrompt.isBlank() -> null
                    else -> input.negativePrompt
                },
                moodKeywords = input.moodKeywords ?: current.moodKeywords,
            )
            val parents = if (input.parentIds != null) {
                resolveParentRefs(input.parentIds, source, nodeId)
            } else {
                existing.parents
            }
            source.replaceNode(nodeId) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        StyleBibleBody.serializer(),
                        merged,
                    ),
                    parents = parents,
                )
            }
        }
        val out = Output(nodeId.value, touched.distinct())
        return ToolResult(
            title = "update style_bible ${nodeId.value}",
            outputForLlm = "Updated style_bible ${nodeId.value}: fields=${out.updatedFields}. " +
                "contentHash bumped — downstream clips may go stale.",
            data = out,
        )
    }
}
