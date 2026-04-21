package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Clip-level hero-shot toggle. Resolves a [Clip.Video] / [Clip.Audio] to its
 * backing lockfile entry and sets that entry's `pinned` flag to [Input.pinned].
 *
 * Replaces the pre-split `pin_clip_asset` + `unpin_clip_asset` tool pair with
 * a single upsert. For the LLM, two mutually-exclusive branches were pure spec
 * cost — `set_<concept>_pinned` mirrors the `set_character_ref` shape we use
 * for source-node upserts. See `docs/decisions/2026-04-21-debt-merge-pin-unpin-tool-pairs.md`.
 *
 * Resolution path: `ClipId` → `Clip.Video|Audio.assetId` →
 * `Lockfile.findByAssetId` (most-recent match) → `Lockfile.withEntryPinned`.
 * Same contract as `set_lockfile_entry_pinned` downstream of the resolution:
 * GC skips pinned rows, `regenerate_stale_clips` leaves pinned clips
 * stale-but-frozen.
 *
 * Failure cases, each loud:
 *  - Clip doesn't exist in the project.
 *  - Clip is [Clip.Text] (no assetId; pinning is meaningless for hand-typed text).
 *  - Clip has an asset but no matching lockfile entry (imported media, not
 *    AIGC-generated). The error points the agent at `set_lockfile_entry_pinned`
 *    for the explicit-hash path.
 *
 * Idempotent — calling with the same [Input.pinned] as the current state is a
 * no-op; [Output.changed] is `false` in that case.
 */
class SetClipAssetPinnedTool(
    private val projects: ProjectStore,
) : Tool<SetClipAssetPinnedTool.Input, SetClipAssetPinnedTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /** `true` freezes the clip's lockfile entry as a hero shot; `false` clears the pin. */
        val pinned: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val clipId: String,
        val assetId: String,
        val inputHash: String,
        val toolId: String,
        /** Pinned flag before the call (for idempotency inspection). */
        val pinnedBefore: Boolean,
        /** Pinned flag after the call — always equals [Input.pinned]. */
        val pinnedAfter: Boolean,
        /** True when the call actually flipped the flag (`pinnedBefore != pinnedAfter`). */
        val changed: Boolean,
    )

    override val id: String = "set_clip_asset_pinned"
    override val helpText: String =
        "Upsert the pinned flag on a clip's underlying AIGC lockfile entry. Use this when the " +
            "user points at a specific hero shot on the timeline (\"don't change this clip even " +
            "when I edit Mei's hair\", or the inverse \"go ahead and re-roll this one\"). " +
            "Resolves clipId → assetId → lockfile entry. Fails loudly for text clips (no asset) " +
            "and for clips whose asset has no lockfile entry (imported media — use " +
            "set_lockfile_entry_pinned with an explicit inputHash if that's what you mean). " +
            "Idempotent — pass pinned=true to freeze, pinned=false to clear."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") {
                put("type", "string")
                put(
                    "description",
                    "Id of the clip to pin/unpin. Must resolve to a Clip.Video or Clip.Audio on the timeline.",
                )
            }
            putJsonObject("pinned") {
                put("type", "boolean")
                put(
                    "description",
                    "true to freeze the clip's lockfile entry as a hero shot; false to clear the pin.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"), JsonPrimitive("pinned"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val cid = ClipId(input.clipId)

        val assetId = project.assetIdForClip(cid)
        val entry = project.lockfile.findByAssetId(assetId)
            ?: error(
                "Clip ${input.clipId} has asset ${assetId.value} but no lockfile entry — likely an " +
                    "imported media asset (not AIGC-generated). Use set_lockfile_entry_pinned with an " +
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
            outputForLlm = "Clip ${input.clipId} → asset ${assetId.value} ${verbBefore}$verbNow. " +
                "Underlying lockfile entry ${entry.inputHash} produced by ${entry.toolId} — $gcHint",
            data = Output(
                projectId = pid.value,
                clipId = input.clipId,
                assetId = assetId.value,
                inputHash = entry.inputHash,
                toolId = entry.toolId,
                pinnedBefore = pinnedBefore,
                pinnedAfter = input.pinned,
                changed = changed,
            ),
        )
    }
}

/**
 * Shared resolution: clipId → assetId. Walks every track in timeline order,
 * fails loud with a concrete diagnostic for the two actionable failure modes
 * (unknown clip, text clip with no asset). Private to this file.
 */
private fun io.talevia.core.domain.Project.assetIdForClip(clipId: ClipId): AssetId {
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
