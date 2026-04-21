package io.talevia.core.domain.source.genre.tutorial

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.Json

/**
 * Typed builders and accessors for the Tutorial genre.
 *
 * Mirrors the vlog / narrative / music-mv ext files: writes go through
 * [Source.addNode] with a body pre-encoded via the canonical [Json]
 * (`JsonConfig.default`). Reads decode only if [SourceNode.kind] matches
 * the expected constant; otherwise the accessor returns `null`.
 */

private val json: Json = JsonConfig.default

// region — writers

fun Source.addTutorialScript(
    id: SourceNodeId,
    body: TutorialScriptBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = TutorialNodeKinds.SCRIPT,
        body = json.encodeToJsonElement(TutorialScriptBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addTutorialBrollLibrary(
    id: SourceNodeId,
    body: TutorialBrollLibraryBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = TutorialNodeKinds.BROLL_LIBRARY,
        body = json.encodeToJsonElement(TutorialBrollLibraryBody.serializer(), body),
        parents = parents,
    ),
)

fun Source.addTutorialBrandSpec(
    id: SourceNodeId,
    body: TutorialBrandSpecBody,
    parents: List<SourceRef> = emptyList(),
): Source = addNode(
    SourceNode(
        id = id,
        kind = TutorialNodeKinds.BRAND_SPEC,
        body = json.encodeToJsonElement(TutorialBrandSpecBody.serializer(), body),
        parents = parents,
    ),
)

// endregion

// region — readers

/** @return the decoded body when [SourceNode.kind] matches, else `null`. */
fun SourceNode.asTutorialScript(): TutorialScriptBody? =
    if (kind == TutorialNodeKinds.SCRIPT) {
        json.decodeFromJsonElement(TutorialScriptBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asTutorialBrollLibrary(): TutorialBrollLibraryBody? =
    if (kind == TutorialNodeKinds.BROLL_LIBRARY) {
        json.decodeFromJsonElement(TutorialBrollLibraryBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asTutorialBrandSpec(): TutorialBrandSpecBody? =
    if (kind == TutorialNodeKinds.BRAND_SPEC) {
        json.decodeFromJsonElement(TutorialBrandSpecBody.serializer(), body)
    } else {
        null
    }

// endregion
