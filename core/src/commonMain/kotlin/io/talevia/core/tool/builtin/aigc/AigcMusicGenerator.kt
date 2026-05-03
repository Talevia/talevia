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
import io.talevia.core.domain.source.consistency.FoldedPrompt
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.MusicGenRequest
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Generate a music track via a [MusicGenEngine], persist the bytes into the
 * project bundle via a [BundleBlobWriter], append the resulting [MediaAsset]
 * to `Project.assets`, and surface an `AssetId` that `add_clip` can drop onto
 * an audio track. Closes the AIGC music lane from VISION §2 ("AIGC: 音乐生成")
 * — the last of the four generative modalities the VISION calls out (image /
 * video / TTS / music).
 *
 * Bytes land at `<bundleRoot>/media/<assetId>.<format>` so the generated
 * music travels with the project bundle.
 *
 * Seed + provenance handling (VISION §3.1): delegates to [AigcPipeline] so
 * this tool and every future AIGC tool share one implementation of "mint a
 * seed if missing, record the full generation parameters." Music providers
 * are often seed-lossy (the same seed doesn't guarantee an identical waveform)
 * but recording the caller's intent still lets the lockfile cache key stay
 * meaningful, and future providers that *do* honour seeds will see them.
 *
 * Lockfile cache (VISION §3.1 "产物可 pin"): hash over `(tool, model, seed,
 * duration, format, effective prompt, bindings)`. A style_bible or brand_palette
 * bound via `consistencyBindingIds` is folded into the prompt the same way as
 * image gen — so the same "冷色调、钢琴、慢节奏" style bible produces cache-
 * coherent music across calls. character_ref bindings with voiceIds do NOT
 * apply here (music gen doesn't use speaking voice selection) — the tool
 * silently ignores character_ref voice, matching the VISION §5.5 model where
 * style/brand is the cross-modal knob, voice is the speaking-only knob.
 *
 * Permission: `"aigc.generate"` — same bucket as image / video / TTS because
 * all four incur external cost + need lockfile coherence.
 */
class AigcMusicGenerator(
    private val engine: MusicGenEngine,
    private val bundleBlobWriter: BundleBlobWriter,
    private val projectStore: ProjectStore,
) : MusicAigcGenerator {

    companion object {
        const val ID = "generate_music"
    }

    @Serializable
    data class Input(
        val prompt: String,
        val model: String = "musicgen-melody",
        /** Target duration in seconds. Engines may clamp to provider-specific maxima. */
        val durationSeconds: Double = 15.0,
        val format: String = "mp3",
        val seed: Long? = null,
        val projectId: String? = null,
        val consistencyBindingIds: List<String>? = null,
    )

    @Serializable
    data class Output(
        val assetId: String,
        val durationSeconds: Double,
        val format: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val parameters: JsonObject,
        val effectivePrompt: String,
        val appliedConsistencyBindingIds: List<String>,
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun generate(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        AigcBudgetGuard.enforce(ID, projectStore, pid, ctx)
        val seed = AigcPipeline.ensureSeed(input.seed)
        val folded = resolveConsistency(input, pid)

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to ID,
                "model" to input.model,
                "dur" to input.durationSeconds.toString(),
                "fmt" to input.format,
                "seed" to seed.toString(),
                "prompt" to folded.effectivePrompt,
                "bindings" to folded.appliedNodeIds.joinToString(","),
                "neg" to (folded.negativePrompt ?: ""),
            ),
            variantIndex = ctx.variantIndex,
        )

        if (!ctx.isReplay) {
            val cached = AigcPipeline.findCached(projectStore, pid, inputHash)
            ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = ID, hit = cached != null))
            if (cached != null) {
                return hit(cached, folded, input)
            }
        }

        val result = AigcPipeline.withProgress(
            ctx = ctx,
            jobId = "gen-music-${inputHash.take(8)}",
            startMessage = "generating ${input.durationSeconds}s music with ${input.model}",
            toolId = ID,
            providerId = engine.providerId,
        ) {
            engine.generate(
                MusicGenRequest(
                    prompt = folded.effectivePrompt,
                    modelId = input.model,
                    seed = seed,
                    durationSeconds = input.durationSeconds,
                    format = input.format,
                ),
                onWarmup = { phase ->
                    ctx.publishEvent(
                        BusEvent.ProviderWarmup(
                            sessionId = ctx.sessionId,
                            providerId = engine.providerId,
                            phase = phase,
                            epochMs = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                },
            )
        }
        val music = result.music

        val newAssetId = AssetId(Uuid.random().toString())
        val bundleSource = bundleBlobWriter.writeBlob(pid, newAssetId, music.audioBytes, music.format)
        val newAsset = MediaAsset(
            id = newAssetId,
            source = bundleSource,
            metadata = MediaMetadata(
                duration = music.durationSeconds.seconds,
                resolution = Resolution(0, 0),
                frameRate = null,
            ),
        )

        val baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(), input).jsonObject
        val costCents = AigcPricing.estimateCents(ID, result.provenance, baseInputs)
        AigcPipeline.record(
            store = projectStore,
            projectId = pid,
            toolId = ID,
            inputHash = inputHash,
            assetId = newAssetId,
            provenance = result.provenance,
            sourceBinding = folded.appliedNodeIds.map { SourceNodeId(it) }.toSet(),
            baseInputs = baseInputs,
            costCents = costCents,
            sessionId = ctx.sessionId,
            resolvedPrompt = folded.effectivePrompt,
            originatingMessageId = ctx.messageId,
            newAsset = newAsset,
            variantIndex = ctx.variantIndex,
        )
        ctx.publishEvent(
            BusEvent.AigcCostRecorded(
                sessionId = ctx.sessionId,
                projectId = pid,
                toolId = ID,
                assetId = newAssetId.value,
                costCents = costCents,
            ),
        )

        val prov = result.provenance
        val out = Output(
            assetId = newAssetId.value,
            durationSeconds = music.durationSeconds,
            format = music.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            parameters = prov.parameters,
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            cacheHit = false,
        )
        val bindingTail = if (folded.appliedNodeIds.isEmpty()) ""
        else " [bindings: ${folded.appliedNodeIds.joinToString(", ")}]"
        return ToolResult(
            title = "generate music",
            outputForLlm = "Generated ${music.durationSeconds}s ${music.format} music " +
                "(asset ${out.assetId}) via ${prov.providerId}/${prov.modelId} seed=${prov.seed}$bindingTail",
            data = out,
        )
    }

    private fun hit(entry: LockfileEntry, folded: FoldedPrompt, input: Input): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            assetId = entry.assetId.value,
            durationSeconds = input.durationSeconds,
            format = input.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            parameters = prov.parameters,
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            cacheHit = true,
        )
        return ToolResult(
            title = "generate music (cached)",
            outputForLlm = "Reused cached music ${out.assetId} (lockfile hit; seed=${prov.seed}, model=${prov.modelId})",
            data = out,
        )
    }

    private suspend fun resolveConsistency(input: Input, pid: ProjectId): FoldedPrompt {
        val bindingIds = input.consistencyBindingIds
        if (bindingIds != null && bindingIds.isEmpty()) {
            return io.talevia.core.domain.source.consistency
                .foldConsistencyIntoPrompt(input.prompt, emptyList())
        }
        val project = projectStore.get(pid)
            ?: error("Project ${pid.value} not found when resolving consistency bindings")
        return AigcPipeline.foldPrompt(
            project = project,
            basePrompt = input.prompt,
            bindingIds = bindingIds?.map { SourceNodeId(it) },
        )
    }
}
