package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Copy every `MediaSource.File` asset on the project into the bundle's
 * `media/` directory and rewrite its source to `MediaSource.BundleFile`,
 * so `git push` / `cp -R` reproduces the project on another machine
 * without the original absolute paths (VISION §3.1 / §5.4 bundle
 * portability).
 *
 * Assets already stored as `BundleFile`, `Http`, or `Platform` are
 * skipped. Per-asset failures (file missing, I/O error) are captured in
 * `Output.failures` rather than aborting the batch — one unreadable
 * drive shouldn't lose the other 39 consolidations. Idempotent: running
 * twice on the same project is a no-op (second call reports
 * `consolidated=0, alreadyBundled=N`).
 *
 * Streams bytes via okio `source`/`sink.writeAll` + atomic `tmpfile`
 * move so a gigabyte rush doesn't live in memory mid-copy and so a
 * crashed JVM doesn't leak a half-written `.mp4` in `media/`. Same copy
 * pattern as `import_media`'s `copy_into_bundle=true` branch — the two
 * tools' inline streaming copy is logged as a pain-point for eventual
 * consolidation behind a streaming `BundleBlobWriter` variant.
 */
class ConsolidateMediaIntoBundleTool(
    private val projects: ProjectStore,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val clock: Clock = Clock.System,
) : Tool<ConsolidateMediaIntoBundleTool.Input, ConsolidateMediaIntoBundleTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud directs the agent at `switch_project`.
         */
        val projectId: String? = null,
    )

    @Serializable data class ConsolidatedAsset(
        val assetId: String,
        val originalPath: String,
        val bundleRelativePath: String,
    )

    @Serializable data class FailedConsolidation(
        val assetId: String,
        val originalPath: String,
        val error: String,
    )

    @Serializable data class Output(
        val projectId: String,
        /** Assets flipped from `MediaSource.File` → `MediaSource.BundleFile` this call. */
        val consolidated: List<ConsolidatedAsset> = emptyList(),
        /** Assets that were already `MediaSource.BundleFile` — no work required. */
        val alreadyBundled: Int = 0,
        /** Assets we can't consolidate (Http / Platform sources the caller must resolve first). */
        val unsupportedSourceCount: Int = 0,
        /** Per-asset I/O or path-resolution failures. Caller typically retries / relinks. */
        val failures: List<FailedConsolidation> = emptyList(),
    )

    override val id: String = "consolidate_media_into_bundle"
    override val helpText: String =
        "Copy every MediaSource.File asset referenced by the project into the bundle's media/ " +
            "directory and rewrite the asset to MediaSource.BundleFile. Use before a git push / " +
            "cp -R so the bundle reproduces on another machine without the original absolute " +
            "paths. Idempotent — already-bundled assets are skipped. Per-asset I/O failures " +
            "are captured in `failures` rather than aborting the batch."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("media.import")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
        }
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        val bundleRoot = projects.pathOf(pid)
            ?: error(
                "consolidate_media_into_bundle requires a file-backed ProjectStore with the " +
                    "project registered at a path. Project ${pid.value} has no registered bundle " +
                    "path; open_project / create_project(path=...) first.",
            )
        val mediaDir = bundleRoot.resolve("media")

        val project = projects.get(pid) ?: error("Project ${pid.value} not found")

        val consolidated = mutableListOf<ConsolidatedAsset>()
        val failures = mutableListOf<FailedConsolidation>()
        var alreadyBundled = 0
        var unsupported = 0

        val replacements = HashMap<AssetId, MediaAsset>()
        for (asset in project.assets) {
            when (val src = asset.source) {
                is MediaSource.BundleFile -> alreadyBundled += 1
                is MediaSource.Http -> unsupported += 1
                is MediaSource.Platform -> unsupported += 1
                is MediaSource.File -> {
                    val outcome = consolidateOne(asset, src, mediaDir)
                    outcome.consolidated?.let { (newAsset, summary) ->
                        replacements[asset.id] = newAsset
                        consolidated += summary
                    }
                    outcome.failure?.let { failures += it }
                }
            }
        }

        if (replacements.isNotEmpty()) {
            projects.mutate(pid) { prior ->
                prior.copy(
                    assets = prior.assets.map { replacements[it.id] ?: it },
                )
            }
        }

        val out = Output(
            projectId = pid.value,
            consolidated = consolidated,
            alreadyBundled = alreadyBundled,
            unsupportedSourceCount = unsupported,
            failures = failures,
        )
        val unsupportedTail = if (unsupported == 0) "" else ", $unsupported unsupported (http/platform)"
        val failedTail = if (failures.isEmpty()) "" else ", ${failures.size} failed"
        val summary = "Project ${pid.value}: consolidated ${consolidated.size} asset(s) into bundle, " +
            "$alreadyBundled already bundled$unsupportedTail$failedTail."
        return ToolResult(
            title = "consolidate_media_into_bundle (${consolidated.size} asset(s))",
            outputForLlm = summary,
            data = out,
        )
    }

    /**
     * Per-asset result union. Exactly one of [consolidated] / [failure] is
     * non-null — kept as two fields rather than a sealed hierarchy because
     * the caller just forwards both into the batch Output and never pattern-
     * matches them.
     */
    private data class OneOutcome(
        val consolidated: Pair<MediaAsset, ConsolidatedAsset>? = null,
        val failure: FailedConsolidation? = null,
    )

    private fun consolidateOne(
        asset: MediaAsset,
        src: MediaSource.File,
        mediaDir: okio.Path,
    ): OneOutcome = runCatching {
        val sourcePath = src.path.toPath()
        if (!fs.exists(sourcePath)) {
            return OneOutcome(
                failure = FailedConsolidation(
                    assetId = asset.id.value,
                    originalPath = src.path,
                    error = "source file not found",
                ),
            )
        }
        val ext = src.path.substringAfterLast('.', missingDelimiterValue = "bin").ifBlank { "bin" }
        fs.createDirectories(mediaDir)
        val target = mediaDir.resolve("${asset.id.value}.$ext")
        val tmp = mediaDir.resolve(
            "${target.name}.tmp.${kotlin.random.Random.nextLong().toString(radix = 36).removePrefix("-")}",
        )
        fs.source(sourcePath).use { input ->
            fs.sink(tmp).buffer().use { sink -> sink.writeAll(input) }
        }
        fs.atomicMove(tmp, target)

        val bundleRelative = "media/${target.name}"
        val newAsset = asset.copy(
            source = MediaSource.BundleFile(bundleRelative),
            updatedAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        OneOutcome(
            consolidated = newAsset to ConsolidatedAsset(
                assetId = asset.id.value,
                originalPath = src.path,
                bundleRelativePath = bundleRelative,
            ),
        )
    }.getOrElse { t ->
        OneOutcome(
            failure = FailedConsolidation(
                assetId = asset.id.value,
                originalPath = src.path,
                error = t.message ?: t::class.simpleName ?: "unknown",
            ),
        )
    }
}
