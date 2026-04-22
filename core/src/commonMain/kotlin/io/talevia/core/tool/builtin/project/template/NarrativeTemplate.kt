package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.genre.narrative.NarrativeSceneBody
import io.talevia.core.domain.source.genre.narrative.NarrativeShotBody
import io.talevia.core.domain.source.genre.narrative.NarrativeStorylineBody
import io.talevia.core.domain.source.genre.narrative.NarrativeWorldBody
import io.talevia.core.domain.source.genre.narrative.addNarrativeScene
import io.talevia.core.domain.source.genre.narrative.addNarrativeShot
import io.talevia.core.domain.source.genre.narrative.addNarrativeStoryline
import io.talevia.core.domain.source.genre.narrative.addNarrativeWorld

/**
 * `narrative` genre skeleton — world + storyline + one scene stub + one shot stub +
 * one character_ref + one style_bible. Wired via `parents` so DAG propagation
 * works from day zero.
 */
internal fun seedNarrativeTemplate(): Pair<Source, List<String>> {
    val worldId = SourceNodeId("world-1")
    val storyId = SourceNodeId("story-1")
    val sceneId = SourceNodeId("scene-1")
    val shotId = SourceNodeId("shot-1")
    val characterId = SourceNodeId("protagonist")
    val styleId = SourceNodeId("style")

    val s: Source = Source.EMPTY
        .addCharacterRef(
            characterId,
            CharacterRefBody(name = "protagonist", visualDescription = "TODO: describe the protagonist"),
        )
        .addStyleBible(
            styleId,
            StyleBibleBody(name = "style", description = "TODO: describe the visual style"),
        )
        .addNarrativeWorld(
            worldId,
            NarrativeWorldBody(name = "world", description = "TODO: describe the world / setting"),
            parents = listOf(SourceRef(styleId)),
        )
        .addNarrativeStoryline(
            storyId,
            NarrativeStorylineBody(logline = "TODO: one-sentence pitch"),
            parents = listOf(SourceRef(worldId)),
        )
        .addNarrativeScene(
            sceneId,
            NarrativeSceneBody(
                title = "opening",
                action = "TODO: describe what happens in the opening scene",
                characterIds = listOf(characterId.value),
            ),
            parents = listOf(SourceRef(storyId), SourceRef(characterId)),
        )
        .addNarrativeShot(
            shotId,
            NarrativeShotBody(
                sceneId = sceneId.value,
                framing = "wide",
                action = "TODO: describe the first shot",
            ),
            parents = listOf(SourceRef(sceneId)),
        )
    return s to listOf(
        characterId.value, styleId.value, worldId.value,
        storyId.value, sceneId.value, shotId.value,
    )
}
