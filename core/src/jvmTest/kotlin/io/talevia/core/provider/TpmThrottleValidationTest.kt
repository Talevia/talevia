package io.talevia.core.provider

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Defensive-constructor validation pins for [TpmThrottle]. The
 * sibling [TpmThrottleTest] (8 happy-path tests) covers the
 * acquire/settle/stallFor pipeline and the bufferRatio's effective-
 * budget behaviour, but **0 of 3** `init` `require` rejections
 * are individually pinned today.
 *
 * Why this matters (negative-tpmLimit attack pattern): a misconfigured
 * `TpmThrottle(tpmLimit = -1L)` would slip past type-checking (Long
 * accepts any value) but yield `(tpmLimit * bufferRatio).toLong()`
 * = a negative budget. Then `acquire(estimate).coerceAtMost(budget)`
 * = clamp to negative → `used + clamped <= budget` is `0 + negative
 * <= negative`, which is true, so every reservation succeeds
 * immediately even though the cap was meant to throttle. The throttle
 * silently does nothing — undetectable until the provider 429s.
 *
 * Same drift class as cycle 322 [PerModelCompactionThreshold] /
 * [PerModelCompactionBudget] defensive-constructor pins. The `init`
 * blocks ALREADY EXIST (lines 44-48 of TpmThrottle.kt); this file
 * pins each rejection case explicitly so a refactor relaxing any
 * `require` lands in test-red — same protective shape as cycle 321
 * BundleFile constructor pins.
 *
 * Mirrors the existing [io.talevia.core.agent.RetryPolicy] precedent
 * (`invalidConstructorArgsFailLoud` test, ~6 require checks).
 * TpmThrottle's 3 require checks now get individually-pinned coverage
 * symmetric with cycle 322's compaction resolver pattern.
 */
class TpmThrottleValidationTest {

    // ── tpmLimit > 0 (strict positive) ─────────────────────────────

    @Test fun rejectsZeroTpmLimit() {
        // Strict-positive: 0 is invalid (a 0 TPM limit means no
        // throughput allowed at all, which is degenerate not
        // throttling). Pin: must throw, not silently accept.
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = 0)
        }
    }

    @Test fun rejectsNegativeTpmLimit() {
        // Critical pin: negative tpmLimit yields negative budget,
        // which silently no-ops the throttle (every acquire passes).
        // The throttle would consume mutex hops without ever blocking.
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = Long.MIN_VALUE)
        }
    }

    @Test fun acceptsMinimalPositiveTpmLimit() {
        // Boundary: 1 is the minimum valid value. Anti-pin against
        // a refactor that tightens to `tpmLimit > 100` or similar
        // — single-token throttling is a degenerate but legitimate
        // configuration (e.g. integration test stress paths).
        TpmThrottle(tpmLimit = 1)
    }

    // ── bufferRatio in [0.0, 1.0] (inclusive) ──────────────────────

    @Test fun rejectsNegativeBufferRatio() {
        // Negative bufferRatio yields negative budget (same shape
        // as negative tpmLimit). Pin: fail-loud at construction.
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = 100, bufferRatio = -0.1)
        }
    }

    @Test fun rejectsBufferRatioGreaterThanOne() {
        // > 1.0 means "use more than the limit" — pathological.
        // The throttle would attempt to schedule beyond what the
        // provider allows, defeating its preventative purpose.
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = 100, bufferRatio = 1.01)
        }
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = 100, bufferRatio = 2.0)
        }
    }

    @Test fun acceptsBufferRatioAtZeroBoundary() {
        // Inclusive: 0.0 is valid (degenerate "block everything"
        // — budget == 0 floored to 1 by `coerceAtLeast(1L)` in
        // acquire, so single-token requests still pass; larger
        // ones wait forever which is the correct outcome at
        // ratio=0). Pin observed boundary inclusivity so a
        // refactor to `> 0.0` doesn't silently reject.
        TpmThrottle(tpmLimit = 100, bufferRatio = 0.0)
    }

    @Test fun acceptsBufferRatioAtOneBoundary() {
        // Inclusive upper bound: 1.0 is valid (use the full
        // limit, no headroom for accounting drift). Degenerate
        // but legitimate.
        TpmThrottle(tpmLimit = 100, bufferRatio = 1.0)
    }

    @Test fun acceptsDefaultBufferRatio() {
        // Default 0.85 is inside the valid range. Anti-pin
        // against a refactor that tightens past the default
        // (e.g. `bufferRatio > 0.5`).
        TpmThrottle(tpmLimit = 100)
    }

    // ── windowMs > 0 (strict positive) ─────────────────────────────

    @Test fun rejectsZeroWindowMs() {
        // Zero window means "no sliding window" — the evict()
        // logic would have a cutoff equal to now, immediately
        // evicting every record. Pathological because the
        // throttle never actually accumulates state.
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = 100, windowMs = 0)
        }
    }

    @Test fun rejectsNegativeWindowMs() {
        // Negative window yields cutoff > now, evicting nothing
        // ever — the records ArrayDeque grows unboundedly with
        // every acquire. Pin: fail-loud at construction.
        assertFailsWith<IllegalArgumentException> {
            TpmThrottle(tpmLimit = 100, windowMs = -1)
        }
    }

    @Test fun acceptsMinimalPositiveWindowMs() {
        // Boundary: 1ms is the minimum valid value. Anti-pin
        // against refactor that tightens to `windowMs >= 1000`
        // (which would be a sensible production floor but not
        // a constructor-level invariant).
        TpmThrottle(tpmLimit = 100, windowMs = 1)
    }

    @Test fun acceptsDefaultWindowMs() {
        // Default 60_000ms (= 1 minute) is the canonical TPM
        // window. Anti-pin against drift in the default.
        TpmThrottle(tpmLimit = 100)
    }

    // ── Combined: minimum-valid args succeed ──────────────────────

    @Test fun acceptsMinimumValidArgsCombination() {
        // Triple-boundary pin: smallest legal tpmLimit (1), zero
        // bufferRatio, smallest legal windowMs (1ms). Confirms the
        // 3 require checks are fully orthogonal — no hidden
        // cross-validation that rejects a combination of valid
        // singletons.
        TpmThrottle(tpmLimit = 1, bufferRatio = 0.0, windowMs = 1)
    }

    @Test fun acceptsAllMaximalValidArgsCombination() {
        // Inverse boundary pin: largest sensible values (Long.MAX_VALUE
        // tpmLimit, ratio 1.0, very long window). All 3 axes at
        // upper boundary still succeed.
        TpmThrottle(
            tpmLimit = Long.MAX_VALUE,
            bufferRatio = 1.0,
            windowMs = Long.MAX_VALUE,
        )
    }
}
