package io.talevia.core.tool.builtin.aigc

import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `debt-aigc-tool-consolidation` phase 3 family. Sealed interface the
 * `AigcGenerateTool` dispatcher consumes — phase 3b (cycle 38) routed
 * `dispatch{Image,Video,Music,Speech}` through the per-kind sub-interfaces;
 * phase 3c-1 (cycle 39) collapsed the temporary `ToolBacked*Generator`
 * adapter layer by having the underlying `Tool<I, O>` classes implement
 * the per-kind interface directly. Phase 3c-2 will drop the `Tool<I, O>`
 * declaration from those classes (test migration is the heavy bit there);
 * phase 3c-3 renames `Generate*Tool` → `Aigc*Generator` and folds the
 * provider call sites into bespoke generator bodies.
 *
 * Why a sealed interface instead of one umbrella interface with a `kind`
 * enum: per-kind input + output types (e.g. `AigcImageGenerator.Input` vs
 * `AigcVideoGenerator.Input`) make a single-method
 * `generate(input: Any, ctx): ToolResult<Any>` shape lose the type
 * discipline the dispatcher relies on. Per-kind sub-interface gives the
 * dispatcher a typed `generate(input: AigcImageGenerator.Input, ctx)`
 * signature without an `Any`-cast at the call site. Sealed marker keeps
 * the family closed — no rogue impls outside this package.
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
        input: AigcImageGenerator.Input,
        ctx: ToolContext,
    ): ToolResult<AigcImageGenerator.Output>

    suspend fun generateBatch(
        input: AigcImageGenerator.Input,
        ctx: ToolContext,
        variantCount: Int,
    ): List<ToolResult<AigcImageGenerator.Output>>
}

sealed interface VideoAigcGenerator : AigcGenerator {
    suspend fun generate(
        input: AigcVideoGenerator.Input,
        ctx: ToolContext,
    ): ToolResult<AigcVideoGenerator.Output>
}

sealed interface MusicAigcGenerator : AigcGenerator {
    suspend fun generate(
        input: AigcMusicGenerator.Input,
        ctx: ToolContext,
    ): ToolResult<AigcMusicGenerator.Output>
}

sealed interface SpeechAigcGenerator : AigcGenerator {
    suspend fun generate(
        input: AigcSpeechGenerator.Input,
        ctx: ToolContext,
    ): ToolResult<AigcSpeechGenerator.Output>
}
