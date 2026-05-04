package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.RenderCache
import io.talevia.core.domain.source.Source
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for [Project] field defaults + [OutputProfile.DEFAULT_1080P]
 * pin. Cycle 303 audit: no direct `ProjectTest.kt` /
 * `ProjectFieldDefaultsTest.kt` (verified via cycle 289-banked
 * duplicate-check idiom). The 9-field shape is exercised across
 * many tests but defaults + DEFAULT_1080P literal values have no
 * dedicated pin.
 *
 * Same audit-pattern fallback as cycles 207-302. Sister of cycle
 * 302's SessionFieldDefaultsTest.
 *
 * Why this matters: Project is the root data class persisted in
 * every `talevia.json` bundle. OutputProfile.DEFAULT_1080P is the
 * render spec every new project starts with — drift silently
 * changes file sizes, codecs, or aspect ratios fleet-wide.
 *
 * Drift surface protected:
 *   - Field default change (e.g. `parentProjectId = ProjectId("")`
 *     instead of null) silently misroutes lineage.
 *   - DEFAULT_1080P drift (1920x1080 → 1280x720, h264 → h265, mp4
 *     → mkv) silently breaks legacy bundle compat AND export
 *     cache identity.
 *   - Lockfile / RenderCache / ClipRenderCache `EMPTY` companion
 *     drift to non-empty default would silently inflate every
 *     fresh project's serialised state.
 *   - Bitrate constants (8 Mbps video / 192 kbps audio) silently
 *     change file sizes.
 */
class ProjectFieldDefaultsTest {

    private val pid = ProjectId("p1")
    private val json: Json = JsonConfig.default

    private fun minimalProject(): Project = Project(
        id = pid,
        timeline = Timeline(),
        // Everything else defaulted.
    )

    // ── Project field defaults ──────────────────────────────

    @Test fun assetsDefaultsToEmptyList() {
        assertEquals(
            emptyList(),
            minimalProject().assets,
            "Project.assets MUST default to empty (every fresh project starts with no media)",
        )
    }

    @Test fun sourceDefaultsToSourceEmpty() {
        // Pin: per source, default is Source.EMPTY (revision=0
        // + no nodes). Drift to a fresh `Source()` would still
        // be empty but lose the singleton identity used in
        // some compat paths.
        assertEquals(
            Source.EMPTY,
            minimalProject().source,
            "Project.source MUST default to Source.EMPTY",
        )
    }

    @Test fun outputProfileDefaultsToDefault1080p() {
        // Marquee default-render-spec pin: every fresh project
        // starts at DEFAULT_1080P. Drift to a different default
        // silently changes file sizes / codecs across the fleet.
        assertEquals(
            OutputProfile.DEFAULT_1080P,
            minimalProject().outputProfile,
            "Project.outputProfile MUST default to OutputProfile.DEFAULT_1080P",
        )
    }

    @Test fun parentProjectIdDefaultsToNull() {
        // Marquee lineage pin: per source line 25, null for
        // roots and pre-lineage blobs. Drift to fabricate a
        // default would silently mark every root project as a
        // fork.
        assertNull(
            minimalProject().parentProjectId,
            "Project.parentProjectId MUST default to null (root, NOT fork)",
        )
    }

    @Test fun lockfileDefaultsToLockfileEmpty() {
        // Pin: per source line 28, empty so pre-lockfile
        // projects decode without migration. Drift to non-
        // empty default would inflate every fresh project's
        // serialised state.
        assertSame(
            Lockfile.EMPTY,
            minimalProject().lockfile,
            "Project.lockfile MUST default to Lockfile.EMPTY (singleton; drift would inflate state)",
        )
    }

    @Test fun renderCacheDefaultsToRenderCacheEmpty() {
        assertSame(
            RenderCache.EMPTY,
            minimalProject().renderCache,
            "Project.renderCache MUST default to RenderCache.EMPTY",
        )
    }

