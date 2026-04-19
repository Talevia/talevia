package io.talevia.core.domain

import io.talevia.core.ProjectId
import io.talevia.core.domain.lockfile.Lockfile
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
