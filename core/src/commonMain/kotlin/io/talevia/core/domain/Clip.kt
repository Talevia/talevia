package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
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
    /**
     * Which [io.talevia.core.domain.source.SourceNode]s this clip derives from.
     *
     * Empty set means "not bound to any source node" — acceptable for hand-authored
     * clips from pre-source workflows, but such clips cannot benefit from incremental
     * compilation (VISION §3.2). AIGC-produced clips should populate this with the
     * ids of the nodes whose contentHash went into the prompt / parameters, so a
     * change upstream flows through `Source.stale` → `Project.staleClips` → the
     * incremental render path.
     */
    abstract val sourceBinding: Set<SourceNodeId>

    /**
     * Epoch-millis of the last structural change to this clip, or `null`
     * when the backing project blob was written before recency tracking
     * existed. Stamped by [FileProjectStore] on `upsert` — new
     * clips get `now`, unchanged clips preserve their prior stamp,
     * content-changed clips get `now`. Tools do NOT stamp manually.
     * Exposed to the LLM via `project_query(select=timeline_clips,
     * sortBy="recent")`; nulls sort last so pre-recency projects degrade
     * gracefully to a deterministic tail.
     */
    abstract val updatedAtEpochMs: Long?

    @Serializable @SerialName("video")
    data class Video(
        override val id: ClipId,
        override val timeRange: TimeRange,
        override val sourceRange: TimeRange,
        override val transforms: List<Transform> = emptyList(),
        val assetId: AssetId,
        val filters: List<Filter> = emptyList(),
        override val sourceBinding: Set<SourceNodeId> = emptySet(),
        override val updatedAtEpochMs: Long? = null,
    ) : Clip()

    @Serializable @SerialName("audio")
    data class Audio(
        override val id: ClipId,
        override val timeRange: TimeRange,
        override val sourceRange: TimeRange,
        override val transforms: List<Transform> = emptyList(),
        val assetId: AssetId,
        val volume: Float = 1.0f,
        /**
         * Fade-in ramp length at the clip's head, in seconds. `0.0` disables.
         * Must be `<= timeRange.duration`; the engine applies a linear-to-[volume]
         * envelope over the first [fadeInSeconds] of playback. Complements [volume]:
         * set the steady-state level with `volume`, shape attack/release with the
         * fade fields.
         */
        val fadeInSeconds: Float = 0.0f,
        /**
         * Fade-out ramp length at the clip's tail, in seconds. `0.0` disables.
         * `fadeInSeconds + fadeOutSeconds` must not exceed `timeRange.duration`
         * (otherwise the fades would overlap, which has no well-defined envelope).
         */
        val fadeOutSeconds: Float = 0.0f,
        override val sourceBinding: Set<SourceNodeId> = emptySet(),
        override val updatedAtEpochMs: Long? = null,
    ) : Clip()

    @Serializable @SerialName("text")
    data class Text(
        override val id: ClipId,
        override val timeRange: TimeRange,
        override val transforms: List<Transform> = emptyList(),
        val text: String,
        val style: TextStyle = TextStyle(),
        override val sourceBinding: Set<SourceNodeId> = emptySet(),
        override val updatedAtEpochMs: Long? = null,
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

/**
 * A render-time filter attached to a [Clip.Video].
 *
 * `name` is the ffmpeg / engine-side identifier ("brightness", "blur", "lut", …) and
 * `params` is the numeric knob bag for simple scalar filters. Filters that need a
 * bound project asset — e.g. a 3D LUT `.cube` file the engine loads from disk — set
 * [assetId] instead of trying to cram a path into [params] (which is `Map<String, Float>`
 * on purpose: numeric-only). The engine resolves [assetId] through `MediaPathResolver`
 * at render time; tools never pass absolute paths.
 */
@Serializable
data class Filter(
    val name: String,
    val params: Map<String, Float> = emptyMap(),
    val assetId: AssetId? = null,
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
