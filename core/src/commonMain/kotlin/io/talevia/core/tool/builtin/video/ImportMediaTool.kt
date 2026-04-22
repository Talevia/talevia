package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.NoopProxyGenerator
import io.talevia.core.platform.ProxyGenerator
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.PathGuard
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit

/**
 * Register a local file as a project asset: probes via the engine, persists into
 * [MediaStorage]'s catalog, **and appends the probed [io.talevia.core.domain.MediaAsset]
 * to `Project.assets`** so downstream consumers (`project_query(select=assets)`,
 * `add_clip`'s `RequiresAssets` applicability, the lockfile discipline) actually
 * see it in the right place.
 *
 * Why both stores: [MediaStorage] is the global blob / proxy / metadata catalog
 * (cross-project, survives project deletion); [Project.assets] is the per-project
 * inventory the UI lists and the timeline binds clips to. Earlier the tool only
 * wrote the former, so `import_media` succeeded but every asset-scoped follow-up
 * ("which assets does this project have?", "add this imported clip to the
 * timeline") silently failed.
 */
class ImportMediaTool(
    private val storage: MediaStorage,
    private val engine: VideoEngine,
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
    /**
     * Optional post-import hook that generates thumbnails / low-res previews /
     * audio waveforms and merges them into `MediaAsset.proxies`. Defaults to
     * [NoopProxyGenerator] (returns an empty list) so tests + platforms
     * without a wired generator keep working. JVM apps (CLI / desktop /
     * server) wire an FFmpeg-backed generator in their composition root.
     * Generator exceptions are swallowed — proxy failure must not break
     * the import itself.
     */
    private val proxyGenerator: ProxyGenerator = NoopProxyGenerator,
) : Tool<ImportMediaTool.Input, ImportMediaTool.Output> {

    @Serializable data class Input(
        /**
         * Single-file convenience — the pre-batch shape. Exactly one of
         * [path] or [paths] must be non-null. Both forms share the same
         * probe / persist / append code path.
         */
        val path: String? = null,
        /**
         * Batch form (VISION §5.4 "小白路径摩擦项"). When non-null, every
         * path is imported; probing fans out via `coroutineScope + async`
         * so a 40-file rsync finishes at the slowest-probe latency rather
         * than 40×. Per-path failures are captured in
         * [Output.failed] rather than aborting the batch — one bad clip
         * shouldn't lose the other 39. Must be non-empty + pairwise
         * distinct when supplied.
         */
        val paths: List<String>? = null,
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
    )

    @Serializable data class ImportedAsset(
        val path: String,
        val assetId: String,
        val durationSeconds: Double,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val proxyCount: Int = 0,
    )

    @Serializable data class FailedImport(
        val path: String,
        val error: String,
    )

    @Serializable data class Output(
        val projectId: String,
        /**
         * The first successfully-imported asset's id, or `""` when every
         * path failed. Kept alongside [imported] / [failed] so the
         * pre-batch callers ("paste this one file, tell me the assetId")
         * don't need to reach into the list for the common case.
         */
        val assetId: String,
        val durationSeconds: Double,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        /** Total assets in the project AFTER this import — a quick sanity signal for the agent. */
        val projectAssetCount: Int,
        /** Count of proxies (thumbnails / low-res / waveforms) generated for this asset. Zero = generator not wired or a non-fatal generation failure. */
        val proxyCount: Int = 0,
        /** Per-path successful imports. Length 1 for single-path calls. Defaulted for back-compat. */
        val imported: List<ImportedAsset> = emptyList(),
        /** Per-path failures — paths that threw during probe / persist. Empty when everything succeeded. */
        val failed: List<FailedImport> = emptyList(),
    )

    override val id = "import_media"
    override val helpText =
        "Import a media file by path: probes its metadata, registers it with MediaStorage, and " +
            "appends the asset to the current project's inventory. Returns the new assetId so you " +
            "can `add_clip(assetId=…)` immediately after. Defaults projectId from the session " +
            "binding — pass it only when importing into a non-current project."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("media.import")
    // Import needs a project binding to know which project.assets to append to.
    // `list_projects` / `create_project` / `switch_project` remain Always so the
    // agent can always get to a binding before trying to import.
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description",
                    "Absolute path to a single media file. Mutually exclusive with `paths`; exactly one " +
                        "of the two must be supplied.",
                )
            }
            putJsonObject("paths") {
                put("type", "array")
                put(
                    "description",
                    "Absolute paths for a batch import. Each path is probed concurrently; per-path " +
                        "failures land in Output.failed without aborting the batch. Mutually " +
                        "exclusive with `path`. Must be non-empty and pairwise distinct.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to import into the session's current project (set via switch_project).",
                )
            }
        }
        // No hard-required field at the schema level: `path` xor `paths` is enforced in execute().
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        // Exactly one of path / paths must be supplied.
        val pathsToImport: List<String> = when {
            input.path != null && input.paths != null -> error(
                "import_media: pass either `path` (single file) or `paths` (batch), not both.",
            )
            input.path != null -> listOf(input.path)
            input.paths != null -> {
                require(input.paths.isNotEmpty()) {
                    "import_media: `paths` must be non-empty when supplied."
                }
                require(input.paths.size == input.paths.distinct().size) {
                    "import_media: `paths` must be pairwise distinct; got ${input.paths}."
                }
                input.paths
            }
            else -> error("import_media: must supply `path` (single file) or `paths` (batch).")
        }
        pathsToImport.forEach { PathGuard.validate(it, requireAbsolute = true) }
        val pid = ctx.resolveProjectId(input.projectId)

        // Probe + proxy-generate each path concurrently — imports are IO-bound.
        // Capture per-path success / failure without aborting the batch so one
        // bad clip doesn't lose the other 39.
        val results: List<ProbeResult> = coroutineScope {
            pathsToImport.map { p -> async { probeOne(p) } }.awaitAll()
        }

        // Serialise the project-mutate calls — the ProjectStore mutex would
        // serialise them anyway, and doing them in a single mutate makes the
        // "assets after this batch" count deterministic relative to each
        // successful import. Iterate in the caller's path order.
        val successes = results.filterIsInstance<ProbeResult.Success>()
        val failures = results.filterIsInstance<ProbeResult.Failure>()
        val updated = if (successes.isEmpty()) {
            projects.get(pid)
                ?: error("Project ${pid.value} not found during import_media")
        } else {
            projects.mutate(pid) { project ->
                var assets = project.assets
                for (s in successes) {
                    val existing = assets.any { it.id == s.asset.id }
                    if (!existing) assets = assets + s.asset
                }
                project.copy(assets = assets)
            }
        }

        val imported = successes.map { s ->
            ImportedAsset(
                path = s.path,
                assetId = s.asset.id.value,
                durationSeconds = s.asset.metadata.duration.toDouble(DurationUnit.SECONDS),
                width = s.asset.metadata.resolution?.width,
                height = s.asset.metadata.resolution?.height,
                videoCodec = s.asset.metadata.videoCodec,
                audioCodec = s.asset.metadata.audioCodec,
                proxyCount = s.asset.proxies.size,
            )
        }
        val failedReports = failures.map { FailedImport(path = it.path, error = it.message) }

        val firstOk = imported.firstOrNull()
        val out = Output(
            projectId = pid.value,
            assetId = firstOk?.assetId ?: "",
            durationSeconds = firstOk?.durationSeconds ?: 0.0,
            width = firstOk?.width,
            height = firstOk?.height,
            videoCodec = firstOk?.videoCodec,
            audioCodec = firstOk?.audioCodec,
            projectAssetCount = updated.assets.size,
            proxyCount = firstOk?.proxyCount ?: 0,
            imported = imported,
            failed = failedReports,
        )

        val summary = buildString {
            if (pathsToImport.size == 1) {
                val first = imported.firstOrNull()
                if (first != null) {
                    append("Imported asset ${first.assetId} (${first.durationSeconds}s, ${first.width}x${first.height})")
                    if (first.proxyCount > 0) append(" + ${first.proxyCount} proxy(s)")
                    append(" into project ${pid.value}; project now has ${out.projectAssetCount} asset(s).")
                } else {
                    append("import_media failed for ${pathsToImport.single()}: ${failedReports.single().error}")
                }
            } else {
                append(
                    "Batch import: ${imported.size} ok / ${failedReports.size} failed across " +
                        "${pathsToImport.size} paths in project ${pid.value}; project now has " +
                        "${out.projectAssetCount} asset(s).",
                )
                if (failedReports.isNotEmpty()) {
                    append(" Failures: ")
                    failedReports.take(3).forEach { f ->
                        append("${f.path.substringAfterLast('/')}=${f.error}; ")
                    }
                    if (failedReports.size > 3) append("and ${failedReports.size - 3} more.")
                }
            }
        }
        val title = when {
            pathsToImport.size == 1 -> "import ${pathsToImport.single().substringAfterLast('/')}"
            else -> "import_media batch (${imported.size}/${pathsToImport.size})"
        }
        return ToolResult(
            title = title,
            outputForLlm = summary,
            data = out,
        )
    }

    private sealed interface ProbeResult {
        val path: String
        data class Success(override val path: String, val asset: MediaAsset) : ProbeResult
        data class Failure(override val path: String, val message: String) : ProbeResult
    }

    /**
     * Probe + proxy-generate a single path, returning the stamped asset or a
     * captured failure. Never throws — every exception maps to
     * [ProbeResult.Failure] so the caller's batch aggregation stays in one
     * straight-line code path.
     */
    private suspend fun probeOne(path: String): ProbeResult = runCatching {
        val asset = storage.import(MediaSource.File(path)) { source -> engine.probe(source) }
        val proxies = runCatching { proxyGenerator.generate(asset) }.getOrDefault(emptyList())
        val stamped = asset.copy(
            proxies = (asset.proxies + proxies).deduplicateProxies(),
            updatedAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        ProbeResult.Success(path = path, asset = stamped)
    }.getOrElse { t ->
        ProbeResult.Failure(
            path = path,
            message = t.message ?: t::class.simpleName ?: "unknown",
        )
    }
}

/**
 * De-dupe proxies by `(purpose, source)` so a repeated import doesn't
 * accumulate stale thumbnails when the generator re-runs on an already-
 * imported asset. Preserves insertion order — newer entries overwrite
 * same-key older ones via the `associateBy { … }.values` idiom.
 */
private fun List<io.talevia.core.domain.ProxyAsset>.deduplicateProxies(): List<io.talevia.core.domain.ProxyAsset> {
    if (isEmpty()) return this
    return associateBy { it.purpose to it.source }.values.toList()
}
