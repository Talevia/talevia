package io.talevia.core.tool.builtin.aigc

import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * Phase 3a of `debt-aigc-tool-consolidation` (cycle 34). Introduces the
 * internal abstraction the dispatcher will eventually hold instead of
 * the legacy `Tool<I, O>` instances; phase 3b switches the dispatcher
 * to consume these (`AigcGenerateToolDispatchers` will type its
 * `image: AigcGenerator.Image?` etc.); phase 3c drops the `Tool<I, O>`
 * implementations entirely and folds the underlying provider call sites
 * into bespoke generator classes.
 *
 * **Phase 3a scope**: pure addition. The four `ToolBacked*Generator`
 * classes wrap the existing `Tool<I, O>` instances and re-expose the
 * methods the dispatcher uses today. **Zero call-site changes** —
 * `AigcGenerateToolDispatchers` keeps its existing `GenerateImageTool?`
 * etc. fields. The new types exist alongside; phase 3b is the migration
 * cycle.
 *
 * Why a sealed interface instead of one umbrella interface with a
 * `kind` enum: per-kind input + output types (e.g.
 * `GenerateImageTool.Input` vs `GenerateVideoTool.Input`) make a
 * single-method `generate(input: Any, ctx): ToolResult<Any>` shape
 * lose the type discipline the dispatcher relies on. Per-kind
 * sub-interface gives the dispatcher a typed `generate(input:
 * GenerateImageTool.Input, ctx)` signature without an `Any`-cast at
 * the call site. Sealed marker keeps the family closed — no rogue
 * impls outside this package.
 */
sealed interface AigcGenerator

/**
 * Image-kind generator. Exposes `engine` so the dispatcher can read
 * `engine.supportsNativeBatch` to decide between
 * [generate] (single-call) and [generateBatch] (cycle 33's native-batch
 * path that issues one provider call for `n=variantCount`).
 */
sealed interface ImageAigcGenerator : AigcGenerator {
    val engine: ImageGenEngine

    suspend fun generate(
        input: GenerateImageTool.Input,
        ctx: ToolContext,
    ): ToolResult<GenerateImageTool.Output>

    suspend fun generateBatch(
        input: GenerateImageTool.Input,
        ctx: ToolContext,
        variantCount: Int,
    ): List<ToolResult<GenerateImageTool.Output>>
}

sealed interface VideoAigcGenerator : AigcGenerator {
    suspend fun generate(
        input: GenerateVideoTool.Input,
        ctx: ToolContext,
    ): ToolResult<GenerateVideoTool.Output>
}

sealed interface MusicAigcGenerator : AigcGenerator {
    suspend fun generate(
        input: GenerateMusicTool.Input,
        ctx: ToolContext,
    ): ToolResult<GenerateMusicTool.Output>
}

sealed interface SpeechAigcGenerator : AigcGenerator {
    suspend fun generate(
        input: SynthesizeSpeechTool.Input,
        ctx: ToolContext,
    ): ToolResult<SynthesizeSpeechTool.Output>
}

/**
 * Phase-3a wrapper: routes [generate] / [generateBatch] through the
 * existing [GenerateImageTool] `Tool<I, O>` impl. Phase 3c will replace
 * this with a bespoke implementation that drops the `Tool<I, O>` shell
 * (id / inputSchema / permissionSpec / etc.) which the dispatcher
 * doesn't read.
 */
internal class ToolBackedImageGenerator(
    val tool: GenerateImageTool,
) : ImageAigcGenerator {
    override val engine: ImageGenEngine get() = tool.engine

    override suspend fun generate(
        input: GenerateImageTool.Input,
        ctx: ToolContext,
    ): ToolResult<GenerateImageTool.Output> = tool.execute(input, ctx)

    override suspend fun generateBatch(
        input: GenerateImageTool.Input,
        ctx: ToolContext,
        variantCount: Int,
    ): List<ToolResult<GenerateImageTool.Output>> = tool.executeBatch(input, ctx, variantCount)
}

internal class ToolBackedVideoGenerator(
    val tool: GenerateVideoTool,
) : VideoAigcGenerator {
    override suspend fun generate(
        input: GenerateVideoTool.Input,
        ctx: ToolContext,
    ): ToolResult<GenerateVideoTool.Output> = tool.execute(input, ctx)
}

internal class ToolBackedMusicGenerator(
    val tool: GenerateMusicTool,
) : MusicAigcGenerator {
    override suspend fun generate(
        input: GenerateMusicTool.Input,
        ctx: ToolContext,
    ): ToolResult<GenerateMusicTool.Output> = tool.execute(input, ctx)
}

internal class ToolBackedSpeechGenerator(
    val tool: SynthesizeSpeechTool,
) : SpeechAigcGenerator {
    override suspend fun generate(
        input: SynthesizeSpeechTool.Input,
        ctx: ToolContext,
    ): ToolResult<SynthesizeSpeechTool.Output> = tool.execute(input, ctx)
}
