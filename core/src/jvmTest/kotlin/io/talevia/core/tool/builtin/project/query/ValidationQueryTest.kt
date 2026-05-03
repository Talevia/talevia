package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.ValidationIssue
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runValidationQuery] —
 * `project_query(select=validation)`. Thin wrapper over
 * `computeProjectValidationIssues` (cycle 96 covers the
 * underlying axes). Cycle 133 audit: 67 LOC, 0 transitive test
 * refs; the wrapper-specific framing (passed flag, summary
 * format, title shape, severity tally) was previously
 * unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`passed = errorCount == 0`** — warnings do NOT block.
 *    Per kdoc: "passed = (total == 0)" is what the standalone
 *    tool used to expose; this wrapper surfaces the same
 *    semantic via title shape ("ok" vs "N error(s)"). A
 *    regression flipping passed to depend on TOTAL issues
 *    (errors + warnings) would silently fail-loud on
 *    duration-mismatch warnings the user can safely ignore.
 *
 * 2. **Title differentiates pass vs fail.** Per code:
 *    "project_query validation: ok" vs "project_query
 *    validation: N error(s)". UI consumers branch on the title
 *    text. A regression collapsing both into one shape would
 *    erase the at-a-glance pass/fail signal.
 *
 * 3. **outputForLlm bullet format with severity-bracketed
 *    locator.** Per code: "- [<severity>] <code> (track=X
 *    clip=Y): <message>". The locator is omitted entirely when
 *    both trackId and clipId are null (source-DAG-level
 *    issues). A regression always emitting "()" or always
 *    omitting locator would either look broken or silently
 *    lose context.
 */
class ValidationQueryTest {

    private val timeRange = TimeRange(start = Duration.ZERO, duration = 5.seconds)

