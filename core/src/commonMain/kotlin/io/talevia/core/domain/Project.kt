package io.talevia.core.domain

import io.talevia.core.ProjectId
import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: ProjectId,
    val timeline: Timeline,
    val assets: List<MediaAsset> = emptyList(),
    val outputProfile: OutputProfile = OutputProfile.DEFAULT_1080P,
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
