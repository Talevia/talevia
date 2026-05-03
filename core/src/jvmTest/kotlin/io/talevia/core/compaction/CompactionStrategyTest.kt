package io.talevia.core.compaction

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [CompactionStrategy.parseOrDefault] — the
 * agent-facing string parser. Cycle 78 audit found this class had no
 * direct test (1 transitive reference only). The parser's
 * default-on-unknown-input contract is load-bearing per the kdoc:
 * "a typo never silently skips the summary — it just behaves like the
 * unparametrised path." Cycle 79 pins each alias explicitly.
 */
class CompactionStrategyTest {

    @Test fun nullDefaultsToSummarizeAndPrune() {
        assertEquals(
            CompactionStrategy.SUMMARIZE_AND_PRUNE,
            CompactionStrategy.parseOrDefault(null),
        )
    }

    @Test fun blankAndEmptyDefaultToSummarizeAndPrune() {
        assertEquals(
            CompactionStrategy.SUMMARIZE_AND_PRUNE,
            CompactionStrategy.parseOrDefault(""),
        )
        assertEquals(
            CompactionStrategy.SUMMARIZE_AND_PRUNE,
            CompactionStrategy.parseOrDefault("   "),
        )
        assertEquals(
            CompactionStrategy.SUMMARIZE_AND_PRUNE,
            CompactionStrategy.parseOrDefault("\n\t"),
        )
    }

    @Test fun summarizeAndPruneAliasesAllResolveToSameEnum() {
        // Pin the canonical + spelling-variant + "default" aliases.
        // British/American spelling parity matters because the kdoc
        // explicitly accepts both ("summarize" vs "summarise") so that
        // an agent / user / config in either dialect doesn't silently
        // fall through to default and waste a summary round-trip.
        for (alias in listOf("summarize_and_prune", "summarise_and_prune", "default")) {
            assertEquals(
                CompactionStrategy.SUMMARIZE_AND_PRUNE,
                CompactionStrategy.parseOrDefault(alias),
                "alias '$alias' must resolve to SUMMARIZE_AND_PRUNE",
            )
        }
    }

    @Test fun pruneOnlyAliasesAllResolveToSameEnum() {
        // Pin the canonical + short alias + descriptive alias for the
        // skip-summary path. Each is intentional; the agent might pick
        // any depending on which prompt nudge it just read.
        for (alias in listOf("prune_only", "prune", "no_summary")) {
            assertEquals(
                CompactionStrategy.PRUNE_ONLY,
                CompactionStrategy.parseOrDefault(alias),
                "alias '$alias' must resolve to PRUNE_ONLY",
            )
        }
    }

    @Test fun parsingIsCaseInsensitive() {
        // Pin: kdoc says "case-insensitive". The agent might shout-case
        // the value coming from a JSON Schema enum.
        for (alias in listOf("PRUNE_ONLY", "Prune_Only", "PrUnE_OnLy")) {
            assertEquals(
                CompactionStrategy.PRUNE_ONLY,
                CompactionStrategy.parseOrDefault(alias),
                "alias '$alias' must resolve case-insensitively",
            )
        }
        for (alias in listOf("SUMMARIZE_AND_PRUNE", "SuMmArIzE_aNd_PrUnE")) {
            assertEquals(
                CompactionStrategy.SUMMARIZE_AND_PRUNE,
                CompactionStrategy.parseOrDefault(alias),
            )
        }
    }

    @Test fun hyphensAndUnderscoresAreInterchangeable() {
        // Pin: kdoc says "underscore- or hyphen-separated". The agent
        // might prefer kebab-case for human readability while the enum
        // is snake_case in code.
        assertEquals(
            CompactionStrategy.PRUNE_ONLY,
            CompactionStrategy.parseOrDefault("prune-only"),
        )
        assertEquals(
            CompactionStrategy.PRUNE_ONLY,
            CompactionStrategy.parseOrDefault("no-summary"),
        )
        assertEquals(
            CompactionStrategy.SUMMARIZE_AND_PRUNE,
            CompactionStrategy.parseOrDefault("summarize-and-prune"),
        )
    }

    @Test fun whitespaceIsTrimmedBeforeParsing() {
        // Pin: kdoc-implicit `trim()` step. Agent / config might emit
        // leading/trailing whitespace from JSON envelope formatting.
        assertEquals(
            CompactionStrategy.PRUNE_ONLY,
            CompactionStrategy.parseOrDefault("  prune_only  "),
        )
        assertEquals(
            CompactionStrategy.PRUNE_ONLY,
            CompactionStrategy.parseOrDefault("\nprune\t"),
        )
    }

    @Test fun unknownValuesDefaultToSummarizeAndPruneNotThrow() {
        // The load-bearing safety contract: typo / unknown value falls
        // through to the safe-summary path, never silently skipping
        // the summary OR throwing. Pin so a future
        // "fail-fast on unknown" refactor catches this assertion.
        for (typo in listOf("summarise", "summary", "compact", "all", "pruneonly", "garbage")) {
            assertEquals(
                CompactionStrategy.SUMMARIZE_AND_PRUNE,
                CompactionStrategy.parseOrDefault(typo),
                "unknown value '$typo' must default to SUMMARIZE_AND_PRUNE (safe path), not throw",
            )
        }
    }

    @Test fun enumHasExactlyTwoVariants() {
        // Pin the binary toggle. If a third variant lands (e.g.
        // SUMMARIZE_ONLY), the parser needs another alias group; this
        // assertion fails fast and forces the parser update before
        // shipping.
        assertEquals(2, CompactionStrategy.values().size)
    }
}
