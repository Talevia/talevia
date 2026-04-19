package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.consistency.BrandPaletteBody
import io.talevia.core.domain.source.consistency.addBrandPalette
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
 * Create or replace a `core.consistency.brand_palette` source node (VISION §3.3) —
 * the brand-tier identity for ad / marketing genres. Hex colors are validated to
 * the canonical `#RRGGBB` shape; anything malformed surfaces a hard error rather
 * than silently letting a typo into a deliverable.
 */
class DefineBrandPaletteTool(
    private val projects: ProjectStore,
) : Tool<DefineBrandPaletteTool.Input, DefineBrandPaletteTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val name: String,
        val hexColors: List<String>,
        val nodeId: String? = null,
        val typographyHints: List<String> = emptyList(),
        val parentIds: List<String> = emptyList(),
    )

    @Serializable data class Output(
        val nodeId: String,
        val name: String,
        val replaced: Boolean,
    )

    override val id: String = "define_brand_palette"
    override val helpText: String =
        "Create or replace a brand palette node (core.consistency.brand_palette). " +
            "First color in hexColors is treated as primary. Pass nodeId via consistencyBindingIds " +
            "of AIGC tools so the brand colors / type land in every generated asset for this project."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("hexColors") {
                put("type", "array")
                put("description", "Brand hex colors, e.g. ['#0A84FF', '#FF3B30']. First is primary.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("nodeId") { put("type", "string") }
            putJsonObject("typographyHints") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put("description", "Optional source-node ids this brand_palette depends on. Editing any parent cascades contentHash changes.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("name"), JsonPrimitive("hexColors"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.hexColors.isNotEmpty()) { "hexColors must contain at least one color" }
        val normalised = input.hexColors.map { c ->
            val s = c.trim().let { if (it.startsWith("#")) it else "#$it" }
            require(HEX6_REGEX.matches(s)) { "hex color '$c' is not in #RRGGBB form" }
            s.uppercase().let { "#${it.substring(1)}" }
        }
        val nodeId = SourceNodeId(input.nodeId?.takeIf { it.isNotBlank() } ?: slugifyId(input.name, "brand"))
        val body = BrandPaletteBody(
            name = input.name,
            hexColors = normalised,
            typographyHints = input.typographyHints,
        )
        val pid = ProjectId(input.projectId)
        var replaced = false
        projects.mutateSource(pid) { source ->
            val parents = resolveParentRefs(input.parentIds, source, nodeId)
            val existing = source.byId[nodeId]
            if (existing != null) {
                require(existing.asBrandPalette() != null) {
                    "node ${nodeId.value} exists but has kind ${existing.kind}; use a different nodeId or remove first"
                }
                replaced = true
                source.replaceNode(nodeId) { node ->
                    node.copy(
                        body = JsonConfig.default.encodeToJsonElement(BrandPaletteBody.serializer(), body),
                        parents = parents,
                    )
                }
            } else {
                source.addBrandPalette(nodeId, body, parents)
            }
        }
        val out = Output(nodeId.value, input.name, replaced)
        val verb = if (replaced) "Replaced" else "Defined"
        return ToolResult(
            title = if (replaced) "replace brand_palette ${input.name}" else "define brand_palette ${input.name}",
            outputForLlm = "$verb brand_palette node ${out.nodeId} (\"${input.name}\"; ${normalised.size} color(s)). " +
                "Pass it via consistencyBindingIds for branded shots.",
            data = out,
        )
    }

    companion object {
        private val HEX6_REGEX = Regex("#[0-9A-Fa-f]{6}")
    }
}
