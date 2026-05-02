package io.talevia.core.domain.render

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.platform.OutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Unit verification that the per-clip mezzanine fingerprint is stable under
 * equivalent inputs and perturbs on every dimension a cached mezzanine cares
 * about (clip shape, neighbour fades, bound-source content, output profile,
 * engine id).
 *
 * These assertions pin the [ClipRenderCache] correctness invariants without
 * needing a real ffmpeg binary — they're the cheapest lane for regression
 * coverage, kept in core so the fingerprint function stays KMP-safe.
 */
class ClipFingerprintTest {

    private val baseClip = Clip.Video(
        id = ClipId("c1"),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId("asset-a"),
    )

    private val baseOutput = OutputSpec(
        targetPath = "/tmp/out.mp4",
        resolution = Resolution(1920, 1080),
        frameRate = 30,
        videoCodec = "h264",
        audioCodec = "aac",
    )

    /**
     * Thin helper so the existing assertions stay terse. Every call picks the
     * same default `engineId`; tests that specifically vary the engine-id axis
     * pass an explicit value.
     */
    private fun fp(
        clip: Clip.Video = baseClip,
        fades: TransitionFades? = null,
        boundSourceDeepHashes: Map<SourceNodeId, String> = emptyMap(),
        output: OutputSpec = baseOutput,
        engineId: String = "test-engine",
    ): String = clipMezzanineFingerprint(clip, fades, boundSourceDeepHashes, output, engineId)

    @Test fun identicalInputsProduceIdenticalFingerprint() {
        assertEquals(fp(), fp())
    }

    @Test fun fingerprintIgnoresOutputPath() {
        val a = fp(output = baseOutput.copy(targetPath = "/tmp/a.mp4"))
        val b = fp(output = baseOutput.copy(targetPath = "/other/z.mp4"))
        // outputPath is deliberately out of the fingerprint — same mezzanine reused
        // across differently-named export targets in the same profile.
        assertEquals(a, b, "fingerprint must not depend on the final target path")
    }

    @Test fun clipShapeChangePerturbs() {
        val base = fp()
        val shifted = fp(clip = baseClip.copy(sourceRange = TimeRange(1.seconds, 4.seconds)))
        assertNotEquals(base, shifted, "sourceRange change must perturb fingerprint")
    }

    @Test fun filterChangePerturbs() {
        val base = fp()
        val withFilter = fp(
            clip = baseClip.copy(filters = listOf(Filter(name = "brightness", params = mapOf("value" to 0.2f)))),
        )
        assertNotEquals(base, withFilter, "adding a filter must perturb fingerprint")
    }

    // ----- M4 #3 (专家精修反查) effect-param edit → mezzanine invalidation -----
    //
    // The M4 §5.2 expert-effects criterion #3 wants "edit Filter param →
    // clip stale → regenerate standard chain". Talevia routes effect
    // staleness through the per-clip mezzanine fingerprint (this file's
    // `clipMezzanineFingerprint`), NOT through `staleClipsFromLockfile`:
    //   - lockfile staleness gates AIGC asset cache hits (changing a
    //     filter doesn't invalidate the underlying generated asset);
    //   - mezzanine fingerprint gates per-clip render cache hits
    //     (changing a filter MUST trigger a re-render at the next export).
    // The cases below pin the expert-edit semantic that MILESTONES.md M4
    // #3 names — vignette intensity 0.4 → 0.7, brightness value drift,
    // and multi-filter list where only one slot edits — so a future
    // refactor that drops `filters` from the canonical clip JSON (the
    // accidental-deletion failure mode) breaks the test instead of
    // silently reusing stale mezzanines.

    @Test fun effectParamEditOnVignetteIntensityPerturbsFingerprint() {
        // Canonical example from MILESTONES.md M4 #3: "比如 vignette
        // intensity 从 0.4 → 0.7". Same filter name, same enclosing clip,
        // single param value drifts → mezzanine fingerprint must perturb.
        val v04 = fp(
            clip = baseClip.copy(
                filters = listOf(Filter(name = "vignette", params = mapOf("intensity" to 0.4f))),
            ),
        )
        val v07 = fp(
            clip = baseClip.copy(
                filters = listOf(Filter(name = "vignette", params = mapOf("intensity" to 0.7f))),
            ),
        )
        assertNotEquals(
            v04, v07,
            "M4 #3: editing vignette intensity 0.4 → 0.7 must perturb the mezzanine fingerprint " +
                "so the next export re-renders the clip",
        )
    }

    @Test fun effectParamEditOnBrightnessValuePerturbsFingerprint() {
        // Sibling case for the brightness knob — covers a different filter
        // name + different param key from vignette so a regression that
        // accidentally hashes only specific params (not the whole map)
        // fails here too.
        val b02 = fp(
            clip = baseClip.copy(
                filters = listOf(Filter(name = "brightness", params = mapOf("value" to 0.2f))),
            ),
        )
        val b05 = fp(
            clip = baseClip.copy(
                filters = listOf(Filter(name = "brightness", params = mapOf("value" to 0.5f))),
            ),
        )
        assertNotEquals(
            b02, b05,
            "M4 #3: editing brightness value 0.2 → 0.5 must perturb the mezzanine fingerprint",
        )
    }

