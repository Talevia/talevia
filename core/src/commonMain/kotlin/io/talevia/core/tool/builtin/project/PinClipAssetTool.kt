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
 * Clip-level shortcut for `pin_lockfile_entry` — the expert-path ergonomic the
 * inputHash-keyed base tool deliberately doesn't try to be.
 *
 * Expert flow today without this tool:
 *   1. `describe_project` / `project_query(select=timeline_clips)` → find the clip's assetId.
 *   2. `list_lockfile_entries` → find the row whose assetId matches.
 *   3. Copy the `inputHash`, call `pin_lockfile_entry`.
 * Three tool calls for what is conceptually "this clip right here is the hero
 * shot; freeze it." VISION §5.4 专家路径 calls out the mandate that the expert
 * "精准执行，不越权猜意图" — a shortcut that takes the exact handle the user is
 * pointing at (a ClipId they're looking at in the timeline panel) and maps it
 * to the underlying lockfile row is exactly what "精准" means.
 *
 * Resolution path: `ClipId` → `Clip.Video|Audio.assetId` →
 * `Lockfile.findByAssetId` (most-recent match) → `Lockfile.withEntryPinned`.
 * Same contract as `pin_lockfile_entry` downstream of the resolution — GC
 * skips the row, `regenerate_stale_clips` leaves the clip stale-but-frozen.
 *
 * Failure cases, each loud:
 *  - Clip doesn't exist in the project.
 *  - Clip is [Clip.Text] (no assetId; pinning is meaningless for hand-typed
 *    text). The error explains that.
 *  - Clip has an asset but no matching lockfile entry (imported media isn't
 *    AIGC-generated, so there's no "random-compiler" row to freeze). The
 *    error points the agent at `pin_lockfile_entry` for the explicit hash
 *    path in case they knew what they were doing.
 *
 * Idempotent — repeated calls on an already-pinned clip return
 * `alreadyPinned=true` and don't mutate. Matches [PinLockfileEntryTool]'s
 * contract exactly.
 */
class PinClipAssetTool(
    private val projects: ProjectStore,
) : Tool<PinClipAssetTool.Input, PinClipAssetTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val clipId: String,
        val assetId: String,
        val inputHash: String,
        val toolId: String,
        val alreadyPinned: Boolean,
    )

    override val id: String = "pin_clip_asset"
    override val helpText: String =
        "Clip-level shortcut for pin_lockfile_entry — resolve the clip's current assetId, find " +
            "its lockfile entry, and pin it. Use this when the user is pointing at a specific " +
            "hero shot on the timeline (\"don't change this clip even when I edit Mei's hair\"). " +
            "Fails loudly for text clips (no asset) and for clips whose asset has no lockfile " +
            "entry (imported media, not AIGC). Idempotent. Inverse: unpin_clip_asset."
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
                    "Id of the clip to pin. Must resolve to a Clip.Video or Clip.Audio on the timeline.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))))
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
                    "imported media asset (not AIGC-generated). Use pin_lockfile_entry with an explicit " +
                    "inputHash if you really want to pin some other entry.",
            )

        val alreadyPinned = entry.pinned
        if (!alreadyPinned) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(entry.inputHash, pinned = true))
            }
        }

        val verb = if (alreadyPinned) "was already pinned" else "pinned"
        return ToolResult(
            title = "pin clip ${input.clipId}",
            outputForLlm = "Clip ${input.clipId} → asset ${assetId.value} ($verb). Underlying " +
                "lockfile entry ${entry.inputHash} produced by ${entry.toolId} is now frozen — " +
                "gc_lockfile skips it and regenerate_stale_clips leaves the clip stale-but-frozen. " +
                "Inverse: unpin_clip_asset.",
            data = Output(
                projectId = pid.value,
                clipId = input.clipId,
                assetId = assetId.value,
                inputHash = entry.inputHash,
                toolId = entry.toolId,
                alreadyPinned = alreadyPinned,
            ),
        )
    }
}

/**
 * Clear the pin on the lockfile entry backing a clip — inverse of
 * [PinClipAssetTool]. Same resolution chain and same failure modes.
 * Idempotent: unpinning an already-unpinned clip returns `wasUnpinned=true`.
 */
class UnpinClipAssetTool(
    private val projects: ProjectStore,
) : Tool<UnpinClipAssetTool.Input, UnpinClipAssetTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val clipId: String,
        val assetId: String,
        val inputHash: String,
        val toolId: String,
        val wasUnpinned: Boolean,
    )

    override val id: String = "unpin_clip_asset"
    override val helpText: String =
        "Clip-level shortcut for unpin_lockfile_entry — resolve the clip's current assetId, find " +
            "its lockfile entry, and clear the pin. Inverse of pin_clip_asset. Same failure modes: " +
            "text clips and imported-media clips (no lockfile entry) fail loudly. Idempotent."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val cid = ClipId(input.clipId)

        val assetId = project.assetIdForClip(cid)
        val entry = project.lockfile.findByAssetId(assetId)
            ?: error(
                "Clip ${input.clipId} has asset ${assetId.value} but no lockfile entry — use " +
                    "unpin_lockfile_entry with an explicit inputHash if you know which entry to target.",
            )

        val wasUnpinned = !entry.pinned
        if (entry.pinned) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(entry.inputHash, pinned = false))
            }
        }

        val verb = if (wasUnpinned) "was already unpinned" else "unpinned"
        return ToolResult(
            title = "unpin clip ${input.clipId}",
            outputForLlm = "Clip ${input.clipId} → asset ${assetId.value} ($verb). Lockfile entry " +
                "${entry.inputHash} (${entry.toolId}) is no longer frozen — gc_lockfile can GC it " +
                "and regenerate_stale_clips will re-dispatch if the clip goes stale.",
            data = Output(
                projectId = pid.value,
                clipId = input.clipId,
                assetId = assetId.value,
                inputHash = entry.inputHash,
                toolId = entry.toolId,
                wasUnpinned = wasUnpinned,
            ),
        )
    }
}

/**
 * Shared resolution: clipId → assetId. Walks every track in timeline order,
 * fails loud with a concrete diagnostic for the two actionable failure modes
 * (unknown clip, text clip with no asset). Private to this file — the pin /
 * unpin tools are the only callers and duplicating the 15 lines would invite
 * drift; both tools need identical error phrasing.
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
