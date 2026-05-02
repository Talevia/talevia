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
        ) : Input
    }

    /**
     * Flat unified output — `kind` discriminator tells consumers which
     * kind-specific nullables are populated. Sealed Output was rejected
     * (the result-handling glue is uniform across kinds; sealed shape
     * would duplicate boilerplate without typing wins).
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
    )

    override val id: String = "aigc_generate"
    override val helpText: String =
        "Generate AIGC media (image | video | music | speech) and import it as a project asset. " +
            "Pick `kind` to dispatch — each kind carries its own params (image: width/height; video: " +
            "durationSeconds + width/height; music: durationSeconds; speech: voice/format/speed). " +
            "Bytes persist into the project bundle's media/ dir so assets travel with the project. " +
            "Lockfile cache: identical inputs return the cached asset without re-billing the provider. " +
            "Pass consistencyBindingIds to fold character / style / brand source nodes into the prompt."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildOneOfInputSchema()

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> = when (input) {
        is Input.Image -> dispatchImage(input, ctx)
        is Input.Video -> dispatchVideo(input, ctx)
        is Input.Music -> dispatchMusic(input, ctx)
        is Input.Speech -> dispatchSpeech(input, ctx)
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
        val result = tool.execute(inner, ctx)
        val o = result.data
        return ToolResult(
            title = result.title,
            outputForLlm = result.outputForLlm,
            data = Output(
                assetId = o.assetId,
                kind = "image",
                providerId = o.providerId,
                modelId = o.modelId,
                modelVersion = o.modelVersion,
                seed = o.seed,
                parameters = o.parameters,
                effectivePrompt = o.effectivePrompt,
                appliedConsistencyBindingIds = o.appliedConsistencyBindingIds,
                cacheHit = o.cacheHit,
                width = o.width,
                height = o.height,
                negativePrompt = o.negativePrompt,
                referenceAssetIds = o.referenceAssetIds,
                loraAdapterIds = o.loraAdapterIds,
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
        val result = tool.execute(inner, ctx)
        val o = result.data
        return ToolResult(
            title = result.title,
            outputForLlm = result.outputForLlm,
            data = Output(
                assetId = o.assetId,
                kind = "video",
                providerId = o.providerId,
                modelId = o.modelId,
                modelVersion = o.modelVersion,
                seed = o.seed,
                parameters = o.parameters,
                effectivePrompt = o.effectivePrompt,
                appliedConsistencyBindingIds = o.appliedConsistencyBindingIds,
                cacheHit = o.cacheHit,
                width = o.width,
                height = o.height,
                durationSeconds = o.durationSeconds,
                negativePrompt = o.negativePrompt,
                referenceAssetIds = o.referenceAssetIds,
                loraAdapterIds = o.loraAdapterIds,
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
        val result = tool.execute(inner, ctx)
        val o = result.data
        return ToolResult(
            title = result.title,
            outputForLlm = result.outputForLlm,
            data = Output(
                assetId = o.assetId,
                kind = "music",
                providerId = o.providerId,
                modelId = o.modelId,
                modelVersion = o.modelVersion,
                seed = o.seed,
                parameters = o.parameters,
                effectivePrompt = o.effectivePrompt,
                appliedConsistencyBindingIds = o.appliedConsistencyBindingIds,
                cacheHit = o.cacheHit,
                durationSeconds = o.durationSeconds,
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
        val result = tool.execute(inner, ctx)
        val o = result.data
        return ToolResult(
            title = result.title,
            outputForLlm = result.outputForLlm,
            data = Output(
                assetId = o.assetId,
                kind = "speech",
                providerId = o.providerId,
                modelId = o.modelId,
                modelVersion = o.modelVersion,
                seed = input.seed ?: 0L, // TTS Output has no seed; reflect input or default
                parameters = o.parameters,
                effectivePrompt = input.prompt, // TTS Output's text is in `voice` description; prompt = text
                appliedConsistencyBindingIds = o.appliedConsistencyBindingIds,
                cacheHit = o.cacheHit,
                durationSeconds = null,
                voice = o.voice,
                format = o.format,
                language = o.language,
            ),
        )
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
}
