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
 * Add / remove / duplicate / reorder tracks AND insert / remove
 * transitions on the timeline — consolidated dispatcher absorbing the
 * pre-cycle-49 separate `track_action` + `transition_action` tools
 * into a single `timeline_action(action=...)` surface
 * (`debt-tool-consolidation-timeline-action-phase1a`, cycle 49).
 *
 * Phase 1b (next cycle) will absorb `filter_action` similarly. Phase
 * 1a leaves `filter_action` separate because its FilterActionVerbs
 * registry shape (object-based verb dispatch) is the unique complexity
 * of the 3-way merge — kept out of phase 1a's scope so this phase
 * stays mechanical.
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
class TimelineActionTool(
    private val store: ProjectStore,
) : Tool<TimelineActionTool.Input, TimelineActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /**
         * `"add_track"`, `"remove_track"`, `"duplicate_track"`, `"reorder_track"`,
         * `"add_transition"`, or `"remove_transition"`. Case-sensitive.
         * Cycle 49 absorbed `transition_action` into the unified `timeline_action`
         * surface — verbs are disambiguated with `_track` / `_transition` suffix.
         */
        val action: String,
        /** add_track only. `video` / `audio` / `subtitle` / `effect`; case-insensitive. */
        val trackKind: String? = null,
        /**
         * add_track only. Optional explicit id. Defaults to a generated UUID.
         * Fails if an existing track has the same id.
         */
        val trackId: String? = null,
        /**
         * remove_track-only: track ids to drop (at least one required).
         * reorder_track-only: ids in the desired stacking order — first id
         * becomes the bottom track (index 0), unlisted tracks keep
         * their current relative order at the tail. Empty list rejected
         * on either action as a likely caller mistake.
         */
        val trackIds: List<String> = emptyList(),
        /**
         * remove_track only. Drop tracks even when they hold clips (every clip
         * on them is discarded too). Default `false` throws if any
         * target track is non-empty, leaving the whole batch untouched.
         */
        val force: Boolean = false,
        /**
         * duplicate_track only: per-item shape — each entry carries its own
         * sourceTrackId + optional newTrackId. Cloned tracks are appended
         * to the timeline in the order listed. Optional per-item
         * `newTrackId` must not collide with any existing track or any
         * earlier-in-batch clone; omit to auto-generate
         * `${sourceTrackId}-copy-${n}`. All-or-nothing; one snapshot per
         * call. Empty list rejected.
         */
        val items: List<DuplicateItem> = emptyList(),
        /**
         * add_transition only. Transitions to insert; at least one.
         * Pairs must be on same track + adjacent + both video clips.
         */
        val transitionItems: List<TransitionAddItem>? = null,
        /**
         * remove_transition only. Transition clip ids (from prior add_transition).
         * At least one required; ids must be on the effect track with the
         * `transition:` sentinel asset prefix.
         */
        val transitionClipIds: List<String>? = null,
    )

    /** One add_transition request. Absorbed from the pre-cycle-49 `TransitionActionTool.AddItem`. */
    @Serializable data class TransitionAddItem(
        val fromClipId: String,
        val toClipId: String,
        val transitionName: String = "fade",
        val durationSeconds: Double = 0.5,
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

    /** add_transition per-item result. Absorbed from `TransitionActionTool.AddResult`. */
    @Serializable data class TransitionAddResult(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
        val fromClipId: String,
        val toClipId: String,
    )

    /** remove_transition per-item result. Absorbed from `TransitionActionTool.RemoveResult`. */
    @Serializable data class TransitionRemoveResult(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
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
        /** duplicate_track only: per-item duplication summary. Empty on every other action. */
        val duplicateResults: List<DuplicateItemResult> = emptyList(),
        /** reorder_track only: full track id list after the reorder. Empty on every other action. */
        val newOrder: List<String> = emptyList(),
        /** add_transition only: per-transition insertion summary. */
        val addedTransitions: List<TransitionAddResult> = emptyList(),
        /** remove_transition only: per-transition removal summary. */
        val removedTransitions: List<TransitionRemoveResult> = emptyList(),
        /** remove_transition only: transitions still on the affected track(s) after the batch. */
        val remainingTransitionsTotal: Int = 0,
    )

    override val id: String = "timeline_action"
    override val helpText: String =
        "6-verb timeline dispatcher: add_track / remove_track / duplicate_track / reorder_track " +
            "/ add_transition / remove_transition. add_track + trackKind (video|audio|subtitle|effect) " +
            "+ optional trackId (default fresh UUID). remove_track + trackIds + optional force=true " +
            "(drops clips on non-empty tracks; otherwise batch aborts with clip count). " +
            "duplicate_track + items=[{sourceTrackId, newTrackId?}…] clones whole tracks (every clip " +
            "gets a fresh ClipId, all attached state preserved); cloned tracks appended in list " +
            "order. reorder_track + trackIds (partial ordering; first id becomes bottom track / " +
            "index 0; unlisted tracks keep relative tail order) — controls PiP stacking, audio " +
            "sub-mix priority, subtitle fallback. add_transition + transitionItems (each: " +
            "fromClipId, toClipId, transitionName default 'fade', durationSeconds default 0.5; " +
            "pairs must be same-track + adjacent video clips). remove_transition + " +
            "transitionClipIds (ids from a prior add_transition; effect-track clips with the " +
            "`transition:` asset sentinel). All-or-nothing; one snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "timeline.write",
        permissionFrom = { inputJson ->
            // Only `remove_track` is destructive (drops clips); `remove_transition`
            // stays at `timeline.write` (transitions are pure overlay clips on an
            // effect track, no source-clip data discarded). All other verbs are
            // also non-destructive timeline edits.
            if (isRemoveTrackAction(inputJson)) "project.destructive" else "timeline.write"
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
                    "Track verbs: `add_track` create empty; `remove_track` drop one/many; " +
                        "`duplicate_track` clone (fresh ClipIds); `reorder_track` change stacking. " +
                        "Transition verbs: `add_transition` insert between adjacent clip pairs; " +
                        "`remove_transition` delete by transition clip id.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("add_track"),
                            JsonPrimitive("remove_track"),
                            JsonPrimitive("duplicate_track"),
                            JsonPrimitive("reorder_track"),
                            JsonPrimitive("add_transition"),
                            JsonPrimitive("remove_transition"),
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
                    "duplicate_track only. Per-item track duplications. At least one required.",
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
            putJsonObject("transitionItems") {
                put("type", "array")
                put("description", "add_transition only. Transitions to insert; at least one.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("fromClipId") { put("type", "string") }
                        putJsonObject("toClipId") { put("type", "string") }
                        putJsonObject("transitionName") {
                            put("type", "string")
                            put("description", "fade | dissolve | slide | wipe (engine-specific)")
                        }
                        putJsonObject("durationSeconds") {
                            put("type", "number")
                            put("description", "Default 0.5s; longer transitions overlap more material.")
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("fromClipId"), JsonPrimitive("toClipId"))),
                    )
                    put("additionalProperties", false)
                }
            }
            putJsonObject("transitionClipIds") {
                put("type", "array")
                put(
                    "description",
                    "remove_transition only. Transition clip ids (from prior add_transition). At least one.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "add_track" -> executeAdd(store, pid, input, ctx)
            "remove_track" -> executeRemove(store, pid, input, ctx)
            "duplicate_track" -> executeDuplicate(store, pid, input, ctx)
            "reorder_track" -> executeReorder(store, pid, input, ctx)
            "add_transition" -> executeTransitionAdd(store, pid, input, ctx)
            "remove_transition" -> executeTransitionRemove(store, pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: add_track, remove_track, " +
                    "duplicate_track, reorder_track, add_transition, remove_transition",
            )
        }
    }

    private companion object {
        /**
         * Regex-match `"action":"remove_track"` (allowing whitespace) in the
         * raw input JSON. Runs before kotlinx.serialization decode for
         * [PermissionSpec.permissionFrom]'s tier gate — we cannot rely on
         * the typed Input here because the permission tier is resolved
         * before the dispatcher deserialises. Malformed input (missing /
         * non-string action) falls through to the lower `timeline.write`
         * tier, matching the tool's safer-default rationale. Note: only
         * `remove_track` triggers the destructive tier; `remove_transition`
         * stays on `timeline.write` (transitions are pure overlay clips,
         * no source-clip data discarded by their removal).
         */
        private val REMOVE_TRACK_ACTION_REGEX = Regex(
            "\"action\"\\s*:\\s*\"remove_track\"",
        )

        private fun isRemoveTrackAction(inputJson: String): Boolean =
            REMOVE_TRACK_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
