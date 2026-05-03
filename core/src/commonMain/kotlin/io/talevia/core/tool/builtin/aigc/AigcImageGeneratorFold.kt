package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.consistency.FoldedPrompt
import io.talevia.core.platform.BundleMediaPathResolver

/**
 * Consistency-fold + reference-asset-path resolution helpers for
 * [AigcImageGenerator] (`debt-split-generate-image-tool`, cycle 37).
 * Extracted from the main file because both [resolveConsistency] and
 * [resolveReferenceAssetPaths] are called by both `execute()` (single-
 * variant) and `executeBatch()` (multi-variant) — keeping them together
 * here makes "where does the image-gen tool turn an input into a
 * folded prompt + asset paths?" a one-grep answer.
 *
 * **Axis**: this file grows with consistency-fold complexity (per-genre
 * fold extensions, new SourceNode kinds becoming bindable, etc.). It
 * stays decoupled from the per-flow orchestration in `AigcImageGenerator.kt`
 * + `AigcImageGeneratorBatch.kt`.
 */

internal suspend fun AigcImageGenerator.resolveReferenceAssetPaths(
    pid: ProjectId,
    assetIds: List<String>,
): List<String> {
    if (assetIds.isEmpty()) return emptyList()
    val project = projectStore.get(pid)
        ?: error("project ${pid.value} not found when resolving reference assets")
    val bundleRoot = projectStore.pathOf(pid)
        ?: error(
            "project ${pid.value} has no registered bundle path; reference asset resolution " +
                "requires a file-backed ProjectStore — open or create the bundle first.",
        )
    val resolver = BundleMediaPathResolver(project, bundleRoot)
    return assetIds.map { resolver.resolve(AssetId(it)) }
}

internal suspend fun AigcImageGenerator.resolveConsistency(
    input: AigcImageGenerator.Input,
    pid: ProjectId,
): FoldedPrompt {
    val bindingIds = input.consistencyBindingIds
    if (bindingIds != null && bindingIds.isEmpty()) {
        return io.talevia.core.domain.source.consistency
            .foldConsistencyIntoPrompt(input.prompt, emptyList())
    }
    val project = projectStore.get(pid)
        ?: error("Project ${pid.value} not found when resolving consistency bindings")
    return AigcPipeline.foldPrompt(
        project = project,
        basePrompt = input.prompt,
        bindingIds = bindingIds?.map { SourceNodeId(it) },
    )
}
