package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.Serializable

/**
 * Per-project record of every AIGC production. This is the VISION §3.1 "lockfile" —
 * `package-lock.json` for the random compiler: the minimum set of facts that let a
 * future run ask "have we generated this exact artifact before? if so, reuse it
 * instead of re-calling the provider."
 *
 * Keyed by a stable [LockfileEntry.inputHash] over the tool's canonical inputs
 * (tool id, effective prompt, model + version, seed, output dimensions, …).
 * [LockfileEntry.provenance] preserves the full generation record for audit; the
 * cache lookup only needs the hash.
 *
 * Ordered [List] rather than a [Map] so entries have a stable insertion order (useful
 * for UI rendering of "recent generations") and the serialized shape is append-only.
 * Lookups go through [findByInputHash], which is O(n) — at project scale (~100s of
 * entries) this is fine; if we ever blow that number we add a `byHash` transient
 * index the same way [io.talevia.core.domain.source.Source.byId] works.
 */
@Serializable
data class Lockfile(
    val entries: List<LockfileEntry> = emptyList(),
) {
    fun findByInputHash(hash: String): LockfileEntry? = entries.lastOrNull { it.inputHash == hash }

    /**
     * Look up the most recent entry that produced [assetId]. Used by stale-clip
     * detection to walk Clip → Asset → conditioning source nodes without
     * requiring `Clip.sourceBinding` to be threaded through `add_clip`.
     */
    fun findByAssetId(assetId: AssetId): LockfileEntry? = entries.lastOrNull { it.assetId == assetId }

    fun append(entry: LockfileEntry): Lockfile = copy(entries = entries + entry)

    companion object {
        val EMPTY: Lockfile = Lockfile()
    }
}

/**
 * One lockfile record. A hit on [inputHash] means "we already produced [assetId] from
 * equivalent inputs" — the tool can return the cached asset without re-calling its
 * provider.
 *
 * @property inputHash FNV-1a 64-bit hex over the tool's canonical input string —
 *   includes tool id, effective prompt (post consistency-fold), model + version,
 *   seed, dimensions, and any other field that can change the output.
 * @property toolId Which tool produced this entry (for debugging / UI grouping).
 * @property assetId The persisted asset produced by the generation.
 * @property provenance Full generation record — seed, model, parameters, timestamps.
 * @property sourceBinding Source-node ids that conditioned this generation. These are
 *   what [io.talevia.core.domain.Clip.sourceBinding] should copy from when the asset
 *   becomes a clip, so the stale-propagation lane (VISION §3.2) can mark it stale
 *   when any of these nodes changes.
 * @property sourceContentHashes Snapshot of `SourceNode.contentHash` for every id in
 *   [sourceBinding] at the time this entry was written. Lets the stale-clip detector
 *   compare today's source hash against the value the generation actually consumed —
 *   the lockfile becomes the audit record of "what hash was the input when we
 *   produced this artifact". Empty for legacy entries written before this field
 *   existed; those are treated as "unknown" by the detector (not stale, not fresh).
 */
@Serializable
data class LockfileEntry(
    val inputHash: String,
    val toolId: String,
    val assetId: AssetId,
    val provenance: GenerationProvenance,
    val sourceBinding: Set<SourceNodeId> = emptySet(),
    val sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
)
