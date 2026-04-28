package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
import kotlin.uuid.ExperimentalUuidApi

/**
 * Add or remove tracks on the timeline — the consolidated
 * action-dispatched form that replaces `AddTrackTool` + `RemoveTrackTool`
 * (`debt-consolidate-video-add-remove-verbs-tracks`, following the same
 * pattern as cycles 19 → 23: `transition_action`, `filter_action`,
 * `project_snapshot_action`, `project_maintenance_action`,
 * `session_action`).
 *
 * ## Input contract
 *
 * - `action` = `"add"` or `"remove"` (required).
 * - `action="add"`: required `trackKind` (`video` / `audio` / `subtitle`
 *   / `effect`, case-insensitive); optional `trackId` (defaults to a
 *   fresh UUID; fails if the id already exists). Does NOT mutate clip
 *   contents — an empty track is a no-op at render time. Still emits a
 *   `Part.TimelineSnapshot` so `revert_timeline` can undo.
 * - `action="remove"`: required `trackIds` (list, ≥ 1); optional `force`
 *   (default false). Non-empty tracks require `force=true`; otherwise
 *   the whole batch aborts with the offending track's clip count so
 *   the agent can retry with `force=true` or remove clips first. One
 *   snapshot per batch.
 *
 * ## Permission tier split
 *
 * `add` uses `timeline.write` (non-destructive — empty track adds no
 * content). `remove` uses `project.destructive` (may discard clips
 * with `force=true`; even empty-track drops the agent may have
 * declared with downstream intent count as destructive). Dispatched
 * via [PermissionSpec.permissionFrom] (cycle 21 extension). Base
 * tier is `timeline.write` so an unknown / malformed action-field
 * lands on the safer (lower-friction) tier — `execute()`'s
 * `require(...)` rejects unknown actions before touching the store,
 * so the tier on unknown input is never actually exercised, and
 * biasing toward the non-destructive default keeps the common add
 * path free of ASK friction.
 */
