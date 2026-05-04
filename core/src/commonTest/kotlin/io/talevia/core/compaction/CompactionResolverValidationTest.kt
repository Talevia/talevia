package io.talevia.core.compaction

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Defensive-constructor validation pins for
 * [PerModelCompactionThreshold] and [PerModelCompactionBudget].
 * Both resolvers accept user-supplied scalars (`ratio`, `keepRatio`,
 * `fallback`) that drive every per-turn compaction decision; cycle
 * 322 added `init { require(...) }` blocks to fail loud at
 * construction when these scalars are out-of-range.
 *
 * Why the validation matters (negative-ratio attack pattern):
 * a `ratio = -0.5` slips silently past type-checking (Double accepts
 * any finite value) but yields negative thresholds. Downstream
 * [io.talevia.core.agent.CompactionGate.maybeCompact] gates with
 * `estimated <= threshold`; estimated is always `>= 0`, so a
 * negative threshold makes that comparison fail on every turn,
 * making compaction fire constantly. Pre-cycle-322 callers had no
 * fail-loud signal — agents would just hit the provider with
 * unbounded compaction overhead until someone noticed cost spikes.
 *
 * Same defensive-contract drift class as cycle 321 BundleFile
 * constructor pins (path-traversal rejects). Pin every
 * out-of-range case so a refactor relaxing a `require` lands in
 * test-red.
 *
 * Mirrors the existing
 * [io.talevia.core.agent.RetryPolicy] pattern: the
 * pre-cycle-322 RetryPolicy already validates
 * `jitterFactor in 0.0..1.0`; this test file extends the same
 * shape to compaction resolvers.
 */
class CompactionResolverValidationTest {

    // ── PerModelCompactionThreshold.ratio bounds ───────────────────

    @Test fun thresholdRejectsNegativeRatio() {
        assertFailsWith<IllegalArgumentException> {
            PerModelCompactionThreshold(
                contextWindowByRef = emptyMap(),
                ratio = -0.1,
            )
        }
    }

    @Test fun thresholdRejectsRatioGreaterThanOne() {
        assertFailsWith<IllegalArgumentException> {
            PerModelCompactionThreshold(
                contextWindowByRef = emptyMap(),
                ratio = 1.01,
            )
        }
    }

    @Test fun thresholdAcceptsRatioAtZero() {
        // Inclusive lower bound. Pin: 0.0 is valid (degenerate "compact
        // every turn" but not pathological). Drift to exclusive
        // bound (`ratio > 0.0`) would silently reject this edge.
        PerModelCompactionThreshold(
            contextWindowByRef = emptyMap(),
            ratio = 0.0,
        )
    }

    @Test fun thresholdAcceptsRatioAtOne() {
        // Inclusive upper bound. Pin: 1.0 is valid (compact only on
        // exact context-window-equal — degenerate but not pathological).
        PerModelCompactionThreshold(
            contextWindowByRef = emptyMap(),
            ratio = 1.0,
        )
    }

    @Test fun thresholdAcceptsDefaultRatio() {
        // The default `0.85` is inside the valid range. Anti-pin
        // against a refactor that tightens the bound past the
        // default.
        PerModelCompactionThreshold(contextWindowByRef = emptyMap())
    }

    // ── PerModelCompactionThreshold.fallback bounds ────────────────

    @Test fun thresholdRejectsNegativeFallback() {
        assertFailsWith<IllegalArgumentException> {
            PerModelCompactionThreshold(
                contextWindowByRef = emptyMap(),
                fallback = -1,
            )
        }
    }

    @Test fun thresholdAcceptsZeroFallback() {
        // Inclusive: 0 is valid (means "compact every turn for
        // unknown models"). Pin: zero is NOT considered pathological.
        PerModelCompactionThreshold(
            contextWindowByRef = emptyMap(),
            fallback = 0,
        )
    }

    @Test fun thresholdAcceptsDefaultFallback() {
        // Default is 120_000 — confirm anti-pin.
        PerModelCompactionThreshold(contextWindowByRef = emptyMap())
    }

    // ── PerModelCompactionBudget.keepRatio bounds ──────────────────

    @Test fun budgetRejectsNegativeKeepRatio() {
        assertFailsWith<IllegalArgumentException> {
            PerModelCompactionBudget(
                contextWindowByRef = emptyMap(),
                keepRatio = -0.1,
            )
        }
    }

    @Test fun budgetRejectsKeepRatioGreaterThanOne() {
        assertFailsWith<IllegalArgumentException> {
            PerModelCompactionBudget(
                contextWindowByRef = emptyMap(),
                keepRatio = 1.5,
            )
        }
    }

    @Test fun budgetAcceptsKeepRatioAtZero() {
        // Inclusive lower bound. Note: keepRatio=0 means scaled
        // budget always rounds to 0, which falls through to
        // fallback per the `if (scaled <= 0) return fallback` guard
        // (see PerModelCompactionBudget.invoke). So the value is
        // valid at construction time even though the resolver
        // would never produce a non-fallback budget.
        PerModelCompactionBudget(
            contextWindowByRef = emptyMap(),
            keepRatio = 0.0,
        )
    }

    @Test fun budgetAcceptsKeepRatioAtOne() {
        // Inclusive upper bound. keepRatio=1.0 means scaled budget
        // == contextWindow; degenerate (compaction is a no-op) but
        // not pathological at construction.
        PerModelCompactionBudget(
            contextWindowByRef = emptyMap(),
            keepRatio = 1.0,
        )
    }

    @Test fun budgetAcceptsDefaultKeepRatio() {
        // Default 0.30 is inside the range.
        PerModelCompactionBudget(contextWindowByRef = emptyMap())
    }

    // ── Cross-resolver invariant: gap stays load-bearing ───────────

    @Test fun thresholdRatioMustExceedBudgetKeepRatio() {
        // The pinned trigger-thrash-protective invariant from cycle
        // 317: `threshold_ratio - keep_ratio >= 0.50`. With both
        // ratios now bounded to [0.0, 1.0], the gap can still be
        // misconfigured (e.g. threshold=0.5, keepRatio=0.6 →
        // negative gap → compaction lands ABOVE re-trigger →
        // immediate re-fire). Pin: the cycle 317 gap test caught
        // this AT THE DEFAULTS (0.85 - 0.30 = 0.55), but a
        // composition root passing custom values could still
        // produce an inverted gap. Future hardening would lift
        // the gap check into the resolver constructors; for now
        // pin observed behaviour: both constructors INDEPENDENTLY
        // accept any in-range value, the gap invariant lives in
        // [PerModelCompactionBudgetRealProvidersTest] (cycle 317).
        // This test confirms NO cross-validation between the two
        // resolvers at construction (anti-pin against
        // accidentally adding cross-coupling that would break
        // composition-root callers wiring them independently).
        // Both succeed even though their gap is inverted.
        PerModelCompactionThreshold(
            contextWindowByRef = emptyMap(),
            ratio = 0.5,
        )
        PerModelCompactionBudget(
            contextWindowByRef = emptyMap(),
            keepRatio = 0.6,
        )
        // No exception thrown — gap invariant is the COMPOSITION
        // root's responsibility (today: the cycle 317 ratio-pair
        // pin in the real-providers test catches drift in defaults).
    }
}
