package io.talevia.core.tool.builtin.aigc

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [CompareAigcCandidatesTool]. Uses a [FakeAigcTool] registered
 * under a real AIGC id (`generate_image`) so the whitelist accepts it —
 * we're testing fan-out / parallel dispatch / error capture, not the
 * whitelist itself.
 */
class CompareAigcCandidatesToolTest {

    @Serializable data class FakeInput(
        val model: String = "default",
        val prompt: String = "",
    )

    @Serializable data class FakeOutput(
        val assetId: String,
        val modelId: String,
    )

    /**
     * Minimal Tool double. Each call records the received `model` so tests
     * can verify the fan-out injected the right value per candidate. A
     * `failOnModel` list triggers a thrown exception for matching models —
     * exercises CompareAigcCandidatesTool's per-candidate error capture.
     */
    private class FakeAigcTool(
        override val id: String = "generate_image",
        val failOnModel: Set<String> = emptySet(),
    ) : Tool<FakeInput, FakeOutput> {
        val receivedModels = mutableListOf<String>()
        override val helpText: String = "fake"
        override val inputSerializer: KSerializer<FakeInput> = FakeInput.serializer()
        override val outputSerializer: KSerializer<FakeOutput> = FakeOutput.serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("model") { put("type", "string") }
                putJsonObject("prompt") { put("type", "string") }
            }
            put("required", JsonArray(emptyList()))
        }

        override suspend fun execute(input: FakeInput, ctx: ToolContext): ToolResult<FakeOutput> {
            receivedModels += input.model
            if (input.model in failOnModel) {
                error("simulated failure for model '${input.model}'")
            }
            return ToolResult(
                title = "fake",
                outputForLlm = "fake asset for ${input.model}",
                data = FakeOutput(assetId = "asset-${input.model}", modelId = input.model),
            )
        }
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun compare(registry: ToolRegistry): CompareAigcCandidatesTool =
        CompareAigcCandidatesTool(registry)

    @Test fun twoModelsFanOutAndBothSucceed() = runTest {
        val registry = ToolRegistry()
        val fake = FakeAigcTool()
        registry.register(fake)

        val out = compare(registry).execute(
            CompareAigcCandidatesTool.Input(
                toolId = "generate_image",
                baseInput = buildJsonObject { put("prompt", "a tree") },
                models = listOf("sdxl", "flux-dev"),
            ),
            ctx(),
        ).data

        assertEquals("generate_image", out.toolId)
        assertEquals(2, out.candidates.size)
        assertEquals(2, out.successCount)
        assertEquals(0, out.errorCount)
        assertEquals(setOf("sdxl", "flux-dev"), out.candidates.map { it.modelId }.toSet())
        assertEquals("asset-sdxl", out.candidates.first { it.modelId == "sdxl" }.assetId)
        assertEquals("asset-flux-dev", out.candidates.first { it.modelId == "flux-dev" }.assetId)
        // Both candidates dispatched with the right model.
        assertTrue("sdxl" in fake.receivedModels)
        assertTrue("flux-dev" in fake.receivedModels)
    }

    @Test fun oneModelFailsOthersStillReturn() = runTest {
        val registry = ToolRegistry()
        val fake = FakeAigcTool(failOnModel = setOf("broken"))
        registry.register(fake)

        val out = compare(registry).execute(
            CompareAigcCandidatesTool.Input(
                toolId = "generate_image",
                baseInput = buildJsonObject { put("prompt", "a tree") },
                models = listOf("sdxl", "broken", "flux-dev"),
            ),
            ctx(),
        ).data

        assertEquals(3, out.candidates.size)
        assertEquals(2, out.successCount)
        assertEquals(1, out.errorCount)
        val broken = out.candidates.single { it.modelId == "broken" }
        assertNotNull(broken.error)
        assertTrue(broken.error!!.contains("simulated failure"))
        assertNull(broken.assetId)
        assertNull(broken.output)
        // Siblings survive.
        val sdxl = out.candidates.single { it.modelId == "sdxl" }
        assertEquals("asset-sdxl", sdxl.assetId)
        assertNull(sdxl.error)
    }

    @Test fun unknownToolIdRejected() = runTest {
        val registry = ToolRegistry()
        val ex = assertFailsWith<IllegalArgumentException> {
            compare(registry).execute(
                CompareAigcCandidatesTool.Input(
                    toolId = "fs_read",
                    baseInput = buildJsonObject { },
                    models = listOf("x"),
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("is not an AIGC comparison target"), ex.message)
    }

    @Test fun emptyModelsListRejected() = runTest {
        val registry = ToolRegistry()
        registry.register(FakeAigcTool())
        val ex = assertFailsWith<IllegalArgumentException> {
            compare(registry).execute(
                CompareAigcCandidatesTool.Input(
                    toolId = "generate_image",
                    baseInput = buildJsonObject { },
                    models = emptyList(),
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("non-empty"), ex.message)
    }

    @Test fun duplicateModelsRejected() = runTest {
        val registry = ToolRegistry()
        registry.register(FakeAigcTool())
        val ex = assertFailsWith<IllegalArgumentException> {
            compare(registry).execute(
                CompareAigcCandidatesTool.Input(
                    toolId = "generate_image",
                    baseInput = buildJsonObject { },
                    models = listOf("sdxl", "sdxl"),
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("distinct"), ex.message)
    }

    @Test fun baseInputContainingModelRejected() = runTest {
        // Guardrail: callers must not include a `model` field in baseInput —
        // compare_aigc_candidates injects one per candidate, and leaving a
        // stale base value would be confusing (is it the default, a tie-
        // breaker, overridden for all?). Fail loud.
        val registry = ToolRegistry()
        registry.register(FakeAigcTool())
        val ex = assertFailsWith<IllegalArgumentException> {
            compare(registry).execute(
                CompareAigcCandidatesTool.Input(
                    toolId = "generate_image",
                    baseInput = buildJsonObject {
                        put("prompt", "a tree")
                        put("model", "sneak-in")
                    },
                    models = listOf("a", "b"),
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("'model' field"), ex.message)
    }

    @Test fun allowedToolIdButNotRegisteredFailsLoud() = runTest {
        // generate_music is in ALLOWED_TOOL_IDS but not registered here —
        // container-level composition guarantees could still leave gaps
        // (e.g. REPLICATE_API_TOKEN missing → no music tool registered).
        val registry = ToolRegistry()
        val ex = assertFailsWith<IllegalStateException> {
            compare(registry).execute(
                CompareAigcCandidatesTool.Input(
                    toolId = "generate_music",
                    baseInput = buildJsonObject { put("prompt", "a jam") },
                    models = listOf("musicgen-melody"),
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("not registered"), ex.message)
    }

    @Test fun singleModelIsDegenerateButShipsFine() = runTest {
        // A degenerate "A/B" with only one candidate still dispatches and
        // returns — the bullet doesn't require ≥2 models, and requiring
        // so would force callers to pick a second via their own heuristic.
        val registry = ToolRegistry()
        val fake = FakeAigcTool()
        registry.register(fake)
        val out = compare(registry).execute(
            CompareAigcCandidatesTool.Input(
                toolId = "generate_image",
                baseInput = buildJsonObject { put("prompt", "a tree") },
                models = listOf("sdxl"),
            ),
            ctx(),
        ).data
        assertEquals(1, out.candidates.size)
        assertEquals(1, out.successCount)
        assertEquals("asset-sdxl", out.candidates.single().assetId)
    }
}
