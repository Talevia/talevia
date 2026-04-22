package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.consistency.FoldedPrompt
import io.talevia.core.domain.source.consistency.FoldedVoice
import io.talevia.core.domain.source.consistency.consistencyNodes
import io.talevia.core.domain.source.consistency.foldConsistencyIntoPrompt
import io.talevia.core.domain.source.consistency.resolveConsistencyBindings
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import io.talevia.core.util.fnv1a64Hex
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import io.talevia.core.domain.source.consistency.foldVoice as foldVoiceFn

/**
 * Shared pipeline for AIGC tools (image-gen, future TTS / music / text-to-video).
 *
 * Encapsulates two things VISION §3.1 calls for on every generative call, so every new
 * AIGC tool does not reinvent them:
 *
 *   1. **Seed discipline.** Callers don't always know a seed; we mint one client-side
 *      before calling the engine so provenance is always complete. Tools call
 *      [ensureSeed] to get a seed suitable for passing to the engine + recording.
 *
 *   2. **Lockfile cache hit / miss.** The canonical "inputs" of a generation hash to
 *      a stable [LockfileEntry.inputHash]. A matching entry means we've already
 *      produced this exact output — we can return the asset without hitting the
 *      provider again. Tools call [findCached] before generating and [record] after.
 *
 * The pipeline is *stateless* and plain functions — deliberately not an abstract class
 * — because Kotlin composition beats inheritance and tools have different I/O shapes.
 * They all call the same handful of helpers.
 */
internal object AigcPipeline {

    /**
     * If [explicit] is null, generate a deterministic-but-unguessable seed via
     * [nextClientSideSeed]. Either way the returned value is safe to pass to the
     * engine and record in provenance.
     */
    fun ensureSeed(explicit: Long?): Long = explicit ?: nextClientSideSeed()

    /**
     * Fold consistency bindings into [basePrompt] using [project]'s source graph.
     *
     * [bindingIds] semantics:
     *  - `null`  — auto-fold: pick up all consistency nodes from the project source
     *              (VISION §5.5 "cross-shot consistency without explicit wiring").
     *  - `[]`    — explicitly no binding: skip folding even if nodes exist.
     *  - non-empty — fold only the listed nodes.
     */
    fun foldPrompt(
        project: Project,
        basePrompt: String,
        bindingIds: List<SourceNodeId>?,
    ): FoldedPrompt {
        if (bindingIds != null && bindingIds.isEmpty()) return foldConsistencyIntoPrompt(basePrompt, emptyList())
        val nodes = if (bindingIds == null) {
            project.source.consistencyNodes()
        } else {
            project.source.resolveConsistencyBindings(bindingIds)
        }
        return foldConsistencyIntoPrompt(basePrompt, nodes)
    }

    /**
     * Resolve consistency bindings into a voice pick for TTS / voice-clone calls.
     *
     * [bindingIds] semantics match [foldPrompt]: null = auto (all character_refs),
     * `[]` = explicitly no binding. Ambiguous auto (multiple characters with
     * voiceIds) returns no-voice rather than throwing — the caller can fall back to
     * the user's explicit `voice` input.
     */
    fun foldVoice(
        project: Project,
        bindingIds: List<SourceNodeId>?,
    ): FoldedVoice {
        if (bindingIds != null && bindingIds.isEmpty()) return FoldedVoice(voiceId = null, appliedNodeIds = emptyList())
        val nodes = if (bindingIds == null) {
            project.source.consistencyNodes()
        } else {
            project.source.resolveConsistencyBindings(bindingIds)
        }
        return foldVoiceFn(nodes)
    }

    /**
     * Compute the canonical input hash for cache lookup / storage.
     *
     * [fields] are concatenated with `|` as a field separator and `=` inside each
     * `key=value` pair. Callers pass every input that can change the output — for
     * image-gen: tool id, model, version, seed, dimensions, effective prompt,
     * applied binding ids. The order of [fields] matters only in so far as callers
     * are consistent with themselves; we don't sort, because collision across
     * different tools keying their inputs differently is impossible (tool id is
     * always the first field).
     */
    fun inputHash(fields: List<Pair<String, String>>): String {
        val canonical = fields.joinToString(separator = "|") { (k, v) -> "$k=$v" }
        return fnv1a64Hex(canonical)
    }

