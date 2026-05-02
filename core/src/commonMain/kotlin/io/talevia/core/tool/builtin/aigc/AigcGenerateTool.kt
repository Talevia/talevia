package io.talevia.core.tool.builtin.aigc

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Unified AIGC generation dispatcher (`debt-aigc-tool-consolidation` phase 1).
 * Replaces the LLM-facing surface of [GenerateImageTool] / [GenerateVideoTool]
 * / [GenerateMusicTool] / [SynthesizeSpeechTool] with a single tool spec
 * gated on `kind: enum {image | video | music | speech}`.
 *
 * **Phases.** Phase 1 (cycle 23) introduced the dispatcher abstraction
 * and registered it alongside the 4 originals so both surfaces were
 * reachable. Phase 2 (cycle 27, this comment) un-registered the 4
 * originals — the LLM-facing surface is now `aigc_generate(kind=...)`
 * only. Phase 3 (`aigc-tool-consolidation-phase3-internalise-helpers`)
 * will factor the 4 originals from `Tool<I, O>` to an internal
 * `AigcGenerator` sealed interface that this dispatcher holds, dropping
 * the `Tool<I, O>` boilerplate they no longer need.
 *
 * Internal dispatch is pure delegation — each kind's `execute()` calls
 * the corresponding existing tool's `execute()` and projects its rich
 * per-kind Output to this tool's flat unified [Output] shape (kind-
 * specific fields are nullable; `kind` discriminator says which to
 * expect). LockfileEntry continues to stamp `toolId="generate_image"`
 * etc. via the inner tool — phase 3 will unify the stamp once the
 * helpers are no longer `Tool<I, O>` (until then, changing the stamp
 * would require plumbing an override through `ToolContext` which buys
 * nothing the un-registration didn't already give us).
 *
 * Design choices in this phase:
 *
 * - **Sealed [Input] with `@JsonClassDiscriminator("kind")`** so each
 *   variant carries kind-specific fields without the kitchen-sink
 *   anti-pattern (one shared bag of nullable fields the LLM might
 *   misapply across kinds). LLM JSON Schema is hand-written `oneOf`
 *   reflecting this — no auto-derivation from KSerializer.
 *
 * - **Flat unified [Output]** with kind-specific nullables. The LLM
 *   reads `kind` and knows which fields to expect; consumers that need
 *   strict typing decode the inner tool's per-kind Output (still
 *   accessible during phase 1 via the inner Tool instance). Sealed
 *   Output was rejected because the result-handling glue (cache hits,
 *   provenance, cost) is uniform — duplicating Output shapes per kind
 *   adds boilerplate without typing wins for the dispatch lane.
 *
 * - **Engines optional** (mirrors [registerAigcTools] passing `null`
 *   for unconfigured providers). Each kind throws "engine not
 *   configured" at dispatch time when its inner tool is absent — same
 *   diagnostic shape the registry's null-skip would give if the
 *   originals weren't registered.
 */
