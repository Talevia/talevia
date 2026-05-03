package io.talevia.core.tool.builtin.project.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [IntentClassifier] —
 * `core/tool/builtin/project/template/IntentClassifier.kt`.
 * Deterministic keyword-bag classifier that maps a free-
 * form user intent to one of 5 genre template ids.
 * Cycle 194 audit: 87 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Zero-match fallback is `"narrative"` with `score=0`
 *    and a "no signal" reason.** Per kdoc: novice intents
 *    without obvious keywords get the most general
 *    template. Drift to "throw on no-match" or different
 *    fallback would change the no-signal UX.
 *
 * 2. **Tie-break order: `templateOrder = [narrative, vlog,
 *    ad, musicmv, tutorial]`.** When two genres tie on
 *    score, the earlier in the list wins (most general
 *    first). Drift would silently re-route ambiguous
 *    intents.
 *
 * 3. **Substring matching is lowercase + NOT word-boundary
 *    tokenised.** Per kdoc: "matched against the intent
 *    text directly (not word-boundary tokenised) so 'music
 *    video' and 'how-to' survive hyphenation / punctuation
 *    quirks." Plus the documented edge case: musicmv's
 *    " mv" pattern has a LEADING SPACE so it doesn't match
 *    "movement" / "removed" — drift would silently
 *    over-classify.
 */
class IntentClassifierTest {

    private fun classify(intent: String): IntentClassifier.Classification =
        IntentClassifier.classify(intent)

    // ── Zero-match fallback ─────────────────────────────────

    @Test fun emptyIntentReturnsNarrativeWithNoSignalReason() {
        val result = classify("")
        assertEquals("narrative", result.template)
        assertEquals(0, result.score)
        assertTrue(
            "no genre keywords" in result.reason,
            "reason cites no-signal; got: ${result.reason}",
        )
        assertTrue(
            "defaulting to narrative" in result.reason,
            "reason cites default; got: ${result.reason}",
        )
        assertTrue(
            "override by setting template explicitly" in result.reason,
            "reason cites override hint; got: ${result.reason}",
        )
    }

    @Test fun intentWithNoMatchingKeywordsReturnsNarrative() {
        val result = classify("just make something")
        assertEquals("narrative", result.template)
        assertEquals(0, result.score)
    }

    // ── Per-genre keyword matching ──────────────────────────

    @Test fun narrativeKeywordsClassifyAsNarrative() {
        // Pin: each documented narrative keyword matches.
        val keywords = listOf("story", "short film", "screenplay", "scene", "plot", "drama", "fiction")
        for (kw in keywords) {
            val result = classify("Make a $kw piece")
            assertEquals(
                "narrative",
                result.template,
                "intent with '$kw' classifies as narrative; got: $result",
            )
            assertTrue(result.score >= 1, "score >= 1 for matching keyword")
        }
    }

    @Test fun vlogKeywordsClassifyAsVlog() {
        val keywords = listOf("vlog", "daily life", "day in the life", "diary", "travel", "lifestyle")
        for (kw in keywords) {
            val result = classify("I want a $kw video")
            assertEquals(
                "vlog",
                result.template,
                "intent with '$kw' classifies as vlog; got: $result",
            )
        }
    }

    @Test fun adKeywordsClassifyAsAd() {
        val keywords = listOf("commercial", "advert", "product", "campaign", "promo", "marketing")
        for (kw in keywords) {
            val result = classify("Create a $kw piece")
            assertEquals("ad", result.template, "intent with '$kw' classifies as ad")
        }
    }

    @Test fun musicmvKeywordsClassifyAsMusicMv() {
        val keywords = listOf("music video", "song", "lyric", "band", "artist", "track")
        for (kw in keywords) {
            val result = classify("Build a $kw thing")
            assertEquals(
                "musicmv",
                result.template,
                "intent with '$kw' classifies as musicmv; got: $result",
            )
        }
    }

