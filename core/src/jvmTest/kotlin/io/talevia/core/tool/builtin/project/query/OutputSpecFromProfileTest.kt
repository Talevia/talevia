package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [outputSpecFromProfile] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/query/RenderStaleQuery.kt:30`.
 * Cycle 277 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-276.
 *
 * `outputSpecFromProfile` is the pure mapping function from
 * the domain-side [OutputProfile] (the project's chosen
 * resolution / fps / codec settings) to the engine-facing
 * [io.talevia.core.platform.OutputSpec] (what `render_stale`
 * compares mezzanine fingerprints against). Used by
 * `select=render_stale` to compute "would the current
 * project output produce the same bytes as what's cached?"
 * for every clip.
 *
 * Drift signals:
 *   - **Field-mapping drift** (e.g. videoBitrate ↔
 *     audioBitrate swap) silently breaks render-cache
 *     fingerprinting — every clip would be reported as
 *     stale on every query.
 *   - **Frame-rate drift to fractional** (the function uses
 *     integer division `numerator / denominator`) silently
 *     drops the fractional part. For 1/1 ratios (24, 30, 60
 *     fps) this is fine; for NTSC-style 30000/1001 = 29.97
 *     fps it produces 29 (not 30), which downstream may
 *     not expect. Pin documents the actual behavior.
 *   - **Drift in `targetPath = ""`** (drift to a non-empty
 *     placeholder like "render-stale.mp4") would corrupt
 *     the fingerprint since OutputSpec.targetPath is
 *     hashed.
 *
 * Pins three correctness contracts:
 *
 *  1. **Resolution mapping**: `width` and `height` echoed
 *     verbatim through the new `Resolution(width, height)`
 *     construction. Drift to swap dimensions silently
 *     transposes every clip's render fingerprint.
 *
 *  2. **Field-by-field echo** for the 5 codec/bitrate
 *     fields (videoCodec / audioCodec / videoBitrate /
 *     audioBitrate / container). Marquee swap-detection
 *     pins (audio↔video bitrate is a common copy-paste
 *     mistake).
 *
 *  3. **`targetPath = ""` empty placeholder** — the caller
 *     fills it in. Drift to a non-empty default would
 *     pollute every render-stale fingerprint.
 *
 * Plus frame-rate semantics:
 *   - Integer ratios (24/1, 30/1, 60/1) produce exact
 *     integer fps.
 *   - Fractional ratio (e.g. 30000/1001 NTSC) integer-
 *     divides to 29 — pin documents the actual behavior so
 *     a future "NTSC support" cycle knows to refactor here.
 */
class OutputSpecFromProfileTest {

    @Test fun resolutionMappingPassesThroughWidthAndHeight() {
        // Marquee resolution pin: drift to swap (height,
        // width) would silently transpose every clip's
        // render fingerprint.
        val profile = OutputProfile(
            resolution = Resolution(width = 1920, height = 1080),
            frameRate = FrameRate.FPS_30,
        )
        val spec = outputSpecFromProfile(profile)
        assertEquals(1920, spec.resolution.width, "width MUST round-trip")
        assertEquals(1080, spec.resolution.height, "height MUST round-trip")
    }

    @Test fun nonStandardResolutionRoundTrips() {
        // Sister pin: 9:16 portrait + 1:1 square don't get
        // mangled.
        val portrait = OutputProfile(Resolution(1080, 1920), FrameRate.FPS_30)
        assertEquals(1080, outputSpecFromProfile(portrait).resolution.width)
        assertEquals(1920, outputSpecFromProfile(portrait).resolution.height)

        val square = OutputProfile(Resolution(1024, 1024), FrameRate.FPS_30)
        assertEquals(1024, outputSpecFromProfile(square).resolution.width)
        assertEquals(1024, outputSpecFromProfile(square).resolution.height)
    }

    // ── Frame rate division ──────────────────────────────

    @Test fun integerFrameRateDividesToExactInt() {
        // Pin: integer-ratio FrameRates (24/1, 30/1, 60/1)
        // produce exact integer fps via `numerator /
        // denominator`.
        for ((rate, expected) in listOf(
            FrameRate.FPS_24 to 24,
            FrameRate.FPS_30 to 30,
            FrameRate.FPS_60 to 60,
        )) {
            val profile = OutputProfile(Resolution(1920, 1080), rate)
            assertEquals(
                expected,
                outputSpecFromProfile(profile).frameRate,
                "FrameRate.${rate.numerator}/${rate.denominator} MUST produce $expected fps",
            )
        }
    }

    @Test fun fractionalFrameRateIntegerDividesAwayFraction() {
        // Marquee gotcha pin: per source line 33,
        // `numerator / denominator` is INTEGER division. NTSC
        // 30000/1001 = 29.97 fps surfaces as 29 (NOT 30, NOT
        // a Double). Pin documents the actual behavior so a
        // future NTSC cycle knows to refactor here.
        val ntsc = OutputProfile(
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate(30000, 1001),
        )
        assertEquals(
            29,
            outputSpecFromProfile(ntsc).frameRate,
            "NTSC 30000/1001 (29.97 fps) MUST integer-divide to 29 (gotcha — drift to fractional surfaces here)",
        )
    }

    @Test fun frameRateOneOverOneIsValid() {
        // Edge: 1 fps (slow timelapse). Pin documents the
        // function handles tiny rates without crashing.
        val slow = OutputProfile(Resolution(1920, 1080), FrameRate(1, 1))
        assertEquals(1, outputSpecFromProfile(slow).frameRate)
    }

    // ── Field-by-field echo + swap detection ──────────────

    @Test fun videoBitrateEchoes() {
        // Marquee swap-detection pin: drift to echo
        // audioBitrate into spec.videoBitrate (or vice versa)
        // is a common copy-paste mistake. Use distinct
        // sentinel values (12345 / 67890) so swaps surface.
        val profile = OutputProfile(
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
            videoBitrate = 12_345L,
            audioBitrate = 67_890L,
        )
        val spec = outputSpecFromProfile(profile)
        assertEquals(12_345L, spec.videoBitrate, "videoBitrate MUST round-trip (drift to swap with audio surfaces here)")
        assertEquals(67_890L, spec.audioBitrate, "audioBitrate MUST round-trip")
    }

    @Test fun videoCodecEchoes() {
        // Sister swap-detection pin for codec strings.
        val profile = OutputProfile(
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
            videoCodec = "h265",
            audioCodec = "opus",
        )
        val spec = outputSpecFromProfile(profile)
        assertEquals("h265", spec.videoCodec)
        assertEquals("opus", spec.audioCodec)
    }

    @Test fun containerEchoes() {
        for (c in listOf("mp4", "mov", "mkv", "webm")) {
            val profile = OutputProfile(
                resolution = Resolution(1920, 1080),
                frameRate = FrameRate.FPS_30,
                container = c,
            )
            assertEquals(c, outputSpecFromProfile(profile).container)
        }
    }

    // ── targetPath placeholder ────────────────────────────

    @Test fun targetPathIsEmptyPlaceholder() {
        // Marquee pin: per source line 31, `targetPath = ""`.
        // The caller (render-stale fingerprint computation)
        // doesn't need a real path — it just hashes the
        // OutputSpec. Drift to a non-empty placeholder would
        // pollute every fingerprint, making every clip
        // appear stale on every query.
        val spec = outputSpecFromProfile(OutputProfile.DEFAULT_1080P)
        assertEquals(
            "",
            spec.targetPath,
            "outputSpecFromProfile MUST set targetPath to empty string (caller fills it)",
        )
    }

    // ── Default profile shortcut ──────────────────────────

    @Test fun defaultProfileMapsTo1920x1080At30FpsH264() {
        // Pin: OutputProfile.DEFAULT_1080P passes through to
        // a coherent 1080p / 30 fps / h264 / aac / 8 Mbps spec.
        // Drift in any default would silently change every
        // fresh project's render-stale fingerprint.
        val spec = outputSpecFromProfile(OutputProfile.DEFAULT_1080P)
        assertEquals(1920, spec.resolution.width)
        assertEquals(1080, spec.resolution.height)
        assertEquals(30, spec.frameRate)
        assertEquals("h264", spec.videoCodec)
        assertEquals("aac", spec.audioCodec)
        assertEquals(8_000_000L, spec.videoBitrate)
        assertEquals(192_000L, spec.audioBitrate)
        assertEquals("mp4", spec.container)
    }

    @Test fun emptyMetadataMapByDefault() {
        // Pin: OutputSpec has a `metadata` map field
        // defaulted to empty. `outputSpecFromProfile`
        // doesn't populate it — keeps the spec
        // deterministic across re-invocations (drift to
        // populating with a clock / random key would break
        // the bit-exact fingerprint).
        val spec = outputSpecFromProfile(OutputProfile.DEFAULT_1080P)
        assertEquals(
            true,
            spec.metadata.isEmpty(),
            "outputSpecFromProfile MUST NOT populate metadata (drift would break deterministic fingerprint); got: ${spec.metadata}",
        )
    }
}
