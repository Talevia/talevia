package io.talevia.core.tool.builtin.aigc

import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * VISION §5.2 "相同 source + 相同 toolchain 重跑产物是否 bit-identical" —
 * the missing primitive for lockfile-driven replay.
 *
 * Given an existing [LockfileEntry.inputHash], re-dispatches the tool that
 * produced it with the exact [LockfileEntry.baseInputs] captured at
 * generation time, explicitly bypassing the findCached short-circuit so a
 * fresh provider call happens. The new generation lands a second lockfile
 * entry (unpinned) alongside the original, leaving the original intact for
 * comparison. The agent (or UI) can then pin the preferred asset via
 * `project_pin_action(target=lockfile_entry)` or delete the loser.
 *
 * **What this tool verifies.**
 *
 * - *Cache-path integrity*: the recorded `baseInputs` + `toolId` are
 *   sufficient to reconstruct the exact tool dispatch. If the replay fails
 *   for a non-provider reason (missing tool registration, malformed
 *   baseInputs) the lockfile entry is effectively un-replayable and the
 *   error surface here tells the user.
 * - *Provider determinism*: whether the same inputs produce the same output
 *   bytes. Image and music models are nondeterministic today (OpenAI
 *   DALL-E, Replicate MusicGen); the replay produces a new asset and the
 *   agent can compare side-by-side. A seed-stable local model would
 *   produce bit-identical bytes — the primitive is the same either way.
 * - *Consistency-fold drift*: the target tool re-runs its consistency fold
 *   against **today's** source graph. If source nodes were edited between
 *   the original generation and the replay, the re-folded prompt differs,
 *   the recomputed inputHash differs, and [Output.inputHashStable] is
 *   `false`. That case overlaps with `regenerate_stale_clips` — the
 *   replay is the lower-level primitive, stale-clip regeneration is the
 *   higher-level batched path.
 *
 * **What this tool does NOT do.**
 *
 * - Byte-level bit-identity verification. Comparing asset bytes requires a
 *   platform-level blob reader we don't have in `core/commonMain` today;
 *   we ship both `originalAssetId` and `newAssetId` so the caller can
 *   inspect externally.
 * - Pinning either entry. Pinning is a deliberate user action; we don't
 *   assume the replay or the original is the winner.
 * - Recovering from legacy lockfile entries that pre-date `baseInputs`
 *   capture. Those entries have an empty `baseInputs` JsonObject and can
 *   never be replayed — we return a clear error.
 *
 * @see CompareAigcCandidatesTool for A/B comparison across *different*
 *   models (parallel fan-out, zero lockfile lookups); ReplayLockfileTool
 *   is for same-model re-dispatch of a *past* generation.
 * @see io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool for
 *   the batched path that regenerates every stale clip after a source edit.
 */