    private fun videoClip(
        id: String,
        assetId: String,
        binding: Set<SourceNodeId> = emptySet(),
        duration: Duration = 5.seconds,
    ) = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start = Duration.ZERO, duration = duration),
        sourceRange = TimeRange(start = Duration.ZERO, duration = duration),
        assetId = AssetId(assetId),
        sourceBinding = binding,
    )

    private fun audioClip(
        id: String,
        assetId: String,
        volume: Float = 1.0f,
    ) = Clip.Audio(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        volume = volume,
    )

    private fun assetWithId(id: String) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(
            duration = 10.seconds,
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
        ),
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        assets: List<MediaAsset> = emptyList(),
        nodes: List<SourceNode> = emptyList(),
        tracks: List<Track>? = null,
        timelineDuration: Duration? = null,
    ): Project {
        val resolvedTracks = tracks
            ?: if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        val duration = timelineDuration
            ?: clips.maxOfOrNull { it.timeRange.start + it.timeRange.duration }
            ?: Duration.ZERO
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = resolvedTracks, duration = duration),
            assets = assets,
            source = Source(nodes = nodes),
        )
    }

    private fun decodeIssues(out: ProjectQueryTool.Output): List<ValidationIssue> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ValidationIssue.serializer()),
            out.rows,
        )

    // ── pass path: empty project ──────────────────────────────────

    @Test fun emptyProjectPassesWithOkTitleAndZeroIssuesSummary() {
        val result = runValidationQuery(project())
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeIssues(result.data))
        // Pin: title = "project_query validation: ok"
        assertEquals("project_query validation: ok", result.title)
        // Pin: empty-issues summary form names the project.
        assertTrue(
            "Project p passed validation (0 issues)." in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    // ── pass with warnings only (no errors) ───────────────────────

    @Test fun projectWithWarningOnlyPassesAndTitleSaysOk() {
        // Pin marquee: warnings do NOT fail validation. Per kdoc:
        // "passed = (total == 0)" measures errors; warnings count
        // toward total but don't block.
        // Construct: timeline duration mismatch (warn).
        val a = assetWithId("a")
        val clip = videoClip("c1", "a", duration = 5.seconds)
        val proj = project(
            clips = listOf(clip),
            assets = listOf(a),
            timelineDuration = 2.seconds, // < clip end → warn
        )
        val result = runValidationQuery(proj)
        // Has 1 warning, 0 errors.
        val issues = decodeIssues(result.data)
        assertEquals(1, issues.size)
        assertEquals("warn", issues.single().severity)
        assertEquals("duration-mismatch", issues.single().code)
        // Title: "ok" because errorCount == 0.
        assertEquals(
            "project_query validation: ok",
            result.title,
            "warnings don't fail; title still ok",
        )
    }

    // ── fail path: errors block ──────────────────────────────────

    @Test fun projectWithErrorFailsAndTitleNamesErrorCount() {
        // Pin: 1 error → "project_query validation: 1 error(s)"
        // — singular form via "(s)" suffix (NOT "1 error" or
        // "1 errors").
        val clip = videoClip("c1", "missing", duration = 5.seconds)
        val proj = project(clips = listOf(clip)) // dangling-asset error
        val result = runValidationQuery(proj)
        val issues = decodeIssues(result.data)
        assertEquals(1, issues.size)
        assertEquals("error", issues.single().severity)
        assertEquals("dangling-asset", issues.single().code)
        // Pin: title = "project_query validation: 1 error(s)"
        assertEquals("project_query validation: 1 error(s)", result.title)
    }

    @Test fun multipleErrorsCountsCorrectlyInTitle() {
        // Multiple dangling-asset errors → title shows total count.
        val c1 = videoClip("c1", "missing-1", duration = 3.seconds)
        val c2 = videoClip("c2", "missing-2", duration = 3.seconds)
        val proj = project(clips = listOf(c1, c2))
        val result = runValidationQuery(proj)
        // 2 dangling-asset errors.
        assertEquals("project_query validation: 2 error(s)", result.title)
    }

    // ── outputForLlm: bullet format + severity bracketing ────────

    @Test fun nonEmptyOutputUsesHeaderPlusBulletList() {
        val a = assetWithId("a")
        val volumeError = audioClip("a1", "a", volume = 9.0f)
        val tracks = listOf(Track.Audio(TrackId("at"), listOf(volumeError)))
        // Set timelineDuration explicitly to 5s (matching the
        // audio clip's duration) so we don't pick up an
        // accidental duration-mismatch warning. This test is
        // about the bullet format on a single error issue.
        val proj = project(assets = listOf(a), tracks = tracks, timelineDuration = 5.seconds)
        val out = runValidationQuery(proj).outputForLlm
        // Pin header format: "Project p: N error(s), N warning(s)."
        assertTrue("Project p:" in out, "header includes project id; got: $out")
        assertTrue("1 error(s)" in out, "error count; got: $out")
        assertTrue("0 warning(s)" in out, "warn count; got: $out")
        // Pin bullet format: "- [<severity>] <code> ...".
        assertTrue("- [error] volume-range" in out, "bullet format; got: $out")
    }

    @Test fun bulletFormatIncludesTrackAndClipLocator() {
        // Pin: when both trackId and clipId are populated, locator
        // is "(track=X clip=Y)".
        val clip = videoClip("c1", "missing", duration = 3.seconds)
        val out = runValidationQuery(project(clips = listOf(clip))).outputForLlm
        // Pin format: "- [error] dangling-asset (track=vt clip=c1):
        // ...".
        assertTrue(
            "(track=vt clip=c1)" in out,
            "track+clip locator; got: $out",
        )
    }

    @Test fun bulletFormatOmitsLocatorWhenBothNull() {
        // Source-DAG-level issues (cycle / dangling-parent) emit
        // ValidationIssue with both trackId AND clipId null.
        // Pin: NO "()" leak; the locator is dropped entirely.
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("ghost-parent"))),
        )
        val proj = project(nodes = listOf(a))
        val out = runValidationQuery(proj).outputForLlm
        // Should contain a source-parent-dangling line WITHOUT a
        // "(track=" or "(clip=" prefix.
        assertTrue("source-parent-dangling" in out, "issue surfaces; got: $out")
        // The line for source-parent-dangling should be:
        // "- [error] source-parent-dangling: <message>"
        // — the colon-no-paren shape.
        val sourceLines = out.lines().filter { "source-parent-dangling" in it }
        for (line in sourceLines) {
            assertTrue(
                ":" in line && "(" !in line.substringBefore(":"),
                "no locator parens before colon; got: $line",
            )
        }
    }

    // ── error-vs-warn tally separation ───────────────────────────

    @Test fun headerSeparatesErrorAndWarningCountsIndependently() {
        // Plant 1 error (dangling-asset) + 1 warn (duration-mismatch).
        val a = assetWithId("a")
        val clip = videoClip("c1", "missing", duration = 5.seconds) // dangling-asset error
        val proj = project(
            clips = listOf(clip),
            assets = listOf(a),
            timelineDuration = 2.seconds, // duration-mismatch warn
        )
        val out = runValidationQuery(proj).outputForLlm
        assertTrue("1 error(s)" in out, "got: $out")
        assertTrue("1 warning(s)" in out, "got: $out")
    }

    @Test fun titleErrorCountIgnoresWarnings() {
        // Pin: title shows ONLY error count, NOT total. Per code:
        // `errorCount == 0 → ok` else `$errorCount error(s)`.
        val a = assetWithId("a")
        val clip = videoClip("c1", "a", duration = 5.seconds)
        val proj = project(
            clips = listOf(clip),
            assets = listOf(a),
            timelineDuration = 2.seconds, // 1 warn, 0 errors
        )
        val result = runValidationQuery(proj)
        // 0 errors → title = ok despite the warning.
        assertEquals("project_query validation: ok", result.title)
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runValidationQuery(project())
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_VALIDATION, result.data.select)
    }

    @Test fun totalAndReturnedMirrorIssueCount() {
        // Pin: total == returned == issues.size. No pagination
        // applied (issue counts are bounded in practice).
        val c1 = videoClip("c1", "missing-1", duration = 3.seconds)
        val c2 = videoClip("c2", "missing-2", duration = 3.seconds)
        val proj = project(clips = listOf(c1, c2))
        val result = runValidationQuery(proj)
        assertEquals(2, result.data.total)
        assertEquals(2, result.data.returned)
    }

    // ── ValidationIssue field round-trip ─────────────────────────

    @Test fun validationIssueRowRoundTripsAllFields() {
        // Pin: the row format is the ValidationIssue type
        // directly (per kdoc, "directly the ValidationIssue type
        // that lives in core.domain"). Severity / code /
        // message / trackId / clipId all round-trip.
        val clip = videoClip("c1", "missing-asset", duration = 3.seconds)
        val proj = project(clips = listOf(clip))
        val issue = decodeIssues(runValidationQuery(proj).data).single()
        assertEquals("error", issue.severity)
        assertEquals("dangling-asset", issue.code)
        assertEquals("vt", issue.trackId)
        assertEquals("c1", issue.clipId)
        assertTrue(
            "missing-asset" in issue.message,
            "message names the missing asset id; got: ${issue.message}",
        )
    }
}
