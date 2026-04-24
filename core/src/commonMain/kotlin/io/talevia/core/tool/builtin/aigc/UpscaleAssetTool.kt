package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.bus.BusEvent
import io.talevia.core.cost.AigcPricing
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.BundleMediaPathResolver
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.UpscaleRequest
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
 * Upscale / super-resolve an imported image asset via an [UpscaleEngine],
 * persist the bytes into the project bundle via a [BundleBlobWriter], append
 * the resulting [MediaAsset] to `Project.assets`, and surface a new `AssetId`
 * that downstream tools can use. Closes the VISION §2 "ML 加工: … 超分 …" lane.
 *
 * Bytes land at `<bundleRoot>/media/<assetId>.<format>` so the upscaled
 * artefact travels with the project bundle.
 *
 * Placed under `tool/builtin/aigc/` (not `ml/`) because its engine output is
 * bytes rather than text — it shares the seed / lockfile / provenance
 * disciplines with [GenerateImageTool] et al. (`ml/` is the "analysis →
 * derived text" lane: `describe_asset`, `transcribe_asset`). The folder split
 * is operational; the semantic line is "does it emit bytes?". If we add
 * denoise / inpaint / style-transfer later they belong here too.
 *
 * Seed + provenance (VISION §3.1). Delegates to [AigcPipeline] so this tool
 * uses the same "mint a seed if missing, record the full generation
 * parameters" policy as image / video / music gen. Diffusion-based SR models
 * are seed-sensitive; seed-ignoring ESRGAN-style models still get the seed
 * in provenance so the lockfile hash stays meaningful.
 *
 * Lockfile cache (VISION §3.1). Hash over
 * `(tool, sourceAssetId, model, scale, seed, format)`. A second call with
 * identical inputs returns the cached upscaled asset without re-invoking the
 * provider. Source binding is empty — this tool doesn't fold character /
 * style consistency (upscaling is a pixel-fidelity operation, not a creative
 * one), so stale-propagation operates on the *source asset* identity alone
 * via the assetId in the hash. If a user regenerates the source asset, the
 * assetId changes and the upscale cache busts naturally.
 *
 * Permission: `"aigc.generate"` — same bucket as image / video / TTS / music
 * because it incurs external cost + produces a new artifact.
 */
class UpscaleAssetTool(
    private val engine: UpscaleEngine,
    private val bundleBlobWriter: BundleBlobWriter,
    private val projectStore: ProjectStore,
) : Tool<UpscaleAssetTool.Input, UpscaleAssetTool.Output> {

    @Serializable data class Input(
        val assetId: String,
        val scale: Int = 2,
        val model: String = "real-esrgan-4x",
        val seed: Long? = null,
        val format: String = "png",
        val projectId: String? = null,
    )

    @Serializable data class Output(
        val sourceAssetId: String,
        val upscaledAssetId: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val scale: Int,
        val width: Int,
        val height: Int,
        val format: String,
        val parameters: JsonObject,
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    override val id: String = "upscale_asset"
    override val helpText: String =
        "Upscale / super-resolve an imported image asset via an ML provider (default model: " +
            "real-esrgan-4x, default scale: 2). Output bytes land in the project bundle's " +
            "media/ directory so the upscaled image travels with the project. Returns a new " +
            "assetId you can substitute via clip_action(action=\"replace\") or drop onto the timeline via " +
            "add_clip. Lockfile cache kicks in automatically — a second call with identical " +
            "(assetId, model, scale, seed, format) returns the same upscaled asset without " +
            "re-billing the provider."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("assetId") {
                put("type", "string")
                put("description", "Source asset id to upscale. Must resolve to an image the provider accepts.")
            }
            putJsonObject("scale") {
                put("type", "integer")
                put("description", "Upscale factor. 2, 3, or 4 in practice; engines clamp to what the chosen model supports.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: real-esrgan-4x).")
            }
            putJsonObject("seed") {
                put("type", "integer")
                put("description", "Optional seed for stochastic SR models. If omitted the tool picks one client-side so provenance is still complete. Explicit seeds make cache hits meaningful.")
            }
            putJsonObject("format") {
                put("type", "string")
                put("description", "Output container: png (default, lossless) or jpg.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required when the project lockfile cache should be consulted.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"))))
        put("additionalProperties", false)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.scale in 2..8) { "scale must be between 2 and 8; got ${input.scale}" }
        val pid = ctx.resolveProjectId(input.projectId)
        AigcBudgetGuard.enforce(id, projectStore, pid, ctx)
        val seed = AigcPipeline.ensureSeed(input.seed)
        val sourceAssetId = AssetId(input.assetId)
        val project = projectStore.get(pid) ?: error("project ${pid.value} not found")
        val bundleRoot = projectStore.pathOf(pid)
            ?: error(
                "project ${pid.value} has no registered bundle path; upscale_asset requires a " +
                    "file-backed ProjectStore — open or create the bundle first.",
            )
        val sourcePath = BundleMediaPathResolver(project, bundleRoot).resolve(sourceAssetId)

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "asset" to input.assetId,
                "model" to input.model,
                "scale" to input.scale.toString(),
                "seed" to seed.toString(),
                "format" to input.format,
            ),
        )

        if (!ctx.isReplay) {
            val cached = AigcPipeline.findCached(projectStore, pid, inputHash)
            ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = id, hit = cached != null))
            if (cached != null) {
                return hit(cached, input, seed)
            }
        }

        val result = AigcPipeline.withProgress(
            ctx = ctx,
            jobId = "upscale-${inputHash.take(8)}",
            startMessage = "upscaling asset ${input.assetId} x${input.scale} with ${input.model}",
        ) {
            engine.upscale(
                UpscaleRequest(
                    imagePath = sourcePath,
                    modelId = input.model,
                    scale = input.scale,
                    seed = seed,
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
        val image = result.image

        val newAssetId = AssetId(Uuid.random().toString())
        val bundleSource = bundleBlobWriter.writeBlob(pid, newAssetId, image.imageBytes, image.format)
        val newAsset = MediaAsset(
            id = newAssetId,
            source = bundleSource,
            metadata = MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(image.width, image.height),
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
            sourceBinding = emptySet(),
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
            sourceAssetId = input.assetId,
            upscaledAssetId = newAssetId.value,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            scale = input.scale,
            width = image.width,
            height = image.height,
            format = image.format,
            parameters = prov.parameters,
            cacheHit = false,
        )
        return ToolResult(
            title = "upscale asset x${input.scale}",
            outputForLlm = "Upscaled ${input.assetId} → ${out.upscaledAssetId} (${image.width}x${image.height} " +
                "${image.format}) via ${prov.providerId}/${prov.modelId} seed=${prov.seed}",
            data = out,
        )
    }

    private fun hit(entry: LockfileEntry, input: Input, seed: Long): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            sourceAssetId = input.assetId,
            upscaledAssetId = entry.assetId.value,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            scale = input.scale,
            width = 0,
            height = 0,
            format = input.format,
            parameters = prov.parameters,
            cacheHit = true,
        )
        return ToolResult(
            title = "upscale asset (cached) x${input.scale}",
            outputForLlm = "Reused cached upscaled asset ${out.upscaledAssetId} (lockfile hit; seed=$seed, model=${prov.modelId})",
            data = out,
        )
    }
}
