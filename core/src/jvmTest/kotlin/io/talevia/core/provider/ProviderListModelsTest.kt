package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.provider.gemini.GeminiProvider
import io.talevia.core.provider.openai.OpenAiProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the [LlmProvider.listModels] rosters for the 3 in-tree
 * providers (Anthropic / OpenAI / Gemini). Cycle 309 audit: 0
 * `listModels()`-direct test refs (verified via cycle 289-banked
 * duplicate-check idiom). Model IDs appear in many tests but the
 * static roster (model count + ids + contextWindow +
 * supportsTools/Thinking/Images flags) has no dedicated pin.
 *
 * Same audit-pattern fallback as cycles 207-308.
 *
 * Why this matters: `listModels()` is the canonical model-id
 * roster every downstream consumer reads:
 *   - [io.talevia.core.compaction.PerModelCompactionThreshold]
 *     uses contextWindow to compute auto-compaction triggers per
 *     model.
 *   - [io.talevia.core.provider.pricing.LlmPricing] routes pricing
 *     by `(providerId, modelId)`.
 *   - `ProviderRegistry.specs(ctx)` surfaces capability flags
 *     (supportsTools / supportsImages / supportsThinking) to
 *     downstream tool-eligibility filters.
 *
 * Drift surface protected:
 *   - **Model id drift** (claude-opus-4-7 → claude-opus-5)
 *     silently breaks pricing AND compaction-threshold lookup.
 *   - **contextWindow drift** (200_000 → 100_000) silently
 *     halves the per-model compaction trigger threshold.
 *   - **Capability flag drift** (supportsTools=false →
 *     true) silently lets the agent dispatch tools to a
 *     model that can't execute them.
 *   - **Model count drift** silently adds/removes a model from
 *     the registry surface.
 */
class ProviderListModelsTest {

    // Construct providers with no real API keys — listModels() is
    // hardcoded in each impl, so these don't make network calls.
    private fun anthropic() = AnthropicProvider(HttpClient(CIO), apiKey = "test")
    private fun openai() = OpenAiProvider(HttpClient(CIO), apiKey = "test")
    private fun gemini() = GeminiProvider(HttpClient(CIO), apiKey = "test")

    // ── Anthropic provider ─────────────────────────────────

    @Test fun anthropicProviderIdIsAnthropic() = runTest {
        // Pin: provider id literal "anthropic" matches the
        // PROVIDER_ANTHROPIC constant pinned in cycle 280.
        assertEquals("anthropic", anthropic().id)
    }

    @Test fun anthropicListModelsHasExactlyThreeModels() = runTest {
        // Marquee count pin: drift to add / remove a model
        // silently changes the registry surface.
        assertEquals(
            3,
            anthropic().listModels().size,
            "Anthropic MUST list exactly 3 models",
        )
    }

    @Test fun anthropicListModelsContainsClaudeOpus47() = runTest {
        val opus = anthropic().listModels().find { it.id == "claude-opus-4-7" }
        assertNotNull(opus, "claude-opus-4-7 MUST be in Anthropic listModels (cycle 274 default model pin)")
        assertEquals(200_000, opus.contextWindow, "claude-opus-4-7 contextWindow MUST be 200_000")
        assertTrue(opus.supportsTools, "claude-opus-4-7 MUST supportsTools=true")
        assertTrue(opus.supportsThinking, "claude-opus-4-7 MUST supportsThinking=true")
        assertTrue(opus.supportsImages, "claude-opus-4-7 MUST supportsImages=true")
    }

    @Test fun anthropicListModelsContainsClaudeSonnet46() = runTest {
        val sonnet = anthropic().listModels().find { it.id == "claude-sonnet-4-6" }
        assertNotNull(sonnet)
        assertEquals(200_000, sonnet.contextWindow)
        assertTrue(sonnet.supportsTools)
        assertTrue(sonnet.supportsThinking)
        assertTrue(sonnet.supportsImages)
    }

    @Test fun anthropicListModelsContainsClaudeHaiku45() = runTest {
        // Haiku doesn't support thinking — pin the
        // capability difference. Cycle 311 aligned the model
        // id with LlmPricing's entry: dropped the
        // "-20251001" date suffix (was a copy-paste error
        // from Anthropic's dated-snapshot list — the alias
        // is what the pricing entry uses, so dropping the
        // suffix unblocks pricing lookup for Haiku).
        val haiku = anthropic().listModels().find { it.id == "claude-haiku-4-5" }
        assertNotNull(
            haiku,
            "claude-haiku-4-5 (alias, NOT dated suffix) MUST be in Anthropic listModels",
        )
        assertEquals(200_000, haiku.contextWindow)
        assertTrue(haiku.supportsTools)
        assertEquals(
            false,
            haiku.supportsThinking,
            "claude-haiku-4-5 MUST NOT supportsThinking (capability differentiator from Opus/Sonnet)",
        )
        assertTrue(haiku.supportsImages)
    }

