package io.talevia.core.tool.builtin.video.export

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [mimeTypeFor] / [buildPerClipCostAttribution] —
 * `core/tool/builtin/video/export/ExportToolOutputBuilder.kt`.
 * Pure helpers backing ExportTool's `Output` shape: MIME
 * classification + per-clip cost attribution. Cycle 177
 * audit: 59 LOC, 0 direct test refs (used by ExportTool's
 * own integration tests but the helper-level contracts —
 * extension table, case insensitivity, fallback,
 * cost-map-keyed-by-clipId, total reduction — were never
 * pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`mimeTypeFor` table covers documented extensions
 *    case-insensitively + falls back to
 *    `"application/octet-stream"` for unknowns.** Drift
 *    in the table would silently mislabel exports — the
 *    UI shows the wrong icon, browsers download with the
 *    wrong handler.
 *
 * 2. **`buildPerClipCostAttribution` keys the map by
 *    `clip.id.value` (NOT `clip.assetId.value`).** Two
 *    clips sharing the same asset get distinct map
 *    entries with the same cents (the sum is intentional
 *    per kdoc: "Clips sharing an asset report the same
 *    cents per clip; the sum is intentional because each
 *    clip references the same paid output").
 *
 * 3. **Text clips ARE in the map with null cents (NOT
 *    skipped, despite the kdoc claim).** This is a
 *    kdoc/impl mismatch — the kdoc says "Empty inputs
 *    and clips without `assetId` (text clips) are
 *    skipped" but the impl writes
 *    `perClip[clip.id.value] = null` unconditionally.
 *    Test pins ACTUAL behavior + flagged as P2 debt.
 */
class ExportToolOutputBuilderTest {

    // ── mimeTypeFor: documented table ────────────────────────

    @Test fun videoExtensionsMapToDocumentedMimeTypes() {
        // Pin: each extension in the documented table maps
        // to the right MIME type. Drift would silently
        // mislabel exports.
        assertEquals("video/mp4", mimeTypeFor("/tmp/a.mp4"))
        assertEquals("video/mp4", mimeTypeFor("/tmp/a.m4v"))
        assertEquals("video/quicktime", mimeTypeFor("/tmp/a.mov"))
        assertEquals("video/webm", mimeTypeFor("/tmp/a.webm"))
        assertEquals("video/x-matroska", mimeTypeFor("/tmp/a.mkv"))
        assertEquals("video/x-msvideo", mimeTypeFor("/tmp/a.avi"))
        assertEquals("image/gif", mimeTypeFor("/tmp/a.gif"))
    }

    @Test fun audioExtensionsMapToDocumentedMimeTypes() {
        assertEquals("audio/mpeg", mimeTypeFor("/tmp/a.mp3"))
        assertEquals("audio/mp4", mimeTypeFor("/tmp/a.m4a"))
        assertEquals("audio/wav", mimeTypeFor("/tmp/a.wav"))
    }

    @Test fun extensionMatchingIsCaseInsensitive() {
        // Marquee case-insensitive pin: `.MP4` and `.mp4`
        // map to the same MIME. Drift to "case-sensitive"
        // would mislabel uppercase-extension paths
        // (Windows drag-and-drop frequently produces
        // .MP4 / .MOV).
        assertEquals("video/mp4", mimeTypeFor("/tmp/A.MP4"))
        assertEquals("video/mp4", mimeTypeFor("/tmp/a.Mp4"))
        assertEquals("video/quicktime", mimeTypeFor("/tmp/a.MOV"))
        assertEquals("audio/wav", mimeTypeFor("/tmp/a.WAV"))
    }

    @Test fun unknownExtensionFallsBackToOctetStream() {
        // Marquee fallback pin: anything not in the
        // documented table → application/octet-stream.
        // Drift to "throw" would crash export on
        // user-chosen exotic extensions.
        assertEquals("application/octet-stream", mimeTypeFor("/tmp/a.foo"))
        assertEquals("application/octet-stream", mimeTypeFor("/tmp/a.unknown"))
        assertEquals("application/octet-stream", mimeTypeFor("/tmp/a.zip"))
    }

    @Test fun pathWithoutExtensionFallsBackToOctetStream() {
        // Pin: `substringAfterLast('.', "")` returns ""
        // when there's no '.' in the path → fallback.
        assertEquals("application/octet-stream", mimeTypeFor("/tmp/noext"))
        assertEquals("application/octet-stream", mimeTypeFor("noext"))
        assertEquals("application/octet-stream", mimeTypeFor(""))
    }

    @Test fun pathWithMultipleDotsUsesLastExtension() {
        // Pin: substringAfterLast picks the LAST dot —
        // a path like `/tmp/my.video.mp4` resolves to "mp4".
        assertEquals("video/mp4", mimeTypeFor("/tmp/my.video.mp4"))
        assertEquals("video/quicktime", mimeTypeFor("/tmp/a.b.c.mov"))
    }

    @Test fun pathThatIsJustAnExtensionFallsBackBecauseNoLeadingDot() {
        // Subtle pin: `"mp4".substringAfterLast('.', "")`
        // returns "" because there's no '.' in the string.
        // Drift to "treat string as extension when no
        // path" would falsely classify bare strings.
        assertEquals("application/octet-stream", mimeTypeFor("mp4"))
        assertEquals("application/octet-stream", mimeTypeFor("mov"))
    }

    @Test fun dotPrefixedExtensionStringResolvesToMime() {
        // Pin: ".mp4" → "video/mp4" (substringAfterLast
        // splits on the leading dot, leaving "mp4").
        assertEquals("video/mp4", mimeTypeFor(".mp4"))
    }

    // ── buildPerClipCostAttribution helpers ──────────────────

    private fun anyRange() = TimeRange(start = 0.seconds, duration = 1.seconds)

    private fun videoClip(id: String, assetId: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId(assetId),
    )

    private fun audioClip(id: String, assetId: String): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId(assetId),
    )

    private fun textClip(id: String): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = anyRange(),
        text = "hi",
    )

    private fun lockfileEntry(assetId: String, cents: Long): LockfileEntry = LockfileEntry(
        toolId = "generate_image",
        inputHash = "hash-$assetId",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "test",
            modelId = "test",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        costCents = cents,
    )

    private fun project(
        clips: List<Clip>,
        lockfileEntries: List<LockfileEntry> = emptyList(),
    ): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v"), clips = clips),
            ),
        ),
        lockfile = EagerLockfile(entries = lockfileEntries),
    )

    // ── buildPerClipCostAttribution: empty / single clip ─────

    @Test fun emptyTimelineProducesEmptyMapAndZeroTotal() {
        val (perClip, total) = buildPerClipCostAttribution(
            Project(id = ProjectId("p"), timeline = Timeline()),
        )
        assertEquals(emptyMap(), perClip)
        assertEquals(0L, total)
    }

    @Test fun singleVideoClipWithLockfileEntryAttributesCost() {
        // Pin: a video clip whose assetId is in the lockfile
        // gets the cents attributed; total equals that
        // cents.
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 50)),
        )
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(mapOf("c1" to 50L), perClip)
        assertEquals(50L, total)
    }

    @Test fun videoClipWithoutLockfileEntryGetsNullCents() {
        // Pin: clip's asset isn't AIGC-produced → no
        // lockfile entry → cents = null. The map STILL
        // contains the clipId (so the agent sees
        // "unpriced" vs missing). Drift to "skip
        // unpriced" would lose the documented
        // "this clip is unpriced" signal.
        val proj = project(
            clips = listOf(videoClip("c1", "asset-1")),
            lockfileEntries = emptyList(),
        )
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(1, perClip.size, "clip is in the map")
        assertNull(perClip["c1"], "cents = null for unpriced clip")
        assertEquals(0L, total, "total excludes null cents")
    }

    @Test fun audioClipAttributionWorksLikeVideo() {
        val proj = project(
            clips = listOf(audioClip("c-audio", "asset-1")),
            lockfileEntries = listOf(lockfileEntry("asset-1", 30)),
        )
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(mapOf("c-audio" to 30L), perClip)
        assertEquals(30L, total)
    }

    // ── buildPerClipCostAttribution: text clip handling ──────

    @Test fun textClipIsInMapWithNullCentsKdocMismatch() {
        // KDOC/IMPL MISMATCH PIN: the kdoc says "Empty
        // inputs and clips without `assetId` (text clips)
        // are skipped — they'd never have a lockfile entry
        // anyway." But the impl unconditionally writes
        // `perClip[clip.id.value] = cents` (which is null
        // for text clips because their assetId match
        // returns null). Test pins ACTUAL behavior — text
        // clips ARE in the map with null cents.
        //
        // Flagged as P2 debt this cycle so the user can
        // decide whether to fix the kdoc (drop "skipped"
        // claim, accept that text clips show as
        // "unpriced") or fix the impl (add `continue`
        // when assetId is null, exclude text clips from
        // map). Same pattern as cycle 172's DeepContentHash
        // finding.
        val proj = project(clips = listOf(textClip("c-text")))
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(
            1,
            perClip.size,
            "text clip IS in the map (NOT skipped per kdoc) — see backlog `debt-export-output-builder-text-clip-skipped-claim-vs-impl`",
        )
        assertNull(perClip["c-text"], "text clip cents = null")
        assertEquals(0L, total)
    }

    @Test fun mixedClipsProduceCorrectAggregation() {
        // Pin: walking multiple clips of mixed types
        // produces a map with one entry per clip plus
        // total = sum of non-null cents. Text clips
        // contribute null entries (per ACTUAL behavior).
        val proj = project(
            clips = listOf(
                videoClip("v1", "asset-vid"),
                audioClip("a1", "asset-aud"),
                textClip("t1"),
                videoClip("v2", "asset-unpriced"),
            ),
            lockfileEntries = listOf(
                lockfileEntry("asset-vid", 100),
                lockfileEntry("asset-aud", 50),
            ),
        )
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(4, perClip.size, "all 4 clips in map")
        assertEquals(100L, perClip["v1"])
        assertEquals(50L, perClip["a1"])
        assertNull(perClip["t1"])
        assertNull(perClip["v2"])
        assertEquals(150L, total, "total = 100 + 50, null entries excluded")
    }

    @Test fun clipsSharingSameAssetReportSameCentsAndSumIntentionally() {
        // Marquee shared-asset attribution pin (per kdoc):
        // "Clips sharing an asset report the same cents per
        // clip; the sum is intentional because each clip
        // references the same paid output." Drift to
        // "deduplicate by assetId" would under-attribute
        // when the same asset appears N times.
        val proj = project(
            clips = listOf(
                videoClip("c1", "asset-shared"),
                videoClip("c2", "asset-shared"),
                videoClip("c3", "asset-shared"),
            ),
            lockfileEntries = listOf(lockfileEntry("asset-shared", 100)),
        )
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(3, perClip.size)
        assertEquals(100L, perClip["c1"])
        assertEquals(100L, perClip["c2"])
        assertEquals(100L, perClip["c3"])
        assertEquals(
            300L,
            total,
            "shared asset SUMMED per-clip — drift to dedup would yield 100 instead",
        )
    }

    // ── multi-track walks ────────────────────────────────────

    @Test fun multipleTracksAreAllWalked() {
        // Pin: outer `for (track in project.timeline.tracks)`
        // covers every track. Drift to "first track only"
        // would silently miss audio-track clips.
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(videoClip("v1", "asset-v")),
                    ),
                    Track.Audio(
                        id = TrackId("a"),
                        clips = listOf(audioClip("a1", "asset-a")),
                    ),
                ),
            ),
            lockfile = EagerLockfile(
                entries = listOf(
                    lockfileEntry("asset-v", 60),
                    lockfileEntry("asset-a", 40),
                ),
            ),
        )
        val (perClip, total) = buildPerClipCostAttribution(proj)
        assertEquals(2, perClip.size, "both tracks contribute")
        assertEquals(60L, perClip["v1"])
        assertEquals(40L, perClip["a1"])
        assertEquals(100L, total)
    }

    // ── return type contract ────────────────────────────────

    @Test fun returnsPairOfMapAndLong() {
        // Pin: signature is `Pair<Map<String, Long?>, Long>`
        // — the map's value type is nullable (so a clip
        // can be in the map with no price), the total is
        // non-null Long. Drift to `Pair<Map<…, Long>, Long?>`
        // would either remove the unpriced-clip
        // discriminator or make total nullable for no
        // reason.
        val proj = project(clips = listOf(videoClip("c1", "asset-1")))
        // Compile-time-typed locals pin the signature
        // shape: a drift would fail to compile here.
        val result: Pair<Map<String, Long?>, Long> = buildPerClipCostAttribution(proj)
        val mapAsMap: Map<String, Long?> = result.first
        val totalAsLong: Long = result.second
        assertTrue(mapAsMap.containsKey("c1"))
        assertEquals(0L, totalAsLong)
    }
}
