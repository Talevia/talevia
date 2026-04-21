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
 * Upsert-with-patch tool for a `core.consistency.brand_palette` source node
 * (VISION §3.3). Single entry point that replaces the former
 * `define_brand_palette` / `update_brand_palette` pair.
 *
 * Semantics:
 *  - **Create path** (node doesn't exist): `name` + `hexColors` required. `nodeId`
 *    defaults to a slugged variant of `name`. Hex colors are validated to the
 *    canonical `#RRGGBB` shape — typos fail loud.
 *  - **Patch path** (node exists): every body field optional; at least one must be
 *    set. Unspecified fields inherit (null = keep).
 *  - **Kind collision** on an existing node with a different kind fails loud.
 *
 * Per-field semantics on the patch path:
 *  - `name`: null → keep, non-blank → replace.
 *  - `hexColors`: null → keep, non-empty → replace. **Cannot be cleared** — a palette
 *    with zero colors is a data-model error, not a valid intermediate state. Delete
 *    the node entirely via `remove_source_node` if that's what you want.
 *  - `typographyHints`: null → keep, `[]` → clear, non-empty → replace.
 *  - `parentIds`: null → keep, `[]` → clear, non-empty → replace.
 *
 * Bumps `contentHash` so downstream clips go stale.
 */
class SetBrandPaletteTool(
    private val projects: ProjectStore,
) : Tool<SetBrandPaletteTool.Input, SetBrandPaletteTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val name: String? = null,
        val hexColors: List<String>? = null,
        val nodeId: String? = null,
        val typographyHints: List<String>? = null,
        val parentIds: List<String>? = null,
    )

    @Serializable data class Output(
        val nodeId: String,
        val created: Boolean,
        val updatedFields: List<String>,
    )

    override val id: String = "set_brand_palette"
    override val helpText: String =
        "Upsert a brand_palette node (core.consistency.brand_palette). Create-or-patch in one call: " +
            "if the node doesn't exist, name + hexColors are required; if it exists, every body " +
            "field is optional (null = keep, at least one must be set). First color in hexColors is " +
            "treated as primary; each must be in #RRGGBB shape. hexColors cannot be cleared — delete " +
            "the node via remove_source_node instead. Kind-collision on nodeId fails loud. Bumps " +
            "contentHash so downstream clips go stale."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") {
                put("type", "string")
                put(
                    "description",
                    "Optional explicit id. On create defaults to a slugged variant of name. On patch, " +
                        "names which node to update.",
                )
            }
            putJsonObject("name") {
                put("type", "string")
                put("description", "Required on create.")
            }
            putJsonObject("hexColors") {
                put("type", "array")
                put(
                    "description",
                    "Brand hex colors, e.g. ['#0A84FF', '#FF3B30']. First is primary. Required on create. " +
                        "null → keep (patch), non-empty → replace. Cannot be empty when provided.",
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
                put(
                    "description",
                    "Optional source-node ids this brand_palette depends on. Editing any parent " +
                        "cascades contentHash changes. null → keep, [] → clear, non-empty → replace.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
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

        val touched = mutableListOf<String>()
        input.name?.let { touched += "name" }
        normalisedColors?.let { touched += "hexColors" }
        input.typographyHints?.let { touched += "typographyHints" }
        input.parentIds?.let { touched += "parentIds" }

        val explicitId = input.nodeId?.takeIf { it.isNotBlank() }
        val candidateId = explicitId
            ?: input.name?.takeIf { it.isNotBlank() }?.let { slugifyId(it, "brand") }
        val pid = ProjectId(input.projectId)
        var created = false
        var resolvedNodeId: SourceNodeId? = null

        projects.mutateSource(pid) { source ->
            val existingId = candidateId?.let(::SourceNodeId)
            val existing = existingId?.let { source.byId[it] }
            if (existing != null) {
                require(existing.asBrandPalette() != null) {
                    "node ${existing.id.value} exists but has kind ${existing.kind}; " +
                        "use a different nodeId or remove the existing node first"
                }
                require(touched.isNotEmpty()) {
                    "set_brand_palette: node ${existing.id.value} already exists; pass at least one " +
                        "body field to patch it (nothing-to-update is almost always a caller mistake)."
                }
                val current = existing.asBrandPalette()!!
                val merged = BrandPaletteBody(
                    name = input.name ?: current.name,
                    hexColors = normalisedColors ?: current.hexColors,
                    typographyHints = input.typographyHints ?: current.typographyHints,
                )
                val parents = if (input.parentIds != null) {
                    resolveParentRefs(input.parentIds, source, existing.id)
                } else {
                    existing.parents
                }
                resolvedNodeId = existing.id
                source.replaceNode(existing.id) { node ->
                    node.copy(
                        body = JsonConfig.default.encodeToJsonElement(
                            BrandPaletteBody.serializer(),
                            merged,
                        ),
                        parents = parents,
                    )
                }
            } else {
                require(!input.name.isNullOrBlank()) {
                    "set_brand_palette: creating a new brand_palette requires `name`."
                }
                require(normalisedColors != null && normalisedColors.isNotEmpty()) {
                    "set_brand_palette: creating a new brand_palette requires `hexColors` " +
                        "(at least one #RRGGBB color)."
                }
                val newId = SourceNodeId(candidateId ?: slugifyId(input.name, "brand"))
                val body = BrandPaletteBody(
                    name = input.name,
                    hexColors = normalisedColors,
                    typographyHints = input.typographyHints.orEmpty(),
                )
                val parents = resolveParentRefs(input.parentIds.orEmpty(), source, newId)
                created = true
                resolvedNodeId = newId
                source.addBrandPalette(newId, body, parents)
            }
        }
        val nodeIdOut = resolvedNodeId!!.value
        val out = Output(nodeIdOut, created, touched.distinct())
        val verb = if (created) "Created" else "Patched"
        val fieldsNote = if (out.updatedFields.isNotEmpty()) " fields=${out.updatedFields}" else ""
        return ToolResult(
            title = if (created) "create brand_palette ${input.name ?: nodeIdOut}" else "patch brand_palette $nodeIdOut",
            outputForLlm = "$verb brand_palette node $nodeIdOut.$fieldsNote " +
                "contentHash bumped — downstream clips may go stale.",
            data = out,
        )
    }

    companion object {
        private val HEX6_REGEX = Regex("#[0-9A-Fa-f]{6}")
    }
}
