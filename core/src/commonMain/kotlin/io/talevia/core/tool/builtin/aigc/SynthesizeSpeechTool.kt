package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.cost.AigcPricing
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Synthesize a voiceover via a [TtsEngine], persist the bytes into the project
 * bundle via a [BundleBlobWriter], append the resulting [MediaAsset] to
 * `Project.assets`, and surface an `AssetId` `add_clip` can drop onto an audio
 * track. The first AIGC audio tool — pairs with `transcribe_asset` (audio →
 * text) to close the round-trip for VISION §2's audio compiler lane.
 *
 * Bytes land at `<bundleRoot>/media/<assetId>.<format>` so the synthesized
 * voiceover travels with the project bundle.
 *
 * Lockfile cache (VISION §3.1): hash key is `(tool, model, voice, format,
 * speed, text)`. The `voice` folded in is the *resolved* voice — character_ref
 * binding if any, else the caller's explicit `voice` — so a cache hit is correct
 * across the two ways the agent might spell the same generation. No seed — TTS
 * providers don't expose one and identical inputs produce identical (or
 * perceptually identical) audio, so the hash is naturally stable.
 *
 * Consistency bindings (VISION §5.5 — audio lane): callers pass
 * `consistencyBindingIds` (character_ref node ids) and optionally `projectId`.
 * If a bound character has a non-null `voiceId`, that voice *overrides* the
 * explicit `voice` input — the agent's intent "this character speaks" should
 * drive voice selection, not a parallel voice string the caller forgot to
 * update. Multiple bound characters with voiceIds → loud failure; the agent
 * rebinds with only the speaker. Characters without voiceIds are ignored
 * (the binding is still recorded in the lockfile's sourceBinding so the
 * clip is stale if the character later gains a voice).
 *
 * Permission: `"aigc.generate"` — same bucket as image gen because both incur
 * external cost + are seed-fragile in spirit (cache invariants, audit trail).
 */
/**
 * @param engines Priority-ordered [TtsEngine] list. The tool tries the first
 *   engine; if that throws (transient network errors, provider outages, 5xx
 *   that the engine didn't internally recover from), it falls through to the
 *   next engine in the list. Cache hits short-circuit the chain — they don't
 *   call any engine at all. The lockfile records the provider that actually
 *   produced the audio (via `result.provenance.providerId`), so a mixed-
 *   provider history is auditable after the fact. `require(engines.isNotEmpty())`:
 *   tool registration is gated on at least one wired engine.
 */
