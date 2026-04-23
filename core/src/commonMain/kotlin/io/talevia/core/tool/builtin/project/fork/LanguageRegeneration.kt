package io.talevia.core.tool.builtin.project.fork

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.project.ForkProjectTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Iterate every [Clip.Text] on [fork]'s timeline and dispatch the registered
 * `synthesize_speech` tool against the fork's project id with a target
 * [language]. Used by `ForkProjectTool` when `variantSpec.language` is set.
 *
 * Fails loud when the registry is missing or the TTS tool isn't wired —
 * silently skipping the regeneration would leave the fork with the source
 * project's original-language voiceovers and no indication that the
 * translation step failed.
 *
 * Extracted from `ForkProjectTool` in the `debt-split-fork-project-tool`
 * cycle; behaviour is identical to the old private method.
 */
internal suspend fun regenerateTtsInLanguage(
    registry: ToolRegistry?,
    forkId: ProjectId,
    fork: Project,
    language: String,
    ctx: ToolContext,
): List<ForkProjectTool.LanguageRegenResult> {
    require(language.isNotBlank()) {
        "variantSpec.language must be a non-blank ISO-639-1 code (e.g. 'en', 'es')"
    }
    val reg = registry ?: error(
        "variantSpec.language was set but this ForkProjectTool has no ToolRegistry wired — " +
            "install TtsEngine/SynthesizeSpeechTool in the container or drop variantSpec.language.",
    )
    val tts = reg["synthesize_speech"] ?: error(
        "variantSpec.language requires the `synthesize_speech` tool to be registered on this " +
            "container — wire a TtsEngine or drop variantSpec.language.",
    )

    val results = mutableListOf<ForkProjectTool.LanguageRegenResult>()
    for (track in fork.timeline.tracks) {
        for (clip in track.clips) {
            if (clip !is Clip.Text) continue
            if (clip.text.isBlank()) continue
            val payload = buildJsonObject {
                put("text", clip.text)
                put("projectId", forkId.value)
                put("language", language)
                val bindings = clip.sourceBinding.map { it.value }.sorted()
                if (bindings.isNotEmpty()) {
                    put("consistencyBindingIds", JsonArray(bindings.map { JsonPrimitive(it) }))
                }
            }
            val result = tts.dispatch(payload, ctx)
            val outputJson = tts.encodeOutput(result).jsonObject
            val assetId = (outputJson["assetId"] as? JsonPrimitive)?.content
                ?: error("synthesize_speech returned no assetId for clip ${clip.id.value}")
            val cacheHit = (outputJson["cacheHit"] as? JsonPrimitive)?.content == "true"
            results += ForkProjectTool.LanguageRegenResult(
                clipId = clip.id.value,
                assetId = assetId,
                cacheHit = cacheHit,
            )
        }
    }
    return results
}