@OptIn(ExperimentalUuidApi::class)
class TrackActionTool(
    private val store: ProjectStore,
) : Tool<TrackActionTool.Input, TrackActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** `"add"`, `"remove"`, `"duplicate"`, or `"reorder"`. Case-sensitive. */
        val action: String,
        /** Add-only. `video` / `audio` / `subtitle` / `effect`; case-insensitive. */
        val trackKind: String? = null,
        /**
         * Add-only. Optional explicit id. Defaults to a generated UUID.
         * Fails if an existing track has the same id.
         */
        val trackId: String? = null,
        /**
         * Remove-only: track ids to drop (at least one required).
         * Reorder-only: ids in the desired stacking order — first id
         * becomes the bottom track (index 0), unlisted tracks keep
         * their current relative order at the tail. Empty list rejected
         * on either action as a likely caller mistake.
         */
        val trackIds: List<String> = emptyList(),
        /**
         * Remove-only. Drop tracks even when they hold clips (every clip
         * on them is discarded too). Default `false` throws if any
         * target track is non-empty, leaving the whole batch untouched.
         */
        val force: Boolean = false,
        /**
         * Duplicate-only: per-item shape — each entry carries its own
         * sourceTrackId + optional newTrackId. Cloned tracks are appended
         * to the timeline in the order listed. Optional per-item
         * `newTrackId` must not collide with any existing track or any
         * earlier-in-batch clone; omit to auto-generate
         * `${sourceTrackId}-copy-${n}`. All-or-nothing; one snapshot per
         * call. Empty list rejected.
         */
        val items: List<DuplicateItem> = emptyList(),
    )

    /** Per-track payload for `action="duplicate"`. */
    @Serializable data class DuplicateItem(
        val sourceTrackId: String,
        /**
         * Optional explicit id for the cloned track. Must not collide with an
         * existing track id nor with another item's newTrackId in the same batch.
         * Omit to auto-generate `${sourceTrackId}-copy-${n}`.
         */
        val newTrackId: String? = null,
    )

    @Serializable data class RemoveItemResult(
        val trackId: String,
        val trackKind: String,
        val droppedClipCount: Int,
    )

    @Serializable data class DuplicateItemResult(
        val sourceTrackId: String,
        val newTrackId: String,
        val clipCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        /** Add-only: id of the newly-created track. Empty on remove. */
        val trackId: String = "",
        /** Add-only: kind of the newly-created track. Empty on remove. */
        val trackKind: String = "",
        /** Add-only: track count after the add. Zero on remove. */
        val totalTrackCount: Int = 0,
        /** Remove-only: per-track removal summary. Empty on add. */
        val results: List<RemoveItemResult> = emptyList(),
        /** Remove-only: echoes the request's `force` flag. False on add. */
        val forced: Boolean = false,
        /** Both: id of the emitted `Part.TimelineSnapshot`. */
        val snapshotId: String = "",
        /** Duplicate-only: per-item duplication summary. Empty on every other action. */
        val duplicateResults: List<DuplicateItemResult> = emptyList(),
        /** Reorder-only: full track id list after the reorder. Empty on every other action. */
        val newOrder: List<String> = emptyList(),
    )

    override val id: String = "track_action"
    override val helpText: String =
        "Add, remove, duplicate, or reorder tracks atomically. action=add + trackKind " +
            "(video|audio|subtitle|effect) + optional trackId (defaults to fresh UUID). " +
            "action=remove + trackIds + optional force=true (drops clips on non-empty tracks; " +
            "otherwise batch aborts with clip count). action=duplicate + items=[{sourceTrackId, " +
            "newTrackId?}…] clones whole tracks (every clip gets a fresh ClipId, all attached " +
            "state preserved); cloned tracks appended in list order. action=reorder + trackIds " +
            "(partial ordering; first id becomes bottom track / index 0; unlisted tracks keep " +
            "relative tail order) — controls PiP stacking, audio sub-mix priority, subtitle " +
            "fallback. One snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "timeline.write",
        permissionFrom = { inputJson ->
            if (isRemoveAction(inputJson)) "project.destructive" else "timeline.write"
        },
    )
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "`add` to create an empty track; `remove` to drop one or many tracks; " +
                        "`duplicate` to clone whole tracks (each clip gets fresh ClipId); " +
                        "`reorder` to change the timeline track stacking order.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("add"),
                            JsonPrimitive("remove"),
                            JsonPrimitive("duplicate"),
                            JsonPrimitive("reorder"),
                        ),
                    ),
                )
            }
            putJsonObject("trackKind") {
                put("type", "string")
                put("description", "Add-only. One of: video, audio, subtitle, effect (case-insensitive).")
            }
            putJsonObject("trackId") {
                put("type", "string")
                put(
                    "description",
                    "Add-only. Optional explicit id. Defaults to a generated UUID. " +
                        "Fails if an existing track has the same id.",
                )
            }
            putJsonObject("trackIds") {
                put("type", "array")
                put(
                    "description",
                    "Remove-only: track ids to drop (at least one required). " +
                        "Reorder-only: partial or full ordering — first id becomes the bottom " +
                        "track; unlisted tracks keep relative tail order.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("force") {
                put("type", "boolean")
                put(
                    "description",
                    "Remove-only. Drop tracks even if they hold clips (discards those clips). Default false.",
                )
            }
            putJsonObject("items") {
                put("type", "array")
                put(
                    "description",
                    "Duplicate-only. Per-item track duplications. At least one required.",
                )
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sourceTrackId") { put("type", "string") }
                        putJsonObject("newTrackId") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional explicit id for the cloned track. Defaults to " +
                                    "'<sourceTrackId>-copy-<n>'. Fails if collides with " +
                                    "existing or earlier-in-batch track.",
                            )
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("sourceTrackId"))))
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "add" -> executeAdd(store, pid, input, ctx)
            "remove" -> executeRemove(store, pid, input, ctx)
            "duplicate" -> executeDuplicate(store, pid, input, ctx)
            "reorder" -> executeReorder(store, pid, input, ctx)
            else -> error("unknown action '${input.action}'; accepted: add, remove, duplicate, reorder")
        }
    }

    private companion object {
        /**
         * Regex-match `"action":"remove"` (allowing whitespace) in the raw
         * input JSON. Runs before kotlinx.serialization decode for
         * [PermissionSpec.permissionFrom]'s tier gate — we cannot rely on
         * the typed Input here because the permission tier is resolved
         * before the dispatcher deserialises. Malformed input (missing /
         * non-string action) falls through to the lower `timeline.write`
         * tier, matching the tool's safer-default rationale.
         */
        private val REMOVE_ACTION_REGEX = Regex(
            "\"action\"\\s*:\\s*\"remove\"",
        )

        private fun isRemoveAction(inputJson: String): Boolean =
            REMOVE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
