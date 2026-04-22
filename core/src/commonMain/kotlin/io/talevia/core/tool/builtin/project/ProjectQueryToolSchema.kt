package io.talevia.core.tool.builtin.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The LLM-facing JSON Schema for [ProjectQueryTool.Input]. Lives in a sibling
 * file so `ProjectQueryTool.kt` — the dispatcher — doesn't carry ~170 lines of
 * `putJsonObject { ... }` boilerplate alongside its routing logic. Per the
 * `debt-split-project-query-tool` cycle: the row data classes stay nested on
 * `ProjectQueryTool` so external callers keep decoding via
 * `ProjectQueryTool.TrackRow.serializer()` etc.; only the schema prose moved.
 *
 * The field descriptions mirror the per-field KDoc on `ProjectQueryTool.Input`
 * — update both in lockstep when you add a new filter.
 */
internal val PROJECT_QUERY_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("projectId") {
            put("type", "string")
            put(
                "description",
                "Optional — omit to use the session's current project (set via switch_project).",
            )
        }
        putJsonObject("select") {
            put("type", "string")
            put(
                "description",
                "What to query: tracks | timeline_clips | assets | transitions | " +
                    "lockfile_entries | clips_for_asset | clips_for_source | " +
                    "clip | lockfile_entry | project_metadata | consistency_propagation | " +
                    "spend (case-insensitive).",
            )
        }
        putJsonObject("trackKind") {
            put("type", "string")
            put(
                "description",
                "video|audio|subtitle|effect (case-insensitive). Applies to " +
                    "select=tracks and select=timeline_clips.",
            )
        }
        putJsonObject("trackId") {
            put("type", "string")
            put("description", "Exact track id. select=timeline_clips only.")
        }
        putJsonObject("fromSeconds") {
            put("type", "number")
            put("description", "Time window lower bound (seconds). select=timeline_clips only.")
        }
        putJsonObject("toSeconds") {
            put("type", "number")
            put("description", "Time window upper bound (seconds). select=timeline_clips only.")
        }
        putJsonObject("onlyNonEmpty") {
            put("type", "boolean")
            put("description", "Skip tracks whose clips list is empty. select=tracks only.")
        }
        putJsonObject("onlySourceBound") {
            put("type", "boolean")
            put(
                "description",
                "Keep only clips with non-empty sourceBinding (AIGC-derived). " +
                    "select=timeline_clips only.",
            )
        }
        putJsonObject("onlyPinned") {
            put("type", "boolean")
            put(
                "description",
                "Pin filter. select=timeline_clips: true=clips pinned / false=clips NOT pinned. " +
                    "select=lockfile_entries: true=only pinned entries.",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put("description", "video|audio|image|all (default all). select=assets only.")
        }
        putJsonObject("onlyUnused") {
            put("type", "boolean")
            put("description", "Keep only assets with zero clip references. select=assets only.")
        }
        putJsonObject("onlyReferenced") {
            put("type", "boolean")
            put(
                "description",
                "true = keep only assets referenced by clip/filter/lockfile. " +
                    "false = keep only un-referenced (safe-to-delete). select=assets only.",
            )
        }
        putJsonObject("onlyOrphaned") {
            put("type", "boolean")
            put(
                "description",
                "Keep only transitions whose flanking video clips are both missing " +
                    "(GC candidates). select=transitions only.",
            )
        }
        putJsonObject("toolId") {
            put("type", "string")
            put(
                "description",
                "Filter lockfile entries by producing tool id (e.g. generate_image, " +
                    "synthesize_speech). select=lockfile_entries only.",
            )
        }
        putJsonObject("sinceEpochMs") {
            put("type", "integer")
            put(
                "description",
                "Keep lockfile entries with createdAtEpochMs >= sinceEpochMs. " +
                    "select=lockfile_entries only.",
            )
        }
        putJsonObject("maxAgeDays") {
            put("type", "integer")
            put(
                "description",
                "Drop snapshots captured strictly earlier than now - maxAgeDays. " +
                    "select=snapshots only.",
            )
        }
        putJsonObject("assetId") {
            put("type", "string")
            put(
                "description",
                "Required for select=clips_for_asset. Asset id to look up clip references for.",
            )
        }
        putJsonObject("sourceNodeId") {
            put("type", "string")
            put(
                "description",
                "Source node id. Required for select=clips_for_source and " +
                    "select=consistency_propagation; optional filter for " +
                    "select=lockfile_entries (\"this character's generation history\").",
            )
        }
        putJsonObject("clipId") {
            put("type", "string")
            put("description", "Required for select=clip. Clip id for drill-down.")
        }
        putJsonObject("inputHash") {
            put("type", "string")
            put(
                "description",
                "Required for select=lockfile_entry. Lockfile entry inputHash for drill-down.",
            )
        }
        putJsonObject("sortBy") {
            put("type", "string")
            put(
                "description",
                "Ordering applied before offset+limit. Accepted values depend on select; " +
                    "see the tool description.",
            )
        }
        putJsonObject("limit") {
            put("type", "integer")
            put(
                "description",
                "Max rows (default 100, clamped to [1, 500]). Applied after filter+sort+offset.",
            )
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("description", "Skip N rows after filter+sort (default 0).")
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
