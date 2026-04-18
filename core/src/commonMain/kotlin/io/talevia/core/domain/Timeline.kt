package io.talevia.core.domain

import io.talevia.core.TrackId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
    @Serializable(with = DurationSerializer::class) val duration: Duration = Duration.ZERO,
    val frameRate: FrameRate = FrameRate.FPS_30,
    val resolution: Resolution = Resolution(1920, 1080),
)

@Serializable
sealed class Track {
    abstract val id: TrackId
    /** Must be ordered by `clip.timeRange.start`. Enforced at construction time. */
    abstract val clips: List<Clip>

    @Serializable @SerialName("video")
    data class Video(
        override val id: TrackId,
        override val clips: List<Clip> = emptyList(),
    ) : Track()

    @Serializable @SerialName("audio")
    data class Audio(
        override val id: TrackId,
        override val clips: List<Clip> = emptyList(),
    ) : Track()

    @Serializable @SerialName("subtitle")
    data class Subtitle(
        override val id: TrackId,
        override val clips: List<Clip> = emptyList(),
    ) : Track()

    @Serializable @SerialName("effect")
    data class Effect(
        override val id: TrackId,
        override val clips: List<Clip> = emptyList(),
    ) : Track()
}