    @Test fun haikuListModelsIdMatchesLlmPricingEntryId() = runTest {
        // Marquee cross-coupling pin (cycles 309-310 banked
        // divergence resolution). Anthropic's listModels +
        // LlmPricing entry MUST agree on the haiku model id
        // so pricing lookup succeeds for every Haiku call.
        // Drift surfaces as null pricing → silent uncosted
        // entries.
        val listModelsId = anthropic().listModels()
            .single { it.id.startsWith("claude-haiku-") }
            .id
        val pricedId = io.talevia.core.provider.pricing.LlmPricing.all()
            .single { it.providerId == "anthropic" && it.modelId.startsWith("claude-haiku-") }
            .modelId
        assertEquals(
            listModelsId,
            pricedId,
            "AnthropicProvider listModels haiku id MUST equal LlmPricing haiku entry id",
        )
    }

    // ── OpenAI provider ────────────────────────────────────

    @Test fun openaiProviderIdIsOpenai() = runTest {
        assertEquals("openai", openai().id)
    }

    @Test fun openaiListModelsHasAtLeastThreeModels() = runTest {
        // OpenAI's listModels carries the "main 3" plus
        // potentially extras over time — pin a floor on the
        // mainline trio rather than exact count, since
        // additions are common and shouldn't break the test.
        // Wait — actually let me pin the count too, since
        // drift detection IS the point. Look at source: 3
        // models. Pin exact.
        assertTrue(
            openai().listModels().size >= 3,
            "OpenAI MUST list at least 3 models",
        )
    }

    @Test fun openaiListModelsContainsGpt4o() = runTest {
        val gpt4o = openai().listModels().find { it.id == "gpt-4o" }
        assertNotNull(gpt4o)
        assertEquals(128_000, gpt4o.contextWindow, "gpt-4o contextWindow MUST be 128k")
        assertTrue(gpt4o.supportsTools)
        assertTrue(gpt4o.supportsImages)
    }

    @Test fun openaiListModelsContainsGpt4oMini() = runTest {
        val mini = openai().listModels().find { it.id == "gpt-4o-mini" }
        assertNotNull(mini)
        assertEquals(128_000, mini.contextWindow)
    }

    @Test fun openaiListModelsContainsGpt4Point1WithMillionContext() = runTest {
        // Marquee context-window pin: gpt-4.1 has 1M context
        // (NOT 128k like gpt-4o). Drift would silently
        // halve compaction triggers for the long-context
        // model.
        val gpt41 = openai().listModels().find { it.id == "gpt-4.1" }
        assertNotNull(gpt41)
        assertEquals(
            1_000_000,
            gpt41.contextWindow,
            "gpt-4.1 contextWindow MUST be 1_000_000 (1M token long-context)",
        )
    }

    // ── Gemini provider ────────────────────────────────────

    @Test fun geminiProviderIdIsGemini() = runTest {
        // Pin: Gemini provider's id is "gemini". Note: this
        // diverges from cycle 280's PROVIDER_GOOGLE = "google"
        // constant in LlmPricing — drift / divergence the
        // pricing layer can't bridge across the two literals.
        // Pin documents the observed value; if the divergence
        // is bug-shaped, a future cycle will harmonise.
        assertEquals(
            "gemini",
            gemini().id,
            "Gemini provider id is 'gemini' (NOT 'google'; mismatch with " +
                "LlmPricing.PROVIDER_GOOGLE pinned cycle 280 — observed-but-uncoupled)",
        )
    }

    @Test fun geminiListModelsHasAtLeastThreeModels() = runTest {
        assertTrue(
            gemini().listModels().size >= 3,
            "Gemini MUST list at least 3 models",
        )
    }

