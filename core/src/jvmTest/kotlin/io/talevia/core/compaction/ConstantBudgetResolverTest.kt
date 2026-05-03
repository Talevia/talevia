package io.talevia.core.compaction

import io.talevia.core.session.ModelRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Direct tests for [constantBudgetResolver] —
 * `core/src/commonMain/kotlin/io/talevia/core/compaction/CompactionBudget.kt:67`.
 * Cycle 263 audit: 0 test refs (the sibling
 * `CompactionBudgetTest` exercises [PerModelCompactionBudget]
 * but doesn't directly call `constantBudgetResolver`).
 *
 * Same audit-pattern fallback as cycles 207-262.
 *
 * `constantBudgetResolver(protectUserTurns, pruneKeepTokens)`
 * is the helper for `Compactor` callers that don't want
 * per-model scaling — it builds a single [CompactionBudget]
 * and returns a `(ModelRef) -> CompactionBudget` lambda that
 * always yields the same instance regardless of the model.
 *
 * Per the kdoc: "Used as [Compactor]'s default so pre-cycle
 * constructors (`Compactor(provider, store, bus)` without
 * explicit budget) reproduce the legacy
 * `CompactionBudget.DEFAULT` behaviour."
 *
 * Drift signals:
 *   - Drift to "rebuild budget on each call" would silently
 *     change object identity (tests that check for stable
 *     instances would break).
 *   - Drift to "scale by ModelRef" would silently break
 *     callers expecting the resolver to NOT depend on model.
 *   - Drift in arg validation (skipping `require()`) would
 *     silently produce invalid budgets at construction —
 *     drift surfaces here when `protectUserTurns=0` /
 *     `pruneKeepTokens=0` should still throw via the
 *     CompactionBudget init.
 *
 * Pins three correctness contracts:
 *
 *  1. **Returned lambda is "constant" — same instance for
 *     every ModelRef input**. Marquee identity invariant.
 *     Drift to "rebuild on each call" surfaces here as
 *     `assertSame` fails.
 *
 *  2. **Constructed budget reflects the args verbatim**:
 *     `protectUserTurns` and `pruneKeepTokens` echo the
 *     constructor args.
 *
 *  3. **Arg validation propagates from CompactionBudget init**:
 *     `protectUserTurns < 1` throws; `pruneKeepTokens <= 0`
 *     throws (via the `require()` in `CompactionBudget.init`).
 *     Drift to skip validation would silently produce invalid
 *     budgets that crash later in the compactor's prune loop.
 */
class ConstantBudgetResolverTest {

    // ── 1. Constant lambda — same instance for every model ──

    @Test fun resolverReturnsSameInstanceForEveryModelRef() {
        // Marquee identity pin: the returned lambda is constant
        // — every ModelRef call yields the SAME budget instance
        // (NOT a fresh one). Drift to "rebuild per call"
        // surfaces here.
        val resolver = constantBudgetResolver(
            protectUserTurns = 2,
            pruneKeepTokens = 40_000,
        )
        val anthropic = resolver(ModelRef("anthropic", "claude-sonnet-4-6"))
        val openai = resolver(ModelRef("openai", "gpt-4o"))
        val gemini = resolver(ModelRef("google", "gemini-2-pro"))
        // All three calls return the SAME instance — `===`
        // identity, not just structural equality.
        assertSame(
            anthropic,
            openai,
            "constantBudgetResolver MUST return the SAME budget instance regardless of provider",
        )
        assertSame(
            anthropic,
            gemini,
            "constantBudgetResolver MUST return the SAME budget instance regardless of provider",
        )
    }

    @Test fun resolverIgnoresModelRefVariantField() {
        // Pin: the resolver is `model-agnostic` — same lambda
        // body returns same constant regardless of how
        // ModelRef differs. Drift to look at variant / cache
        // by ref would surface here.
        val resolver = constantBudgetResolver(2, 40_000)
        val withVariant = resolver(
            ModelRef("anthropic", "claude-sonnet-4-6", variant = "thinking"),
        )
        val withoutVariant = resolver(
            ModelRef("anthropic", "claude-sonnet-4-6", variant = null),
        )
        assertSame(withVariant, withoutVariant)
    }

    // ── 2. Constructed budget echoes args ───────────────────

    @Test fun budgetReflectsProtectUserTurnsArg() {
        for (turns in listOf(1, 2, 5, 100)) {
            val budget = constantBudgetResolver(
                protectUserTurns = turns,
                pruneKeepTokens = 40_000,
            )(ModelRef("any", "any"))
            assertEquals(turns, budget.protectUserTurns)
        }
    }

    @Test fun budgetReflectsPruneKeepTokensArg() {
        for (tokens in listOf(1_000, 40_000, 100_000, 1_000_000)) {
            val budget = constantBudgetResolver(
                protectUserTurns = 2,
                pruneKeepTokens = tokens,
            )(ModelRef("any", "any"))
            assertEquals(tokens, budget.pruneKeepTokens)
        }
    }

    @Test fun resolverWithLegacyDefaultArgsMatchesCompactionBudgetDefault() {
        // Pin: per the kdoc, the helper "reproduces the
        // legacy CompactionBudget.DEFAULT behaviour" when
        // called with `(protectUserTurns=2, pruneKeepTokens=40_000)`.
        // Drift in the helper or in the DEFAULT constants
        // would silently de-sync.
        val resolver = constantBudgetResolver(
            protectUserTurns = CompactionBudget.DEFAULT.protectUserTurns,
            pruneKeepTokens = CompactionBudget.DEFAULT.pruneKeepTokens,
        )
        val budget = resolver(ModelRef("any", "any"))
        assertEquals(CompactionBudget.DEFAULT, budget, "structural equality with DEFAULT")
        assertEquals(2, budget.protectUserTurns)
        assertEquals(40_000, budget.pruneKeepTokens)
    }

    // ── 3. Arg validation propagates ────────────────────────

    @Test fun protectUserTurnsZeroFailsViaCompactionBudgetInit() {
        // Pin: per `CompactionBudget.init`,
        // `protectUserTurns >= 1` is required. Drift to "skip
        // validation in the helper" would silently produce an
        // invalid budget that crashes later in the prune
        // loop. The require() must fire AT CONSTRUCTION TIME
        // (eagerly), NOT lazily on the first invoke().
        assertFailsWith<IllegalArgumentException> {
            constantBudgetResolver(protectUserTurns = 0, pruneKeepTokens = 40_000)
        }
    }

    @Test fun protectUserTurnsNegativeFailsViaCompactionBudgetInit() {
        assertFailsWith<IllegalArgumentException> {
            constantBudgetResolver(protectUserTurns = -1, pruneKeepTokens = 40_000)
        }
    }

    @Test fun pruneKeepTokensZeroFailsViaCompactionBudgetInit() {
        // Pin: `pruneKeepTokens > 0` required. 0-tokens
        // budget would silently let the compactor prune
        // everything down to nothing — drift to skip
        // validation surfaces here.
        assertFailsWith<IllegalArgumentException> {
            constantBudgetResolver(protectUserTurns = 2, pruneKeepTokens = 0)
        }
    }

    @Test fun pruneKeepTokensNegativeFailsViaCompactionBudgetInit() {
        assertFailsWith<IllegalArgumentException> {
            constantBudgetResolver(protectUserTurns = 2, pruneKeepTokens = -1)
        }
    }

    // ── 4. Validation timing — eager at construction ────────

    @Test fun validationFiresEagerlyNotLazily() {
        // Pin: `constantBudgetResolver` constructs the
        // CompactionBudget BEFORE returning the lambda — so
        // invalid args throw NOW, not on the first invoke().
        // Drift to "lazy construction" would let invalid
        // resolvers be created and only crash much later
        // (potentially in a hot-path).
        var lambdaCallCount = 0
        // This MUST throw before any lambda body could run.
        assertFailsWith<IllegalArgumentException> {
            constantBudgetResolver(protectUserTurns = 0, pruneKeepTokens = 40_000)
            // If validation were lazy, we'd get past this
            // line and call the lambda below.
            lambdaCallCount += 1
        }
        assertEquals(0, lambdaCallCount, "post-failure code MUST NOT have run")
    }
}
