package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.genre.tutorial.TutorialBrandSpecBody
import io.talevia.core.domain.source.genre.tutorial.TutorialBrollLibraryBody
import io.talevia.core.domain.source.genre.tutorial.TutorialScriptBody
import io.talevia.core.domain.source.genre.tutorial.addTutorialBrandSpec
import io.talevia.core.domain.source.genre.tutorial.addTutorialBrollLibrary
import io.talevia.core.domain.source.genre.tutorial.addTutorialScript

/**
 * `tutorial` genre skeleton — tutorial_script + tutorial_broll_library (empty)
 * + tutorial_brand_spec + style_bible.
 */
internal fun seedTutorialTemplate(): Pair<Source, List<String>> {
    val styleId = SourceNodeId("style")
    val brandId = SourceNodeId("brand-spec")
    val scriptId = SourceNodeId("script")
    val brollId = SourceNodeId("broll")

    val s: Source = Source.EMPTY
        .addStyleBible(
            styleId,
            StyleBibleBody(name = "style", description = "TODO: describe the visual style"),
        )
        .addTutorialBrandSpec(
            brandId,
            TutorialBrandSpecBody(
                productName = "TODO: product name",
                brandColors = emptyList(),
                lowerThirdStyle = "TODO: lower-third treatment",
            ),
        )
        .addTutorialScript(
            scriptId,
            TutorialScriptBody(
                title = "TODO: tutorial title",
                spokenText = "TODO: voiceover script",
                segments = listOf("intro", "demo", "wrap"),
            ),
            parents = listOf(SourceRef(styleId), SourceRef(brandId)),
        )
        .addTutorialBrollLibrary(
            brollId,
            TutorialBrollLibraryBody(
                assetIds = emptyList(),
                notes = "TODO: import screen-capture / demo clips and bind assetIds here",
            ),
        )
    return s to listOf(styleId.value, brandId.value, scriptId.value, brollId.value)
}
