package io.talevia.core.domain.source

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.util.contentHashOf
import io.talevia.core.util.fnv1a64Hex
import kotlinx.serialization.json.JsonObject

/**
 * Modality-aware shallow hash for a single [SourceNode]. For nodes whose body
 * actually splits along modality lines (today only `core.consistency.character_ref`
 * — `visualDescription` / `referenceAssetIds` / `loraPin` are visual, `voiceId`
 * is audio, `name` participates in both), this returns a hash over only the
 * fields the [modality] consumes. For every other kind we currently treat the
 * body as modality-uniform and fall back to the existing [SourceNode.contentHash];
 * adding the next mixed-modality kind is a single `when` arm.
 *
 * Falls back to [SourceNode.contentHash] on decode failure (e.g. partially
 * filled / malformed character_ref bodies on disk) so the staleness detector
 * never throws — at worst it loses modality awareness for that one entry and
 * reverts to the "edit anything → stale every consumer" behavior we had
 * before this lane existed.
 */
fun SourceNode.contentHashFor(modality: Modality): String =
    when (kind) {
        ConsistencyKinds.CHARACTER_REF -> characterRefHashFor(modality)
        else -> contentHash
    }

private fun SourceNode.characterRefHashFor(modality: Modality): String {
    val json = JsonConfig.default
    val decoded = runCatching { json.decodeFromJsonElement(CharacterRefBody.serializer(), body) }
        .getOrNull() ?: return contentHash
    val sliced: CharacterRefBody = when (modality) {
        // Visual slice: drop voiceId — voice changes must not stale image frames.
        Modality.Visual -> decoded.copy(voiceId = null)
        // Audio slice: drop visual-only fields. Name + voiceId remain because
        // a TTS prompt that references the character by name is sensitive to
        // the name itself.
        Modality.Audio -> CharacterRefBody(
            name = decoded.name,
            visualDescription = "",
            referenceAssetIds = emptyList(),
            loraPin = null,
            voiceId = decoded.voiceId,
        )
    }
    val slicedBody = json.encodeToJsonElement(CharacterRefBody.serializer(), sliced) as JsonObject
    return contentHashOf(kind, slicedBody, parents)
}

/**
 * Modality-aware deep content hash — same recursion shape as
 * [deepContentHashOf] but uses [contentHashFor] at each step so a visual-only
 * clip's deep fingerprint is unaffected by audio-only field edits in any
 * ancestor (and vice versa).
 *
 * Cache is keyed by [SourceNodeId] alone — callers must use a fresh map per
 * modality (the convenience overload below handles that automatically).
 */
fun Source.deepContentHashOfFor(
    nodeId: SourceNodeId,
    modality: Modality,
    cache: MutableMap<SourceNodeId, String> = mutableMapOf(),
    inProgress: MutableSet<SourceNodeId> = mutableSetOf(),
): String {
    cache[nodeId]?.let { return it }
    val node = byId[nodeId] ?: run {
        val sentinel = "missing:${nodeId.value}"
        cache[nodeId] = sentinel
        return sentinel
    }
    if (nodeId in inProgress) {
        // Same cycle-defense sentinel as deepContentHashOf — never cached so
        // the same id under a different ancestor walk still resolves to its
        // real hash.
        return "cycle:${nodeId.value}"
    }
    inProgress += nodeId
    val parentHashes = node.parents
        .map { it.nodeId }
        .sortedBy { it.value }
        .joinToString(separator = "|") { "${it.value}=${deepContentHashOfFor(it, modality, cache, inProgress)}" }
    val folded = fnv1a64Hex("shallow=${node.contentHashFor(modality)}|parents=$parentHashes|modality=${modality.name}")
    cache[nodeId] = folded
    inProgress -= nodeId
    return folded
}
