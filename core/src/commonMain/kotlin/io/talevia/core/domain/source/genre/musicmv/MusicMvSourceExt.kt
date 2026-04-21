package io.talevia.core.domain.source.genre.musicmv

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.Json

/**
 * Typed builders and accessors for the Music-MV genre.
 *
 * Mirrors the vlog / narrative ext files: writes go through [Source.addNode]
 * with a body pre-encoded via the canonical [Json] (`JsonConfig.default`).
 * Reads decode only if [SourceNode.kind] matches the expected constant;
 * otherwise the accessor returns `null` so callers can do kind-dispatch with
 * a `when`/`let` chain without try/catch.
 *
 * `add*` helpers accept optional `parents` because performance shots typically
 * depend on the track (for tempo) and the visual concept (for look); wiring
 * those parents at construction is what lets edits to the track or concept
 * flow through the DAG (`Source.stale`) to every downstream shot.
 */

private val json: Json = JsonConfig.default

// region — writers

fun Source.addMusicMvTrack(
    id: SourceNodeId,
    body: MusicMvTrackBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = MusicMvNodeKinds.TRACK,
        body = json.encodeToJsonElement(MusicMvTrackBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addMusicMvVisualConcept(
    id: SourceNodeId,
    body: MusicMvVisualConceptBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = MusicMvNodeKinds.VISUAL_CONCEPT,
        body = json.encodeToJsonElement(MusicMvVisualConceptBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addMusicMvPerformanceShot(
    id: SourceNodeId,
    body: MusicMvPerformanceShotBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = MusicMvNodeKinds.PERFORMANCE_SHOT,
        body = json.encodeToJsonElement(MusicMvPerformanceShotBody.serializer(), body),
        parents = parents,
    ),
)

// endregion

// region — readers

/** @return the decoded body when [SourceNode.kind] matches, else `null`. */
fun SourceNode.asMusicMvTrack(): MusicMvTrackBody? =
    if (kind == MusicMvNodeKinds.TRACK) {
        json.decodeFromJsonElement(MusicMvTrackBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asMusicMvVisualConcept(): MusicMvVisualConceptBody? =
    if (kind == MusicMvNodeKinds.VISUAL_CONCEPT) {
        json.decodeFromJsonElement(MusicMvVisualConceptBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asMusicMvPerformanceShot(): MusicMvPerformanceShotBody? =
    if (kind == MusicMvNodeKinds.PERFORMANCE_SHOT) {
        json.decodeFromJsonElement(MusicMvPerformanceShotBody.serializer(), body)
    } else {
        null
    }

// endregion
