package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Upsert the pinned flag on either a clip's underlying AIGC lockfile entry
 * (via `target=clip`, resolved `clipId → assetId → lockfile entry`) or
 * directly on a lockfile entry (via `target=lockfile_entry`, keyed by
 * `inputHash`). Replaces the pre-merge `set_clip_asset_pinned` +
 * `set_lockfile_entry_pinned` tool pair with a single dispatcher — both
 * paths share `pinnedBefore / pinnedAfter / changed` post-conditions plus
 * the same `Lockfile.withEntryPinned` mutation, so two LLM-visible tool
 * specs for the same underlying Set<Boolean> operation was pure token
 * overhead (§3a.2 / §3a.12).
 *
 * ## Pin semantics
 *
 * When `pinned=true`:
 *  - `gc_lockfile` rescues the entry regardless of `maxAgeDays` /
 *    `keepLatestPerTool` / `preserveLiveAssets=false` verdicts.
 *  - `regenerate_stale_clips` skips any clip whose current lockfile
 *    entry is pinned (reason `"pinned"`), leaving the clip stale-but-
 *    frozen until the user unpins it or replaces the clip outright.
 *  - `gc_lockfile` still drops orphan pinned entries — a pin with no
 *    surviving asset protects nothing.
 *
 * When `pinned=false`: entry is again subject to gc_lockfile policy and
 * regenerate_stale_clips can re-dispatch its clip on the next stale run.
 *
 * Idempotent: calling with the current value is a no-op, `changed=false`.
 *
 * ## Failure modes (all loud)
 *
 *  - `target=clip`, unknown clip id → "Clip {id} not found; use project_query(select=timeline_clips)".
 *  - `target=clip`, clip is `Clip.Text` → "text clips have no asset; pinning is meaningless".
 *  - `target=clip`, clip has an asset but no lockfile entry → "likely an
 *    imported media asset; use target=lockfile_entry with an explicit
 *    inputHash if you want that behavior".
 *  - `target=lockfile_entry`, unknown inputHash → "call project_query(select=lockfile_entries) to list valid hashes".
 *  - Missing / extra target-specific id → reject at the boundary with a
 *    message naming the correct field for the target.
 */
