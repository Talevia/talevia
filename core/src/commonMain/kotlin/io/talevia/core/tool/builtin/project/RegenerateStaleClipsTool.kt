package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.video.emitTimelineSnapshot
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
 * Close the VISION §3.2 refactor loop: edit a source node → this tool
 * regenerates every downstream AIGC clip and splices each new asset back into
 * the timeline. Maps directly to the worked example in VISION §6 ("修改主角发色
 * → 传导到 character reference → 引用该 reference 的所有镜头标记 stale →
 * 只重编译这些镜头").
 *
 * Before this tool the flow required the agent to:
 *   1. `find_stale_clips` — get N stale ids
 *   2. For each, figure out which AIGC tool produced the asset and what inputs
 *      to hand it (not trivially recoverable from `provenance.parameters`, which
 *      holds the *folded* prompt, not the base prompt)
 *   3. Call that tool
 *   4. `replace_clip` with the new asset id
 * Four round-trips per stale clip, plus a nontrivial reverse-engineering step
 * in #2 that every project's agent would redo. This tool does all of it in one
 * call by re-dispatching the exact [LockfileEntry.baseInputs] captured at
 * generation time — consistency folding re-runs against *today's* source
 * graph, so the regeneration automatically picks up the edit that made the
 * clip stale.
 *
 * Skipping rules. We skip clips when:
 *   - The lockfile entry pre-dates `baseInputs` (legacy; we cannot
 *     reconstruct the original tool inputs).
 *   - The original tool id is not registered in the current `ToolRegistry`
 *     (provider / platform config changed since generation).
 *   - The AIGC re-dispatch produces a cache-hit (identical inputs → same
 *     assetId → nothing to swap).
 * Skipped clips remain stale; the tool returns them in [Output.skipped] so
 * the agent can reason about what's unresolved.
 *
 * Permission: `"aigc.generate"` — this batches multiple aigc calls under the
 * single consent the user grants for this tool; callers are explicitly asking
 * "regenerate every stale clip."
 */
class RegenerateStaleClipsTool(
    private val projects: ProjectStore,
    private val tools: ToolRegistry,
) : Tool<RegenerateStaleClipsTool.Input, RegenerateStaleClipsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * Optional filter: when non-empty, only regenerate clips whose ids appear
         * here *and* that are currently stale. Empty (default) means "every stale
         * clip" — the original batch semantics. Ids passed that aren't stale are
         * silently ignored (they return no report; they're already fresh), so
         * callers can use this field as a "regenerate these if needed" hint.
         */
        val clipIds: List<String> = emptyList(),
    )

    @Serializable data class Regenerated(
        val clipId: String,
        val toolId: String,
        val previousAssetId: String,
        val newAssetId: String,
        val sourceBindingIds: List<String>,
    )

    @Serializable data class Skipped(
        val clipId: String,
        val reason: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalStale: Int,
        val regenerated: List<Regenerated>,
        val skipped: List<Skipped>,
    )

    override val id: String = "regenerate_stale_clips"
    override val helpText: String =
        "Regenerate every stale AIGC clip in the project by re-dispatching the tool that produced " +
            "it with its original inputs; consistency folding re-runs against the current source " +
            "graph so the regenerations pick up the edit that made them stale. Swaps each new asset " +
            "onto its clip's timeline slot. Use after editing a character_ref / style_bible / " +
            "brand_palette; one call replaces the find_stale_clips → generate_* → replace_clip " +
            "chain. Clips whose lockfile entry is pinned (set_lockfile_entry_pinned) are skipped with " +
            "reason 'pinned' — a pin is user intent and overrides regeneration."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional: regenerate only these clip ids (if stale). Omit to regenerate every stale clip.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val initialProject = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val allReports = initialProject.staleClipsFromLockfile()
        val reports = if (input.clipIds.isEmpty()) {
            allReports
        } else {
            val filter = input.clipIds.toSet()
            allReports.filter { it.clipId.value in filter }
        }
        if (reports.isEmpty()) {
            return ToolResult(
                title = "regenerate stale clips (none)",
                outputForLlm = "Nothing to do — no clips are stale in project ${input.projectId}.",
                data = Output(
                    projectId = input.projectId,
                    totalStale = 0,
                    regenerated = emptyList(),
                    skipped = emptyList(),
                ),
            )
        }

        val regenerated = mutableListOf<Regenerated>()
        val skipped = mutableListOf<Skipped>()

        for (report in reports) {
            val projectNow = projects.get(pid) ?: error("Project ${input.projectId} disappeared mid-regeneration")
            val entry = projectNow.lockfile.findByAssetId(report.assetId)
            if (entry == null) {
                skipped += Skipped(report.clipId.value, "no lockfile entry for asset ${report.assetId.value}")
                continue
            }
            if (entry.pinned) {
                // VISION §3.1 "产物可 pin" — user marked this generation as a hero
                // shot. Leave the clip stale-but-frozen until the user explicitly
                // unpins it (via set_lockfile_entry_pinned pinned=false) or replaces the clip.
                skipped += Skipped(report.clipId.value, "pinned")
                continue
            }
            if (entry.baseInputs.isEmpty()) {
                skipped += Skipped(
                    report.clipId.value,
                    "legacy lockfile entry (pre-baseInputs); can't reconstruct original tool call",
                )
                continue
            }
            val registered = tools[entry.toolId]
            if (registered == null) {
                skipped += Skipped(report.clipId.value, "tool '${entry.toolId}' is not registered in this container")
                continue
            }

            val lockfileSizeBefore = projectNow.lockfile.entries.size
            // Run the original tool with its raw inputs. Consistency folding inside
            // the tool re-runs against today's source graph, so the effective prompt
            // captures the edit that flagged this clip stale.
            val dispatchResult = runCatching { registered.dispatch(entry.baseInputs, ctx) }
            val ex = dispatchResult.exceptionOrNull()
            if (ex != null) {
                skipped += Skipped(
                    report.clipId.value,
                    "re-dispatch of ${entry.toolId} failed: ${ex.message ?: ex::class.simpleName}",
                )
                continue
            }

            val projectAfter = projects.get(pid)
                ?: error("Project ${input.projectId} disappeared after dispatching ${entry.toolId}")
            if (projectAfter.lockfile.entries.size <= lockfileSizeBefore) {
                // Cache hit or tool produced no new entry — either way, nothing new
                // to splice. Could mean today's input hash matches a previous entry
                // (e.g. someone reverted the source edit before calling us).
                skipped += Skipped(
                    report.clipId.value,
                    "${entry.toolId} produced no new lockfile entry (cache hit or no-op)",
                )
                continue
            }
            val newEntry = projectAfter.lockfile.entries.last()
            val newAssetId = newEntry.assetId

            var swapped = false
            projects.mutate(pid) { p ->
                val replacementBinding = newEntry.sourceBinding
                val newTracks = p.timeline.tracks.map { track ->
                    val target = track.clips.firstOrNull { it.id == report.clipId } ?: return@map track
                    val replaced: Clip = when (target) {
                        is Clip.Video -> {
                            swapped = true
                            target.copy(assetId = newAssetId, sourceBinding = replacementBinding)
                        }
                        is Clip.Audio -> {
                            swapped = true
                            target.copy(assetId = newAssetId, sourceBinding = replacementBinding)
                        }
                        is Clip.Text -> target
                    }
                    replaceClipOnTrack(track, target, replaced)
                }
                p.copy(timeline = p.timeline.copy(tracks = newTracks))
            }
            if (!swapped) {
                skipped += Skipped(
                    report.clipId.value,
                    "clip vanished from timeline before regeneration could splice",
                )
                continue
            }
            regenerated += Regenerated(
                clipId = report.clipId.value,
                toolId = entry.toolId,
                previousAssetId = entry.assetId.value,
                newAssetId = newAssetId.value,
                sourceBindingIds = newEntry.sourceBinding.map { it.value },
            )
        }

        // One snapshot covers the whole batch — matches the "one intent = one undo"
        // rule used by add_subtitles / auto_subtitle_clip.
        val snapshotId = if (regenerated.isNotEmpty()) {
            emitTimelineSnapshot(ctx, projects.get(pid)!!.timeline)
        } else {
            null
        }

        val summary = buildString {
            append("Regenerated ${regenerated.size} of ${reports.size} stale clip(s) in ${input.projectId}")
            if (skipped.isNotEmpty()) {
                append("; skipped ${skipped.size}")
            }
            if (snapshotId != null) {
                append(". Timeline snapshot: ${snapshotId.value}")
            }
            if (skipped.isNotEmpty()) {
                append(". Skips: ")
                append(skipped.take(5).joinToString("; ") { "${it.clipId} (${it.reason})" })
                if (skipped.size > 5) append("; …")
            }
        }

        return ToolResult(
            title = "regenerate stale clips x${regenerated.size}",
            outputForLlm = summary,
            data = Output(
                projectId = input.projectId,
                totalStale = reports.size,
                regenerated = regenerated,
                skipped = skipped,
            ),
        )
    }

    private fun replaceClipOnTrack(track: Track, removed: Clip, replacement: Clip): Track {
        val clips = track.clips.map { if (it.id == removed.id) replacement else it }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
