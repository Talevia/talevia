package io.talevia.android

import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlin.time.Duration
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Color as AndroidColor

/**
 * Fade envelope derived from `add_transition` — a head fade-in and/or a
 * tail fade-out on the neighbouring video clips. Null duration means no
 * fade on that edge. Split out of `Media3VideoEngine.kt` as part of
 * `debt-split-android-media3-video-engine` (2026-04-23).
 */
internal data class ClipFades(val headFade: Duration? = null, val tailFade: Duration? = null)

/**
 * Scan the timeline for `add_transition`-emitted Effect-track clips and
 * map each affected video clip's id to its fade envelope. Mirrors
 * `FfmpegVideoEngine.transitionFadesFor` — every transition name collapses
 * to a dip-to-black fade split across the two neighbours (halfDur on each
 * side of the boundary). That's the cross-engine parity floor; richer
 * transitions need a timeline-model change (A/B overlap) and land later.
 */
internal fun transitionFadesFor(timeline: Timeline, videoClips: List<Clip.Video>): Map<String, ClipFades> {
    val transitions = timeline.tracks
        .filterIsInstance<Track.Effect>()
        .flatMap { it.clips.filterIsInstance<Clip.Video>() }
        .filter { it.assetId.value.startsWith("transition:") }
    if (transitions.isEmpty()) return emptyMap()
    val acc = mutableMapOf<String, ClipFades>()
    for (trans in transitions) {
        val half = trans.timeRange.duration / 2
        val boundary = trans.timeRange.start + half
        val from = videoClips.firstOrNull { it.timeRange.end == boundary }
        val to = videoClips.firstOrNull { it.timeRange.start == boundary }
        if (from != null) {
            val prev = acc[from.id.value] ?: ClipFades()
            acc[from.id.value] = prev.copy(tailFade = half)
        }
        if (to != null) {
            val prev = acc[to.id.value] ?: ClipFades()
            acc[to.id.value] = prev.copy(headFade = half)
        }
    }
    return acc
}

/**
 * Build the fade-to-black [TextureOverlay]s for a clip's transition
 * envelope. The returned overlays are meant to be composited **under** the
 * subtitle overlays so captions stay legible while the image dips.
 * Presentation times on each overlay are clip-local microseconds (Media3
 * passes the clip's own timeline to [FadeBlackOverlay.getOverlaySettings],
 * starting from 0).
 */
internal fun fadeOverlaysFor(clip: Clip.Video, fades: Map<String, ClipFades>): List<TextureOverlay> {
    val envelope = fades[clip.id.value] ?: return emptyList()
    val clipDurUs = clip.sourceRange.duration.inWholeMicroseconds
    val result = mutableListOf<TextureOverlay>()
    envelope.headFade?.let { d ->
        val endUs = d.inWholeMicroseconds.coerceAtMost(clipDurUs)
        if (endUs > 0) {
            result += FadeBlackOverlay(startUs = 0, endUs = endUs, startAlpha = 1f, endAlpha = 0f)
        }
    }
    envelope.tailFade?.let { d ->
        val len = d.inWholeMicroseconds.coerceAtMost(clipDurUs)
        if (len > 0) {
            val startUs = (clipDurUs - len).coerceAtLeast(0)
            result += FadeBlackOverlay(startUs = startUs, endUs = clipDurUs, startAlpha = 0f, endAlpha = 1f)
        }
    }
    return result
}

/**
 * Full-frame black [BitmapOverlay] whose `alphaScale` ramps linearly between
 * [startAlpha] and [endAlpha] across `[startUs, endUs]`. Outside that window
 * the alpha sticks at the nearer endpoint — so a head fade-in (1 → 0) stays
 * transparent for the rest of the clip, and a tail fade-out (0 → 1) stays
 * transparent until its window opens.
 *
 * Covering the frame pixel-for-pixel: `configure(videoSize)` gives us the
 * exact render size, we allocate a black ARGB bitmap of that dimension, and
 * the default OverlaySettings (scale=(1,1), centered) maps the bitmap 1:1
 * onto the video frame. The bitmap is reused across frames so the GL
 * texture upload happens once per clip.
 */
internal class FadeBlackOverlay(
    private val startUs: Long,
    private val endUs: Long,
    private val startAlpha: Float,
    private val endAlpha: Float,
) : BitmapOverlay() {
    private var blackBitmap: AndroidBitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        blackBitmap?.recycle()
        blackBitmap = AndroidBitmap
            .createBitmap(videoSize.width, videoSize.height, AndroidBitmap.Config.ARGB_8888)
            .apply { eraseColor(AndroidColor.BLACK) }
    }

    override fun getBitmap(presentationTimeUs: Long): AndroidBitmap =
        blackBitmap ?: AndroidBitmap
            .createBitmap(16, 16, AndroidBitmap.Config.ARGB_8888)
            .apply { eraseColor(AndroidColor.BLACK) }
            .also { blackBitmap = it }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val alpha = when {
            presentationTimeUs <= startUs -> startAlpha
            presentationTimeUs >= endUs -> endAlpha
            else -> {
                val span = (endUs - startUs).coerceAtLeast(1L)
                val progress = (presentationTimeUs - startUs).toFloat() / span.toFloat()
                startAlpha + (endAlpha - startAlpha) * progress
            }
        }
        return OverlaySettings.Builder().setAlphaScale(alpha.coerceIn(0f, 1f)).build()
    }

    override fun release() {
        super.release()
        blackBitmap?.recycle()
        blackBitmap = null
    }
}