class ReplayLockfileTool(
    private val registry: ToolRegistry,
    private val projects: ProjectStore,
) : Tool<ReplayLockfileTool.Input, ReplayLockfileTool.Output> {

    @Serializable data class Input(
        /** The lockfile entry's `inputHash` to replay. */
        val inputHash: String,
        /** Optional; defaults to the session's current project binding. */
        val projectId: String? = null,
    )

    @Serializable data class Output(
        val originalInputHash: String,
        val toolId: String,
        val originalAssetId: String,
        val newAssetId: String,
        /**
         * The inputHash of the *new* lockfile entry appended by the replay.
         * Normally equal to [originalInputHash]. Differs when the target
         * tool's consistency fold produced a different effective prompt
         * against today's source graph (i.e. the bound source nodes were
         * edited between original generation and replay).
         */
        val newInputHash: String,
        /**
         * `true` when [newInputHash] equals [originalInputHash] — the
         * replay's computed inputs matched the original's exactly. `false`
         * means consistency drift: the replay effectively ran against a
         * different source state than the original. Either outcome is
         * useful signal; the flag disambiguates.
         */
        val inputHashStable: Boolean,
        val originalProvenance: GenerationProvenance,
        val newProvenance: GenerationProvenance,
    )

    override val id: String = "replay_lockfile"
    override val helpText: String =
        "Re-run a past AIGC generation by inputHash, using the exact baseInputs captured in the " +
            "project lockfile. The target tool (generate_image / generate_video / generate_music / " +
            "synthesize_speech / upscale_asset) is dispatched with the original inputs and the " +
            "lockfile cache is bypassed, so a fresh provider call happens even though an entry " +
            "already exists. The new generation lands an unpinned lockfile entry next to the " +
            "original; pin the winner with project_pin_action(target=lockfile_entry). Use this to verify provider " +
            "determinism or re-roll a past generation without reconstructing inputs by hand. " +
            "Fails if the entry pre-dates baseInputs capture or the original tool id isn't " +
            "registered in this container."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability = io.talevia.core.tool.ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("inputHash") {
                put("type", "string")
                put(
                    "description",
                    "The `inputHash` of an existing lockfile entry to replay. Obtain via " +
                        "project_query(select=lockfile).",
                )
            }
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional. Defaults to the session's current project binding.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("inputHash"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        val projectBefore = projects.get(pid)
            ?: error("Project ${pid.value} not found")
        val entry = projectBefore.lockfile.findByInputHash(input.inputHash)
            ?: error(
                "No lockfile entry with inputHash='${input.inputHash}' in project ${pid.value}. " +
                    "Call project_query(select=lockfile) to see available entries.",
            )
        if (entry.baseInputs.isEmpty()) {
            error(
                "Lockfile entry '${input.inputHash}' (tool=${entry.toolId}) pre-dates baseInputs " +
                    "capture — the original tool call can't be reconstructed. This entry is " +
                    "not replayable.",
            )
        }
        val target = registry[entry.toolId] ?: run {
            // `aigc-tool-consolidation-phase2-unregister-originals` (cycle 27):
            // the 4 generate_*/synthesize_speech tools are no longer
            // registered as standalone surfaces — `aigc_generate` routes
            // them via `kind`. Lockfile entries written before phase 2
            // still carry the legacy toolId and can't be replayed
            // through the dispatcher cleanly because `baseInputs` is
            // the inner tool's Input shape (no `kind` discriminator,
            // and SynthesizeSpeechTool used `text` where the dispatcher
            // expects `prompt`). Surface the situation as a precise
            // error rather than the generic "tool not found" so the
            // user / agent knows the entry pre-dates the consolidation
            // and re-running the original prompt through `aigc_generate`
            // is the right next step.
            if (entry.toolId in LEGACY_AIGC_TOOL_IDS) {
                error(
                    "Lockfile entry '${input.inputHash}' (tool=${entry.toolId}) pre-dates " +
                        "aigc-tool-consolidation phase 2 — the standalone " +
                        "${entry.toolId} tool is no longer registered. Re-issue the original " +
                        "request via aigc_generate(kind=" +
                        "${LEGACY_AIGC_TOOL_IDS[entry.toolId]}, prompt=...) instead.",
                )
            }
            error(
                "Target tool '${entry.toolId}' is not registered in this container. " +
                    "The provider / engine may have been removed since this entry was written.",
            )
        }

        val lockfileSizeBefore = projectBefore.lockfile.entries.size
        // forReplay() flips ctx.isReplay=true so the AIGC tool skips its
        // findCached short-circuit and calls the provider even though an
        // entry with this inputHash already exists. Source consistency fold
        // still runs, so a drifted source graph will produce a different
        // effective prompt (hence different newInputHash) — flagged via
        // inputHashStable.
        target.dispatch(entry.baseInputs, ctx.forReplay())

        val projectAfter = projects.get(pid)
            ?: error("Project ${pid.value} disappeared mid-replay")
        val lockfileSizeAfter = projectAfter.lockfile.entries.size
        if (lockfileSizeAfter <= lockfileSizeBefore) {
            error(
                "Replay of ${entry.toolId} appended no new lockfile entry — the target tool " +
                    "may have failed silently or is not instrumented to record generations. " +
                    "Original entry left untouched.",
            )
        }
        val newEntry = projectAfter.lockfile.entries.last()

        val stable = newEntry.inputHash == entry.inputHash
        val driftNote = if (stable) "" else
            " — consistency fold drifted since original (source graph was edited)"
        return ToolResult(
            title = "replay lockfile ${entry.toolId}",
            outputForLlm = "Replayed ${entry.toolId}: original asset ${entry.assetId.value} → " +
                "new asset ${newEntry.assetId.value}$driftNote. Both entries are in the " +
                "lockfile; pin the winner with project_pin_action(target=lockfile_entry).",
            data = Output(
                originalInputHash = entry.inputHash,
                toolId = entry.toolId,
                originalAssetId = entry.assetId.value,
                newAssetId = newEntry.assetId.value,
                newInputHash = newEntry.inputHash,
                inputHashStable = stable,
                originalProvenance = entry.provenance,
                newProvenance = newEntry.provenance,
            ),
        )
    }

    private companion object {
        // Legacy AIGC tool ids -> aigc_generate kind discriminator.
        // The mapping is informational only (used to compose the error
        // message above); we don't actually re-dispatch through the
        // dispatcher because the stored `baseInputs` shape doesn't
        // round-trip cleanly (see comment at the call site).
        private val LEGACY_AIGC_TOOL_IDS: Map<String, String> = mapOf(
            "generate_image" to "image",
            "generate_video" to "video",
            "generate_music" to "music",
            "synthesize_speech" to "speech",
        )
    }
}
