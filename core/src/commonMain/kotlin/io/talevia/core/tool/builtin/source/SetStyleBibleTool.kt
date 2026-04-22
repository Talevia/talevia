package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
 * Upsert-with-patch tool for a `core.consistency.style_bible` source node
 * (VISION §3.3). Single entry point that replaces the former
 * `define_style_bible` / `update_style_bible` pair.
 *
 * Semantics:
 *  - **Create path** (node doesn't exist): `name` + `description` required.
 *    `nodeId` defaults to a slugged variant of `name`.
 *  - **Patch path** (node exists): every body field optional; at least one must be
 *    set. Unspecified fields inherit (null = keep).
 *  - **Kind collision** on an existing node with a different kind fails loud.
 *
 * Per-field semantics on the patch path:
 *  - `name` / `description`: null → keep, non-blank → replace. Blank is rejected.
 *  - `lutReferenceAssetId`: null → keep, `""` → clear, non-blank → set.
 *  - `negativePrompt`: null → keep, `""` → clear, non-blank → set.
 *  - `moodKeywords`: null → keep, `[]` → clear, non-empty → replace.
 *  - `parentIds`: null → keep, `[]` → clear, non-empty → replace.
 *
 * Bumps `contentHash` so downstream clips go stale.
 */
class SetStyleBibleTool(
    private val projects: ProjectStore,
) : Tool<SetStyleBibleTool.Input, SetStyleBibleTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** Required on create. Optional on patch. */
        val name: String? = null,
        /** Required on create. Optional on patch. */
        val description: String? = null,
        /** Optional explicit id. On create defaults to a slugged variant of `name`. */
        val nodeId: String? = null,
        /** null → keep (patch) / unset (create), `""` → clear, non-blank → set. */
        val lutReferenceAssetId: String? = null,
        /** null → keep (patch) / unset (create), `""` → clear, non-blank → set. */
        val negativePrompt: String? = null,
        /** null → keep (patch) / empty (create), `[]` → clear, non-empty → replace. */
        val moodKeywords: List<String>? = null,
        /** null → keep (patch) / empty (create), `[]` → clear, non-empty → replace. */
        val parentIds: List<String>? = null,
    )

    @Serializable data class Output(
        val nodeId: String,
        val created: Boolean,
        val updatedFields: List<String>,
    )

    override val id: String = "set_style_bible"
    override val helpText: String =
        "Upsert a style_bible node (core.consistency.style_bible). Create-or-patch in one call: " +
            "if the node doesn't exist, name + description are required; if it exists, every body " +
            "field is optional (null = keep, at least one must be set). Kind-collision on nodeId " +
            "fails loud. Use \"\" on optional string fields to clear them; [] on list fields to clear. " +
            "Bumps contentHash so downstream clips go stale."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

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
                put("description", "Short handle, e.g. 'cinematic-warm'. Required on create.")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Natural-language style description. Required on create.")
            }
            putJsonObject("lutReferenceAssetId") {
                put("type", "string")
                put(
                    "description",
                    "Optional LUT asset pin. null → keep (patch) / unset (create), \"\" → clear, " +
                        "non-blank → set.",
                )
            }
            putJsonObject("negativePrompt") {
                put("type", "string")
                put(
                    "description",
                    "Freeform text the AIGC pass should steer away from. null → keep, \"\" → clear, " +
                        "non-blank → set.",
                )
            }
            putJsonObject("moodKeywords") {
                put("type", "array")
                put(
                    "description",
                    "Short adjectives folded into the prompt — 'warm', 'gritty', 'frenetic'. " +
                        "null → keep, [] → clear, non-empty → replace.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional source-node ids this style_bible depends on (e.g. a brand_palette). " +
                        "Editing any parent cascades contentHash changes. null → keep, [] → clear, " +
                        "non-empty → replace.",
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
        input.description?.let {
            require(it.isNotBlank()) { "description, when provided, must not be blank." }
        }

        val touched = mutableListOf<String>()
        input.name?.let { touched += "name" }
        input.description?.let { touched += "description" }
        input.lutReferenceAssetId?.let { touched += "lutReferenceAssetId" }
        input.negativePrompt?.let { touched += "negativePrompt" }
        input.moodKeywords?.let { touched += "moodKeywords" }
        input.parentIds?.let { touched += "parentIds" }

        val explicitId = input.nodeId?.takeIf { it.isNotBlank() }
        val candidateId = explicitId
            ?: input.name?.takeIf { it.isNotBlank() }?.let { slugifyId(it, "style") }
        val pid = ProjectId(input.projectId)
        var created = false
        var resolvedNodeId: SourceNodeId? = null

        projects.mutateSource(pid) { source ->
            val existingId = candidateId?.let(::SourceNodeId)
            val existing = existingId?.let { source.byId[it] }
            if (existing != null) {
                require(existing.asStyleBible() != null) {
                    "node ${existing.id.value} exists but has kind ${existing.kind}; " +
                        "use a different nodeId or remove the existing node first"
                }
                require(touched.isNotEmpty()) {
                    "set_style_bible: node ${existing.id.value} already exists; pass at least one " +
                        "body field to patch it (nothing-to-update is almost always a caller mistake)."
                }
                val current = existing.asStyleBible()!!
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
                    resolveParentRefs(input.parentIds, source, existing.id)
                } else {
                    existing.parents
                }
                resolvedNodeId = existing.id
                source.replaceNode(existing.id) { node ->
                    node.copy(
                        body = JsonConfig.default.encodeToJsonElement(
                            StyleBibleBody.serializer(),
                            merged,
                        ),
                        parents = parents,
                    )
                }
            } else {
                require(!input.name.isNullOrBlank()) {
                    "set_style_bible: creating a new style_bible requires `name`."
                }
                require(!input.description.isNullOrBlank()) {
                    "set_style_bible: creating a new style_bible requires `description`."
                }
                val newId = SourceNodeId(candidateId ?: slugifyId(input.name, "style"))
                val body = StyleBibleBody(
                    name = input.name,
                    description = input.description,
                    lutReference = input.lutReferenceAssetId?.takeIf { it.isNotBlank() }?.let(::AssetId),
                    negativePrompt = input.negativePrompt?.takeIf { it.isNotBlank() },
                    moodKeywords = input.moodKeywords.orEmpty(),
                )
                val parents = resolveParentRefs(input.parentIds.orEmpty(), source, newId)
                created = true
                resolvedNodeId = newId
                source.addStyleBible(newId, body, parents)
            }
        }
        val nodeIdOut = resolvedNodeId!!.value
        val out = Output(nodeIdOut, created, touched.distinct())
        val verb = if (created) "Created" else "Patched"
        val fieldsNote = if (out.updatedFields.isNotEmpty()) " fields=${out.updatedFields}" else ""
        return ToolResult(
            title = if (created) "create style_bible ${input.name ?: nodeIdOut}" else "patch style_bible $nodeIdOut",
            outputForLlm = "$verb style_bible node $nodeIdOut.$fieldsNote " +
                "contentHash bumped — downstream clips may go stale.",
            data = out,
        )
    }
}
