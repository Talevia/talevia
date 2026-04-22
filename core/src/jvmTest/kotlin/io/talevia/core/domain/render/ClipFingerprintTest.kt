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
 * about (clip shape, neighbour fades, bound-source content, output profile).
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

    @Test fun identicalInputsProduceIdenticalFingerprint() {
        val a = clipMezzanineFingerprint(baseClip, fades = null, boundSourceDeepHashes = emptyMap(), output = baseOutput)
        val b = clipMezzanineFingerprint(baseClip, fades = null, boundSourceDeepHashes = emptyMap(), output = baseOutput)
        assertEquals(a, b)
    }

    @Test fun fingerprintIgnoresOutputPath() {
        val a = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput.copy(targetPath = "/tmp/a.mp4"))
        val b = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput.copy(targetPath = "/other/z.mp4"))
        // outputPath is deliberately out of the fingerprint — same mezzanine reused
        // across differently-named export targets in the same profile.
        assertEquals(a, b, "fingerprint must not depend on the final target path")
    }

    @Test fun clipShapeChangePerturbs() {
        val base = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput)
        val shifted = clipMezzanineFingerprint(
            baseClip.copy(sourceRange = TimeRange(1.seconds, 4.seconds)),
            null, emptyMap(), baseOutput,
        )
        assertNotEquals(base, shifted, "sourceRange change must perturb fingerprint")
    }

    @Test fun filterChangePerturbs() {
        val base = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput)
        val withFilter = clipMezzanineFingerprint(
            baseClip.copy(filters = listOf(Filter(name = "brightness", params = mapOf("value" to 0.2f)))),
            null, emptyMap(), baseOutput,
        )
        assertNotEquals(base, withFilter, "adding a filter must perturb fingerprint")
    }

    @Test fun transitionFadesPerturbFingerprint() {
        val noFades = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput)
        val headOnly = clipMezzanineFingerprint(
            baseClip, TransitionFades(headFade = 0.5.seconds), emptyMap(), baseOutput,
        )
        val bothFades = clipMezzanineFingerprint(
            baseClip, TransitionFades(headFade = 0.5.seconds, tailFade = 0.5.seconds), emptyMap(), baseOutput,
        )
        assertNotEquals(noFades, headOnly)
        assertNotEquals(headOnly, bothFades)
    }

    @Test fun boundSourceHashChangePerturbs() {
        val base = clipMezzanineFingerprint(
            baseClip, null,
            mapOf(SourceNodeId("mei") to "deadbeef"),
            baseOutput,
        )
        val drifted = clipMezzanineFingerprint(
            baseClip, null,
            mapOf(SourceNodeId("mei") to "cafebabe"),
            baseOutput,
        )
        assertNotEquals(base, drifted, "source deep-hash change must perturb fingerprint")
    }

    @Test fun boundSourceOrderDoesNotPerturb() {
        // Map iteration order may differ by JVM; the fingerprint sorts entries by
        // key so two logically-equal maps hash the same.
        val a = clipMezzanineFingerprint(
            baseClip, null,
            linkedMapOf(SourceNodeId("a") to "h1", SourceNodeId("b") to "h2"),
            baseOutput,
        )
        val b = clipMezzanineFingerprint(
            baseClip, null,
            linkedMapOf(SourceNodeId("b") to "h2", SourceNodeId("a") to "h1"),
            baseOutput,
        )
        assertEquals(a, b, "binding map order must not perturb fingerprint")
    }

    @Test fun outputProfileEssentialsPerturbFingerprint() {
        val base = clipMezzanineFingerprint(baseClip, null, emptyMap(), baseOutput)
        val resChange = clipMezzanineFingerprint(
            baseClip, null, emptyMap(),
            baseOutput.copy(resolution = Resolution(1280, 720)),
        )
        val fpsChange = clipMezzanineFingerprint(
            baseClip, null, emptyMap(),
            baseOutput.copy(frameRate = 60),
        )
        val codecChange = clipMezzanineFingerprint(
            baseClip, null, emptyMap(),
            baseOutput.copy(videoCodec = "h265"),
        )
        val bitrateChange = clipMezzanineFingerprint(
            baseClip, null, emptyMap(),
            baseOutput.copy(videoBitrate = 16_000_000),
        )
        assertNotEquals(base, resChange)
        assertNotEquals(base, fpsChange)
        assertNotEquals(base, codecChange)
        assertNotEquals(base, bitrateChange)
    }
}
