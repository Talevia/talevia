package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Clip {
    abstract val id: ClipId
    /** Position on the timeline. */
    abstract val timeRange: TimeRange
    /** Position in the source media (for media-backed clips); ignored for synthetic clips. */
    abstract val sourceRange: TimeRange?
    abstract val transforms: List<Transform>

    @Serializable @SerialName("video")
    data class Video(
        override val id: ClipId,
        override val timeRange: TimeRange,
        override val sourceRange: TimeRange,
        override val transforms: List<Transform> = emptyList(),
        val assetId: AssetId,
        val filters: List<Filter> = emptyList(),
    ) : Clip()

    @Serializable @SerialName("audio")
    data class Audio(
        override val id: ClipId,
        override val timeRange: TimeRange,
        override val sourceRange: TimeRange,
        override val transforms: List<Transform> = emptyList(),
        val assetId: AssetId,
        val volume: Float = 1.0f,
    ) : Clip()

    @Serializable @SerialName("text")
    data class Text(
        override val id: ClipId,
        override val timeRange: TimeRange,
        override val transforms: List<Transform> = emptyList(),
        val text: String,
        val style: TextStyle = TextStyle(),
    ) : Clip() {
        override val sourceRange: TimeRange? = null
    }
}

@Serializable
data class Transform(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
)

@Serializable
data class Filter(
    val name: String,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
data class TextStyle(
    val fontFamily: String = "system",
    val fontSize: Float = 48f,
    val color: String = "#FFFFFF",
    val backgroundColor: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
)
