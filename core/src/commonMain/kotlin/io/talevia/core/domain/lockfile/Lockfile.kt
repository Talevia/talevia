package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

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
 * Lookups go through [findByInputHash] / [findByAssetId], which are O(1): both consult
 * the [byInputHash] / [byAssetId] transient indexes reconstructed from [entries] on
 * deserialize via the default-init pattern used by [io.talevia.core.domain.source.Source.byId].
 *
 * The append-only ledger allows duplicate hashes (a provider re-runs and happens to
 * produce the same hash twice). [findByInputHash] must return the **most recent** match
 * — Kotlin's [List.associateBy] overwrites duplicate keys with the later element, so
 * the last-wins semantic is preserved by the map.
 */
@Serializable
data class Lockfile(
    val entries: List<LockfileEntry> = emptyList(),
) {
    /**
     * Hash → most recent [LockfileEntry] with that [LockfileEntry.inputHash].
     *
     * Reconstructed from [entries] on deserialize via the default lazy-init pattern
     * used by [io.talevia.core.domain.source.Source.byId]. `associateBy` overwrites
     * on duplicate keys, so the resulting entry is the last one in insertion order —
     * matching the original `entries.lastOrNull { … }` semantic.
     */
    @Transient
    val byInputHash: Map<String, LockfileEntry> = entries.associateBy { it.inputHash }

    /**
     * Asset id → most recent [LockfileEntry] that produced that asset.
     *
     * Same reconstruction + last-wins guarantee as [byInputHash].
     */
    @Transient
    val byAssetId: Map<AssetId, LockfileEntry> = entries.associateBy { it.assetId }

    fun findByInputHash(hash: String): LockfileEntry? = byInputHash[hash]

    /**
     * Look up the most recent entry that produced [assetId]. Used by stale-clip
     * detection to walk Clip → Asset → conditioning source nodes without
     * requiring `Clip.sourceBinding` to be threaded through `add_clip`.
     */
    fun findByAssetId(assetId: AssetId): LockfileEntry? = byAssetId[assetId]

    fun append(entry: LockfileEntry): Lockfile = copy(entries = entries + entry)

    /**
     * Flip the `pinned` flag on the most recent entry matching [inputHash].
     *
     * VISION §3.1 "产物可 pin" — once the user marks a hero-shot entry pinned,
     * it survives GC policy sweeps ([io.talevia.core.tool.builtin.project.GcLockfileTool])
     * and `regenerate_stale_clips` skips re-generating its clip even when the
     * bound source changed. Returns the lockfile unchanged when no entry matches,
     * so callers can fail loudly on their own.
     *
     * Only the most recent match is toggled — `findByInputHash` semantics. Older
     * duplicate-hash entries (the append-only ledger allows them when a provider
     * re-runs and happens to produce the same hash twice) stay untouched because
     * they are not the one a cache lookup would return.
     */
    fun withEntryPinned(inputHash: String, pinned: Boolean): Lockfile {
        val idx = entries.indexOfLast { it.inputHash == inputHash }
        if (idx < 0) return this
        val current = entries[idx]
        if (current.pinned == pinned) return this
        return copy(entries = entries.toMutableList().apply { this[idx] = current.copy(pinned = pinned) })
    }

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
 * @property baseInputs The raw (pre-folding) tool Input JSON that produced this
 *   entry, captured so `regenerate_stale_clips` can re-dispatch the exact same tool
 *   call with the *current* source state — consistency folding re-runs against
 *   today's character_ref / style_bible, yielding a fresh generation. Empty for
 *   legacy entries written before this field existed; the regenerate tool skips
 *   those (it can't reconstruct what the agent originally asked for).
 * @property pinned VISION §3.1 "产物可 pin" — user intent "this exact generation is
 *   the hero shot, don't regenerate it even when downstream source changes". Set via
 *   `pin_lockfile_entry`; cleared via `unpin_lockfile_entry`. When true:
 *   - `gc_lockfile` policy sweeps skip the entry regardless of age/count verdict,
 *   - `regenerate_stale_clips` skips every clip whose current lockfile entry is
 *     pinned (reason `"pinned"`), leaving the clip stale-but-frozen until the user
 *     unpins or removes the asset.
 *   Pinned entries are still subject to `prune_lockfile` — an orphan entry (no
 *   surviving asset) is dead weight regardless of intent, and the pin had no
 *   artifact to protect anyway. Default `false` preserves the legacy shape; old
 *   serialized lockfiles missing this field deserialize as unpinned.
 */
@Serializable
data class LockfileEntry(
    val inputHash: String,
    val toolId: String,
    val assetId: AssetId,
    val provenance: GenerationProvenance,
    val sourceBinding: Set<SourceNodeId> = emptySet(),
    val sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
    val baseInputs: JsonObject = JsonObject(emptyMap()),
    val pinned: Boolean = false,
)