class SynthesizeSpeechTool(
    private val engines: List<TtsEngine>,
    private val bundleBlobWriter: BundleBlobWriter,
    private val projectStore: ProjectStore,
) : Tool<SynthesizeSpeechTool.Input, SynthesizeSpeechTool.Output> {

    init {
        require(engines.isNotEmpty()) {
            "SynthesizeSpeechTool requires at least one TtsEngine; got empty list."
        }
    }

    /**
     * Single-engine convenience ctor — the common case. Mirrors the pre-fallback
     * shape so a container wiring just one TTS provider (the majority today)
     * doesn't need `listOf(...)` boilerplate.
     */
    constructor(
        engine: TtsEngine,
        bundleBlobWriter: BundleBlobWriter,
        projectStore: ProjectStore,
    ) : this(listOf(engine), bundleBlobWriter, projectStore)

    @Serializable
    data class Input(
        val text: String,
        val voice: String = "alloy",
        val model: String = "tts-1",
        val format: String = "mp3",
        val speed: Double = 1.0,
        val projectId: String? = null,
        val consistencyBindingIds: List<String>? = null,
        /**
         * Optional ISO-639-1 language hint (e.g. `"en"`, `"es"`, `"zh"`).
         * Participates in the lockfile inputHash so the same text in a
         * different language does not cache-hit. See [TtsRequest.language]
         * for provider-side behaviour.
         */
        val language: String? = null,
    )

    @Serializable
    data class Output(
        val assetId: String,
        val format: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val voice: String,
        val parameters: JsonObject,
        /** Node ids of character_refs whose voiceId drove this call (empty if the voice came from `input.voice`). */
        val appliedConsistencyBindingIds: List<String> = emptyList(),
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
        /** Echo of the ISO-639-1 language hint, when one was passed. Null otherwise. */
        val language: String? = null,
    )

    override val id: String = "synthesize_speech"
    override val helpText: String =
        "Synthesize speech from text via a TTS provider (default: tts-1 / alloy / mp3) and " +
            "import it as a project asset. Bytes land in the project bundle's media/ directory " +
            "so the voiceover travels with the project. Lockfile caching kicks in automatically — " +
            "a second call with identical (text, voice, model, format, speed) returns the same " +
            "asset without re-billing the provider. Pass consistencyBindingIds with a character_ref " +
            "id whose voiceId is set to use that character's voice automatically (overrides the " +
            "explicit voice input). Use add_clip to drop the result onto an audio track."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Script to speak. OpenAI caps single calls at ~4096 characters.")
            }
            putJsonObject("voice") {
                put("type", "string")
                put("description", "Provider-scoped voice id (OpenAI: alloy, echo, fable, onyx, nova, shimmer). Default: alloy.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (OpenAI: tts-1 fast, tts-1-hd higher quality). Default: tts-1.")
            }
            putJsonObject("format") {
                put("type", "string")
                put("description", "Audio container: mp3 (default), opus, aac, flac, wav, pcm.")
            }
            putJsonObject("speed") {
                put("type", "number")
                put("description", "Playback speed multiplier (1.0 = normal). OpenAI accepts 0.25–4.0.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required to consult the project lockfile or to resolve consistencyBindingIds.")
            }
            putJsonObject("consistencyBindingIds") {
                put("type", "array")
                put(
                    "description",
                    "Character_ref node ids whose voiceId should drive voice selection. " +
                        "null (default) = auto-pick all character_refs; [] = explicitly no binding; non-empty = use only listed nodes. " +
                        "Ambiguous auto (multiple characters with voiceIds) silently falls back to the explicit voice input.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("language") {
                put("type", "string")
                put(
                    "description",
                    "Optional ISO-639-1 language hint (e.g. en / es / zh). Participates in the " +
                        "lockfile cache key so the same text in a different language is a fresh " +
                        "generation, not a stale hit.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("text"))))
        put("additionalProperties", false)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        AigcBudgetGuard.enforce(id, projectStore, pid, ctx)
        val folded = resolveVoice(input, pid)
        val resolvedVoice = folded.voiceId ?: input.voice

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "model" to input.model,
                "voice" to resolvedVoice,
                "format" to input.format,
                "speed" to input.speed.toString(),
                "text" to input.text,
                "language" to (input.language ?: ""),
            ),
        )

        if (!ctx.isReplay) {
            val cached = AigcPipeline.findCached(projectStore, pid, inputHash)
            ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = id, hit = cached != null))
            if (cached != null) {
                return hit(cached, input, resolvedVoice, folded.appliedNodeIds)
            }
        }

        val request = TtsRequest(
            text = input.text,
            modelId = input.model,
            voice = resolvedVoice,
            format = input.format,
            speed = input.speed,
            language = input.language,
        )
        val result = AigcPipeline.withProgress<TtsResult>(
            ctx = ctx,
            jobId = "tts-${inputHash.take(8)}",
            startMessage = "synthesising speech (${input.text.length} chars) with ${input.model}",
        ) {
            synthesizeWithFallback(engines, request) { phase, providerId ->
                ctx.publishEvent(
                    BusEvent.ProviderWarmup(
                        sessionId = ctx.sessionId,
                        providerId = providerId,
                        phase = phase,
                        epochMs = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }

        val newAssetId = AssetId(Uuid.random().toString())
        val bundleSource = bundleBlobWriter.writeBlob(pid, newAssetId, result.audio.audioBytes, result.audio.format)
        // Duration / sample rate would need an audio probe to fill in honestly. The TTS
        // endpoint doesn't echo a duration and we have no portable audio probe in
        // commonMain — leaving it Duration.ZERO is the same compromise the image
        // engine makes for non-image dimensions.
        val newAsset = MediaAsset(
            id = newAssetId,
            source = bundleSource,
            metadata = MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(0, 0),
                frameRate = null,
            ),
        )

        val baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(), input).jsonObject
        val costCents = AigcPricing.estimateCents(id, result.provenance, baseInputs)
        AigcPipeline.record(
            store = projectStore,
            projectId = pid,
            toolId = id,
            inputHash = inputHash,
            assetId = newAssetId,
            provenance = result.provenance,
            sourceBinding = folded.appliedNodeIds.map(::SourceNodeId).toSet(),
            baseInputs = baseInputs,
            costCents = costCents,
            sessionId = ctx.sessionId,
            originatingMessageId = ctx.messageId,
            newAsset = newAsset,
        )
        ctx.publishEvent(
            BusEvent.AigcCostRecorded(
                sessionId = ctx.sessionId,
                projectId = pid,
                toolId = id,
                assetId = newAssetId.value,
                costCents = costCents,
            ),
        )

        val prov = result.provenance
        val out = Output(
            assetId = newAssetId.value,
            format = result.audio.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            voice = resolvedVoice,
            parameters = prov.parameters,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            cacheHit = false,
            language = input.language,
        )
        val bindingTail = if (folded.appliedNodeIds.isEmpty()) ""
        else " [voice from: ${folded.appliedNodeIds.joinToString(", ")}]"
        return ToolResult(
            title = "synthesize speech",
            outputForLlm = "Synthesized ${input.text.length}-char ${result.audio.format} audio (asset ${out.assetId}) " +
                "via ${prov.providerId}/${prov.modelId} voice=$resolvedVoice$bindingTail",
            data = out,
        )
    }

    private suspend fun resolveVoice(input: Input, pid: ProjectId): io.talevia.core.domain.source.consistency.FoldedVoice {
        val bindingIds = input.consistencyBindingIds
        if (bindingIds != null && bindingIds.isEmpty()) {
            return io.talevia.core.domain.source.consistency.FoldedVoice(voiceId = null, appliedNodeIds = emptyList())
        }
        val project = projectStore.get(pid)
            ?: error("Project ${pid.value} not found when resolving consistency bindings")
        return if (bindingIds == null) {
            // Auto: soft-fail on ambiguous voices rather than crashing the generation.
            runCatching { AigcPipeline.foldVoice(project, null) }
                .getOrElse { io.talevia.core.domain.source.consistency.FoldedVoice(voiceId = null, appliedNodeIds = emptyList()) }
        } else {
            AigcPipeline.foldVoice(project, bindingIds.map(::SourceNodeId))
        }
    }

    private fun hit(entry: LockfileEntry, input: Input, voice: String, appliedBindings: List<String>): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            assetId = entry.assetId.value,
            format = input.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            voice = voice,
            parameters = prov.parameters,
            appliedConsistencyBindingIds = appliedBindings,
            cacheHit = true,
            language = input.language,
        )
        return ToolResult(
            title = "synthesize speech (cached)",
            outputForLlm = "Reused cached audio ${out.assetId} (lockfile hit; voice=$voice, model=${prov.modelId})",
            data = out,
        )
    }
}
