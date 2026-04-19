package io.talevia.core.domain.source.genre.vlog

import io.talevia.core.AssetId
import kotlinx.serialization.Serializable

/**
 * Typed payload bodies for the Vlog genre's [io.talevia.core.domain.source.SourceNode]s.
 *
 * Each body is a regular [kotlinx.serialization.Serializable] data class — it is encoded
 * into a [kotlinx.serialization.json.JsonElement] via the canonical
 * [io.talevia.core.JsonConfig.default] at write time and decoded on read by
 * [VlogSourceExt] accessors. Core itself never sees these types; that is what keeps the
 * genre schema out of Core.
 */

/** Original shot footage referenced by [AssetId] plus free-form creator notes. */
@Serializable
data class VlogRawFootageBody(
    val assetIds: List<AssetId>,
    val notes: String = "",
)

/**
 * User-facing intent for the edit (e.g. "capture a graduation day mood"). Optional target
 * duration + mood tag let the agent pick pacing and music without us inventing an enum.
 */
@Serializable
data class VlogEditIntentBody(
    val description: String,
    val targetDurationSeconds: Int? = null,
    val mood: String? = null,
)

/**
 * Named style preset — free-form [params] keep the schema open while keeping the name
 * indexable for UI / agent queries.
 */
@Serializable
data class VlogStylePresetBody(
    val name: String,
    val params: Map<String, String> = emptyMap(),
)