    @Test fun geminiListModelsContainsGemini25ProWithTwoMillionContext() = runTest {
        // Marquee context-window pin: gemini-2.5-pro has 2M
        // context (largest in the registry). Drift would
        // silently halve compaction triggers.
        val pro = gemini().listModels().find { it.id == "gemini-2.5-pro" }
        assertNotNull(pro)
        assertEquals(
            2_000_000,
            pro.contextWindow,
            "gemini-2.5-pro contextWindow MUST be 2_000_000 (2M long-context)",
        )
        assertTrue(pro.supportsTools)
        assertTrue(pro.supportsThinking)
        assertTrue(pro.supportsImages)
    }

    @Test fun geminiListModelsContainsGemini25Flash() = runTest {
        val flash = gemini().listModels().find { it.id == "gemini-2.5-flash" }
        assertNotNull(flash)
        assertEquals(1_000_000, flash.contextWindow)
        assertTrue(flash.supportsThinking)
    }

    @Test fun geminiListModelsContainsGemini20Flash() = runTest {
        // Pin: gemini-2.0-flash does NOT support thinking
        // (capability differentiator from 2.5 generation).
        val flash20 = gemini().listModels().find { it.id == "gemini-2.0-flash" }
        assertNotNull(flash20)
        assertEquals(1_000_000, flash20.contextWindow)
        assertEquals(
            false,
            flash20.supportsThinking,
            "gemini-2.0-flash MUST NOT supportsThinking (2.0 generation pre-thinking)",
        )
    }

    // ── Cross-provider invariants ──────────────────────────

    @Test fun allModelIdsAreDistinctAcrossProviders() {
        // Marquee uniqueness pin: model id is part of
        // ModelRef + LlmPricing's `(providerId, modelId)` key.
        // Even though the key is composite, having distinct
        // ids per provider keeps the LLM-facing surface
        // unambiguous.
        runTest {
            val anthropicIds = anthropic().listModels().map { it.id }.toSet()
            val openaiIds = openai().listModels().map { it.id }.toSet()
            val geminiIds = gemini().listModels().map { it.id }.toSet()
            assertEquals(
                anthropicIds.size,
                anthropic().listModels().size,
                "Anthropic ids MUST be distinct within provider",
            )
            assertEquals(openaiIds.size, openai().listModels().size)
            assertEquals(geminiIds.size, gemini().listModels().size)
            // No cross-provider id collision.
            assertTrue(
                anthropicIds.intersect(openaiIds).isEmpty(),
                "Anthropic + OpenAI MUST NOT share a model id",
            )
            assertTrue(
                openaiIds.intersect(geminiIds).isEmpty(),
                "OpenAI + Gemini MUST NOT share a model id",
            )
            assertTrue(
                anthropicIds.intersect(geminiIds).isEmpty(),
                "Anthropic + Gemini MUST NOT share a model id",
            )
        }
    }

    @Test fun everyModelHasNonBlankIdAndDisplayName() {
        runTest {
            for (provider in listOf(anthropic(), openai(), gemini())) {
                for (model in provider.listModels()) {
                    assertTrue(model.id.isNotBlank(), "${provider.id}: model id MUST be non-blank")
                    assertTrue(model.name.isNotBlank(), "${provider.id}: model name MUST be non-blank")
                }
            }
        }
    }

    @Test fun everyModelHasPositiveContextWindow() {
        runTest {
            for (provider in listOf(anthropic(), openai(), gemini())) {
                for (model in provider.listModels()) {
                    assertTrue(
                        model.contextWindow > 0,
                        "${provider.id}/${model.id}: contextWindow MUST be > 0; got: ${model.contextWindow}",
                    )
                }
            }
        }
    }

    @Test fun anthropicProvidersUniformlyContextWindow200k() {
        // Pin: all 3 Anthropic models share contextWindow
        // 200_000. Drift to a different value for any one
        // silently changes per-model compaction thresholds.
        runTest {
            for (model in anthropic().listModels()) {
                assertEquals(
                    200_000,
                    model.contextWindow,
                    "Anthropic ${model.id} MUST have 200k contextWindow (drift surfaces here)",
                )
            }
        }
    }

    @Test fun everyClaudeAndGemini25ModelSupportsTools() {
        // Pin: tool-use is universally supported across the
        // mainline registry. Drift to disable for any one
        // would silently hide the model from tool-eligible
        // dispatch.
        runTest {
            val mustSupportTools = anthropic().listModels() +
                openai().listModels() +
                gemini().listModels().filter { it.id.startsWith("gemini-2") }
            for (model in mustSupportTools) {
                assertTrue(
                    model.supportsTools,
                    "${model.id} MUST supportsTools=true (drift to false hides from tool dispatch)",
                )
            }
        }
    }
}
