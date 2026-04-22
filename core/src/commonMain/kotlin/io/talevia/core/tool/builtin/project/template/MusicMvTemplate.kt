package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.BrandPaletteBody
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addBrandPalette
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.genre.musicmv.MusicMvPerformanceShotBody
import io.talevia.core.domain.source.genre.musicmv.MusicMvVisualConceptBody
import io.talevia.core.domain.source.genre.musicmv.addMusicMvPerformanceShot
import io.talevia.core.domain.source.genre.musicmv.addMusicMvVisualConcept

/**
 * `musicmv` genre skeleton — visual_concept + performance_shot + character_ref
 * (performer) + brand_palette. The `musicmv.track` node is deliberately
 * **not** seeded because it requires an imported music asset id — the caller
 * nudges the user to `import_media` then define a track node.
 */
internal fun seedMusicMvTemplate(): Pair<Source, List<String>> {
    val paletteId = SourceNodeId("brand-palette")
    val performerId = SourceNodeId("performer")
    val conceptId = SourceNodeId("visual-concept")
    val shotId = SourceNodeId("performance-1")

    val s: Source = Source.EMPTY
        .addBrandPalette(
            paletteId,
            BrandPaletteBody(name = "brand-palette", hexColors = listOf("#000000")),
        )
        .addCharacterRef(
            performerId,
            CharacterRefBody(name = "performer", visualDescription = "TODO: describe the performer's look"),
        )
        .addMusicMvVisualConcept(
            conceptId,
            MusicMvVisualConceptBody(
                logline = "TODO: one-sentence MV concept",
                mood = "TODO: mood",
                paletteRef = paletteId.value,
            ),
            parents = listOf(SourceRef(paletteId)),
        )
        .addMusicMvPerformanceShot(
            shotId,
            MusicMvPerformanceShotBody(
                performer = "performer",
                action = "TODO: describe the performance beat this shot covers",
            ),
            parents = listOf(SourceRef(conceptId), SourceRef(performerId)),
        )
    return s to listOf(paletteId.value, performerId.value, conceptId.value, shotId.value)
}
