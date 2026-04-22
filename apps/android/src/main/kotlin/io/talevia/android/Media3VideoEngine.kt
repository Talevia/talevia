package io.talevia.android

import android.content.Context
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.platform.lut.CubeLutParser
import io.talevia.core.platform.lut.toMedia3Cube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import android.graphics.Color as AndroidColor

/**
 * Android implementation of [VideoEngine] using Media3's `Transformer` for export.
 * Probe metadata via `MediaMetadataRetriever`. Concat is via
 * `EditedMediaItemSequence` of clipped `EditedMediaItem`s.
 */
class Media3VideoEngine(
    private val context: Context,
    private val pathResolver: MediaPathResolver,
) : VideoEngine {

    override suspend fun probe(source: MediaSource): MediaMetadata = withContext(Dispatchers.IO) {
        val path = sourceToPath(source)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val frameRate = frameRateStr?.toFloatOrNull()?.let { FrameRate(numerator = it.toInt(), denominator = 1) }
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            MediaMetadata(
                duration = durationMs.milliseconds,
                resolution = if (width != null && height != null) Resolution(width, height) else null,
                frameRate = frameRate,
                bitrate = bitrate,
            )
        } finally {
            retriever.release()
        }
    }

    override fun render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: MediaPathResolver?,
    ): Flow<RenderProgress> = callbackFlow {
        val effectiveResolver = resolver ?: pathResolver
        val jobId = UUID.randomUUID().toString()
        trySend(RenderProgress.Started(jobId))

        val videoClips = videoClips(timeline)
        if (videoClips.isEmpty()) {
            trySend(RenderProgress.Failed(jobId, "no video clips to render"))
            close()
            return@callbackFlow
        }

        // Pre-resolve every asset-bound filter (today: only `lut`). Media3's
        // effect builders are synchronous so we cannot call the suspend
        // resolver inside `mapFilterToEffect`. Resolving here also lets us
        // parse each `.cube` once per render even if multiple clips share
        // the same LUT asset.
        val filterAssetPaths = mutableMapOf<String, String>()
        videoClips.forEach { c ->
            c.filters.forEach { f ->
                val aid = f.assetId
                if (aid != null) filterAssetPaths.getOrPut(aid.value) { effectiveResolver.resolve(aid) }
            }
        }
        val lutCache = mutableMapOf<String, SingleColorLut>()
        val subtitles = subtitleClips(timeline)
        val transitionFades = transitionFadesFor(timeline, videoClips)

        val items = videoClips.map { c ->
            val resolvedPath = effectiveResolver.resolve(c.assetId)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("file://$resolvedPath"))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((c.sourceRange.start.inWholeMilliseconds))
                        .setEndPositionMs((c.sourceRange.start + c.sourceRange.duration).inWholeMilliseconds)
                        .build(),
                )
                .build()
            val builder = EditedMediaItem.Builder(mediaItem)
            val videoEffects: MutableList<Effect> = c.filters
                .mapNotNull { mapFilterToEffect(it, filterAssetPaths, lutCache) }
                .toMutableList()
            // Build the overlay list: transition fades first (composited under
            // subtitles so the caption stays legible even while the image dips
            // to black — matches the FFmpeg pipeline, where drawtext runs
            // after the per-clip `fade` filter), then the subtitle overlays.
            val overlayList = buildList {
                addAll(fadeOverlaysFor(c, transitionFades))
                addAll(subtitleOverlaysFor(c, subtitles))
            }
            if (overlayList.isNotEmpty()) videoEffects += OverlayEffect(overlayList)
            if (videoEffects.isNotEmpty()) {
                builder.setEffects(Effects(emptyList(), videoEffects))
            }
            builder.build()
        }
        val sequence = EditedMediaItemSequence(items)
        val composition = Composition.Builder(listOf(sequence)).build()

        val outFile = File(output.targetPath)
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    trySend(RenderProgress.Completed(jobId, output.targetPath))
                    close()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    trySend(RenderProgress.Failed(jobId, exportException.message ?: "media3 transformer error"))
                    close()
                }
            })
            .build()

        transformer.start(composition, outFile.absolutePath)

        // Media3 doesn't push per-frame progress callbacks — poll getProgress() at
        // ~10 Hz while the export is running. ProgressHolder is a small mutable holder
        // the Transformer fills in synchronously on the same thread.
        val pollScope = CoroutineScope(Dispatchers.Default)
        pollScope.launch {
            val holder = androidx.media3.transformer.ProgressHolder()
            while (isActive) {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    trySend(RenderProgress.Frames(jobId, holder.progress / 100f))
                } else if (state == Transformer.PROGRESS_STATE_NOT_STARTED) {
                    break
                }
                delay(100)
            }
        }

        awaitClose {
            pollScope.cancel()
            transformer.cancel()
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceToPath(source))
                val frame = retriever.getFrameAtTime(time.toLong(DurationUnit.MICROSECONDS))
                    ?: error("no frame at $time")
                val baos = java.io.ByteArrayOutputStream()
                frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            } finally {
                retriever.release()
            }
        }

    private fun sourceToPath(source: MediaSource): String = when (source) {
        is MediaSource.File -> source.path
        is MediaSource.Http -> error("Http MediaSource not supported (download first)")
        is MediaSource.Platform -> when (source.scheme) {
            "content" -> source.value
            else -> error("Unknown platform scheme: ${source.scheme}")
        }
        // TODO(file-bundle-migration): BundleFile resolution requires the
        // per-render BundleMediaPathResolver — sourceToPath() is called from
        // probe() / extractFrame() paths that don't have one yet. The render
        // path already routes through pathResolver so it's covered.
        is MediaSource.BundleFile -> error(
            "BundleFile sources require a per-render BundleMediaPathResolver; " +
                "Media3VideoEngine.sourceToPath() does not have one in this code path",
        )
    }

    private fun videoClips(t: Timeline): List<Clip.Video> = t.tracks.filterIsInstance<Track.Video>()
        .flatMap { it.clips.filterIsInstance<Clip.Video>() }
        .sortedBy { it.timeRange.start }

    /**
     * Map a core [Filter] to a Media3 [Effect]. Returns `null` for filters we
     * don't yet support; the caller skips them (the Project state still
     * carries the filter so future Media3 upgrades can pick it up). Parity
     * goal is "FFmpeg filters that land on video clips should also land on
     * Media3" — matches what the FFmpeg JVM engine exposes via `apply_filter`
     * and `apply_lut`.
     *
     * Supported today:
     * - `brightness` → [Brightness] (intensity clamped to [-1, 1])
     * - `saturation` → [HslAdjustment] (Core's 0..1 intensity → Media3 -100..+100)
     * - `blur`       → [GaussianBlur] sigma
     * - `lut`        → [SingleColorLut] (`.cube` file parsed via
     *   `CubeLutParser`; cached by asset id within one render call).
     *
     * Not yet supported:
     * - `vignette`  — Media3 has no built-in Vignette effect; needs a custom
     *   `GlShaderProgram`. Intentional no-op for now.
     */
    private fun mapFilterToEffect(
        filter: Filter,
        filterAssetPaths: Map<String, String>,
        lutCache: MutableMap<String, SingleColorLut>,
    ): Effect? = when (filter.name.lowercase()) {
        "brightness" -> {
            val v = (filter.params["intensity"] ?: filter.params["value"] ?: 0f)
                .coerceIn(-1f, 1f)
            Brightness(v)
        }
        "saturation" -> {
            // Core's apply_filter semantics for saturation: `intensity` is a 0..1
            // knob where 0.5 ≈ unchanged (matches the FFmpeg engine's 0..1 → 0..2
            // mapping). Remap to Media3's [-100, 100] "saturation delta" scale
            // centered at 0 = no change: intensity 0.5 → 0, 1.0 → +100, 0.0 → -100.
            val raw = filter.params["intensity"] ?: filter.params["value"]
            val delta = if (raw != null && filter.params.containsKey("intensity")) {
                ((raw - 0.5f) * 200f).coerceIn(-100f, 100f)
            } else {
                // No intensity given → neutral (no-op rather than dropping).
                0f
            }
            HslAdjustment.Builder().adjustSaturation(delta).build()
        }
        "blur" -> {
            // Match the FFmpeg engine's two-knob shape: `sigma` verbatim, else
            // `radius` on 0..1 mapped to 0..10 sigma.
            val sigma = filter.params["sigma"]
                ?: filter.params["radius"]?.let { (it * 10f).coerceIn(0f, 50f) }
                ?: 5f
            GaussianBlur(sigma)
        }
        "vignette" -> {
            // Media3 has no built-in Vignette. Instead of a custom GlShaderProgram
            // (needs a shader + texture lifecycle + format negotiation), bake a
            // radial-gradient `BitmapOverlay` at the video frame size — same
            // trick we use for transition fade-to-black. Multiplicative-style
            // darkening at the corners, transparent through the center. Matches
            // the FFmpeg `vignette` filter's visual and the iOS `CIVignette`
            // rendering closely enough for cross-engine parity (VISION §5.2).
            val intensity = (filter.params["intensity"] ?: filter.params["value"] ?: 0.5f)
                .coerceIn(0f, 1f)
            OverlayEffect(listOf(VignetteOverlay(intensity)))
        }
        "lut" -> {
            val aid = filter.assetId
            val path = aid?.value?.let { filterAssetPaths[it] }
            if (aid == null || path == null) {
                log.warn(
                    "lut filter missing resolvable assetId — skipping",
                    "filter" to filter.name,
                    "assetId" to aid?.value,
                )
                null
            } else {
                // Parse + cache per asset id so the `.cube` hits disk once
                // even when many clips share the same LUT.
                lutCache.getOrPut(aid.value) {
                    val cube = CubeLutParser.parse(File(path).readText()).toMedia3Cube()
                    SingleColorLut.createFromCube(cube)
                }
            }
        }
        else -> {
            log.warn(
                "unknown filter on Media3 engine — skipping",
                "filter" to filter.name,
            )
            null
        }
    }

    private fun subtitleClips(t: Timeline): List<Clip.Text> = t.tracks.filterIsInstance<Track.Subtitle>()
        .flatMap { it.clips.filterIsInstance<Clip.Text>() }
        .sortedBy { it.timeRange.start }

    /**
     * Fade envelope derived from `add_transition` — a head fade-in and/or a
     * tail fade-out on the neighbouring video clips. Null duration means no
     * fade on that edge.
     */
    private data class ClipFades(val headFade: Duration? = null, val tailFade: Duration? = null)

    /**
     * Scan the timeline for `add_transition`-emitted Effect-track clips and
     * map each affected video clip's id to its fade envelope. Mirrors
     * `FfmpegVideoEngine.transitionFadesFor` — every transition name collapses
     * to a dip-to-black fade split across the two neighbours (halfDur on each
     * side of the boundary). That's the cross-engine parity floor; richer
     * transitions need a timeline-model change (A/B overlap) and land later.
     */
    private fun transitionFadesFor(timeline: Timeline, videoClips: List<Clip.Video>): Map<String, ClipFades> {
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
    private fun fadeOverlaysFor(clip: Clip.Video, fades: Map<String, ClipFades>): List<TextureOverlay> {
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
    private fun subtitleOverlaysFor(clip: Clip.Video, subtitles: List<Clip.Text>): List<TextureOverlay> {
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

    private val log = Loggers.get("Media3VideoEngine")

    companion object {
        private val BOTTOM_CENTER_VISIBLE: OverlaySettings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.8f)
            .setAlphaScale(1f)
            .build()
        private val BOTTOM_CENTER_HIDDEN: OverlaySettings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.8f)
            .setAlphaScale(0f)
            .build()
    }

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
    private class FadeBlackOverlay(
        private val startUs: Long,
        private val endUs: Long,
        private val startAlpha: Float,
        private val endAlpha: Float,
    ) : BitmapOverlay() {
        private var blackBitmap: android.graphics.Bitmap? = null

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            blackBitmap?.recycle()
            blackBitmap = android.graphics.Bitmap
                .createBitmap(videoSize.width, videoSize.height, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(AndroidColor.BLACK) }
        }

        override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap =
            blackBitmap ?: android.graphics.Bitmap
                .createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
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

    /**
     * Static radial-gradient overlay that bakes a single video-sized bitmap at
     * `configure` time — transparent at the center, opaque black at the
     * corners — and returns it on every `getBitmap` call. `getOverlaySettings`
     * is the default (alphaScale = 1), because the actual darkness ramp is
     * already in the bitmap's alpha channel. One GL texture upload per clip.
     *
     * [intensity] 0..1 drives two knobs simultaneously: corner alpha (0 → no
     * vignette; 1 → pitch-black corners) and the radial stop where the fade
     * begins (higher intensity keeps the clear centre smaller, matching how
     * the FFmpeg `vignette` filter intensifies).
     */
    private class VignetteOverlay(private val intensity: Float) : BitmapOverlay() {
        private var bitmap: android.graphics.Bitmap? = null

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            bitmap?.recycle()
            val w = videoSize.width
            val h = videoSize.height
            val bmp = android.graphics.Bitmap
                .createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val cx = w / 2f
            val cy = h / 2f
            val radius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
            val edgeAlpha = (255f * intensity).toInt().coerceIn(0, 255)
            // Clear centre starts at 35%–55% of the radius depending on
            // intensity: a strong vignette should squeeze the bright region
            // inward, not just darken the same corners more.
            val innerStop = (0.55f - 0.2f * intensity).coerceIn(0.1f, 0.9f)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                shader = android.graphics.RadialGradient(
                    cx,
                    cy,
                    radius,
                    intArrayOf(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.argb(edgeAlpha, 0, 0, 0),
                    ),
                    floatArrayOf(innerStop, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            bitmap = bmp
        }

        override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap =
            bitmap ?: android.graphics.Bitmap
                .createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                .also { bitmap = it }

        override fun release() {
            super.release()
            bitmap?.recycle()
            bitmap = null
        }
    }
}
