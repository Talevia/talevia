package io.talevia.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [TimeRange], [FrameRate], and [Resolution] —
 * the basic unit types the timeline + render layer composes with.
 * Cycle 93 audit found these small types had no direct test
 * (TimeRange has 100+ transitive references via every clip
 * test, but the `end` computed property + FrameRate.fps math
 * are arithmetic surfaces that haven't been pinned).
 */
class CommonTypesTest {

    @Test fun timeRangeEndIsStartPlusDuration() {
        val r = TimeRange(start = 5.seconds, duration = 3.seconds)
        assertEquals(8.seconds, r.end)
    }

    @Test fun timeRangeEndAtZeroStartEqualsDuration() {
        val r = TimeRange(start = 0.seconds, duration = 10.seconds)
        assertEquals(10.seconds, r.end)
    }

    @Test fun timeRangeZeroDurationGivesEndEqualsStart() {
        // Edge: a zero-duration range has end == start. Some test
        // patterns build "marker" zero-duration ranges; pin so the
        // arithmetic doesn't accidentally diverge.
        val r = TimeRange(start = 7.seconds, duration = 0.seconds)
        assertEquals(7.seconds, r.end)
    }

    @Test fun frameRateFpsForIntegerFramerates() {
        // Common case: integer framerate (denominator=1) produces
        // exact double.
        assertEquals(24.0, FrameRate(24).fps)
        assertEquals(30.0, FrameRate(30).fps)
        assertEquals(60.0, FrameRate(60).fps)
    }

    @Test fun frameRateFpsForNTSCFractional() {
        // 30000/1001 ≈ 29.97 — the standard NTSC fractional rate.
        // Pin the math so a refactor switching to integer division
        // would catch.
        val ntsc = FrameRate(30_000, 1_001)
        val fps = ntsc.fps
        assertTrue(
            fps in 29.9..29.98,
            "29.97 fps should be in [29.9, 29.98]; got $fps",
        )
    }

    @Test fun frameRateFpsForFractionalRates() {
        // 24000/1001 ≈ 23.976 — film-rate-NTSC. Same pattern.
        val film = FrameRate(24_000, 1_001)
        assertTrue(
            film.fps in 23.97..23.98,
            "film rate fps should be in [23.97, 23.98]; got ${film.fps}",
        )
    }

    @Test fun frameRateConstantsHaveExpectedNumeratorsAndDenominators() {
        // Pin the constants. Cycle 93 audit: a refactor accidentally
        // switching FPS_30 to FrameRate(30000, 1001) would change the
        // default frame rate of every project that didn't set one
        // explicitly.
        assertEquals(24, FrameRate.FPS_24.numerator)
        assertEquals(1, FrameRate.FPS_24.denominator)
        assertEquals(30, FrameRate.FPS_30.numerator)
        assertEquals(1, FrameRate.FPS_30.denominator)
        assertEquals(60, FrameRate.FPS_60.numerator)
        assertEquals(1, FrameRate.FPS_60.denominator)
    }

    @Test fun frameRateDefaultDenominatorIsOne() {
        // Pin the default. Most call sites use `FrameRate(30)` →
        // (30, 1). A refactor changing the default would change
        // every caller's intent.
        assertEquals(1, FrameRate(42).denominator)
        assertEquals(42, FrameRate(42).numerator)
    }

    @Test fun frameRateFpsOnZeroDenominatorIsInfinity() {
        // Pure-math edge: division by zero produces Double.POSITIVE_
        // INFINITY (not throws). Pin the observed behaviour so a
        // refactor adding a `require(denominator > 0)` would catch
        // by failing this test. Today's code accepts `FrameRate(N, 0)`
        // — caller responsibility to not construct one. The test
        // documents the contract.
        val degenerate = FrameRate(30, 0)
        assertEquals(Double.POSITIVE_INFINITY, degenerate.fps)
    }

    @Test fun resolutionEqualityIsByValue() {
        // Data class equality: two Resolutions with same w/h are
        // equal. Trivial for data classes, but pin so a future
        // refactor adding custom `equals` doesn't break the
        // assumption code makes everywhere.
        assertEquals(Resolution(1920, 1080), Resolution(1920, 1080))
    }
}
