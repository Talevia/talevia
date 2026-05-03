package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for the two top-level functions in `core/domain/ProjectValidation.kt` —
 * [computeProjectValidationIssues] and [renderProjectValidationIssues]. Cycle 96 audit
 * found the renderer had **zero** direct test references (only consumed by
 * `ImportProjectFromJsonTool` at envelope-import time, never asserted in a test) and
 * the public `computeProjectValidationIssues` API was only exercised transitively via
 * `ProjectQueryTool(select=validation)` — so the direct entry point used by
 * `ImportProjectFromJsonTool` was unprotected.
 *
 * The renderer's formatting choices are user-visible: an envelope import that fails
 * validation surfaces `renderProjectValidationIssues(issues)` as the `error { ... }`
 * message body. A regression in the truncation / locator / severity bracketing would
 * silently corrupt every import-error message users see.
 *
 * Also pins the `non-positive-duration` rule, which `ValidateProjectToolTest` does not
 * cover (it tests every other validation code, but a clip with `duration == 0s` was
 * never asserted directly).
 */
class ProjectValidationTest {

    // ── renderProjectValidationIssues ──────────────────────────────

    @Test fun rendererReturnsEmptyStringForNoIssues() {
        // Pin: empty list → empty string. Important because callers
        // can use `if (rendered.isEmpty()) ...` to skip the boundary
        // error path entirely.
        assertEquals("", renderProjectValidationIssues(emptyList()))
    }

    @Test fun rendererPrefixesWithSeverityInBrackets() {
        val rendered = renderProjectValidationIssues(
            listOf(
                ValidationIssue(
                    severity = "error",
                    code = "non-positive-duration",
                    message = "clip duration must be > 0",
                ),
            ),
        )
        assertTrue("[error]" in rendered, "severity must render as [error]; got: $rendered")
        assertTrue("non-positive-duration" in rendered, "code must appear; got: $rendered")
        assertTrue("clip duration must be > 0" in rendered)
    }

    @Test fun rendererIncludesTrackAndClipLocator() {
        // Pin: `(track=v1 clip=c1)` style locator when both are present.
        // Format: `- [<sev>] <code> (track=<id> clip=<id>): <message>`.
        val rendered = renderProjectValidationIssues(
            listOf(
                ValidationIssue(
                    severity = "error",
                    code = "dangling-asset",
                    message = "missing",
                    trackId = "v1",
                    clipId = "c1",
                ),
            ),
        )
        assertTrue(
            "(track=v1 clip=c1)" in rendered,
            "expected '(track=v1 clip=c1)' in: $rendered",
        )
    }

    @Test fun rendererTrackOnlyOmitsClip() {
        val rendered = renderProjectValidationIssues(
            listOf(
                ValidationIssue(
                    severity = "warn",
                    code = "duration-mismatch",
                    message = "x",
                    trackId = "v1",
                    clipId = null,
                ),
            ),
        )
        assertTrue("(track=v1)" in rendered, "expected '(track=v1)'; got: $rendered")
        assertFalse("clip=" in rendered, "no clipId — no clip= token; got: $rendered")
    }

    @Test fun rendererClipOnlyOmitsTrack() {
        // Defensive: usually clipIssues sets both, but the type signature
        // allows either-null. Pin the output for the clip-only path so a
        // refactor that always emits both doesn't silently change shape.
        val rendered = renderProjectValidationIssues(
            listOf(
                ValidationIssue(
                    severity = "error",
                    code = "x",
                    message = "y",
                    trackId = null,
                    clipId = "c1",
                ),
            ),
        )
        assertTrue("(clip=c1)" in rendered, "expected '(clip=c1)'; got: $rendered")
        assertFalse("track=" in rendered, "no trackId — no track= token; got: $rendered")
    }

    @Test fun rendererOmitsLocatorWhenBothNull() {
        // Source-DAG issues (cycle / dangling parent) emit locator-less
        // ValidationIssue. Pin: no parens when both are null.
        val rendered = renderProjectValidationIssues(
            listOf(
                ValidationIssue(
                    severity = "error",
                    code = "source-parent-cycle",
                    message = "a → b → a",
                ),
            ),
        )
        // The line shape is `- [error] source-parent-cycle: a → b → a`.
        assertTrue(
            "- [error] source-parent-cycle: a → b → a" in rendered,
            "no-locator line shape unexpected: $rendered",
        )
        // No empty `()` artefact.
        assertFalse("()" in rendered, "empty parens leak: $rendered")
    }

    @Test fun rendererTruncatesAtMaxLinesAndAppendsCount() {
        // Default maxLines = 5 — overshoot and check the suffix.
        val issues = (1..7).map {
            ValidationIssue(severity = "error", code = "c$it", message = "m$it")
        }
        val rendered = renderProjectValidationIssues(issues)
        // First 5 must appear, last 2 must NOT.
        for (i in 1..5) {
            assertTrue("c$i" in rendered, "code c$i must appear in head: $rendered")
        }
        assertFalse("c6" in rendered, "c6 must be truncated: $rendered")
        assertFalse("c7" in rendered, "c7 must be truncated: $rendered")
        assertTrue("… (2 more)" in rendered, "expected '… (2 more)' suffix: $rendered")
    }

