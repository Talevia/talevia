package io.talevia.core.tool.builtin.provider

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.provider.query.AigcCostEstimateRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for `provider_query(select=aigc_cost_estimate)` — the
 * agent's plan-time cost answer for an AIGC dispatch. Bridges
 * [io.talevia.core.cost.AigcPricing.estimateCents] to LLM tool calls
 * so the agent doesn't have to scale list-price strings by hand.
 */
class ProviderQueryAigcCostEstimateTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun tool(): ProviderQueryTool =
        ProviderQueryTool(
            ProviderRegistry(byId = emptyMap(), default = null),
            ProviderWarmupStats.withSupervisor(EventBus()),
            ProjectStoreTestKit.create(),
        )

    @Test fun videoEstimateScalesByDurationSeconds() = runTest {
        val out = tool().execute(
            ProviderQueryTool.Input(
                select = "aigc_cost_estimate",
                toolId = "generate_video",
                providerId = "openai",
                modelId = "sora",
                inputs = buildJsonObject { put("durationSeconds", JsonPrimitive(8)) },
            ),
            ctx(),
        ).data
        assertEquals("aigc_cost_estimate", out.select)
        assertEquals(1, out.total)
        val row = out.rows.decodeRowsAs(AigcCostEstimateRow.serializer()).single()
        // sora is 30¢/s in the table → 8s × 30¢ = 240¢.
        assertEquals(240L, row.cents)
        assertNotNull(row.priceBasis)
    }

    @Test fun imageEstimateDistinguishesSquareFromRect() = runTest {
        val sq = tool().execute(
            ProviderQueryTool.Input(
                select = "aigc_cost_estimate",
                toolId = "generate_image",
                providerId = "openai",
                modelId = "gpt-image-1",
                inputs = buildJsonObject {
                    put("width", JsonPrimitive(1024))
                    put("height", JsonPrimitive(1024))
                },
            ),
            ctx(),
        ).data
        val rect = tool().execute(
            ProviderQueryTool.Input(
                select = "aigc_cost_estimate",
                toolId = "generate_image",
                providerId = "openai",
                modelId = "gpt-image-1",
                inputs = buildJsonObject {
                    put("width", JsonPrimitive(1792))
                    put("height", JsonPrimitive(1024))
                },
            ),
            ctx(),
        ).data
        val sqRow = sq.rows.decodeRowsAs(AigcCostEstimateRow.serializer()).single()
        val rectRow = rect.rows.decodeRowsAs(AigcCostEstimateRow.serializer()).single()
        assertEquals(4L, sqRow.cents)
        assertEquals(6L, rectRow.cents)
    }

    @Test fun unknownProviderReturnsNullCentsThreeStateContract() = runTest {
        // "Unknown" must NOT collapse to 0 — the lockfile / cost rollups
        // depend on the null sentinel meaning "we don't know" vs explicit free.
        val out = tool().execute(
            ProviderQueryTool.Input(
                select = "aigc_cost_estimate",
                toolId = "generate_image",
                providerId = "unknown-provider",
                modelId = "gpt-image-1",
                inputs = buildJsonObject {
                    put("width", JsonPrimitive(1024))
                    put("height", JsonPrimitive(1024))
                },
            ),
            ctx(),
        )
        val row = out.data.rows.decodeRowsAs(AigcCostEstimateRow.serializer()).single()
        assertNull(row.cents, "unknown provider must keep cents=null, not collapse to 0")
        assertTrue("summary must distinguish unknown from free: ${out.outputForLlm}") {
            out.outputForLlm.contains("No pricing rule")
        }
    }

    @Test fun missingToolIdFailsLoud() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            tool().execute(
                ProviderQueryTool.Input(
                    select = "aigc_cost_estimate",
                    providerId = "openai",
                    modelId = "gpt-image-1",
                    inputs = buildJsonObject { put("width", JsonPrimitive(1024)) },
                ),
                ctx(),
            )
        }
        assertTrue("error must name toolId: ${ex.message}") {
            ex.message?.contains("toolId") == true
        }
    }

    @Test fun missingModelIdFailsLoud() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            tool().execute(
                ProviderQueryTool.Input(
                    select = "aigc_cost_estimate",
                    toolId = "generate_image",
                    providerId = "openai",
                    inputs = buildJsonObject { put("width", JsonPrimitive(1024)) },
                ),
                ctx(),
            )
        }
        assertTrue("error must name modelId: ${ex.message}") {
            ex.message?.contains("modelId") == true
        }
    }

    @Test fun toolIdRejectedOnOtherSelects() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            tool().execute(
                ProviderQueryTool.Input(
                    select = "providers",
                    toolId = "generate_image",
                ),
                ctx(),
            )
        }
        assertTrue("error must name aigc_cost_estimate: ${ex.message}") {
            ex.message?.contains("aigc_cost_estimate") == true
        }
    }

    @Test fun ttsEstimateScalesByCharacterCount() = runTest {
        // tts-1 is $0.015 / 1k chars → 0.0015 cents/char.
        // 10000 chars × 0.0015 = 15 cents (rounded).
        val out = tool().execute(
            ProviderQueryTool.Input(
                select = "aigc_cost_estimate",
                toolId = "synthesize_speech",
                providerId = "openai",
                modelId = "tts-1",
                inputs = buildJsonObject { put("text", JsonPrimitive("a".repeat(10_000))) },
            ),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(AigcCostEstimateRow.serializer()).single()
        assertEquals(15L, row.cents)
    }
}