class ProjectPinActionTool(
    private val projects: ProjectStore,
) : Tool<ProjectPinActionTool.Input, ProjectPinActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** [TARGET_CLIP] — resolve via clipId, or [TARGET_LOCKFILE_ENTRY] — direct by inputHash. Case-insensitive. */
        val target: String,
        /** Required for [TARGET_CLIP]; rejected for [TARGET_LOCKFILE_ENTRY]. */
        val clipId: String? = null,
        /** Required for [TARGET_LOCKFILE_ENTRY]; rejected for [TARGET_CLIP]. */
        val inputHash: String? = null,
        /** `true` freezes the entry as a hero shot; `false` clears the pin. */
        val pinned: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val target: String,
        /** Echo of the [Input.clipId] when [Input.target]=clip; null otherwise. */
        val clipId: String? = null,
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        /** Pinned flag before the call. */
        val pinnedBefore: Boolean,
        /** Pinned flag after the call — equals [Input.pinned]. */
        val pinnedAfter: Boolean,
        /** True when the call actually flipped the flag. */
        val changed: Boolean,
    )

    override val id: String = "project_pin_action"
    override val helpText: String =
        "Upsert the pinned flag on an AIGC lockfile entry, either by clip-reference (target=clip, " +
            "resolves clipId → assetId → entry) or by direct hash (target=lockfile_entry, keyed by " +
            "inputHash). When pinned=true, gc_lockfile rescues the entry and regenerate_stale_clips " +
            "leaves the clip stale-but-frozen. When pinned=false, the entry is again subject to policy. " +
            "Idempotent — returns changed=false when the flag already matches. Fails loudly for text " +
            "clips (no asset), clips without a matching lockfile entry (imported media — use " +
            "target=lockfile_entry with an explicit inputHash if you really mean some other entry), " +
            "and unknown inputHash."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("target") {
                put("type", "string")
                put(
                    "description",
                    "clip (resolve via clipId, fails on text clips / imported media) | " +
                        "lockfile_entry (direct by inputHash from project_query(select=lockfile_entries)).",
                )
                put(
                    "enum",
                    buildJsonArray {
                        add(JsonPrimitive(TARGET_CLIP))
                        add(JsonPrimitive(TARGET_LOCKFILE_ENTRY))
                    },
                )
            }
            putJsonObject("clipId") {
                put("type", "string")
                put("description", "Id of the clip to pin/unpin. Required when target=clip; rejected otherwise.")
            }
            putJsonObject("inputHash") {
                put("type", "string")
                put(
                    "description",
                    "Lockfile entry identifier from project_query(select=lockfile_entries). " +
                        "Required when target=lockfile_entry; rejected otherwise.",
                )
            }
            putJsonObject("pinned") {
                put("type", "boolean")
                put("description", "true to freeze as a hero shot; false to clear the pin.")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("target"), JsonPrimitive("pinned"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val target = input.target.lowercase()
        rejectIncompatibleFields(target, input)

        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        return when (target) {
            TARGET_CLIP -> pinByClip(project, pid, input)
            TARGET_LOCKFILE_ENTRY -> pinByLockfileEntry(project, pid, input)
            else -> error(
                "Unknown target '${input.target}'. Valid: $TARGET_CLIP | $TARGET_LOCKFILE_ENTRY.",
            )
        }
    }

    private fun rejectIncompatibleFields(target: String, input: Input) {
        when (target) {
            TARGET_CLIP -> {
                require(!input.clipId.isNullOrBlank()) {
                    "target=clip requires clipId (non-blank)."
                }
                require(input.inputHash == null) {
                    "target=clip rejects inputHash (that field belongs to target=lockfile_entry)."
                }
            }
            TARGET_LOCKFILE_ENTRY -> {
                require(!input.inputHash.isNullOrBlank()) {
                    "target=lockfile_entry requires inputHash (non-blank)."
                }
                require(input.clipId == null) {
                    "target=lockfile_entry rejects clipId (that field belongs to target=clip)."
                }
            }
            else -> error("Unknown target '$target'. Valid: $TARGET_CLIP | $TARGET_LOCKFILE_ENTRY.")
        }
    }

    private suspend fun pinByClip(
        project: Project,
        pid: ProjectId,
        input: Input,
    ): ToolResult<Output> {
        val cid = ClipId(input.clipId!!)
        val assetId = project.assetIdForClip(cid)
        val entry = project.lockfile.findByAssetId(assetId)
            ?: error(
                "Clip ${input.clipId} has asset ${assetId.value} but no lockfile entry — likely an " +
                    "imported media asset (not AIGC-generated). Use target=lockfile_entry with an " +
                    "explicit inputHash if you really want to pin some other entry.",
            )
        val pinnedBefore = entry.pinned
        val changed = pinnedBefore != input.pinned
        if (changed) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(entry.inputHash, pinned = input.pinned))
            }
        }
        val verbNow = if (input.pinned) "pinned" else "unpinned"
        val verbBefore = if (changed) "" else "(was already $verbNow) "
        val gcHint = if (input.pinned) {
            "gc_lockfile skips it and regenerate_stale_clips leaves the clip stale-but-frozen."
        } else {
            "gc_lockfile can GC it and regenerate_stale_clips will re-dispatch if the clip goes stale."
        }
        return ToolResult(
            title = "${if (input.pinned) "pin" else "unpin"} clip ${input.clipId}",
            outputForLlm = "Clip ${input.clipId} → asset ${assetId.value} $verbBefore$verbNow. " +
                "Underlying lockfile entry ${entry.inputHash} produced by ${entry.toolId} — $gcHint",
            data = Output(
                projectId = pid.value,
                target = TARGET_CLIP,
                clipId = input.clipId,
                inputHash = entry.inputHash,
                toolId = entry.toolId,
                assetId = assetId.value,
                pinnedBefore = pinnedBefore,
                pinnedAfter = input.pinned,
                changed = changed,
            ),
        )
    }

    private suspend fun pinByLockfileEntry(
        project: Project,
        pid: ProjectId,
        input: Input,
    ): ToolResult<Output> {
        val entry = project.lockfile.findByInputHash(input.inputHash!!)
            ?: error(
                "Lockfile entry with inputHash '${input.inputHash}' not found in project " +
                    "${input.projectId}. Call project_query(select=lockfile_entries) to see valid hashes.",
            )
        val pinnedBefore = entry.pinned
        val changed = pinnedBefore != input.pinned
        if (changed) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(input.inputHash, pinned = input.pinned))
            }
        }
        val verbNow = if (input.pinned) "pinned" else "unpinned"
        val verbBefore = if (changed) "" else "(was already $verbNow) "
        val gcHint = if (input.pinned) {
            "gc_lockfile will skip it and regenerate_stale_clips will leave its clip frozen."
        } else {
            "gc_lockfile can drop it by policy and regenerate_stale_clips will re-dispatch if its clip goes stale."
        }
        return ToolResult(
            title = "${if (input.pinned) "pin" else "unpin"} lockfile entry ${entry.toolId}/${entry.assetId.value}",
            outputForLlm = "Entry ${input.inputHash} $verbBefore$verbNow. " +
                "Asset ${entry.assetId.value} produced by ${entry.toolId} — $gcHint",
            data = Output(
                projectId = pid.value,
                target = TARGET_LOCKFILE_ENTRY,
                clipId = null,
                inputHash = input.inputHash,
                toolId = entry.toolId,
                assetId = entry.assetId.value,
                pinnedBefore = pinnedBefore,
                pinnedAfter = input.pinned,
                changed = changed,
            ),
        )
    }

    companion object {
        const val TARGET_CLIP: String = "clip"
        const val TARGET_LOCKFILE_ENTRY: String = "lockfile_entry"
    }
}

/**
 * Shared resolution: clipId → assetId. Walks every track in timeline order,
 * fails loud with a concrete diagnostic for the two actionable failure modes
 * (unknown clip, text clip with no asset). Internal to this file so the
 * two pin targets share a single cast-boundary.
 */
private fun Project.assetIdForClip(clipId: ClipId): AssetId {
    for (track in timeline.tracks) {
        val clip = track.clips.firstOrNull { it.id == clipId } ?: continue
        return when (clip) {
            is Clip.Video -> clip.assetId
            is Clip.Audio -> clip.assetId
            is Clip.Text -> error(
                "Clip ${clipId.value} is a text clip (no asset). Pinning only applies to " +
                    "AIGC-generated media clips; text is hand-authored and has no lockfile entry.",
            )
        }
    }
    error(
        "Clip ${clipId.value} not found in project ${id.value}. Call project_query(select=timeline_clips) to " +
            "discover valid clip ids.",
    )
}
