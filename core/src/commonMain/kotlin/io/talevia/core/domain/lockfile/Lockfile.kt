package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.MessageId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Modality
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * Per-project record of every AIGC production. This is the VISION §3.1 "lockfile" —
 * `package-lock.json` for the random compiler: the minimum set of facts that let a
 * future run ask "have we generated this exact artifact before? if so, reuse it
 * instead of re-calling the provider."
 *
 * **Sealed interface (cycle 59 phase 2b-1c-1).** `Lockfile` is the public API
 * contract; [EagerLockfile] is the in-memory data-class impl that holds every
 * entry as a `List<LockfileEntry>`. A future `LazyJsonlLockfile` impl will read
 * `<bundleRoot>/lockfile.jsonl` on-demand for the `phase2-lazy-load` O(1)-open
 * perf win documented in [io.talevia.core.domain.FileProjectStoreLockfileIO].
 *
 * Only [EagerLockfile] is `@Serializable` — the interface is plain Kotlin sealed
 * so the JSON shape is unchanged from the pre-lift data class. Three sites
 * encode/decode `EagerLockfile.serializer()` directly:
 * - [io.talevia.core.tool.builtin.video.export.ExportToolFingerprint] for the
 *   per-clip cache fingerprint (hash stable across the lift).
 * - `LockfileEntriesBenchmark` and `LockfileByHashIndexTest` for round-trip.
 *
 * Keyed by a stable [LockfileEntry.inputHash] over the tool's canonical inputs
 * (tool id, effective prompt, model + version, seed, output dimensions, …).
 * [LockfileEntry.provenance] preserves the full generation record for audit; the
 * cache lookup only needs the hash.
 *
 * Ordered [List] rather than a [Map] so entries have a stable insertion order (useful
 * for UI rendering of "recent generations") and the serialized shape is append-only.
 * Lookups go through [findByInputHash] / [findByAssetId], which are O(1): both consult
 * the [byInputHash] / [byAssetId] indexes (eager impl reconstructs them from
 * [entries] on deserialize via the default-init pattern; lazy impl will compute
 * them on first access).
 *
 * The append-only ledger allows duplicate hashes (a provider re-runs and happens to
 * produce the same hash twice). [findByInputHash] must return the **most recent** match
 * — Kotlin's [List.associateBy] overwrites duplicate keys with the later element, so
 * the last-wins semantic is preserved by the map.
 */
@Serializable(with = LockfileSerializer::class)
sealed interface Lockfile {
    /**
     * Append-only entry list in insertion order. Bound-to-local sites
     * (`val entries = lockfile.entries`) consume it as a `List` for random-access
     * / sized iteration; lazy impls override to materialize on access. Bound-to-
     * local migration to `stream()` is deferred to phase 2b-1c-3 once a concrete
     * lazy impl exists to actually benefit.
     */
    val entries: List<LockfileEntry>

    /** Hash → most recent [LockfileEntry] with that [LockfileEntry.inputHash]. */
    val byInputHash: Map<String, LockfileEntry>

    /** Asset id → most recent [LockfileEntry] that produced that asset. */
    val byAssetId: Map<AssetId, LockfileEntry>

    /**
     * Lazy iteration over entries — `debt-lockfile-lazy-interface-O1-open`
     * phase 2b-1a. Eager impl returns `entries.asSequence()`; lazy impl will
     * yield entries as the JSONL is parsed line-by-line.
     */
    fun stream(): Sequence<LockfileEntry>

    /** O(1) entry count. Lazy impl tracks count without materialising the list. */
    val size: Int

    /** Most-recent entry by insertion order — append-only ledger semantics. */
    fun lastOrNull(): LockfileEntry?

    /** O(1) emptiness check. */
    fun isEmpty(): Boolean

    fun findByInputHash(hash: String): LockfileEntry?

    /**
     * Look up the most recent entry that produced [assetId]. Used by stale-clip
     * detection to walk Clip → Asset → conditioning source nodes without
     * requiring `Clip.sourceBinding` to be threaded through `add_clip`.
     */
    fun findByAssetId(assetId: AssetId): LockfileEntry?

    fun append(entry: LockfileEntry): Lockfile

    /**
     * Flip the `pinned` flag on the most recent entry matching [inputHash].
     *
     * VISION §3.1 "产物可 pin" — once the user marks a hero-shot entry pinned,
     * it survives GC policy sweeps ([io.talevia.core.tool.builtin.project.ProjectMaintenanceActionTool])
     * and `regenerate_stale_clips` skips re-generating its clip even when the
     * bound source changed. Returns the lockfile unchanged when no entry matches,
     * so callers can fail loudly on their own.
     *
     * Only the most recent match is toggled — `findByInputHash` semantics. Older
     * duplicate-hash entries (the append-only ledger allows them when a provider
     * re-runs and happens to produce the same hash twice) stay untouched because
     * they are not the one a cache lookup would return.
     */
    fun withEntryPinned(inputHash: String, pinned: Boolean): Lockfile