    @Test fun clipRenderCacheDefaultsToClipRenderCacheEmpty() {
        assertSame(
            ClipRenderCache.EMPTY,
            minimalProject().clipRenderCache,
            "Project.clipRenderCache MUST default to ClipRenderCache.EMPTY",
        )
    }

    @Test fun snapshotsDefaultsToEmptyList() {
        // Pin: per source line ~62, empty list of named
        // snapshots. Drift to non-empty default would
        // silently surface phantom snapshots.
        assertEquals(emptyList(), minimalProject().snapshots)
    }

    // ── OutputProfile.DEFAULT_1080P literal pin ────────────

    @Test fun default1080pHas1920x1080Resolution() {
        // Marquee resolution pin: drift to 1280x720 / 3840x2160
        // silently changes the canvas every new project starts
        // with.
        assertEquals(
            Resolution(width = 1920, height = 1080),
            OutputProfile.DEFAULT_1080P.resolution,
            "DEFAULT_1080P.resolution MUST be 1920x1080",
        )
    }

    @Test fun default1080pHas30FpsFrameRate() {
        // Marquee fps pin: 30 fps integer (NOT NTSC 30000/1001).
        // Drift to 24 / 60 / NTSC silently changes every new
        // project's render cadence.
        assertEquals(
            FrameRate.FPS_30,
            OutputProfile.DEFAULT_1080P.frameRate,
            "DEFAULT_1080P.frameRate MUST be FPS_30",
        )
    }

    // ── OutputProfile data-class field defaults ────────────

    private fun minimalOutputProfile(): OutputProfile = OutputProfile(
        resolution = Resolution(1920, 1080),
        frameRate = FrameRate.FPS_30,
        // Other 5 fields defaulted.
    )

    @Test fun outputProfileVideoCodecDefaultsToH264() {
        // Marquee codec pin: h264 is the universally-supported
        // default. Drift to h265 / vp9 silently breaks
        // playback on older hardware.
        assertEquals("h264", minimalOutputProfile().videoCodec)
    }

    @Test fun outputProfileAudioCodecDefaultsToAac() {
        assertEquals("aac", minimalOutputProfile().audioCodec)
    }

    @Test fun outputProfileVideoBitrateDefaultsTo8Mbps() {
        // Marquee bitrate pin: 8_000_000 = 8 Mbps for 1080p.
        // Drift silently changes file sizes (e.g. 4 Mbps would
        // halve them; 16 Mbps would double them).
        assertEquals(
            8_000_000L,
            minimalOutputProfile().videoBitrate,
            "videoBitrate MUST default to 8 Mbps (8_000_000)",
        )
    }

    @Test fun outputProfileAudioBitrateDefaultsTo192Kbps() {
        // Marquee audio bitrate pin: 192 kbps for AAC stereo.
        assertEquals(
            192_000L,
            minimalOutputProfile().audioBitrate,
            "audioBitrate MUST default to 192 kbps (192_000)",
        )
    }

    @Test fun outputProfileContainerDefaultsToMp4() {
        // Marquee container pin: mp4 is the universally-
        // imported container. Drift to mkv / webm silently
        // breaks downstream NLE imports.
        assertEquals("mp4", minimalOutputProfile().container)
    }

    // ── DEFAULT_1080P field-by-field invariants ────────────

    @Test fun default1080pAllSevenFieldsMatchDocumentedValues() {
        // Sister cross-check pin: DEFAULT_1080P is constructed
        // ONLY with resolution + frameRate; the other 5 fields
        // come from data class defaults. So DEFAULT_1080P MUST
        // be byte-identical to a hand-built minimal profile.
        assertEquals(
            OutputProfile(
                resolution = Resolution(1920, 1080),
                frameRate = FrameRate.FPS_30,
                videoCodec = "h264",
                audioCodec = "aac",
                videoBitrate = 8_000_000L,
                audioBitrate = 192_000L,
                container = "mp4",
            ),
            OutputProfile.DEFAULT_1080P,
            "DEFAULT_1080P MUST equal a hand-built profile with all defaulted values",
        )
    }

