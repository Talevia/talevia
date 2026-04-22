package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.genre.vlog.VlogEditIntentBody
import io.talevia.core.domain.source.genre.vlog.VlogRawFootageBody
import io.talevia.core.domain.source.genre.vlog.VlogStylePresetBody
import io.talevia.core.domain.source.genre.vlog.addVlogEditIntent
import io.talevia.core.domain.source.genre.vlog.addVlogRawFootage
import io.talevia.core.domain.source.genre.vlog.addVlogStylePreset

/**
 * `vlog` genre skeleton — raw_footage + edit_intent + style_preset + one
 * style_bible.
 */
internal fun seedVlogTemplate(): Pair<Source, List<String>> {
    val intentId = SourceNodeId("intent")
    val footageId = SourceNodeId("footage")
    val presetId = SourceNodeId("style-preset")
    val styleId = SourceNodeId("style")

    val s: Source = Source.EMPTY
        .addStyleBible(
            styleId,
            StyleBibleBody(name = "style", description = "TODO: describe the visual style"),
        )
        .addVlogRawFootage(
            footageId,
            VlogRawFootageBody(assetIds = emptyList(), notes = "TODO: import footage and bind assetIds here"),
        )
        .addVlogEditIntent(
            intentId,
            VlogEditIntentBody(description = "TODO: describe the editing intent / mood"),
        )
        .addVlogStylePreset(
            presetId,
            VlogStylePresetBody(name = "style-preset"),
        )
    return s to listOf(styleId.value, footageId.value, intentId.value, presetId.value)
}