    /**
     * Return a new lockfile keeping only entries for which [predicate] is true —
     * the typed mutation API replacing
     * `lockfile.copy(entries = lockfile.entries.filter(predicate))` (cycle 59).
     * Eager impl forwards to `entries.filter(predicate)`; lazy impl can run the
     * predicate against the stream and write a filtered JSONL.
     */
    fun filterEntries(predicate: (LockfileEntry) -> Boolean): Lockfile

    /**
     * Return a new lockfile with [transform] applied to each entry — the typed
     * mutation API for in-place rewrites (e.g. id renames in
     * [io.talevia.core.domain.source.rewriteSourceBinding]). Eager impl forwards
     * to `entries.map(transform)`; lazy impl streams + writes the transformed
     * JSONL.
     */
    fun mapEntries(transform: (LockfileEntry) -> LockfileEntry): Lockfile

    companion object {
        /**
         * Empty lockfile sentinel. Returns an [EagerLockfile] — there's no useful
         * "lazy zero-data" state, so eager is the natural identity element.
         */
        val EMPTY: Lockfile = EagerLockfile()
    }
}

/**
 * In-memory eager [Lockfile] impl — the only impl today. Serializable;
 * [byInputHash] / [byAssetId] are reconstructed on deserialize via the
 * `@Transient`-with-default-init pattern used by
 * [io.talevia.core.domain.source.Source.byId].
 */
@Serializable
data class EagerLockfile(
    override val entries: List<LockfileEntry> = emptyList(),
) : Lockfile {
    /**
     * Hash → most recent [LockfileEntry] with that [LockfileEntry.inputHash].
     *
     * `associateBy` overwrites on duplicate keys, so the resulting entry is the
     * last one in insertion order — matching the original
     * `entries.lastOrNull { … }` semantic.
     */
    @Transient
    override val byInputHash: Map<String, LockfileEntry> = entries.associateBy { it.inputHash }

    /**
     * Asset id → most recent [LockfileEntry] that produced that asset.
     * Same reconstruction + last-wins guarantee as [byInputHash].
     */
    @Transient
    override val byAssetId: Map<AssetId, LockfileEntry> = entries.associateBy { it.assetId }

    override fun stream(): Sequence<LockfileEntry> = entries.asSequence()

    override val size: Int get() = entries.size

    override fun lastOrNull(): LockfileEntry? = entries.lastOrNull()

    override fun isEmpty(): Boolean = entries.isEmpty()

    override fun findByInputHash(hash: String): LockfileEntry? = byInputHash[hash]

    override fun findByAssetId(assetId: AssetId): LockfileEntry? = byAssetId[assetId]

    override fun append(entry: LockfileEntry): Lockfile = copy(entries = entries + entry)

    override fun withEntryPinned(inputHash: String, pinned: Boolean): Lockfile {
        val idx = entries.indexOfLast { it.inputHash == inputHash }
        if (idx < 0) return this
        val current = entries[idx]
        if (current.pinned == pinned) return this
        return copy(entries = entries.toMutableList().apply { this[idx] = current.copy(pinned = pinned) })
    }

    override fun filterEntries(predicate: (LockfileEntry) -> Boolean): Lockfile =
        copy(entries = entries.filter(predicate))

    override fun mapEntries(transform: (LockfileEntry) -> LockfileEntry): Lockfile =
        copy(entries = entries.map(transform))
}

/**
 * Serializer adapter for `Lockfile` (sealed interface) → goes through
 * `EagerLockfile.serializer()` so the JSON shape is unchanged from the pre-lift
 * data class. Lazy impls materialize their entries to an `EagerLockfile` for
 * encode (rare path — the envelope-shrink already replaces `lockfile` with
 * `Lockfile.EMPTY` before write, so the only call site that hits a non-empty
 * is `ExportToolFingerprint`'s hash, which has its own cast).
 *
 * Without this adapter, `@Serializable sealed interface` would default to
 * polymorphic dispatch and emit a `"type": "EagerLockfile"` class discriminator
 * — breaking on-disk JSON shape and forcing migrations of existing bundles.
 */
