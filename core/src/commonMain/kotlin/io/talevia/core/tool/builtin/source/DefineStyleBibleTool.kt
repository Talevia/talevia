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
 * Create or replace a `core.consistency.style_bible` source node (VISION §3.3).
 *
 * Captures the project-global look — mood, color grade, negative prompts, optional
 * LUT reference. Applies across shots; sits at the front of the folded prompt so
 * downstream character / shot text overrides ambiguity correctly.
 */
class DefineStyleBibleTool(
    private val projects: ProjectStore,
) : Tool<DefineStyleBibleTool.Input, DefineStyleBibleTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val name: String,
        val description: String,
        val nodeId: String? = null,
        val lutReferenceAssetId: String? = null,
        val negativePrompt: String? = null,
        val moodKeywords: List<String> = emptyList(),
    )

    @Serializable data class Output(
        val nodeId: String,
        val name: String,
        val replaced: Boolean,
    )

    override val id: String = "define_style_bible"
    override val helpText: String =
        "Create or replace a project-wide style bible (core.consistency.style_bible). " +
            "Returns the nodeId — pass it in consistencyBindingIds of AIGC tools so the global look " +
            "(mood, palette, negative prompts) folds into every prompt."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("name") { put("type", "string"); put("description", "Short handle, e.g. 'cinematic-warm'.") }
            putJsonObject("description") { put("type", "string"); put("description", "Natural-language style description.") }
            putJsonObject("nodeId") { put("type", "string"); put("description", "Optional explicit id; defaults to a slugged variant of name.") }
            putJsonObject("lutReferenceAssetId") { put("type", "string"); put("description", "Optional asset id of a LUT file used by the traditional color pass.") }
            putJsonObject("negativePrompt") { put("type", "string"); put("description", "Freeform text the AIGC pass should steer away from.") }
            putJsonObject("moodKeywords") {
                put("type", "array")
                put("description", "Short adjectives folded into the prompt — 'warm', 'gritty', 'frenetic'.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("name"), JsonPrimitive("description"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val nodeId = SourceNodeId(input.nodeId?.takeIf { it.isNotBlank() } ?: slugifyId(input.name, "style"))
        val body = StyleBibleBody(
            name = input.name,
            description = input.description,
            lutReference = input.lutReferenceAssetId?.takeIf { it.isNotBlank() }?.let(::AssetId),
            negativePrompt = input.negativePrompt?.takeIf { it.isNotBlank() },
            moodKeywords = input.moodKeywords,
        )
        val pid = ProjectId(input.projectId)
        var replaced = false
        projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
            if (existing != null) {
                require(existing.asStyleBible() != null) {
                    "node ${nodeId.value} exists but has kind ${existing.kind}; use a different nodeId or remove first"
                }
                replaced = true
                source.replaceNode(nodeId) { node ->
                    node.copy(
                        body = JsonConfig.default.encodeToJsonElement(StyleBibleBody.serializer(), body),
                    )
                }
            } else {
                source.addStyleBible(nodeId, body)
            }
        }
        val out = Output(nodeId.value, input.name, replaced)
        val verb = if (replaced) "Replaced" else "Defined"
        return ToolResult(
            title = if (replaced) "replace style_bible ${input.name}" else "define style_bible ${input.name}",
            outputForLlm = "$verb style_bible node ${out.nodeId} (\"${input.name}\"). " +
                "Pass it via consistencyBindingIds when you want this global look applied.",
            data = out,
        )
    }
}