    // ── Serialization round-trip ───────────────────────────

    @Test fun projectRoundTripPreservesFields() {
        val original = Project(
            id = pid,
            timeline = Timeline(),
            parentProjectId = ProjectId("parent"),
        )
        val encoded = json.encodeToString(Project.serializer(), original)
        val decoded = json.decodeFromString(Project.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun outputProfileRoundTripPreservesFields() {
        val original = OutputProfile(
            resolution = Resolution(3840, 2160),
            frameRate = FrameRate.FPS_60,
            videoCodec = "h265",
            audioCodec = "opus",
            videoBitrate = 50_000_000L,
            audioBitrate = 320_000L,
            container = "mkv",
        )
        val encoded = json.encodeToString(OutputProfile.serializer(), original)
        val decoded = json.decodeFromString(OutputProfile.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun minimalProjectJsonDecodesWithEveryDefaultHonored() {
        // Marquee back-compat pin: a JSON envelope with ONLY
        // the 2 required fields (id + timeline) decodes
        // cleanly using every other default.
        val minimalJson = """{
            "id":"p1",
            "timeline":{}
        }""".trimIndent()
        val decoded = json.decodeFromString(Project.serializer(), minimalJson)
        assertEquals(emptyList(), decoded.assets)
        assertEquals(Source.EMPTY, decoded.source)
        assertEquals(OutputProfile.DEFAULT_1080P, decoded.outputProfile)
        assertNull(decoded.parentProjectId)
        assertSame(Lockfile.EMPTY, decoded.lockfile)
        assertSame(RenderCache.EMPTY, decoded.renderCache)
        assertSame(ClipRenderCache.EMPTY, decoded.clipRenderCache)
        assertEquals(emptyList(), decoded.snapshots)
    }

    @Test fun outputProfileJsonRequiresOnlyResolutionAndFrameRate() {
        // Pin: minimal JSON with just the 2 required fields
        // honors every other default.
        val minimalJson = """{
            "resolution":{"width":1920,"height":1080},
            "frameRate":{"numerator":30,"denominator":1}
        }""".trimIndent()
        val decoded = json.decodeFromString(OutputProfile.serializer(), minimalJson)
        assertEquals("h264", decoded.videoCodec)
        assertEquals("aac", decoded.audioCodec)
        assertEquals(8_000_000L, decoded.videoBitrate)
        assertEquals(192_000L, decoded.audioBitrate)
        assertEquals("mp4", decoded.container)
    }

    // ── Equality + hashCode invariants ─────────────────────

    @Test fun twoMinimalProjectsAreEqual() {
        val a = minimalProject()
        val b = minimalProject()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun differentParentProjectIdProducesDistinctProjects() {
        val a = minimalProject()
        val b = minimalProject().copy(parentProjectId = ProjectId("parent"))
        assertNotEquals(a, b)
    }

    @Test fun default1080pIsNotEqualTo1080pAt60Fps() {
        // Sanity sister pin: DEFAULT_1080P is 1080p@30, NOT
        // @60. Drift to align with FPS_60 silently shifts
        // every fresh project's render spec.
        val at60 = OutputProfile(
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_60,
        )
        assertNotEquals(
            OutputProfile.DEFAULT_1080P,
            at60,
            "DEFAULT_1080P (30fps) MUST be distinct from 1080p@60",
        )
    }

    // ── Sanity: required fields cannot be auto-defaulted ───

    @Test fun projectRequiresIdAndTimeline() {
        // Sanity: id + timeline are the 2 required params.
        // The minimal constructor uses both. Pin documents the
        // required-field count via constructor surface.
        val proj = minimalProject()
        assertTrue(proj.id.value.isNotBlank())
        // Timeline() is a no-arg default but is present.
        assertEquals(0, proj.timeline.tracks.size)
    }
}
