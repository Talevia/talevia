package io.talevia.core.domain

import io.talevia.core.ProjectId
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.RenderCache
import io.talevia.core.domain.source.Source
import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: ProjectId,
    val timeline: Timeline,
    val assets: List<MediaAsset> = emptyList(),
    val source: Source = Source.EMPTY,
    val outputProfile: OutputProfile = OutputProfile.DEFAULT_1080P,
    /**
     * Parent project this one was forked from, if any. Set when
     * [io.talevia.core.tool.builtin.project.ForkProjectTool] runs — either the
     * plain fork path or the `variantSpec` path that reshapes aspect /
     * duration. Enables forward navigation ("show me all variants of project
     * X") without threading a sibling table. Null for roots and pre-lineage
     * blobs (serialisation default preserves backward compat — §3a #7).
     */
    val parentProjectId: ProjectId? = null,
    /**
     * Per-project record of every AIGC production — the VISION §3.1 lockfile. Defaults
     * empty so pre-lockfile projects decode without migration. AIGC tools append a
     * [io.talevia.core.domain.lockfile.LockfileEntry] after each generation and check
     * [Lockfile.findByInputHash] first to short-circuit redundant provider calls.
     */
    val lockfile: Lockfile = Lockfile.EMPTY,
    /**
     * Full-timeline export memoization (VISION §3.2). [ExportTool] consults this before
     * handing the timeline to the engine; identical inputs return without re-rendering.
     */
    val renderCache: RenderCache = RenderCache.EMPTY,
    /**
     * Per-clip mezzanine memoization (VISION §3.2 fine cut). Populated by
     * [io.talevia.core.tool.builtin.video.ExportTool] when the engine advertises
     * [io.talevia.core.platform.VideoEngine.supportsPerClipCache] — each clip that
     * cache-misses is rendered to an intermediate mp4 under
     * `<outputDir>/.talevia-render-cache/<projectId>/` and recorded here. A later
     * export with the same clip shape + fades + source hashes + output profile
     * stream-copies the mezzanine via `ffmpeg -f concat -c copy` instead of
     * re-encoding. Empty default preserves backward compat on existing project blobs.
     */
    val clipRenderCache: ClipRenderCache = ClipRenderCache.EMPTY,
    /**
     * Named, restorable points-in-time of the project (VISION §3.4 — "可版本化"). Stored
     * inline rather than a sibling table so the existing [ProjectStore.mutate] mutex
     * already gives us atomic save+restore. See [ProjectSnapshot] for restore semantics
     * (in particular: restoring preserves *this* list, so history isn't a one-way trapdoor).
     */
    val snapshots: List<ProjectSnapshot> = emptyList(),
)

@Serializable
data class OutputProfile(
    val resolution: Resolution,
    val frameRate: FrameRate,
    val videoCodec: String = "h264",
    val audioCodec: String = "aac",
    val videoBitrate: Long = 8_000_000,
    val audioBitrate: Long = 192_000,
    val container: String = "mp4",
) {
    companion object {
        val DEFAULT_1080P = OutputProfile(Resolution(1920, 1080), FrameRate.FPS_30)
    }
}