    @Test fun effectParamEditOnOneFilterInChainPerturbsFingerprint() {
        // Multi-filter chain where only one slot's param edits. Pins that
        // chains aren't summarised lossily (e.g. by name set only) and
        // that ordering-stable encoding catches a single inner mutation.
        val before = fp(
            clip = baseClip.copy(
                filters = listOf(
                    Filter(name = "vignette", params = mapOf("intensity" to 0.4f)),
                    Filter(name = "brightness", params = mapOf("value" to 0.2f)),
                ),
            ),
        )
        val after = fp(
            clip = baseClip.copy(
                filters = listOf(
                    Filter(name = "vignette", params = mapOf("intensity" to 0.7f)),
                    Filter(name = "brightness", params = mapOf("value" to 0.2f)),
                ),
            ),
        )
        assertNotEquals(
            before, after,
            "M4 #3: editing one filter param in a multi-filter chain must perturb the fingerprint " +
                "even when other slots stay byte-identical",
        )
    }

    @Test fun transitionFadesPerturbFingerprint() {
        val noFades = fp()
        val headOnly = fp(fades = TransitionFades(headFade = 0.5.seconds))
        val bothFades = fp(fades = TransitionFades(headFade = 0.5.seconds, tailFade = 0.5.seconds))
        assertNotEquals(noFades, headOnly)
        assertNotEquals(headOnly, bothFades)
    }

    @Test fun boundSourceHashChangePerturbs() {
        val base = fp(boundSourceDeepHashes = mapOf(SourceNodeId("mei") to "deadbeef"))
        val drifted = fp(boundSourceDeepHashes = mapOf(SourceNodeId("mei") to "cafebabe"))
        assertNotEquals(base, drifted, "source deep-hash change must perturb fingerprint")
    }

    @Test fun boundSourceOrderDoesNotPerturb() {
        // Map iteration order may differ by JVM; the fingerprint sorts entries by
        // key so two logically-equal maps hash the same.
        val a = fp(
            boundSourceDeepHashes = linkedMapOf(SourceNodeId("a") to "h1", SourceNodeId("b") to "h2"),
        )
        val b = fp(
            boundSourceDeepHashes = linkedMapOf(SourceNodeId("b") to "h2", SourceNodeId("a") to "h1"),
        )
        assertEquals(a, b, "binding map order must not perturb fingerprint")
    }

    @Test fun outputProfileEssentialsPerturbFingerprint() {
        val base = fp()
        val resChange = fp(output = baseOutput.copy(resolution = Resolution(1280, 720)))
        val fpsChange = fp(output = baseOutput.copy(frameRate = 60))
        val codecChange = fp(output = baseOutput.copy(videoCodec = "h265"))
        val bitrateChange = fp(output = baseOutput.copy(videoBitrate = 16_000_000))
        assertNotEquals(base, resChange)
        assertNotEquals(base, fpsChange)
        assertNotEquals(base, codecChange)
        assertNotEquals(base, bitrateChange)
    }

    @Test fun engineIdPerturbsFingerprint() {
        // Phase-2 axis (2026-04-23): two engines produce byte-different mezzanines
        // at the same OutputSpec (x264 ≠ hardware-encoder ≠ AVAssetWriter), so the
        // fingerprint must differ across engines even with everything else equal.
        // Counter-intuitive edge test (c) in phase-3's list lives here as a unit
        // guard — the integration test (shared-filesystem cross-engine collision)
        // will land in phase 3.
        val ffmpeg = fp(engineId = "ffmpeg-jvm")
        val media3 = fp(engineId = "media3-android")
        val avf = fp(engineId = "avfoundation-ios")
        assertNotEquals(ffmpeg, media3, "ffmpeg-jvm vs media3-android must perturb fingerprint")
        assertNotEquals(media3, avf, "media3-android vs avfoundation-ios must perturb fingerprint")
        assertNotEquals(ffmpeg, avf, "ffmpeg-jvm vs avfoundation-ios must perturb fingerprint")
    }

    @Test fun engineIdEmptyStringStillProducesStableFingerprint() {
        // Sanity: `engineId = ""` is a valid (if degenerate) input — the helper
        // appends `|engine=` with an empty suffix, still deterministic + stable
        // across calls. Confirms the segment doesn't crash on empty string and
        // still produces a distinct value vs. a named engine (the empty engine
        // partitions away from every named one).
        val emptyA = fp(engineId = "")
        val emptyB = fp(engineId = "")
        val named = fp(engineId = "ffmpeg-jvm")
        assertEquals(emptyA, emptyB, "empty engineId must hash deterministically")
        assertNotEquals(emptyA, named, "empty engineId must hash differently from a named engine")
    }
}
