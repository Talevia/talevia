package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.consistency.BrandPaletteBody
import io.talevia.core.domain.source.consistency.asBrandPalette
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
 * Surgical field-level update for a `core.consistency.brand_palette` node
 * (VISION §5.4 professional path). Sibling of [DefineBrandPaletteTool]:
 * patches one or more fields on an existing palette without re-asserting
 * the whole body.
 *
 * Typical intents: "swap the primary color on our brand palette",
 * "add a font hint", "reorder palette so the red is primary".
 *
 * Every body field is optional; at least one must be set. Hex colors
 * are validated on input (same `#RRGGBB` shape as the define tool).
 * List semantics: null → keep, non-empty → replace. `hexColors` cannot
 * be cleared — a brand palette with zero colors is a data-model error,
 * not a valid intermediate state. Delete the node entirely if that's
 * what you want.
 *
 * Bumps `contentHash` so downstream clips go stale.
 */
class UpdateBrandPaletteTool(
    private val projects: ProjectStore,
) : Tool<UpdateBrandPaletteTool.Input, UpdateBrandPaletteTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        val name: String? = null,
        val hexColors: List<String>? = null,
        val typographyHints: List<String>? = null,
        val parentIds: List<String>? = null,
    )

    @Serializable data class Output(
        val nodeId: String,
        val updatedFields: List<String>,
    )

    override val id: String = "update_brand_palette"
    override val helpText: String =
        "Patch one or more fields on an existing brand_palette node without re-asserting the " +
            "whole body. Every body field is optional; at least one must be set. hexColors must " +
            "contain at least one #RRGGBB color when provided (cannot be cleared — delete the " +
            "node instead). Bumps contentHash so downstream clips go stale."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Id of the existing brand_palette node. Must already be defined.")
            }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("hexColors") {
                put("type", "array")
                put(
                    "description",
                    "Replacement palette, e.g. ['#0A84FF', '#FF3B30']. First is primary. " +
                        "Cannot be empty when provided. null → keep current.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("typographyHints") {
                put("type", "array")
                put("description", "null → keep, [] → clear, non-empty → replace.")
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
        input.hexColors?.let { touched += "hexColors" }
        input.typographyHints?.let { touched += "typographyHints" }
        input.parentIds?.let { touched += "parentIds" }
        require(touched.isNotEmpty()) {
            "update_brand_palette requires at least one body field to be set."
        }
        input.name?.let {
            require(it.isNotBlank()) { "name, when provided, must not be blank." }
        }
        val normalisedColors: List<String>? = input.hexColors?.let { colors ->
            require(colors.isNotEmpty()) {
                "hexColors, when provided, must contain at least one color. Delete the node " +
                    "with remove_source_node if you want to remove the palette entirely."
            }
            colors.map { c ->
                val s = c.trim().let { if (it.startsWith("#")) it else "#$it" }
                require(HEX6_REGEX.matches(s)) { "hex color '$c' is not in #RRGGBB form" }
                s.uppercase().let { "#${it.substring(1)}" }
            }
        }

        val nodeId = SourceNodeId(input.nodeId)
        val pid = ProjectId(input.projectId)
        projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error(
                    "node ${nodeId.value} not found in project ${input.projectId}; " +
                        "call define_brand_palette first or list_source_nodes to find the id.",
                )
            val current = existing.asBrandPalette()
                ?: error(
                    "node ${nodeId.value} exists but has kind ${existing.kind}; " +
                        "update_brand_palette only works on core.consistency.brand_palette nodes.",
                )
            val merged = BrandPaletteBody(
                name = input.name ?: current.name,
                hexColors = normalisedColors ?: current.hexColors,
                typographyHints = input.typographyHints ?: current.typographyHints,
            )
            val parents = if (input.parentIds != null) {
                resolveParentRefs(input.parentIds, source, nodeId)
            } else {
                existing.parents
            }
            source.replaceNode(nodeId) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        BrandPaletteBody.serializer(),
                        merged,
                    ),
                    parents = parents,
                )
            }
        }
        val out = Output(nodeId.value, touched.distinct())
        return ToolResult(
            title = "update brand_palette ${nodeId.value}",
            outputForLlm = "Updated brand_palette ${nodeId.value}: fields=${out.updatedFields}. " +
                "contentHash bumped — downstream clips may go stale.",
            data = out,
        )
    }

    companion object {
        private val HEX6_REGEX = Regex("#[0-9A-Fa-f]{6}")
    }
}