class AigcGenerateTool(
    private val image: GenerateImageTool? = null,
    private val video: GenerateVideoTool? = null,
    private val music: GenerateMusicTool? = null,
    private val speech: SynthesizeSpeechTool? = null,
) : Tool<AigcGenerateTool.Input, AigcGenerateTool.Output> {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Input {
        val prompt: String
        val seed: Long?
        val projectId: String?
        val consistencyBindingIds: List<String>?

        /**
         * `aigc-multi-variant-phase2-dispatch`: how many distinct variants
         * to produce in this dispatch. Default `1` (single-variant —
         * existing behaviour). Each iteration calls the underlying
         * generator with the same prompt + seed but a different
         * [ToolContext.variantIndex] so its inputHash differs (phase 1's
         * canonical-string variant segment) and the lockfile records N
         * distinct entries. Providers exposing a native `n` parameter
         * (OpenAI image-gen) are NOT folded into this phase — every kind
         * goes the sequential N-call route. Treating image-gen specially
         * is a perf optimisation deferred to a follow-up; functionally
         * the loop produces identical lockfile shape either way.
         *
         * Values ≤ 0 reject as "must be ≥ 1"; values > 16 reject as
         * "request implausibly large — pick ≤ 16 or split into multiple
         * calls" so the LLM can't accidentally fan out into a huge bill.
         */
        val variantCount: Int

        @Serializable
        @SerialName("image")
        data class Image(
            override val prompt: String,
            val model: String = "gpt-image-1",
            val width: Int = 1024,
            val height: Int = 1024,
            override val seed: Long? = null,
            override val projectId: String? = null,
            override val consistencyBindingIds: List<String>? = null,
            override val variantCount: Int = 1,
        ) : Input

        @Serializable
        @SerialName("video")
        data class Video(
            override val prompt: String,
            val model: String = "sora-2",
            val width: Int = 1280,
            val height: Int = 720,
            val durationSeconds: Double = 5.0,
            override val seed: Long? = null,
            override val projectId: String? = null,
            override val consistencyBindingIds: List<String>? = null,
            override val variantCount: Int = 1,
        ) : Input

        @Serializable
        @SerialName("music")
        data class Music(
            override val prompt: String,
            val durationSeconds: Double = 8.0,
            val model: String = "musicgen",
            override val seed: Long? = null,
            override val projectId: String? = null,
            override val consistencyBindingIds: List<String>? = null,
            override val variantCount: Int = 1,
        ) : Input

        /**
         * `prompt` carries the spoken text for [Speech]. `voice`, `model`,
         * `format`, `speed`, `language` map to [SynthesizeSpeechTool.Input]
         * one-to-one. `seed` is unused by TTS today but kept for shape
         * symmetry — TTS engines may surface it later for prosody control.
         */
        @Serializable
        @SerialName("speech")
        data class Speech(
            override val prompt: String,
            val voice: String = "alloy",
            val model: String = "tts-1",
            val format: String = "mp3",
            val speed: Double = 1.0,
            val language: String? = null,
            override val seed: Long? = null,
            override val projectId: String? = null,
            override val consistencyBindingIds: List<String>? = null,
            override val variantCount: Int = 1,
        ) : Input
    }

    /**
     * Flat unified output — `kind` discriminator tells consumers which
     * kind-specific nullables are populated. Sealed Output was rejected
     * (the result-handling glue is uniform across kinds; sealed shape
     * would duplicate boilerplate without typing wins).
     *
     * Multi-variant shape (`aigc-multi-variant-phase2-dispatch`): for
     * `variantCount > 1` dispatches, top-level fields (assetId, providerId,
     * cacheHit, width, …) describe **variant 0** so existing single-
     * variant call sites keep working without code changes. The full set
     * of N variants is in [variants] (single-element list when
     * variantCount=1; N elements 0..N-1 when variantCount>1). LLM-side
     * consumers reading the unified Output should iterate [variants] when
     * variantCount > 1 and treat the top-level fields as "variant 0
     * shorthand".
     */
    @Serializable
    data class Output(
        val assetId: String,
        val kind: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val parameters: JsonObject,
        val effectivePrompt: String,
        val appliedConsistencyBindingIds: List<String> = emptyList(),
        val cacheHit: Boolean = false,
        // image / video
        val width: Int? = null,
        val height: Int? = null,
        // video / music / speech
        val durationSeconds: Double? = null,
        // video
        val negativePrompt: String? = null,
        val referenceAssetIds: List<String> = emptyList(),
        val loraAdapterIds: List<String> = emptyList(),
        // speech
        val voice: String? = null,
        val format: String? = null,
        val language: String? = null,
        // multi-variant
        val variantCount: Int = 1,
        val variants: List<VariantSummary> = emptyList(),
    )

    /**
     * Per-variant summary for multi-variant dispatches. `variantIndex`
     * matches the [ToolContext.variantIndex] / [LockfileEntry.variantIndex]
     * stamped on the underlying lockfile record. `costCents` is the
     * provider-side cents for that single variant — sum across `variants`
     * for the total dispatch cost. Kind-specific fields are nullable so
     * the same shape covers image / video / music / speech.
     */
    @Serializable
    data class VariantSummary(
        val variantIndex: Int,
        val assetId: String,
        val costCents: Long? = null,
        // image / video
        val width: Int? = null,
        val height: Int? = null,
        // video / music
        val durationSeconds: Double? = null,
    )

    override val id: String = "aigc_generate"
    override val helpText: String =
        "Generate AIGC media (image | video | music | speech) and import it as a project asset. " +
            "Pick `kind` to dispatch — each kind carries its own params (image: width/height; video: " +
            "durationSeconds + width/height; music: durationSeconds; speech: voice/format/speed). " +
            "Bytes persist into the project bundle's media/ dir so assets travel with the project. " +
            "Lockfile cache: identical inputs return the cached asset without re-billing the provider. " +
            "Pass consistencyBindingIds to fold character / style / brand source nodes into the prompt. " +
            "Set variantCount > 1 to fan out into N variants (each its own lockfile entry; cost = N × single)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildOneOfInputSchema()

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        validateVariantCount(input.variantCount)
        return when (input) {
            is Input.Image -> dispatchImage(input, ctx)
            is Input.Video -> dispatchVideo(input, ctx)
            is Input.Music -> dispatchMusic(input, ctx)
            is Input.Speech -> dispatchSpeech(input, ctx)
        }
    }

    private fun validateVariantCount(n: Int) {
        require(n >= 1) { "aigc_generate variantCount must be ≥ 1; got $n" }
        require(n <= MAX_VARIANT_COUNT) {
            "aigc_generate variantCount $n exceeds upper bound $MAX_VARIANT_COUNT — split into multiple calls"
        }
    }

    /**
     * Common loop body for every kind. Calls [perVariant] for each
     * `variantIndex in 0 until input.variantCount` with the per-variant
     * [ToolContext] (set via [ToolContext.forVariant]). Returns a list of
     * the underlying tool's [ToolResult]s in variant order; the first
     * element corresponds to variant 0 and feeds the top-level Output
     * fields below.
     */
    private suspend inline fun <O> runVariants(
        variantCount: Int,
        ctx: ToolContext,
        perVariant: (variantIndex: Int, ctx: ToolContext) -> ToolResult<O>,
    ): List<ToolResult<O>> {
        val results = mutableListOf<ToolResult<O>>()
        for (i in 0 until variantCount) {
            results.add(perVariant(i, ctx.forVariant(i)))
        }
        return results
    }

    private suspend fun dispatchImage(input: Input.Image, ctx: ToolContext): ToolResult<Output> {
        val tool = image ?: error("aigc_generate(kind=image) — image generation engine not configured")
        val inner = GenerateImageTool.Input(
            prompt = input.prompt,
            model = input.model,
            width = input.width,
            height = input.height,
            seed = input.seed,
            projectId = input.projectId,
            consistencyBindingIds = input.consistencyBindingIds,
        )
        val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
            tool.execute(inner, variantCtx)
        }
        val first = results.first().data
        val variants = results.mapIndexed { i, r ->
            VariantSummary(
                variantIndex = i,
                assetId = r.data.assetId,
                width = r.data.width,
                height = r.data.height,
            )
        }
        return ToolResult(
            title = results.first().title,
            outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "image"),
            data = Output(
                assetId = first.assetId,
                kind = "image",
                providerId = first.providerId,
                modelId = first.modelId,
                modelVersion = first.modelVersion,
                seed = first.seed,
                parameters = first.parameters,
                effectivePrompt = first.effectivePrompt,
                appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
                cacheHit = first.cacheHit,
                width = first.width,
                height = first.height,
                negativePrompt = first.negativePrompt,
                referenceAssetIds = first.referenceAssetIds,
                loraAdapterIds = first.loraAdapterIds,
                variantCount = input.variantCount,
                variants = variants,
            ),
        )
    }

    private suspend fun dispatchVideo(input: Input.Video, ctx: ToolContext): ToolResult<Output> {
        val tool = video ?: error("aigc_generate(kind=video) — video generation engine not configured")
        val inner = GenerateVideoTool.Input(
            prompt = input.prompt,
            model = input.model,
            width = input.width,
            height = input.height,
            durationSeconds = input.durationSeconds,
            seed = input.seed,
            projectId = input.projectId,
            consistencyBindingIds = input.consistencyBindingIds,
        )
        val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
            tool.execute(inner, variantCtx)
        }
        val first = results.first().data
        val variants = results.mapIndexed { i, r ->
            VariantSummary(
                variantIndex = i,
                assetId = r.data.assetId,
                width = r.data.width,
                height = r.data.height,
                durationSeconds = r.data.durationSeconds,
            )
        }
        return ToolResult(
            title = results.first().title,
            outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "video"),
            data = Output(
                assetId = first.assetId,
                kind = "video",
                providerId = first.providerId,
                modelId = first.modelId,
                modelVersion = first.modelVersion,
                seed = first.seed,
                parameters = first.parameters,
                effectivePrompt = first.effectivePrompt,
                appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
                cacheHit = first.cacheHit,
                width = first.width,
                height = first.height,
                durationSeconds = first.durationSeconds,
                negativePrompt = first.negativePrompt,
                referenceAssetIds = first.referenceAssetIds,
                loraAdapterIds = first.loraAdapterIds,
                variantCount = input.variantCount,
                variants = variants,
            ),
        )
    }

    private suspend fun dispatchMusic(input: Input.Music, ctx: ToolContext): ToolResult<Output> {
        val tool = music ?: error("aigc_generate(kind=music) — music generation engine not configured")
        val inner = GenerateMusicTool.Input(
            prompt = input.prompt,
            durationSeconds = input.durationSeconds,
            model = input.model,
            seed = input.seed,
            projectId = input.projectId,
            consistencyBindingIds = input.consistencyBindingIds,
        )
        val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
            tool.execute(inner, variantCtx)
        }
        val first = results.first().data
        val variants = results.mapIndexed { i, r ->
            VariantSummary(
                variantIndex = i,
                assetId = r.data.assetId,
                durationSeconds = r.data.durationSeconds,
            )
        }
        return ToolResult(
            title = results.first().title,
            outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "music"),
            data = Output(
                assetId = first.assetId,
                kind = "music",
                providerId = first.providerId,
                modelId = first.modelId,
                modelVersion = first.modelVersion,
                seed = first.seed,
                parameters = first.parameters,
                effectivePrompt = first.effectivePrompt,
                appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
                cacheHit = first.cacheHit,
                durationSeconds = first.durationSeconds,
                variantCount = input.variantCount,
                variants = variants,
            ),
        )
    }

    private suspend fun dispatchSpeech(input: Input.Speech, ctx: ToolContext): ToolResult<Output> {
        val tool = speech ?: error("aigc_generate(kind=speech) — TTS engine not configured")
        val inner = SynthesizeSpeechTool.Input(
            text = input.prompt,
            voice = input.voice,
            model = input.model,
            format = input.format,
            speed = input.speed,
            projectId = input.projectId,
            consistencyBindingIds = input.consistencyBindingIds,
            language = input.language,
        )
        val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
            tool.execute(inner, variantCtx)
        }
        val first = results.first().data
        val variants = results.mapIndexed { i, r ->
            VariantSummary(
                variantIndex = i,
                assetId = r.data.assetId,
            )
        }
        return ToolResult(
            title = results.first().title,
            outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "speech"),
            data = Output(
                assetId = first.assetId,
                kind = "speech",
                providerId = first.providerId,
                modelId = first.modelId,
                modelVersion = first.modelVersion,
                seed = input.seed ?: 0L, // TTS Output has no seed; reflect input or default
                parameters = first.parameters,
                effectivePrompt = input.prompt, // TTS Output's text is in `voice` description; prompt = text
                appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
                cacheHit = first.cacheHit,
                durationSeconds = null,
                voice = first.voice,
                format = first.format,
                language = first.language,
                variantCount = input.variantCount,
                variants = variants,
            ),
        )
    }

    /**
     * For multi-variant dispatches, append a one-line summary of all
     * generated variants after the underlying tool's first-variant
     * outputForLlm. Single-variant dispatches return the underlying
     * line verbatim. Keeps the LLM-facing token cost ≈ 80 chars per
     * extra variant — well below the 500-token §3a threshold.
     */
    private fun composeMultiVariantSummary(
        firstLine: String,
        variants: List<VariantSummary>,
        kind: String,
    ): String {
        if (variants.size <= 1) return firstLine
        val rest = variants.drop(1).joinToString(", ") { "v${it.variantIndex}=${it.assetId}" }
        return "$firstLine + ${variants.size - 1} more $kind variants: $rest"
    }

    companion object {
        /**
         * Hard upper bound on `variantCount` per dispatch. 16 is well
         * above the realistic "give me a few options" use case while
         * preventing accidental fan-out into a 1000-variant request.
         * Bump only with explicit user need + cost discussion.
         */
        const val MAX_VARIANT_COUNT: Int = 16
    }
}

