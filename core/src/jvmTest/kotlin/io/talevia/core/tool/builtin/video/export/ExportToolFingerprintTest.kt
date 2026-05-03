package io.talevia.core.tool.builtin.video.export

import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.platform.OutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [fingerprintOf] / [provenanceOf] —
 * `core/tool/builtin/video/export/ExportToolFingerprint.kt`.
 * The deterministic-helpers shared by [ExportTool] +
 * [ExportDryRunTool]; the same `(timeline, output spec)`
 * fingerprint keys both tools' RenderCache so they can't
 * drift. Cycle 176 audit: 64 LOC, 0 direct test refs (the
 * helpers feed every export determinism test transitively
 * but their own contracts — fingerprint format, output-
 * spec field-by-field sensitivity, provenance-manifest
 * shape — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Fingerprint is sensitive to every load-bearing
 *    OutputSpec field independently** (targetPath,
 *    resolution.width, resolution.height, frameRate,
 *    videoCodec, audioCodec). Drift to "skip a field"
 *    would let a render-cache hit return the wrong
 *    artifact when the user changed e.g. only the
 *    audio codec — semantically a different export but
 *    fingerprint-identical.
 *
 * 2. **Fingerprint format: 16-char lowercase hex (FNV-1a
 *    64-bit).** The format is short enough to embed in
 *    cache filenames + long enough to be collision-safe
 *    at project scale. Drift to base64 / longer hash
 *    (SHA-256) would silently break filename layout
 *    across the cache directory.
 *
 * 3. **`provenanceOf` produces deterministic
 *    `ProvenanceManifest` from the project alone — no
 *    timestamps, no machine ids.** The kdoc explicitly
 *    calls out: "ExportDeterminismTest relies on this."
 *    Two calls on the same Project produce equal
 *    manifests; two Projects with same timeline + lockfile
 *    but different ids produce manifests with different
 *    projectIds but equal hashes.
 */
class ExportToolFingerprintTest {

    private val baseSpec = OutputSpec(
        targetPath = "/tmp/out.mp4",
        resolution = Resolution(width = 1920, height = 1080),
    )

    private val baseTimeline = Timeline()

    // ── Fingerprint determinism ───────────────────────────────

    @Test fun fingerprintIsDeterministicAcrossCalls() {
        // Pin: pure function of `(timeline, spec)`.
        val a = fingerprintOf(baseTimeline, baseSpec)
        val b = fingerprintOf(baseTimeline, baseSpec)
        assertEquals(a, b, "fingerprint deterministic across calls")
    }

    @Test fun fingerprintFormatIs16CharLowercaseHex() {
        // Marquee format pin: FNV-1a-64 hex, 16 chars,
        // lowercase. Drift would silently break cache
        // filename layout.
        val hash = fingerprintOf(baseTimeline, baseSpec)
        assertEquals(16, hash.length, "FNV-1a 64-bit hex length")
        assertTrue(
            hash.all { it in "0123456789abcdef" },
            "lowercase hex only; got: $hash",
        )
    }

    // ── Fingerprint per-field sensitivity ────────────────────

    @Test fun fingerprintIsSensitiveToTargetPath() {
        // Pin: changing targetPath → different fingerprint.
        // Each user-chosen output path has its own cache
        // entry. Drift to "ignore path" would conflate
        // exports to /tmp/a.mp4 vs /tmp/b.mp4.
        val specA = baseSpec.copy(targetPath = "/tmp/a.mp4")
        val specB = baseSpec.copy(targetPath = "/tmp/b.mp4")
        assertNotEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "targetPath change flips fingerprint",
        )
    }

    @Test fun fingerprintIsSensitiveToResolutionWidth() {
        val specA = baseSpec.copy(resolution = Resolution(1920, 1080))
        val specB = baseSpec.copy(resolution = Resolution(1280, 1080))
        assertNotEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "resolution width change flips fingerprint",
        )
    }

    @Test fun fingerprintIsSensitiveToResolutionHeight() {
        val specA = baseSpec.copy(resolution = Resolution(1920, 1080))
        val specB = baseSpec.copy(resolution = Resolution(1920, 720))
        assertNotEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "resolution height change flips fingerprint",
        )
    }

    @Test fun fingerprintIsSensitiveToFrameRate() {
        val specA = baseSpec.copy(frameRate = 30)
        val specB = baseSpec.copy(frameRate = 60)
        assertNotEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "frameRate change flips fingerprint",
        )
    }

    @Test fun fingerprintIsSensitiveToVideoCodec() {
        val specA = baseSpec.copy(videoCodec = "h264")
        val specB = baseSpec.copy(videoCodec = "h265")
        assertNotEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "videoCodec change flips fingerprint",
        )
    }

    @Test fun fingerprintIsSensitiveToAudioCodec() {
        // Marquee per-field-sensitivity pin: every
        // load-bearing field flips the hash. Drift to
        // "skip audioCodec" would let a cache hit return
        // the wrong artifact when only the audio codec
        // changed — semantically a different export but
        // fingerprint-identical.
        val specA = baseSpec.copy(audioCodec = "aac")
        val specB = baseSpec.copy(audioCodec = "mp3")
        assertNotEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "audioCodec change flips fingerprint",
        )
    }

    @Test fun fingerprintIsSensitiveToTimelineEdit() {
        // Pin: the timeline part of the fingerprint
        // catches any structural edit. Two timelines
        // differing only in resolution → different
        // fingerprints (Timeline.resolution is in the
        // canonical JSON).
        val tlA = Timeline(resolution = Resolution(1920, 1080))
        val tlB = Timeline(resolution = Resolution(3840, 2160))
        assertNotEquals(
            fingerprintOf(tlA, baseSpec),
            fingerprintOf(tlB, baseSpec),
        )
    }

    // ── Fingerprint NON-sensitivity to non-fingerprinted fields ──

    @Test fun fingerprintIgnoresOutputBitrate() {
        // Pin: bitrate fields are NOT in the fingerprint
        // hash — encoder tuning shouldn't invalidate the
        // cache (the user can re-encode at a different
        // bitrate from the same timeline + codec without
        // re-rendering the timeline). Drift to "include
        // bitrate" would over-invalidate.
        val specA = baseSpec.copy(videoBitrate = 8_000_000L)
        val specB = baseSpec.copy(videoBitrate = 12_000_000L)
        assertEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "videoBitrate is NOT in the fingerprint",
        )
    }

    @Test fun fingerprintIgnoresContainerFormat() {
        // Pin: container format isn't fingerprinted (hash
        // covers codecs which is what matters for
        // re-render). Drift to "include container" would
        // over-invalidate when only the wrapper changes.
        val specA = baseSpec.copy(container = "mp4")
        val specB = baseSpec.copy(container = "mov")
        assertEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "container format is NOT in the fingerprint",
        )
    }

    @Test fun fingerprintIgnoresMetadataMap() {
        // Pin: container metadata (e.g. provenance comment)
        // is NOT in the fingerprint — that's where
        // provenanceOf goes downstream; folding it in
        // here would create a circular dependency
        // (manifest depends on hash, hash depends on
        // manifest).
        val specA = baseSpec.copy(metadata = emptyMap())
        val specB = baseSpec.copy(metadata = mapOf("comment" to "talevia/v1:..."))
        assertEquals(
            fingerprintOf(baseTimeline, specA),
            fingerprintOf(baseTimeline, specB),
            "metadata map is NOT in the fingerprint",
        )
    }

    // ── provenanceOf: shape + determinism ────────────────────

    @Test fun provenanceManifestEchoesProjectId() {
        val project = Project(
            id = ProjectId("proj-test"),
            timeline = baseTimeline,
        )
        val manifest = provenanceOf(project)
        assertEquals(
            "proj-test",
            manifest.projectId,
            "manifest projectId echoes Project.id.value",
        )
    }

    @Test fun provenanceManifestIsDeterministic() {
        // Marquee determinism pin (the kdoc explicitly
        // calls out ExportDeterminismTest reliance).
        val project = Project(
            id = ProjectId("proj-test"),
            timeline = baseTimeline,
        )
        val a = provenanceOf(project)
        val b = provenanceOf(project)
        assertEquals(a, b, "manifest deterministic across calls")
        assertEquals(a.timelineHash, b.timelineHash)
        assertEquals(a.lockfileHash, b.lockfileHash)
    }

    @Test fun provenanceTimelineHashIs16CharHex() {
        val project = Project(id = ProjectId("p"), timeline = baseTimeline)
        val manifest = provenanceOf(project)
        assertEquals(16, manifest.timelineHash.length)
        assertTrue(manifest.timelineHash.all { it in "0123456789abcdef" })
    }

    @Test fun provenanceLockfileHashIs16CharHex() {
        val project = Project(id = ProjectId("p"), timeline = baseTimeline)
        val manifest = provenanceOf(project)
        assertEquals(16, manifest.lockfileHash.length)
        assertTrue(manifest.lockfileHash.all { it in "0123456789abcdef" })
    }

    @Test fun provenanceTimelineHashFlipsOnTimelineEdit() {
        // Pin: the kdoc says "a Timeline edit flips it,
        // nothing else" — a Timeline-only edit changes the
        // timelineHash but NOT the lockfileHash. Drift to
        // "fold lockfile into timelineHash" would couple
        // them and lose the documented "which side
        // changed?" diagnostic.
        val projectA = Project(
            id = ProjectId("p"),
            timeline = Timeline(resolution = Resolution(1920, 1080)),
        )
        val projectB = projectA.copy(
            timeline = Timeline(resolution = Resolution(3840, 2160)),
        )
        val a = provenanceOf(projectA)
        val b = provenanceOf(projectB)
        assertNotEquals(a.timelineHash, b.timelineHash, "timeline edit flips timelineHash")
        assertEquals(
            a.lockfileHash,
            b.lockfileHash,
            "Timeline edit alone does NOT flip lockfileHash",
        )
    }

    @Test fun provenanceProjectIdDoesNotInfluenceHashes() {
        // Pin: the projectId is recorded in the manifest's
        // projectId field but NOT folded into the hashes.
        // Two projects with identical timeline + lockfile
        // but different ids produce equal hashes (just
        // different projectId fields). Drift to "fold
        // projectId" would invalidate the cross-project
        // RenderCache.
        val a = Project(id = ProjectId("p-1"), timeline = baseTimeline)
        val b = Project(id = ProjectId("p-2"), timeline = baseTimeline)
        val mA = provenanceOf(a)
        val mB = provenanceOf(b)
        assertNotEquals(mA.projectId, mB.projectId, "projectId differs")
        assertEquals(
            mA.timelineHash,
            mB.timelineHash,
            "timeline hashes equal because projectId is NOT folded",
        )
        assertEquals(
            mA.lockfileHash,
            mB.lockfileHash,
            "lockfile hashes equal because projectId is NOT folded",
        )
    }

    @Test fun provenanceSchemaVersionDefaultsToCurrent() {
        // Pin: the manifest carries the documented
        // current schema version. Per ProvenanceManifest
        // kdoc, drift would require a non-additive shape
        // bump.
        val project = Project(id = ProjectId("p"), timeline = baseTimeline)
        val manifest = provenanceOf(project)
        assertEquals(1, manifest.schemaVersion, "current schema is 1")
    }
}
