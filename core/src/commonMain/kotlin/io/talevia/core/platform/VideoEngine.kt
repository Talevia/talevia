package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import kotlinx.coroutines.flow.Flow

/**
 * Per-platform video processing service. The Core defines this contract; each
 * platform supplies an implementation:
 *  - JVM (Desktop / Server): FfmpegVideoEngine (shells out to system ffmpeg/ffprobe)
 *  - iOS: AVFoundationVideoEngine (Swift, injected via SKIE)
 *  - Android: Media3VideoEngine
 */
interface VideoEngine {
    /** Inspect a media file and return its metadata. */
    suspend fun probe(source: MediaSource): MediaMetadata

    /** Render the [timeline] into [output]; emit progress events as work proceeds. */
    fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress>

    /** Generate a single-frame thumbnail at [time]. Returns PNG bytes. */
    suspend fun thumbnail(asset: AssetId, source: MediaSource, time: kotlin.time.Duration): ByteArray
}

data class OutputSpec(
    val targetPath: String,
    val resolution: Resolution,
    val frameRate: Int = 30,
    val videoBitrate: Long = 8_000_000,
    val audioBitrate: Long = 192_000,
    val videoCodec: String = "h264",
    val audioCodec: String = "aac",
    val container: String = "mp4",
)

sealed interface RenderProgress {
    data class Started(val jobId: String) : RenderProgress
    data class Frames(val jobId: String, val ratio: Float, val message: String? = null) : RenderProgress
    data class Completed(val jobId: String, val outputPath: String) : RenderProgress
    data class Failed(val jobId: String, val message: String) : RenderProgress
}