/**
 * Hand-written `oneOf` JSON Schema reflecting the sealed [AigcGenerateTool.Input]
 * variants. kotlinx.serialization can produce the polymorphic JSON at
 * runtime, but the LLM-facing schema is hand-built so per-field
 * descriptions land in the prompt. Each variant has its own
 * `properties` block so the LLM sees only the fields valid for that
 * kind — no kitchen-sink optional bag.
 */
private fun buildOneOfInputSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("oneOf") {
        // image
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "image")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Text description of the image.")
                    }
                    putJsonObject("model") { put("type", "string"); put("description", "Provider-scoped model id (default gpt-image-1).") }
                    putJsonObject("width") { put("type", "integer"); put("description", "Width px (default 1024).") }
                    putJsonObject("height") { put("type", "integer"); put("description", "Height px (default 1024).") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
        // video
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "video")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Text description of the video.")
                    }
                    putJsonObject("model") { put("type", "string"); put("description", "Default sora-2.") }
                    putJsonObject("width") { put("type", "integer"); put("description", "Width px (default 1280).") }
                    putJsonObject("height") { put("type", "integer"); put("description", "Height px (default 720).") }
                    putJsonObject("durationSeconds") { put("type", "number"); put("description", "Clip length seconds (default 5.0).") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
        // music
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "music")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Mood / style description for the music.")
                    }
                    putJsonObject("durationSeconds") { put("type", "number"); put("description", "Clip length seconds (default 8.0).") }
                    putJsonObject("model") { put("type", "string"); put("description", "Default musicgen.") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
        // speech
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "speech")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Spoken text (read verbatim by the TTS engine).")
                    }
                    putJsonObject("voice") { put("type", "string"); put("description", "Default alloy.") }
                    putJsonObject("model") { put("type", "string"); put("description", "Default tts-1.") }
                    putJsonObject("format") { put("type", "string"); put("description", "Audio format (default mp3).") }
                    putJsonObject("speed") { put("type", "number"); put("description", "Default 1.0.") }
                    putJsonObject("language") { put("type", "string"); put("description", "Optional BCP-47 hint.") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
    }
    put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
}

/**
 * Properties present on every variant — `seed`, `projectId`,
 * `consistencyBindingIds`. Extracted so each `oneOf` branch doesn't
 * duplicate the description prose.
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.sharedProps() {
    putJsonObject("seed") {
        put("type", "integer")
        put("description", "Optional reproducibility seed; auto-minted client-side if omitted.")
    }
    putJsonObject("projectId") {
        put("type", "string")
        put("description", "Defaults to session's current project binding.")
    }
    putJsonObject("consistencyBindingIds") {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put(
            "description",
            "Source-node ids to fold. null = auto-fold all consistency nodes; [] = no folding; non-empty = fold only listed.",
        )
    }
    putJsonObject("variantCount") {
        put("type", "integer")
        put(
            "description",
            "How many distinct variants to generate (default 1, max ${AigcGenerateTool.MAX_VARIANT_COUNT}). " +
                "Each variant lands as its own lockfile entry — useful when the user wants to pick from " +
                "several options. N variants = N provider calls = N × cost; pin the chosen one with " +
                "project_pin_action(target=lockfile_entry).",
        )
    }
}