    @Test fun tutorialKeywordsClassifyAsTutorial() {
        val keywords = listOf("tutorial", "how to", "how-to", "guide", "walkthrough", "teach", "learn")
        for (kw in keywords) {
            val result = classify("Make a $kw video")
            assertEquals(
                "tutorial",
                result.template,
                "intent with '$kw' classifies as tutorial; got: $result",
            )
        }
    }

    // ── Lowercase normalisation ─────────────────────────────

    @Test fun matchingIsCaseInsensitive() {
        // Pin: per `intent.lowercase()`, match is
        // case-insensitive.
        assertEquals("narrative", classify("STORY").template)
        assertEquals("narrative", classify("Story").template)
        assertEquals("vlog", classify("VLOG").template)
        assertEquals("musicmv", classify("Music Video").template)
        assertEquals("tutorial", classify("HOW TO").template)
    }

    // ── Substring (NOT word-boundary) matching ──────────────

    @Test fun substringMatchSurvivesHyphenation() {
        // Pin: per kdoc "matched against the intent text
        // directly (not word-boundary tokenised) so
        // 'music video' and 'how-to' survive hyphenation".
        // The intent "make a how-to" matches `how-to` AND
        // `how to` (with substring "how t" in "how-to" — wait,
        // actually "how to" wouldn't match "how-to". Let me re-think.
        //
        // The bags include both "how to" AND "how-to" so
        // both phrasings classify. Pin BOTH variants
        // resolve to tutorial.
        assertEquals("tutorial", classify("how to make X").template)
        assertEquals("tutorial", classify("how-to make X").template)
        // Same idea for music-video / music video.
        assertEquals("musicmv", classify("a music video").template)
        assertEquals("musicmv", classify("a music-video").template)
    }

    // ── Marquee leading-space pattern: " mv" ─────────────────

    @Test fun musicmvLeadingSpacePatternDoesNotOverMatch() {
        // Marquee leading-space pin: per kdoc, musicmv's
        // " mv" pattern has a LEADING SPACE so it doesn't
        // match "movement" / "removed". Drift to "mv"
        // without space would silently over-classify.
        // "remove" → contains "mv" substring without
        // leading space → should NOT match musicmv.
        val resultRemove = classify("remove the dust")
        // "movement" → contains "mv" but NOT " mv".
        val resultMovement = classify("show movement")
        // Both should NOT classify as musicmv (no other
        // matches → fall back to narrative).
        assertEquals(
            "narrative",
            resultRemove.template,
            "'remove' contains 'mv' substring but NOT ' mv' (leading space); should not match musicmv",
        )
        assertEquals(
            "narrative",
            resultMovement.template,
            "'movement' contains 'mv' substring but NOT ' mv' (leading space); should not match musicmv",
        )
    }

    @Test fun musicmvLeadingSpacePatternDoesMatchWithSpace() {
        // Pin: " mv" WITH leading space DOES match.
        // E.g. "make a mv" classifies as musicmv (because
        // " mv" appears with the space before).
        assertEquals(
            "musicmv",
            classify("make a mv quickly").template,
            "intent with ' mv' (preceded by space) matches musicmv",
        )
    }

    // ── Tie-break order ──────────────────────────────────────

    @Test fun tieBreakOrderIsNarrativeVlogAdMusicmvTutorial() {
        // Marquee tie-break pin: per impl
        // `templateOrder.first { scores[it] == max }`,
        // when 2+ genres tie, the earlier in the list
        // wins. The order is [narrative, vlog, ad,
        // musicmv, tutorial] — most general first.
        //
        // Construct an intent with one keyword from EACH
        // bag (each genre scores 1) → narrative wins on
        // tie-break.
        val tieIntent = "story vlog product song tutorial"
        val result = classify(tieIntent)
        assertEquals(
            "narrative",
            result.template,
            "5-way tie → narrative wins (first in templateOrder); got: $result",
        )
    }

    @Test fun tieBreakBetweenVlogAndAdPicksVlog() {
        // Pin: vlog (#2) beats ad (#3) on tie.
        val result = classify("vlog product")
        assertEquals(
            "vlog",
            result.template,
            "vlog/ad tie → vlog wins; got: $result",
        )
    }