object LockfileSerializer : KSerializer<Lockfile> {
    override val descriptor: SerialDescriptor = EagerLockfile.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Lockfile) {
        val eager = value as? EagerLockfile ?: EagerLockfile(value.entries.toList())
        EagerLockfile.serializer().serialize(encoder, eager)
    }

    override fun deserialize(decoder: Decoder): Lockfile =
        EagerLockfile.serializer().deserialize(decoder)
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
 *   `project_pin_action(target=lockfile_entry, pinned=true)`; cleared via `project_pin_action(target=lockfile_entry, pinned=false)`. When true:
 *   - `gc_lockfile` policy sweeps skip the entry regardless of age/count verdict,
 *   - `regenerate_stale_clips` skips every clip whose current lockfile entry is
 *     pinned (reason `"pinned"`), leaving the clip stale-but-frozen until the user
 *     unpins or removes the asset.
 *   Pinned entries are still subject to `prune_lockfile` — an orphan entry (no
 *   surviving asset) is dead weight regardless of intent, and the pin had no
 *   artifact to protect anyway. Default `false` preserves the legacy shape; old
 *   serialized lockfiles missing this field deserialize as unpinned.
 * @property costCents Best-effort USD cost of the generation in integer cents
 *   (i.e. 100 = $1.00), computed by [io.talevia.core.cost.AigcPricing] at record
 *   time from the tool id + provenance + inputs. **Three-state by design:**
 *   `null` = "we don't have a pricing rule for this model / call shape" (do not
 *   count toward totals); `0L` = "explicitly free" (counted, but adds nothing);
 *   positive Long = paid generation. Legacy entries written before this field
 *   existed deserialize as `null`, matching the "unknown" semantic. Feeds
 *   `session_query(select=spend)` and `project_query(select=spend)` aggregations.
 * @property sessionId The session id that issued the tool dispatch, when available.
 *   Required for `session_query(select=spend)` to scope totals to one session. Null
 *   on legacy entries, and on dispatches that somehow ran outside a session (not
 *   expected in production — every Agent turn has a sessionId). Storing on the
 *   entry rather than on a side-index keeps the lookup zero-IO: a session's spend
 *   is just a filter over `project.lockfile.entries`.
 * @property resolvedPrompt The fully-expanded prompt string that was actually
 *   sent to the provider, after consistency-fold (character_ref / style_bible /
 *   etc. prepended). Null when the tool has no prompt concept (upscale,
 *   synthesize_speech — the latter's `text` is already verbatim in `baseInputs`
 *   so duplicating it would waste bytes) or when the entry pre-dates this
 *   field. VISION §5.4 debug: lets the user answer "why didn't this image
 *   respect character_ref?" by comparing the stamped prompt against today's
 *   folded one, without having to re-run the fold in their head.
 * @property originatingMessageId The session message id whose tool call
 *   produced this entry. Lets the audit path answer "which prompt generated
 *   this image?" without having to `grep` session parts for the matching
 *   tool-call payload. Filled from [io.talevia.core.tool.ToolContext.messageId]
 *   at write time by every AIGC tool. Null on legacy entries and on
 *   dispatches that somehow ran without a ToolContext (not expected in
 *   production — every Agent turn has a messageId). VISION §5.2 audit trail.
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
    val costCents: Long? = null,
    val sessionId: String? = null,
    val resolvedPrompt: String? = null,
    val originatingMessageId: MessageId? = null,
    /**
     * Modality-segmented snapshot of each bound node's deep content hash, keyed
     * by [Modality]. Lets [io.talevia.core.domain.staleClipsFromLockfile] compare
     * only the slice the consuming clip actually depends on — e.g. a pure-video
     * generation does not stale when its bound `character_ref`'s `voiceId`
     * flips, because the visual slice's hash is unaffected.
     *
     * Default empty for forward compatibility: legacy entries (and any entries
     * written before this field landed) have no per-modality snapshot, and the
     * staleness detector falls back to the whole-body [sourceContentHashes]
     * comparison — i.e., the same "edit anything → stale" behavior we had
     * before. Once an entry carries this map, the modality-aware comparison
     * supersedes the legacy field.
     *
     * Populated by [io.talevia.core.tool.builtin.aigc.AigcPipeline.record] using
     * [io.talevia.core.domain.source.deepContentHashOfFor], so every AIGC tool
     * stamps both modality slices for every bound node — without each tool
     * needing to know which slice its consumer will read.
     */
    val sourceContentHashesByModality: Map<SourceNodeId, ModalityHashes> = emptyMap(),
    /**
     * Variant index of this generation when the producing AIGC tool requested
     * multiple variants in a single dispatch (`aigc-result-multi-variant`
     * phase 1). Default `0` for single-variant generations and pre-multi-
     * variant entries — every entry in the codebase today is `0` because
     * phase 2 (`aigc-multi-variant-phase2-dispatch`) hasn't surfaced
     * `variantCount` to the AIGC tool inputs yet. Phase 1 lands the schema
     * bit so the inputHash + Replay paths know about it.
     *
     * Included in the inputHash canonical string (see
     * [io.talevia.core.tool.builtin.aigc.AigcPipeline.inputHash] callers)
     * so two variants of the same prompt + seed produce distinct lockfile
     * keys — preventing N variants from clobbering each other in the cache.
     * Old entries (which didn't include the field in their hash) become
     * non-replayable on this cycle's first read; acceptable per user
     * authorization 2026-05-02 "无兼容性 + 理想架构推进".
     *
     * Forward-compat: default `0` so old serialised JSON / SQLite blobs
     * decode to a single-variant entry — value is right by construction
     * for every entry written before this field landed.
     */
    val variantIndex: Int = 0,
)

/**
 * Snapshot of one source node's deep content hash, segmented by [Modality]
 * (visual vs audio). Stored on [LockfileEntry] so the staleness detector can
 * compare only the slice the consuming clip actually consumes — see
 * [LockfileEntry.sourceContentHashesByModality].
 */
@Serializable
data class ModalityHashes(
    val visual: String,
    val audio: String,
) {
    fun forModality(modality: Modality): String = when (modality) {
        Modality.Visual -> visual
        Modality.Audio -> audio
    }
}
