package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.renderStaleClips
import io.talevia.core.platform.OutputSpec
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Default engine id `select=render_stale` and `select=incremental_plan`
 * use when the caller doesn't supply one. Today only the FFmpeg JVM
 * engine has `supportsPerClipCache=true`; Media3 + AVFoundation render
 * whole-timeline so per-clip cache fingerprinting doesn't apply (cf.
 * CLAUDE.md "Known incomplete"). Surfacing the default here keeps the
 * dispatcher input lean — callers only override when they know which
 * engine they're targeting.
 */
internal const val DEFAULT_PER_CLIP_ENGINE_ID = "ffmpeg-jvm"

/**
 * Build the [OutputSpec] used by render-cache fingerprint queries from
 * the project's [OutputProfile]. `targetPath` is empty because the
 * fingerprint helper does not consume it for staleness — only resolution,
 * fps, codecs, container, bitrates feed into it.
 */
internal fun outputSpecFromProfile(profile: OutputProfile): OutputSpec = OutputSpec(
    targetPath = "",
    resolution = Resolution(profile.resolution.width, profile.resolution.height),
    frameRate = profile.frameRate.numerator / profile.frameRate.denominator,
    videoBitrate = profile.videoBitrate,
    audioBitrate = profile.audioBitrate,
    videoCodec = profile.videoCodec,
    audioCodec = profile.audioCodec,
    container = profile.container,
)

/**
 * Row for `select=render_stale` — one Video clip whose computed
 * mezzanine fingerprint at the project's current [OutputProfile] +
 * `engineId` does not match any entry in [Project.clipRenderCache].
 * Mirrors [io.talevia.core.domain.RenderStaleClipReport] one-to-one.
 *
 * Render-staleness is **orthogonal** to lockfile-staleness
 * (`select=stale_clips`):
 *   - lockfile-stale = AIGC asset bytes invalid (bound source drifted).
 *   - render-stale = mezzanine cache miss (clip JSON / filters / fades /
 *     bound-source deep hashes / output / engine moved).
 * The same clip can be one, both, or neither. Use
 * `select=incremental_plan` for the joined 3-bucket view.
 */
@Serializable
data class RenderStaleClipReportRow(
    val clipId: String,
    val fingerprint: String,
)

/**
 * `select=render_stale` — Video clips whose per-clip mezzanine cache
 * doesn't have a matching fingerprint at the project's [OutputProfile]
 * and the chosen engine. Empty list has two meanings the caller
 * disambiguates via timeline shape: eligible shape + zero return =
 * full reuse possible; non-eligible shape (multi-Video-track / mixed
 * clips / no Video track) = per-clip cache doesn't apply, falls back
 * to whole-timeline `RenderCache`.
 *
 * Sorted by `clipId` ASC for reproducible paging. The dispatcher's
 * common `limit` (default 100, max 500) and `offset` apply; `total`
 * reflects the true render-stale count even when `rows` is truncated.
 */
internal fun runRenderStaleQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val engineId = input.engineId ?: DEFAULT_PER_CLIP_ENGINE_ID
    val output = outputSpecFromProfile(project.outputProfile)
    val all = project.renderStaleClips(output, engineId)
        .map { r -> RenderStaleClipReportRow(clipId = r.clipId.value, fingerprint = r.fingerprint) }
        .sortedBy { it.clipId }
    val total = all.size
    val page = all.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(RenderStaleClipReportRow.serializer()), page)

    val totalClips = project.timeline.tracks.sumOf { it.clips.size }
    val summary = if (total == 0) {
        "All eligible Video clips have cached mezzanines for engine=$engineId at the project " +
            "output profile ($totalClips clip(s) on the timeline; nothing to re-render)."
    } else {
        val truncNote = if (total > page.size) " (showing ${page.size} of $total — raise limit to see more)" else ""
        val preview = page.take(5).joinToString("; ") { "${it.clipId} (fp=${it.fingerprint})" }
        val tail = if (page.size > 5) "; …" else ""
        "$total of $totalClips clip(s) render-stale on engine=$engineId$truncNote. $preview$tail"
    }

    return ToolResult(
        title = "project_query render_stale ($total)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_RENDER_STALE,
            total = total,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}
