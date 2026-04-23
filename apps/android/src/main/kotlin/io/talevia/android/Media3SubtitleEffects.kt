package io.talevia.android

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import android.graphics.Color as AndroidColor

private val log = Loggers.get("Media3VideoEngine.subtitle")

private val BOTTOM_CENTER_VISIBLE: OverlaySettings = OverlaySettings.Builder()
    .setBackgroundFrameAnchor(0f, -0.8f)
    .setAlphaScale(1f)
    .build()

private val BOTTOM_CENTER_HIDDEN: OverlaySettings = OverlaySettings.Builder()
    .setBackgroundFrameAnchor(0f, -0.8f)
    .setAlphaScale(0f)
    .build()

/**
 * Flatten every [Clip.Text] across the timeline's subtitle tracks,
 * sorted by start time. Callers pass this to [subtitleOverlaysFor] per
 * video clip.
 *
 * Split out of `Media3VideoEngine.kt` as part of
 * `debt-split-android-media3-video-engine` (2026-04-23).
 */
internal fun subtitleClips(t: Timeline): List<Clip.Text> = t.tracks.filterIsInstance<Track.Subtitle>()
    .flatMap { it.clips.filterIsInstance<Clip.Text>() }
    .sortedBy { it.timeRange.start }

/**
 * Build one [TextureOverlay] per subtitle whose timeline range overlaps
 * [clip]'s timeline range. The overlay is visible only while the subtitle
 * window is active, toggled via [OverlaySettings.alphaScale] (0 outside,
 * 1 inside) — this keeps [TextOverlay.getText] stable so Media3's internal
 * bitmap cache rasterises each spannable once per clip.
 *
 * Positioning mirrors the FFmpeg engine's MVP: bottom-center anchored via
 * `backgroundFrameAnchor(0f, -0.8f)` (x=0 centers horizontally, y=-0.8
 * lands the overlay roughly 10% from the bottom of the 2×2 NDC-like frame
 * Media3 uses). Custom TextStyle positioning is a later knob.
 */
internal fun subtitleOverlaysFor(clip: Clip.Video, subtitles: List<Clip.Text>): List<TextureOverlay> {
    if (subtitles.isEmpty()) return emptyList()
    val clipStart = clip.timeRange.start
    val clipEnd = clip.timeRange.end
    return subtitles.mapNotNull { sub ->
        if (sub.timeRange.start >= clipEnd || sub.timeRange.end <= clipStart) return@mapNotNull null
        val localStartUs = (maxOf(sub.timeRange.start, clipStart) - clipStart).inWholeMicroseconds
        val localEndUs = (minOf(sub.timeRange.end, clipEnd) - clipStart).inWholeMicroseconds
        SubtitleTextOverlay(
            text = buildSpannable(sub.text, sub.style),
            startUs = localStartUs,
            endUs = localEndUs,
            visibleSettings = BOTTOM_CENTER_VISIBLE,
            hiddenSettings = BOTTOM_CENTER_HIDDEN,
        )
    }
}

private fun buildSpannable(text: String, style: TextStyle): SpannableString {
    val s = SpannableString(text)
    val end = text.length
    if (end == 0) return s
    val fg = parseColor(style.color)
    if (fg != null) s.setSpan(ForegroundColorSpan(fg), 0, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    style.backgroundColor?.let { bg ->
        parseColor(bg)?.let { s.setSpan(BackgroundColorSpan(it), 0, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE) }
    }
    val sizePx = style.fontSize.toInt().coerceAtLeast(1)
    s.setSpan(AbsoluteSizeSpan(sizePx, /* dip= */ false), 0, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    val styleFlag = when {
        style.bold && style.italic -> Typeface.BOLD_ITALIC
        style.bold -> Typeface.BOLD
        style.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    if (styleFlag != Typeface.NORMAL) {
        s.setSpan(StyleSpan(styleFlag), 0, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
    if (style.fontFamily.isNotBlank() && style.fontFamily != "system") {
        s.setSpan(TypefaceSpan(style.fontFamily), 0, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
    return s
}

private fun parseColor(hex: String): Int? = runCatching { AndroidColor.parseColor(hex) }
    .onFailure { log.warn("invalid subtitle color; falling back to default", "hex" to hex) }
    .getOrNull()

/**
 * Time-gated [TextOverlay] — Media3 caches the rasterised bitmap keyed on
 * the SpannableString, so we keep [getText] constant and toggle visibility
 * via [OverlaySettings.alphaScale]. The text rasterises once and the GPU
 * blend skips it outside the window.
 */
private class SubtitleTextOverlay(
    private val text: SpannableString,
    private val startUs: Long,
    private val endUs: Long,
    private val visibleSettings: OverlaySettings,
    private val hiddenSettings: OverlaySettings,
) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString = text
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (presentationTimeUs in startUs until endUs) visibleSettings else hiddenSettings
}
