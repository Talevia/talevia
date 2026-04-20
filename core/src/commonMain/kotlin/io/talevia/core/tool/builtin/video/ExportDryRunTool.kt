package io.talevia.core.tool.builtin.video

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.OutputSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.util.fnv1a64Hex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit

/**
 * Read-only preview of what [ExportTool] would do, without touching the engine
 * or mutating the project. Answers the agent / user question: "is this timeline
 * exportable right now? will the render cache hit? what output shape?" — today
 * that requires calling `export` and parsing the error (expensive + side-effecting).
 *
 * Mirrors ExportTool's input/output spec derivation (overrides → timeline defaults),
 * fingerprint computation, stale-guard evaluation, and render-cache lookup. Produces
 * the same fingerprint ExportTool would so a cache-hit prediction is exact. Never
 * calls [io.talevia.core.platform.VideoEngine.render] and never goes through
 * `ProjectStore.mutate`, so a second invocation returns an identical project.
 *
 * Permission: `project.read`.
 */
class ExportDryRunTool(
    private val store: ProjectStore,
) : Tool<ExportDryRunTool.Input, ExportDryRunTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * Optional — a dry-run doesn't need an output path. When provided it tightens
         * the cache-hit check to exact-path equality (matching ExportTool's behavior);
         * when omitted we report a fingerprint-only match and surface whatever path
         * the cached entry carries.
         */
        val outputPath: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Int? = null,
        val videoCodec: String = "h264",
        val audioCodec: String = "aac",
    )

    @Serializable data class Output(
        val projectId: String,
        val outputPath: String?,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val frameRate: Int,
        val durationSeconds: Double,
        val clipCount: Int,
        val trackCount: Int,
        val wouldCacheHit: Boolean,
        val cachedOutputPath: String?,
        val staleClipCount: Int,
        val staleClipIds: List<String>,
        /** True iff [staleClipCount] > 0 — caller would need `allowStale=true` on the real export. */
        val wouldBlockOnStale: Boolean,
        val fingerprint: String,
    )

    override val id: String = "export_dry_run"
    override val helpText: String =
        "Preview what `export` would do without calling the engine or mutating the project. " +
            "Reports the resolved output resolution/fps, clip + track counts, render-cache hit " +
            "prediction (with cached output path), stale-clip count (would export be blocked), " +
            "and the canonical fingerprint. outputPath is optional — when omitted cache-hit " +
            "matches on fingerprint only."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("outputPath") {
                put("type", "string")
                put(
                    "description",
                    "Optional. When provided, cache-hit prediction requires exact path equality " +
                        "(matching ExportTool). When omitted, prediction matches on fingerprint only.",
                )
            }
            putJsonObject("width") { put("type", "integer") }
            putJsonObject("height") { put("type", "integer") }
            putJsonObject("frameRate") { put("type", "integer") }
            putJsonObject("videoCodec") { put("type", "string") }
            putJsonObject("audioCodec") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val project = store.get(ProjectId(input.projectId))
            ?: error("Project ${input.projectId} not found")
        val timeline = project.timeline
        val width = input.width ?: timeline.resolution.width
        val height = input.height ?: timeline.resolution.height
        val fps = input.frameRate ?: (timeline.frameRate.numerator / timeline.frameRate.denominator)

        // Mirror ExportTool's OutputSpec construction. ExportTool's fingerprint includes
        // the output path, so when the caller pins an outputPath we compute exactly the
        // fingerprint ExportTool would compute. When it's null we look for any cache
        // entry whose fingerprint matches this timeline+spec under its stored path —
        // which path fingerprint to report is then ambiguous, so we emit the one the
        // stored entry carries.
        fun specFor(path: String): OutputSpec = OutputSpec(
            targetPath = path,
            resolution = Resolution(width, height),
            frameRate = fps,
            videoCodec = input.videoCodec,
            audioCodec = input.audioCodec,
        )

        val staleReports = project.staleClipsFromLockfile()
        val staleClipIds = staleReports.map { it.clipId.value }

        val (fingerprint, cachedEntry) = if (input.outputPath != null) {
            val fp = fingerprintOf(timeline, specFor(input.outputPath))
            val entry = project.renderCache.findByFingerprint(fp)
                ?.takeIf { it.outputPath == input.outputPath }
            fp to entry
        } else {
            // Scan for any cache entry whose stored path produces a matching fingerprint
            // under the current timeline + spec. Latest-wins mirrors findByFingerprint.
            val match = project.renderCache.entries.asReversed().firstOrNull { entry ->
                fingerprintOf(timeline, specFor(entry.outputPath)) == entry.fingerprint
            }
            val fp = match?.fingerprint ?: fingerprintOf(timeline, specFor(""))
            fp to match
        }
        val wouldCacheHit = cachedEntry != null
        val cachedOutputPath = cachedEntry?.outputPath

        val clipCount = timeline.tracks.sumOf { it.clips.size }
        val trackCount = timeline.tracks.size

        val out = Output(
            projectId = project.id.value,
            outputPath = input.outputPath,
            resolutionWidth = width,
            resolutionHeight = height,
            frameRate = fps,
            durationSeconds = timeline.duration.toDouble(DurationUnit.SECONDS),
            clipCount = clipCount,
            trackCount = trackCount,
            wouldCacheHit = wouldCacheHit,
            cachedOutputPath = cachedOutputPath,
            staleClipCount = staleReports.size,
            staleClipIds = staleClipIds,
            wouldBlockOnStale = staleReports.isNotEmpty(),
            fingerprint = fingerprint,
        )

        val summary = buildString {
            append("dry-run: ")
            append(width).append('x').append(height)
            append(" @ ").append(fps).append("fps, ")
            append(clipCount).append(" clip(s) / ").append(trackCount).append(" track(s). ")
            when {
                staleReports.isNotEmpty() ->
                    append("would block on ").append(staleReports.size).append(" stale clip(s) — pass allowStale=true or regenerate.")
                wouldCacheHit ->
                    append("render cache hit — reusing ").append(cachedOutputPath).append('.')
                else ->
                    append("fresh render required (no cache hit).")
            }
        }
        return ToolResult(
            title = "export dry-run",
            outputForLlm = summary,
            data = out,
        )
    }

    /**
     * Canonical `(timeline, output)` fingerprint used by [ExportTool]'s render cache.
     *
     * Mirrored from ExportTool.kt for dry-run parity; keep in sync. We intentionally
     * duplicate the helper rather than making ExportTool's private copy public — the
     * public surface of a render tool should not leak fingerprinting. If the ExportTool
     * hash format ever changes, update both sites together.
     */
    private fun fingerprintOf(timeline: Timeline, output: OutputSpec): String {
        val json = JsonConfig.default
        val canonical = buildString {
            append(json.encodeToString(Timeline.serializer(), timeline))
            append('|')
            append("path=").append(output.targetPath)
            append("|res=").append(output.resolution.width).append('x').append(output.resolution.height)
            append("|fps=").append(output.frameRate)
            append("|vc=").append(output.videoCodec)
            append("|ac=").append(output.audioCodec)
        }
        return fnv1a64Hex(canonical)
    }
}
