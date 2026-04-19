package io.talevia.core.domain.source.consistency

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.Json

/**
 * Typed builders + accessors for consistency nodes. Same contract as the vlog genre
 * extension: Core sees [kotlinx.serialization.json.JsonElement] bodies; the typed
 * accessor returns `null` on kind mismatch so kind-dispatch can be a `when`/`let`.
 */

private val json: Json = JsonConfig.default

// region — writers

fun Source.addCharacterRef(id: SourceNodeId, body: CharacterRefBody): Source =
    addNode(
        SourceNode(
            id = id,
            kind = ConsistencyKinds.CHARACTER_REF,
            body = json.encodeToJsonElement(CharacterRefBody.serializer(), body),
        ),
    )

fun Source.addStyleBible(id: SourceNodeId, body: StyleBibleBody): Source =
    addNode(
        SourceNode(
            id = id,
            kind = ConsistencyKinds.STYLE_BIBLE,
            body = json.encodeToJsonElement(StyleBibleBody.serializer(), body),
        ),
    )

fun Source.addBrandPalette(id: SourceNodeId, body: BrandPaletteBody): Source =
    addNode(
        SourceNode(
            id = id,
            kind = ConsistencyKinds.BRAND_PALETTE,
            body = json.encodeToJsonElement(BrandPaletteBody.serializer(), body),
        ),
    )

// endregion

// region — readers

fun SourceNode.asCharacterRef(): CharacterRefBody? =
    if (kind == ConsistencyKinds.CHARACTER_REF) {
        json.decodeFromJsonElement(CharacterRefBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asStyleBible(): StyleBibleBody? =
    if (kind == ConsistencyKinds.STYLE_BIBLE) {
        json.decodeFromJsonElement(StyleBibleBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asBrandPalette(): BrandPaletteBody? =
    if (kind == ConsistencyKinds.BRAND_PALETTE) {
        json.decodeFromJsonElement(BrandPaletteBody.serializer(), body)
    } else {
        null
    }

// endregion

// region — source-level resolution

/** Every consistency node in this source, preserving declaration order. */
fun Source.consistencyNodes(): List<SourceNode> =
    nodes.filter { it.kind in ConsistencyKinds.ALL }

/**
 * Look up the [SourceNode]s named by [ids] and return only those whose kind is a
 * consistency kind. Ids pointing at non-consistency nodes or at missing nodes are
 * silently dropped — callers get back "the bindings we could resolve", not an error.
 * (AIGC tool errors on stale bindings would just surface as opaque prompt garbage;
 * better to skip with a warning than to crash the generation.)
 */
fun Source.resolveConsistencyBindings(ids: Collection<SourceNodeId>): List<SourceNode> {
    if (ids.isEmpty()) return emptyList()
    val index = byId
    return ids.mapNotNull { id -> index[id]?.takeIf { it.kind in ConsistencyKinds.ALL } }
}

// endregion