    @Test fun tieBreakBetweenAdAndMusicmvPicksAd() {
        // Pin: ad (#3) beats musicmv (#4) on tie.
        val result = classify("product song")
        assertEquals(
            "ad",
            result.template,
            "ad/musicmv tie → ad wins; got: $result",
        )
    }

    @Test fun tieBreakBetweenMusicmvAndTutorialPicksMusicmv() {
        // Pin: musicmv (#4) beats tutorial (#5) on tie.
        val result = classify("song tutorial")
        assertEquals(
            "musicmv",
            result.template,
            "musicmv/tutorial tie → musicmv wins; got: $result",
        )
    }

    // ── Higher score beats tie-break order ──────────────────

    @Test fun higherScoreBeatsTieBreakOrder() {
        // Pin: scoring is BY COUNT first, tie-break order
        // SECOND. Two ad keywords beats one narrative
        // keyword.
        val result = classify("story product brand campaign")
        // narrative: 1 (story)
        // ad: 3 (product, brand, campaign)
        assertEquals("ad", result.template, "score 3 beats score 1 across tie-break order")
        assertEquals(3, result.score)
    }

    // ── Reason message format ───────────────────────────────

    @Test fun reasonCitesMatchedKeywordCountAndList() {
        val result = classify("story scene plot drama")
        // narrative bag has all 4 — score 4.
        assertEquals(4, result.score)
        assertTrue(
            "matched 4 'narrative' keyword(s):" in result.reason,
            "reason cites count + genre; got: ${result.reason}",
        )
        // Each matched keyword cited.
        for (kw in listOf("story", "scene", "plot", "drama")) {
            assertTrue(
                kw in result.reason,
                "reason cites '$kw'; got: ${result.reason}",
            )
        }
    }

    @Test fun reasonForSingleMatchUsesSingularPhrase() {
        // Pin: keyword(s) format with `keyword(s)` —
        // pluralization is via the parenthetical not
        // genuine count adjustment. The actual count is
        // baked in.
        val result = classify("story")
        assertTrue(
            "matched 1 'narrative' keyword(s): story" in result.reason,
            "got: ${result.reason}",
        )
    }

    // ── Score field reflects max count ──────────────────────

    @Test fun scoreFieldReflectsMaxBagCount() {
        // Score = max across all bags. Drift to "winner's
        // count" would be the same value but conceptually
        // different.
        val result = classify("story plot drama vlog")
        // narrative: 3 (story, plot, drama). vlog: 1.
        // max = 3.
        assertEquals(3, result.score)
        assertEquals("narrative", result.template)
    }

    // ── No keyword match in foreign-language intent ────────

    @Test fun nonEnglishIntentReturnsNarrativeNoMatch() {
        // Pin: non-English (CJK) intent contains no
        // English keywords → falls back to narrative.
        // The classifier is intentionally English-only;
        // this is a documented limitation rather than a
        // bug.
        val result = classify("帮我做一个视频")
        assertEquals("narrative", result.template)
        assertEquals(0, result.score)
        assertTrue("no genre keywords" in result.reason)
    }

    // ── Disjoint-bag invariant smoke test ───────────────────

    @Test fun documentedKeywordsInTheSameBagAllResolveToSameGenre() {
        // Pin: keywords in the same bag don't accidentally
        // collide with another bag (drift to "song" being
        // in both vlog and musicmv would dilute the
        // signal).
        val genreToCanonicalKw = mapOf(
            "narrative" to "screenplay",
            "vlog" to "diary",
            "ad" to "advert",
            "musicmv" to "lyric",
            "tutorial" to "walkthrough",
        )
        for ((genre, kw) in genreToCanonicalKw) {
            val result = classify(kw)
            assertEquals(
                genre,
                result.template,
                "'$kw' is canonical for $genre; got: $result",
            )
        }
    }
}
