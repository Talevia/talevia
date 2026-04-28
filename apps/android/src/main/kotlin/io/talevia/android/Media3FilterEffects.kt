package io.talevia.android

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.SingleColorLut
import io.talevia.core.domain.Filter
import io.talevia.core.domain.FilterKind
import io.talevia.core.domain.kind
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import io.talevia.core.platform.lut.CubeLutParser
import io.talevia.core.platform.lut.toMedia3Cube
import java.io.File
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Color as AndroidColor

private val log = Loggers.get("Media3VideoEngine.filter")

/**
 * Map a core [Filter] to a Media3 [Effect]. Returns `null` for filters
 * we don't yet support; the caller skips them (the Project state still
 * carries the filter so future Media3 upgrades can pick it up). Parity
 * goal is "FFmpeg filters that land on video clips should also land on
 * Media3" — matches what the FFmpeg JVM engine exposes via `apply_filter`
 * and `apply_lut`.
 *
 * Supported today:
 * - `brightness` → [Brightness] (intensity clamped to [-1, 1])
 * - `saturation` → [HslAdjustment] (Core's 0..1 intensity → Media3 -100..+100)
 * - `blur`       → [GaussianBlur] sigma
 * - `vignette`   → radial-gradient [BitmapOverlay] baked at video size
 * - `lut`        → [SingleColorLut] (`.cube` file parsed via
 *   `CubeLutParser`; cached by asset id within one render call).
 *
 * Split out of `Media3VideoEngine.kt` as part of
 * `debt-split-android-media3-video-engine` (2026-04-23).
 */
internal fun mapFilterToEffect(
    filter: Filter,
    filterAssetPaths: Map<String, String>,
    lutCache: MutableMap<String, SingleColorLut>,
): Effect? = when (filter.kind) {
    FilterKind.Brightness -> {
        val v = (filter.params["intensity"] ?: filter.params["value"] ?: 0f)
            .coerceIn(-1f, 1f)
        Brightness(v)
    }
    FilterKind.Saturation -> {
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
    FilterKind.Blur -> {
        // Match the FFmpeg engine's two-knob shape: `sigma` verbatim, else
        // `radius` on 0..1 mapped to 0..10 sigma.
        val sigma = filter.params["sigma"]
            ?: filter.params["radius"]?.let { (it * 10f).coerceIn(0f, 50f) }
            ?: 5f
        GaussianBlur(sigma)
    }
    FilterKind.Vignette -> {
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
    FilterKind.Lut -> {
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
    null -> {
        // Unknown filter name → unrecognised kind → drop silently with a
        // log line. Same semantic as the prior else-branch on the
        // string-switch.
        log.warn(
            "unknown filter on Media3 engine — skipping",
            "filter" to filter.name,
        )
        null
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
internal class VignetteOverlay(private val intensity: Float) : BitmapOverlay() {
    private var bitmap: AndroidBitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        bitmap?.recycle()
        val w = videoSize.width
        val h = videoSize.height
        val bmp = AndroidBitmap.createBitmap(w, h, AndroidBitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val cy = h / 2f
        val radius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val edgeAlpha = (255f * intensity).toInt().coerceIn(0, 255)
        // Clear centre starts at 35%–55% of the radius depending on
        // intensity: a strong vignette should squeeze the bright region
        // inward, not just darken the same corners more.
        val innerStop = (0.55f - 0.2f * intensity).coerceIn(0.1f, 0.9f)
        val paint = Paint().apply {
            isAntiAlias = true
            shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    AndroidColor.argb(edgeAlpha, 0, 0, 0),
                ),
                floatArrayOf(innerStop, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        bitmap = bmp
    }

    override fun getBitmap(presentationTimeUs: Long): AndroidBitmap =
        bitmap ?: AndroidBitmap
            .createBitmap(16, 16, AndroidBitmap.Config.ARGB_8888)
            .also { bitmap = it }

    override fun release() {
        super.release()
        bitmap?.recycle()
        bitmap = null
    }
}
