package io.talevia.core.tool.builtin.aigc

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * VISION §5.2 "新效果接入成本" rubric — A/B comparison primitive for AIGC
 * output. Dispatches the same underlying AIGC tool (image / music / video
 * / speech / upscale) against a list of model ids in parallel, returning
 * one `Candidate` per model. Each underlying dispatch runs through the
 * normal lockfile path, so every successful candidate lands a lockfile
 * entry (unpinned — the caller picks a winner via
 * `set_lockfile_entry_pinned`). The LLM and UI compare side-by-side
 * instead of the user running five separate `generate_image` calls by
 * hand.
 *
 * Input shape is polymorphic: `baseInput` is the raw JSON the callee
 * would receive, minus the `model` field. The tool merges each model
 * id into `baseInput` before dispatch. All AIGC tools ship a
 * `val model: String = "..."` default on their Input — enforced by the
 * [ALLOWED_TOOL_IDS] whitelist so a consumer can't accidentally compare
 * non-AIGC tools (which may not have a `model` field at all).
 *
 * Errors in one candidate do not cancel the others — the Output
 * surfaces successes + failures together, so the agent can commit to
 * a winning model without re-running the batch when one model had a
 * transient 503.
 */
class CompareAigcCandidatesTool(
    private val registry: ToolRegistry,
) : Tool<CompareAigcCandidatesTool.Input, CompareAigcCandidatesTool.Output> {

    @Serializable data class Input(
        /**
         * One of [ALLOWED_TOOL_IDS]. Constrained to AIGC tools that ship a
         * `model: String` input field — the `baseInput` merge below assumes
         * that convention.
         */
        val toolId: String,
        /**
         * The underlying tool's normal Input as a JSON object, minus the
         * `model` field. Each candidate runs with `baseInput + ("model" →
         * modelId)` so every other field (prompt, dimensions, seed,
         * consistencyBindingIds, …) is shared across the A/B.
         */
        val baseInput: JsonObject,
        /**
         * Model ids to compare. Must be non-empty and pairwise distinct.
         * Each id replaces the `model` field of `baseInput` for its
         * dispatch.
         */
        val models: List<String>,
    )

    @Serializable data class Candidate(
        val modelId: String,
        /** Extracted from the successful underlying Output (all AIGC Outputs ship `val assetId: String`). */
        val assetId: String? = null,
        /** Full underlying tool Output JSON — consumers can decode with the paired typed serializer. */
        val output: JsonObject? = null,
        /** Set only when this candidate failed; `assetId` / `output` are null in that case. */
        val error: String? = null,
    )

    @Serializable data class Output(
        val toolId: String,
        val candidates: List<Candidate>,
        val successCount: Int,
        val errorCount: Int,
    )

    override val id: String = "compare_aigc_candidates"
    override val helpText: String =
        "A/B compare multiple AIGC models in one call. Dispatches the chosen AIGC tool " +
            "(generate_image | generate_music | generate_video | synthesize_speech | " +
            "upscale_asset) in parallel across the supplied models, using identical " +
            "baseInput fields. Each successful candidate lands an unpinned lockfile entry via " +
            "the underlying tool — pin the winner with set_lockfile_entry_pinned. Per-candidate " +
            "errors don't cancel siblings; the Output carries successes + failures side-by-side. " +
            "baseInput must NOT include a `model` field — this tool injects it per candidate."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("toolId") {
                put("type", "string")
                put(
                    "description",
                    "AIGC tool to fan out: generate_image | generate_music | generate_video | " +
                        "synthesize_speech | upscale_asset.",
                )
            }
            putJsonObject("baseInput") {
                put("type", "object")
                put(
                    "description",
                    "Shared Input for the underlying tool as a JSON object. Must NOT contain " +
                        "a `model` field (this tool injects per candidate).",
                )
            }
            putJsonObject("models") {
                put("type", "array")
                put(
                    "description",
                    "Model ids to compare. Must be non-empty and pairwise distinct.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("toolId"), JsonPrimitive("baseInput"), JsonPrimitive("models"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.toolId in ALLOWED_TOOL_IDS) {
            "toolId '${input.toolId}' is not an AIGC comparison target. Accepted: " +
                ALLOWED_TOOL_IDS.joinToString(", ") + "."
        }
        require(input.models.isNotEmpty()) { "models must be non-empty" }
        require(input.models.size == input.models.distinct().size) {
            "models must be pairwise distinct; got ${input.models}"
        }
        require("model" !in input.baseInput) {
            "baseInput must not contain a 'model' field — compare_aigc_candidates injects it " +
                "per candidate. Move it into the `models` list instead."
        }
        val tool = registry[input.toolId]
            ?: error(
                "Tool '${input.toolId}' is not registered in this container. Call list_tools to " +
                    "see what's available.",
            )

        val candidates = coroutineScope {
            input.models.map { model ->
                async {
                    val modifiedInput = JsonObject(
                        input.baseInput.toMutableMap().apply { put("model", JsonPrimitive(model)) },
                    )
                    runCatching { tool.dispatch(modifiedInput, ctx) }.fold(
                        onSuccess = { result ->
                            val encoded = tool.encodeOutput(result) as? JsonObject
                            val assetId = encoded?.get("assetId")?.let {
                                (it as? JsonPrimitive)?.content
                            }
                            Candidate(modelId = model, assetId = assetId, output = encoded)
                        },
                        onFailure = { t ->
                            Candidate(
                                modelId = model,
                                error = t.message ?: t::class.simpleName ?: "unknown",
                            )
                        },
                    )
                }
            }.awaitAll()
        }

        val successes = candidates.count { it.error == null }
        val errors = candidates.size - successes
        val assetSummary = candidates.joinToString("; ") { c ->
            if (c.error != null) {
                "${c.modelId}: error=${c.error}"
            } else {
                "${c.modelId}: asset=${c.assetId ?: "?"}"
            }
        }
        return ToolResult(
            title = "compare ${input.toolId} x ${input.models.size} models",
            outputForLlm = "Ran ${input.toolId} with ${input.models.size} candidate model(s): " +
                "$successes ok / $errors error. $assetSummary. Pin the winner with " +
                "set_lockfile_entry_pinned (lockfile entries were appended unpinned).",
            data = Output(
                toolId = input.toolId,
                candidates = candidates,
                successCount = successes,
                errorCount = errors,
            ),
        )
    }

    companion object {
        val ALLOWED_TOOL_IDS: Set<String> = setOf(
            "generate_image",
            "generate_music",
            "generate_video",
            "synthesize_speech",
            "upscale_asset",
        )
    }
}
