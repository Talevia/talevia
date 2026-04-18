package io.talevia.core.domain

import io.talevia.core.AssetId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class MediaAsset(
    val id: AssetId,
    val source: MediaSource,
    val metadata: MediaMetadata,
    val proxies: List<ProxyAsset> = emptyList(),
)

@Serializable
sealed class MediaSource {
    @Serializable @SerialName("file")
    data class File(val path: String) : MediaSource()

    @Serializable @SerialName("http")
    data class Http(val url: String) : MediaSource()

    /** Platform-opaque reference (e.g. iOS PHAsset localIdentifier, Android MediaStore Uri). */
    @Serializable @SerialName("platform")
    data class Platform(val scheme: String, val value: String) : MediaSource()
}

@Serializable
data class MediaMetadata(
    val duration: Duration,
    val resolution: Resolution? = null,
    val frameRate: FrameRate? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitrate: Long? = null,
)

@Serializable
data class ProxyAsset(
    val source: MediaSource,
    val purpose: ProxyPurpose,
    val resolution: Resolution? = null,
)

@Serializable
enum class ProxyPurpose {
    @SerialName("thumbnail") THUMBNAIL,
    @SerialName("low-res") LOW_RES,
    @SerialName("audio-waveform") AUDIO_WAVEFORM,
}