    /** Look up a cached entry by input hash, reading the current project state. */
    suspend fun findCached(
        store: ProjectStore,
        projectId: ProjectId,
        inputHash: String,
    ): LockfileEntry? = store.get(projectId)?.lockfile?.findByInputHash(inputHash)

    /**
     * Persist a new lockfile entry. Uses [ProjectStore.mutate] so the append goes
     * through the store's mutex and can't race with concurrent tool dispatches.
     *
     * As a side effect, snapshots the current `SourceNode.contentHash` for every
     * id in [sourceBinding]. The snapshot lives on
     * [LockfileEntry.sourceContentHashes] and powers stale-clip detection
     * (`Project.staleClipsFromLockfile`) — without it the detector has no anchor
     * to compare today's hash against.
     */
    /**
     * Wrap a long-running provider call so the Agent's UI / SSE subscribers
     * see a [Part.RenderProgress] "started" marker immediately, a "completed"
     * marker on success, or a "failed: …" marker if [block] throws.
     *
     * Same `partId` is reused across all emits so consumers treat this as
     * a single logical progress row (the latest payload wins — pattern
     * mirrors `ExportTool.executeRender`). No middle-progress ratio today:
     * the engine interfaces ([VideoGenEngine] / [ImageGenEngine] / etc.)
     * don't expose poll-progress callbacks yet, so we can only emit
     * bookends. When engines grow a `onProgress: (ratio) -> Unit` param
     * this helper will forward it; until then a single start-complete pair
     * is already enough to flip the UI from "silent" to "generating…".
     *
     * On failure, the progress marker flips to `ratio=0 + "failed: <msg>"`
     * and the exception rethrows untouched — caller's normal error path
     * still runs.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun <T> withProgress(
        ctx: ToolContext,
        jobId: String,
        startMessage: String,
        clock: Clock = Clock.System,
        block: suspend () -> T,
    ): T {
        val partId = PartId(Uuid.random().toString())
        ctx.emitPart(
            Part.RenderProgress(
                id = partId,
                messageId = ctx.messageId,
                sessionId = ctx.sessionId,
                createdAt = clock.now(),
                jobId = jobId,
                ratio = 0f,
                message = startMessage,
            ),
        )
        return try {
            val result = block()
            ctx.emitPart(
                Part.RenderProgress(
                    id = partId,
                    messageId = ctx.messageId,
                    sessionId = ctx.sessionId,
                    createdAt = clock.now(),
                    jobId = jobId,
                    ratio = 1f,
                    message = "completed",
                ),
            )
            result
        } catch (t: Throwable) {
            ctx.emitPart(
                Part.RenderProgress(
                    id = partId,
                    messageId = ctx.messageId,
                    sessionId = ctx.sessionId,
                    createdAt = clock.now(),
                    jobId = jobId,
                    ratio = 0f,
                    message = "failed: ${t.message ?: t::class.simpleName ?: "unknown"}",
                ),
            )
            throw t
        }
    }

    suspend fun record(
        store: ProjectStore,
        projectId: ProjectId,
        toolId: String,
        inputHash: String,
        assetId: AssetId,
        provenance: GenerationProvenance,
        sourceBinding: Set<SourceNodeId>,
        baseInputs: JsonObject = JsonObject(emptyMap()),
        costCents: Long? = null,
        sessionId: io.talevia.core.SessionId? = null,
    ) {
        store.mutate(projectId) { project ->
            val snapshot: Map<SourceNodeId, String> = if (sourceBinding.isEmpty()) emptyMap()
            else buildMap {
                for (id in sourceBinding) {
                    val node = project.source.byId[id] ?: continue
                    put(id, node.contentHash)
                }
            }
            project.copy(
                lockfile = project.lockfile.append(
                    LockfileEntry(
                        inputHash = inputHash,
                        toolId = toolId,
                        assetId = assetId,
                        provenance = provenance,
                        sourceBinding = sourceBinding,
                        sourceContentHashes = snapshot,
                        baseInputs = baseInputs,
                        costCents = costCents,
                        sessionId = sessionId?.value,
                    ),
                ),
            )
        }
    }
}

/**
 * Best-effort cross-platform "pick an unpredictable Long". On targets where
 * `kotlin.random.Random.Default` is cryptographically weak this is still good enough
 * for *seed assignment* — it just needs to be non-colliding across calls in a
 * single session. True unpredictability is provider-side concern.
 */
private fun nextClientSideSeed(): Long = kotlin.random.Random.Default.nextLong()
