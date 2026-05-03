package io.talevia.core.tool.builtin.aigc

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `debt-aigc-tool-consolidation-phase3c-2-drop-tool-interface` (cycle 40).
 * JVM-test-only adapter that re-exposes an `AigcGenerator` as a
 * `Tool<I, O>` for the e2e tests still doing
 * `registry["generate_image"]!!.dispatch(jsonInput, ctx)`. Production
 * post-cycle-27 phase 2 deregistered the 4 AIGC `Tool<I, O>` shells
 * from the LLM-facing surface; the underlying classes kept the
 * interface only because these tests required by-id registry lookup.
 *
 * Phase 3c-2 drops `Tool<I, O>` from the production classes entirely —
 * the surface area that matters (id / inputSchema / helpText /
 * permission / applicability / serializers) lived only for those test
 * sites, which now wire it through this shim. Keeping it in `jvmTest`
 * makes the test-infra tax visible: any future shim consumer is by
 * definition a test (production has only one entry point now —
 * `aigc_generate(kind=...)`).
 *
 * The shim's `inputSchema` is a vacuous `{ "type": "object" }` because
 * none of the current test sites consume the schema for assertions.
 * If a future test wants to assert the LLM-facing schema, that
 * assertion belongs against `AigcGenerateTool` directly (the unified
 * dispatcher), not against this legacy shim.
 */
internal class AigcLegacyToolShim<I : Any, O : Any>(
    private val toolId: String,
    override val inputSerializer: KSerializer<I>,
    override val outputSerializer: KSerializer<O>,
    private val executeFn: suspend (I, ToolContext) -> ToolResult<O>,
) : Tool<I, O> {
    override val id: String get() = toolId
    override val helpText: String = "AIGC legacy test shim — exposes a generator as Tool<I, O> for registry-by-id call sites in e2e tests."
    override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override suspend fun execute(input: I, ctx: ToolContext): ToolResult<O> = executeFn(input, ctx)
}

internal fun toolShimForImage(gen: ImageAigcGenerator): Tool<AigcImageGenerator.Input, AigcImageGenerator.Output> =
    AigcLegacyToolShim(
        toolId = "generate_image",
        inputSerializer = AigcImageGenerator.Input.serializer(),
        outputSerializer = AigcImageGenerator.Output.serializer(),
    ) { input, ctx -> gen.generate(input, ctx) }

internal fun toolShimForVideo(gen: VideoAigcGenerator): Tool<AigcVideoGenerator.Input, AigcVideoGenerator.Output> =
    AigcLegacyToolShim(
        toolId = "generate_video",
        inputSerializer = AigcVideoGenerator.Input.serializer(),
        outputSerializer = AigcVideoGenerator.Output.serializer(),
    ) { input, ctx -> gen.generate(input, ctx) }

internal fun toolShimForMusic(gen: MusicAigcGenerator): Tool<AigcMusicGenerator.Input, AigcMusicGenerator.Output> =
    AigcLegacyToolShim(
        toolId = "generate_music",
        inputSerializer = AigcMusicGenerator.Input.serializer(),
        outputSerializer = AigcMusicGenerator.Output.serializer(),
    ) { input, ctx -> gen.generate(input, ctx) }

internal fun toolShimForSpeech(gen: SpeechAigcGenerator): Tool<AigcSpeechGenerator.Input, AigcSpeechGenerator.Output> =
    AigcLegacyToolShim(
        toolId = "synthesize_speech",
        inputSerializer = AigcSpeechGenerator.Input.serializer(),
        outputSerializer = AigcSpeechGenerator.Output.serializer(),
    ) { input, ctx -> gen.generate(input, ctx) }
