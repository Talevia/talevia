package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
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
 * Upsert-with-patch tool for a `core.consistency.character_ref` source node
 * (VISION §3.3). Single entry point that replaces the former
 * `define_character_ref` / `update_character_ref` pair — the LLM no longer has to
 * decide which branch to take ("does the node exist? → pick define vs update").
 *
 * Semantics:
 *  - **Create path** (node with `nodeId` doesn't exist yet): `name` + `visualDescription`
 *    are required so the new node is fully specified. `nodeId` defaults to a slugged
 *    variant of `name` so the LLM rarely needs to invent ids.
 *  - **Patch path** (node exists): every body field is optional. Unspecified fields
 *    inherit from the current node (null = keep). At least one body field must be set
 *    or the tool fails loud (a no-op upsert is almost certainly a caller mistake).
 *  - **Kind collision**: if a node with `nodeId` exists but is a different kind, the
 *    tool fails loud — use a different nodeId or remove the existing node first.
 *
 * Per-field semantics on the patch path:
 *  - `name` / `visualDescription`: null → keep, non-blank → replace. Blank (after
 *    trim) is rejected when provided.
 *  - `referenceAssetIds`: null → keep, `[]` → clear, non-empty → replace (ordered).
 *  - `voiceId`: null → keep, `""` → clear (unset voice pin), non-blank → set.
 *  - `loraPin`: null → keep current pin. A provided body replaces the pin
 *    wholesale (adapterId required when provided). Use `clearLoraPin=true` to
 *    remove the pin explicitly; mutually exclusive with a non-null `loraPin`.
 *  - `parentIds`: null → keep, `[]` → clear, non-empty → replace (each id must
 *    resolve, no self-reference — same rules as [resolveParentRefs]).
 *
 * Bumps `contentHash` via `replaceNode` on both paths so downstream clips go stale
 * and `find_stale_clips` surfaces them — the refactor-propagation path VISION §3.2
 * requires.
 */
class SetCharacterRefTool(
    private val projects: ProjectStore,
) : Tool<SetCharacterRefTool.Input, SetCharacterRefTool.Output> {

    @Serializable data class LoraPinInput(
        val adapterId: String,
        val weight: Float = 1.0f,
        val triggerTokens: List<String> = emptyList(),
    )

    @Serializable data class Input(
        val projectId: String,
        /** Required on create (when node doesn't exist). Optional on patch. */
        val name: String? = null,
        /** Required on create. Optional on patch. */
        val visualDescription: String? = null,
        /** Optional explicit id. On create defaults to a slugged variant of `name`. On patch the id selector. */
        val nodeId: String? = null,
        /** null → keep (patch) / empty (create), `[]` → clear, non-empty → replace. */
        val referenceAssetIds: List<String>? = null,
        /** null → keep pin, non-null → replace pin wholesale. Mutually exclusive with `clearLoraPin=true`. */
        val loraPin: LoraPinInput? = null,
        /** If true, drop any existing LoRA pin. Incompatible with a non-null `loraPin`. */
        val clearLoraPin: Boolean = false,
        /** null → keep (patch) / unset (create), `""` → clear pin, non-blank → set. */
        val voiceId: String? = null,
        /** null → keep (patch) / empty (create), `[]` → clear, non-empty → replace. */
        val parentIds: List<String>? = null,
    )

    @Serializable data class Output(
        val nodeId: String,
        /** true → new node was created; false → existing node was patched. */
        val created: Boolean,
        /** Body fields the caller actually supplied in this call (regardless of create/patch). */
        val updatedFields: List<String>,
    )

    override val id: String = "set_character_ref"
    override val helpText: String =
        "Upsert a character_ref node (core.consistency.character_ref). Create-or-patch in one call: " +
            "if the node doesn't exist, name + visualDescription are required; if it exists, every body " +
            "field is optional (null = keep, at least one must be set). Kind-collision on nodeId fails " +
            "loud. voiceId=\"\" clears the voice pin; referenceAssetIds=[] / parentIds=[] clear the " +
            "respective list; clearLoraPin=true drops the LoRA pin (mutually exclusive with loraPin). " +
            "Bumps contentHash so downstream clips go stale — pair with find_stale_clips / " +
            "regenerate_stale_clips to propagate edits. Returns {nodeId, created, updatedFields}."
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
                put("description", "Human-readable handle, e.g. 'Mei'. Required on create.")
            }
            putJsonObject("visualDescription") {
                put("type", "string")
                put(
                    "description",
                    "Natural-language description of look / age / costume / vibe — folded into AIGC " +
                        "prompts. Required on create.",
                )
            }
            putJsonObject("referenceAssetIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional project asset ids of canonical reference images. null → keep (patch) / " +
                        "empty (create), [] → clear, non-empty → replace.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("loraPin") {
                put("type", "object")
                put("description", "Optional LoRA pin. If provided, replaces any existing pin wholesale.")
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
                put(
                    "description",
                    "If true, drop any existing LoRA pin. Incompatible with setting loraPin in the same call.",
                )
            }
            putJsonObject("voiceId") {
                put("type", "string")
                put(
                    "description",
                    "Optional provider-scoped voice id (e.g. OpenAI 'alloy', ElevenLabs voice uuid). " +
                        "null → keep (patch) / unset (create), \"\" → clear, non-blank → set. When set, " +
                        "binding this character_ref in synthesize_speech overrides the caller's voice input.",
                )
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional source-node ids this character_ref depends on (e.g. a style_bible that " +
                        "defines the world). Editing any parent cascades contentHash changes. " +
                        "null → keep (patch) / empty (create), [] → clear, non-empty → replace.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(!(input.loraPin != null && input.clearLoraPin)) {
            "set_character_ref: loraPin and clearLoraPin=true are mutually exclusive."
        }
        input.name?.let {
            require(it.isNotBlank()) { "name, when provided, must not be blank." }
        }
        input.visualDescription?.let {
            require(it.isNotBlank()) { "visualDescription, when provided, must not be blank." }
        }

        // Build the list of body fields the caller actually supplied — surfaces back to
        // the LLM via Output.updatedFields so it can verify its intent round-tripped.
        val touched = mutableListOf<String>()
        input.name?.let { touched += "name" }
        input.visualDescription?.let { touched += "visualDescription" }
        input.referenceAssetIds?.let { touched += "referenceAssetIds" }
        input.voiceId?.let { touched += "voiceId" }
        if (input.loraPin != null) touched += "loraPin"
        if (input.clearLoraPin) touched += "loraPin"
        input.parentIds?.let { touched += "parentIds" }

        val explicitId = input.nodeId?.takeIf { it.isNotBlank() }
        // Fall back to slugified name when no explicit id is given — lets "set_character_ref
        // name=Mei" round-trip through the same node on repeated calls without the caller
        // having to remember the id. Derived-from-name side is null when name was also omitted
        // (patch-only flow) — we can't compute a candidate id until we know which node to hit.
        val candidateId = explicitId
            ?: input.name?.takeIf { it.isNotBlank() }?.let { slugifyId(it, "character") }
        val pid = ProjectId(input.projectId)
        var created = false
        var resolvedNodeId: SourceNodeId? = null

        projects.mutateSource(pid) { source ->
            val existingId = candidateId?.let(::SourceNodeId)
            val existing = existingId?.let { source.byId[it] }
            if (existing != null) {
                // --- patch path
                require(existing.asCharacterRef() != null) {
                    "node ${existing.id.value} exists but has kind ${existing.kind}; " +
                        "use a different nodeId or remove the existing node first"
                }
                require(touched.isNotEmpty()) {
                    "set_character_ref: node ${existing.id.value} already exists; pass at least one body " +
                        "field to patch it (nothing-to-update is almost always a caller mistake)."
                }
                val current = existing.asCharacterRef()!!
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
                    resolveParentRefs(input.parentIds, source, existing.id)
                } else {
                    existing.parents
                }
                resolvedNodeId = existing.id
                source.replaceNode(existing.id) { node ->
                    node.copy(
                        body = JsonConfig.default.encodeToJsonElement(
                            CharacterRefBody.serializer(),
                            merged,
                        ),
                        parents = parents,
                    )
                }
            } else {
                // --- create path
                require(!input.name.isNullOrBlank()) {
                    "set_character_ref: creating a new character_ref requires `name`."
                }
                require(!input.visualDescription.isNullOrBlank()) {
                    "set_character_ref: creating a new character_ref requires `visualDescription`."
                }
                val newId = SourceNodeId(candidateId ?: slugifyId(input.name, "character"))
                val body = CharacterRefBody(
                    name = input.name,
                    visualDescription = input.visualDescription,
                    referenceAssetIds = input.referenceAssetIds.orEmpty().map(::AssetId),
                    loraPin = input.loraPin?.let {
                        LoraPin(adapterId = it.adapterId, weight = it.weight, triggerTokens = it.triggerTokens)
                    },
                    voiceId = input.voiceId?.takeIf { it.isNotBlank() },
                )
                val parents = resolveParentRefs(input.parentIds.orEmpty(), source, newId)
                created = true
                resolvedNodeId = newId
                source.addCharacterRef(newId, body, parents)
            }
        }
        val nodeIdOut = resolvedNodeId!!.value
        val out = Output(nodeIdOut, created, touched.distinct())
        val verb = if (created) "Created" else "Patched"
        val fieldsNote = if (out.updatedFields.isNotEmpty()) " fields=${out.updatedFields}" else ""
        return ToolResult(
            title = if (created) "create character_ref ${input.name ?: nodeIdOut}" else "patch character_ref $nodeIdOut",
            outputForLlm = "$verb character_ref node $nodeIdOut.$fieldsNote " +
                "contentHash bumped — downstream clips may go stale (check find_stale_clips).",
            data = out,
        )
    }
}
