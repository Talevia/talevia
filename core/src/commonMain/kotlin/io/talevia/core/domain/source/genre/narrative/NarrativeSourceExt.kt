package io.talevia.core.domain.source.genre.narrative

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.Json

/**
 * Typed builders and accessors for the Narrative genre.
 *
 * Mirrors the vlog-genre ext file shape: writes go through
 * [Source.addNode] with a body pre-encoded via the canonical [Json]
 * (`JsonConfig.default`). Reads decode only if [SourceNode.kind] matches the
 * expected constant; otherwise the accessor returns `null` so callers can do
 * kind-dispatch with a `when`/`let` chain without try/catch.
 *
 * Each `add*` helper accepts optional `parents` because narrative nodes
 * naturally point upstream — scenes depend on world + storyline, shots depend
 * on scenes + the character_refs they feature. Wiring parents at construction
 * is what lets edits to a world propagate through the DAG lane
 * (`Source.stale`) to every downstream scene and shot.
 */

private val json: Json = JsonConfig.default

// region — writers

fun Source.addNarrativeWorld(
    id: SourceNodeId,
    body: NarrativeWorldBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = NarrativeNodeKinds.WORLD,
        body = json.encodeToJsonElement(NarrativeWorldBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addNarrativeStoryline(
    id: SourceNodeId,
    body: NarrativeStorylineBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = NarrativeNodeKinds.STORYLINE,
        body = json.encodeToJsonElement(NarrativeStorylineBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addNarrativeScene(
    id: SourceNodeId,
    body: NarrativeSceneBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = NarrativeNodeKinds.SCENE,
        body = json.encodeToJsonElement(NarrativeSceneBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addNarrativeShot(
    id: SourceNodeId,
    body: NarrativeShotBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = NarrativeNodeKinds.SHOT,
        body = json.encodeToJsonElement(NarrativeShotBody.serializer(), body),
        parents = parents,
    ),
)

// endregion

// region — readers

/** @return the decoded body when [SourceNode.kind] matches, else `null`. */
fun SourceNode.asNarrativeWorld(): NarrativeWorldBody? =
    if (kind == NarrativeNodeKinds.WORLD) {
        json.decodeFromJsonElement(NarrativeWorldBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asNarrativeStoryline(): NarrativeStorylineBody? =
    if (kind == NarrativeNodeKinds.STORYLINE) {
        json.decodeFromJsonElement(NarrativeStorylineBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asNarrativeScene(): NarrativeSceneBody? =
    if (kind == NarrativeNodeKinds.SCENE) {
        json.decodeFromJsonElement(NarrativeSceneBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asNarrativeShot(): NarrativeShotBody? =
    if (kind == NarrativeNodeKinds.SHOT) {
        json.decodeFromJsonElement(NarrativeShotBody.serializer(), body)
    } else {
        null
    }

// endregion
