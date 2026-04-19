package io.talevia.core.domain.source.genre.vlog

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.Json

/**
 * Typed builders and accessors for the Vlog genre.
 *
 * Writes go through [Source.addNode] with a body pre-encoded via the canonical [Json]
 * (single instance — `JsonConfig.default`). Reads decode only if [SourceNode.kind]
 * matches the expected constant; otherwise the accessor returns `null` rather than
 * throwing. That shape means callers can do kind-dispatch with a `when`/`let` chain
 * without try/catch.
 */

private val json: Json = JsonConfig.default

// region — writers

fun Source.addVlogRawFootage(id: SourceNodeId, body: VlogRawFootageBody): Source =
    addNode(
        SourceNode(
            id = id,
            kind = VlogNodeKinds.RAW_FOOTAGE,
            body = json.encodeToJsonElement(VlogRawFootageBody.serializer(), body),
        ),
    )

fun Source.addVlogEditIntent(id: SourceNodeId, body: VlogEditIntentBody): Source =
    addNode(
        SourceNode(
            id = id,
            kind = VlogNodeKinds.EDIT_INTENT,
            body = json.encodeToJsonElement(VlogEditIntentBody.serializer(), body),
        ),
    )

fun Source.addVlogStylePreset(id: SourceNodeId, body: VlogStylePresetBody): Source =
    addNode(
        SourceNode(
            id = id,
            kind = VlogNodeKinds.STYLE_PRESET,
            body = json.encodeToJsonElement(VlogStylePresetBody.serializer(), body),
        ),
    )

// endregion

// region — readers

/** @return the decoded body when [SourceNode.kind] matches, else `null`. */
fun SourceNode.asVlogRawFootage(): VlogRawFootageBody? =
    if (kind == VlogNodeKinds.RAW_FOOTAGE) {
        json.decodeFromJsonElement(VlogRawFootageBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asVlogEditIntent(): VlogEditIntentBody? =
    if (kind == VlogNodeKinds.EDIT_INTENT) {
        json.decodeFromJsonElement(VlogEditIntentBody.serializer(), body)
    } else {
        null
    }

fun SourceNode.asVlogStylePreset(): VlogStylePresetBody? =
    if (kind == VlogNodeKinds.STYLE_PRESET) {
        json.decodeFromJsonElement(VlogStylePresetBody.serializer(), body)
    } else {
        null
    }

// endregion
