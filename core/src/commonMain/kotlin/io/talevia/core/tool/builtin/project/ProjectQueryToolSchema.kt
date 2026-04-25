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
 * `putJsonObject { ... }` boilerplate alongside its routing logic. Row data
 * classes for each select live in their own sibling files under
 * `io.talevia.core.tool.builtin.project.query` — consumers decode with e.g.
 * `TrackRow.serializer()` imported from that package.
 *
 * The field descriptions mirror the per-field KDoc on `ProjectQueryTool.Input`
 * — update both in lockstep when you add a new filter.
 */

/**
 * The [ProjectQueryTool.helpText] body — the paragraph the LLM reads to
 * discover which `select` discriminator values exist and what filter fields
 * each accepts. Extracted to this sibling file so the dispatcher stays
 * focused on routing (§5.2 file hygiene). Byte-identical to what shipped
 * before the extraction — every description sentence is preserved verbatim.
 */
internal val PROJECT_QUERY_HELP_TEXT: String =
    "Unified read-only query over a project. Pick one `select`:\n" +
        "  • tracks — filter: trackKind, onlyNonEmpty. sortBy: index|clipCount|span|recent.\n" +
        "  • timeline_clips — filter: trackKind, trackId, fromSeconds, toSeconds, " +
        "onlySourceBound, onlyPinned. sortBy: startSeconds|durationSeconds|recent.\n" +
        "  • assets — filter: kind (video|audio|image|all), onlyUnused, onlyReferenced. " +
        "sortBy: insertion|duration|duration-asc|id|recent. sortBy=recent orders by " +
        "most-recently-mutated first (null stamps sort last).\n" +
        "  • transitions — filter: onlyOrphaned. Chronological.\n" +
        "  • lockfile_entries — filter: toolId, onlyPinned, sourceNodeId, sinceEpochMs. " +
        "Most-recent first.\n" +
        "  • clips_for_asset — required: assetId.\n" +
        "  • clips_for_source — required: sourceNodeId. Transitive closure.\n" +
        "  • consistency_propagation — required: sourceNodeId. Audits whether node body " +
        "values reach bound clips' AIGC prompts.\n" +
        "  • clip — required: clipId. Drill-down.\n" +
        "  • lockfile_entry — required: inputHash. Drill-down.\n" +
        "  • project_metadata — single-row aggregate across tracks/clips/source/lockfile/snapshots.\n" +
        "  • snapshots — filter: maxAgeDays. Newest first. Default limit 50, max 500.\n" +
        "  • spend — single-row AIGC cost aggregate; breaks down by toolId and sessionId.\n" +
        "  • lockfile_cache_stats — single-row AIGC cache-hit aggregate. totalExports = " +
        "memoized whole-timeline export count; hits/misses = current-timeline clips whose " +
        "assetId does/doesn't match a lockfile entry; perModelBreakdown groups hits by " +
        "(providerId, modelId). No filters.\n" +
        "  • lockfile_orphans — lockfile entries whose assetId is no longer referenced by any " +
        "clip on the timeline (gc candidates). Rows: {assetId, inputHash, toolId, providerId, " +
        "modelId, costCents, createdAtEpochMs, pinned}. Sorted unpinned-first, newest-first; " +
        "pinned entries still appear for audit but at the tail. No filters beyond limit/offset.\n" +
        "  • timeline_diff — filter: fromSnapshotId, toSnapshotId (≥1 required). Tracks/clips " +
        "added/removed/changed + totalChanges. Same-project only.\n" +
        "  • source_binding_stats — per-kind coverage: {kind, totalNodes, boundDirectly, " +
        "boundTransitively, orphans, coverageRatio, orphanNodeIds}. Sorted by kind.\n" +
        "Common: limit (default 100, clamped 1..500), offset (default 0). Filter-on-wrong-select " +
        "fails loud."

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
                    "spend | lockfile_cache_stats | lockfile_orphans | snapshots | timeline_diff | " +
                    "source_binding_stats (case-insensitive).",
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
        putJsonObject("fromSnapshotId") {
            put("type", "string")
            put(
                "description",
                "\"from\" side of a timeline diff. Null = current live state. " +
                    "select=timeline_diff only; at least one of (fromSnapshotId, toSnapshotId) " +
                    "must be non-null.",
            )
        }
        putJsonObject("toSnapshotId") {
            put("type", "string")
            put(
                "description",
                "\"to\" side of a timeline diff. Null = current live state. " +
                    "select=timeline_diff only.",
            )
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