    @Test fun rendererExactlyAtMaxLinesHasNoSuffix() {
        // Pin the boundary: list.size == maxLines should NOT emit the
        // "(N more)" line. A regression using `>=` instead of `>` would
        // emit "(0 more)" — a noisy diff in error messages.
        val issues = (1..5).map {
            ValidationIssue(severity = "error", code = "c$it", message = "m$it")
        }
        val rendered = renderProjectValidationIssues(issues)
        assertFalse("more)" in rendered, "exactly-at-max must not emit suffix: $rendered")
        for (i in 1..5) assertTrue("c$i" in rendered)
    }

    @Test fun rendererRespectsCustomMaxLines() {
        val issues = (1..4).map {
            ValidationIssue(severity = "error", code = "c$it", message = "m$it")
        }
        val rendered = renderProjectValidationIssues(issues, maxLines = 2)
        assertTrue("c1" in rendered)
        assertTrue("c2" in rendered)
        assertFalse("c3" in rendered, "c3 must be truncated when maxLines=2: $rendered")
        assertTrue("… (2 more)" in rendered, "expected '… (2 more)' suffix: $rendered")
    }

    @Test fun rendererJoinsLinesWithNewlines() {
        // Pin: head joined by '\n', '\n' between head and "(N more)".
        val issues = (1..3).map {
            ValidationIssue(severity = "error", code = "c$it", message = "m$it")
        }
        val rendered = renderProjectValidationIssues(issues, maxLines = 2)
        val lines = rendered.split("\n")
        assertEquals(3, lines.size, "expected 3 lines (2 head + 1 suffix); got: $rendered")
        assertTrue(lines[0].startsWith("- [error] c1:"))
        assertTrue(lines[1].startsWith("- [error] c2:"))
        assertEquals("… (1 more)", lines[2])
    }

    // ── computeProjectValidationIssues ─────────────────────────────

    @Test fun computeReturnsEmptyForEmptyProject() {
        val empty = Project(id = ProjectId("p"), timeline = Timeline())
        assertEquals(emptyList(), computeProjectValidationIssues(empty))
    }

    @Test fun computeFlagsNonPositiveDuration() {
        // The non-positive-duration rule is the one validation code
        // ValidateProjectToolTest doesn't cover (it tests every other
        // code). Pin it directly here so a regression returning early
        // for `duration == 0` would catch.
        val zeroDurationClip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(start = Duration.ZERO, duration = Duration.ZERO),
            sourceRange = TimeRange(start = Duration.ZERO, duration = Duration.ZERO),
            assetId = AssetId("a"),
        )
        val asset = MediaAsset(
            id = AssetId("a"),
            source = MediaSource.File("/tmp/a.mp4"),
            metadata = MediaMetadata(
                duration = 10.seconds,
                resolution = Resolution(1920, 1080),
                frameRate = FrameRate.FPS_30,
            ),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v1"), listOf(zeroDurationClip))),
                duration = Duration.ZERO,
            ),
            assets = listOf(asset),
        )
        val issues = computeProjectValidationIssues(project)
        val issue = issues.single { it.code == "non-positive-duration" }
        assertEquals("error", issue.severity)
        assertEquals("c1", issue.clipId)
        assertEquals("v1", issue.trackId)
    }

    @Test fun computeAggregatesAcrossAllThreeAxes() {
        // Pin the kdoc contract: composes timeline duration + per-clip
        // checks + source DAG checks. A regression dropping any one axis
        // from buildList would silently lose validation coverage.
        // Build a project that violates one rule on each axis.

        // Axis 1: timeline duration mismatch (warn).
        // Axis 2: dangling asset error on clip.
        // Axis 3: source DAG self-loop cycle.
        val danglingAssetClip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("missing"),
        )
        val selfRef = io.talevia.core.domain.source.SourceNode(
            id = io.talevia.core.SourceNodeId("self"),
            kind = "narrative.scene",
            parents = listOf(io.talevia.core.domain.source.SourceRef(io.talevia.core.SourceNodeId("self"))),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v1"), listOf(danglingAssetClip))),
                duration = 1.seconds, // < clip end (5s) — duration-mismatch warn
            ),
            source = io.talevia.core.domain.source.Source(nodes = listOf(selfRef)),
        )
        val issues = computeProjectValidationIssues(project)
        val codes = issues.map { it.code }.toSet()
        assertTrue("duration-mismatch" in codes, "axis 1 (timeline duration) must fire: $codes")
        assertTrue("dangling-asset" in codes, "axis 2 (clip integrity) must fire: $codes")
        assertTrue(
            "source-parent-cycle" in codes,
            "axis 3 (source DAG) must fire: $codes",
        )
    }
}
