package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.FileBundleBlobWriter
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okio.FileSystem
import kotlin.time.DurationUnit

/**
 * Register a local file as a project asset: probes the media metadata, builds
 * a [MediaAsset] with a fresh [AssetId], and appends it to the owning
 * [io.talevia.core.domain.Project.assets] via [ProjectStore.mutate].
 *
 * There is no global media catalogue any more — the project bundle is the
 * source of truth. With `copy_into_bundle=true` the bytes are physically
 * copied into `<bundleRoot>/media/<assetId>.<ext>` (so `git push` / `cp -R`
 * carries them to another machine); with the default `copy_into_bundle=false`
 * the asset references the original absolute path via [MediaSource.File]
 * (appropriate for gigabyte raw footage that should not bloat the bundle).
 */
class ImportMediaTool(
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
    /**
     * Used only when [Input.copy_into_bundle] is true — copies bytes from the
     * caller-supplied path into `<bundleRoot>/media/<assetId>.<ext>`. The
     * import-by-reference path (default) doesn't touch the filesystem here
     * (the asset stays at its original [MediaSource.File] absolute path).
     * Defaults to [FileSystem.SYSTEM] so production wiring is automatic;
     * tests that exercise bundle copy can inject a fake.
     */
    private val fs: FileSystem = FileSystem.SYSTEM,
    /**
     * Streams bytes into `<bundleRoot>/media/<assetId>.<ext>` when
     * [Input.copy_into_bundle] is true. Defaults to
     * [FileBundleBlobWriter] over [projects] / [fs] so production wiring is
     * automatic; tests can inject a fake. Sharing this primitive with the
     * AIGC tools means the tmpfile + atomicMove + ext-normalisation logic
     * lives in exactly one place (see
     * `docs/decisions/2026-04-23-debt-streaming-bundle-blob-writer.md`).
     */
    private val bundleBlobWriter: BundleBlobWriter = FileBundleBlobWriter(projects, fs),
    /**
     * Threshold in bytes for the `copy_into_bundle = null` (auto) mode:
     * files at or below this size are copied into the bundle (travel with
     * `git push`); larger files are referenced by absolute path (stay on
     * the user's NAS / SSD). Default 50 MiB matches the bullet spec.
     * Injectable so tests can exercise both branches of the auto decision
     * without constructing a literal 50 MiB file.
     */
    private val autoInBundleThresholdBytes: Long = DEFAULT_AUTO_IN_BUNDLE_THRESHOLD_BYTES,
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
        /**
         * Tri-state. `true` forces bundle-copy (bytes → `<bundle>/media/<assetId>.<ext>`,
         * registered as [MediaSource.BundleFile]). `false` forces reference-by-path
         * (registered as [MediaSource.File]). `null` (default) is **auto**: copy
         * into the bundle when the file is ≤ [autoInBundleThresholdBytes]
         * (50 MiB in production), reference otherwise — on-disk bundle tends
         * to reproduce "small" assets (LUTs, fonts, thumbnails, single-frame
         * reference images, short AIGC products) across machines without
         * exploding bundle size on raw 4K rushes.
         *
         * Why tri-state rather than flipping the boolean default: explicit
         * `true` / `false` callers (tests, scripts, agent tool-calls that
         * name their intent) keep their existing semantics; flipping the
         * default to `true` would silently copy gigabyte imports into
         * bundles for every existing caller. Null = "decide for me" matches
         * VISION §3a-4's "no binary state without a third Unknown/Auto term".
         */
        val copy_into_bundle: Boolean? = null,
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
        "Import a media file: probes metadata + appends to project.assets. Returns assetId. " +
            "projectId defaults from session binding. copy_into_bundle tri-state: omit = auto " +
            "(≤50 MiB copied so git-push ships them; larger referenced by absolute path); " +
            "true = force copy (LUTs/fonts/refs where auto is wrong); false = force reference " +
            "(raw 4K footage that must not inflate bundle)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("media.import")
    // Import needs a project binding to know which project.assets to append to.
    // `list_projects` / `create_project` / `switch_project` remain Always so the
    // agent can always get to a binding before trying to import.
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = IMPORT_MEDIA_INPUT_SCHEMA

    private val probe: MediaImportProbe = MediaImportProbe(
        engine = engine,
        proxyGenerator = proxyGenerator,
        fs = fs,
        bundleBlobWriter = bundleBlobWriter,
        clock = clock,
        autoInBundleThresholdBytes = autoInBundleThresholdBytes,
    )

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

        // Resolve bundle root whenever a copy might be needed — that's both
        // explicit `true` and the auto mode (null), since auto decides per-
        // path inside probeOne and might need it. Explicit `false` is the
        // only case where the bundle-path lookup can be skipped.
        val maybeNeedsBundle = input.copy_into_bundle != false
        val bundleRoot = if (maybeNeedsBundle) {
            projects.pathOf(pid)
                ?: if (input.copy_into_bundle == true) {
                    error(
                        "import_media: copy_into_bundle=true requires a file-backed ProjectStore " +
                            "with the project registered at a path. Project ${pid.value} has no " +
                            "registered bundle path; either drop copy_into_bundle or open the " +
                            "project at a path first via open_project / create_project(path=…).",
                    )
                } else {
                    // Auto mode + no bundle path (e.g. in-memory test store) →
                    // degrade silently to reference-by-path. Explicit `true`
                    // still fails loud above.
                    null
                }
        } else {
            null
        }

        // Probe + proxy-generate each path concurrently — imports are IO-bound.
        // Capture per-path success / failure without aborting the batch so one
        // bad clip doesn't lose the other 39.
        val results: List<ProbeResult> = coroutineScope {
            pathsToImport.map { p ->
                async { probe.probe(p, bundleRoot, input.copy_into_bundle, pid) }
            }.awaitAll()
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

    companion object {
        /** Auto-copy threshold — 50 MiB. Matches the bullet spec. */
        const val DEFAULT_AUTO_IN_BUNDLE_THRESHOLD_BYTES: Long = 50L * 1024L * 1024L
    }
}
