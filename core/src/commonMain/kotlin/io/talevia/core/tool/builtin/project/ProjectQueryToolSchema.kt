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
 * each accepts. Trimmed for `debt-shrink-tool-spec-surface` (cycle 5,
 * 2026-05-01): "  • " → "•", " — filter: " → ": ", "sortBy:" → "| sort:";
 * single-row tags collapsed onto shared line; verbose validation blurb
 * shortened — the per-row schema is already documented by the row data
 * classes, so prose only carries semantic guidance the schema can't (e.g.
 * "use regenerate_stale_clips" cross-tool pointer).
 */
internal val PROJECT_QUERY_HELP_TEXT: String =
    "Unified read-only query over a project. Pick one `select`:\n" +
        "• tracks: trackKind, onlyNonEmpty | sort: index|clipCount|span|recent\n" +
        "• timeline_clips: trackKind, trackId, fromSeconds, toSeconds, onlySourceBound, " +
        "onlyPinned | sort: startSeconds|durationSeconds|recent\n" +
        "• assets: kind (video|audio|image|all), onlyUnused, onlyReferenced | " +
        "sort: insertion|duration|duration-asc|id|recent\n" +
        "• transitions: onlyOrphaned\n" +
        "• lockfile_entries: toolId, onlyPinned, sourceNodeId, sinceEpochMs\n" +
        "• clips_for_asset: assetId (required)\n" +
        "• clips_for_source: sourceNodeId (required) — transitive closure\n" +
        "• consistency_propagation: sourceNodeId (required)\n" +
        "• clip: clipId (required) — drill-down\n" +
        "• lockfile_entry: exactly one of inputHash (forward) | assetId (reverse) — drill-down\n" +
        "• project_metadata, spend, lockfile_cache_stats — single-row aggregates\n" +
        "• snapshots: maxAgeDays\n" +
        "• lockfile_orphans — gc candidates {assetId, inputHash, toolId, providerId, modelId, " +
        "costCents, createdAtEpochMs, pinned}\n" +
        "• timeline_diff, lockfile_diff: fromSnapshotId, toSnapshotId (≥1) — " +
        "lockfile_diff buckets by inputHash (added/removed/unchangedCount)\n" +
        "• source_binding_stats — per-kind {kind, totalNodes, boundDirectly, boundTransitively, " +
        "orphans, coverageRatio, orphanNodeIds}\n" +
        "• stale_clips — {clipId, assetId, changedSourceIds} for every AIGC clip whose " +
        "bound source-node hashes drifted; sorted by clipId. Pair with regenerate_stale_clips.\n" +
        "• validation — {severity, code, message, trackId?, clipId?} structural lint " +
        "(severity error|warn). Passed iff zero errors. Run before export; does NOT cover " +
        "content staleness — use stale_clips.\n" +
        "Common: limit 1..500 (default 100), offset. Filter-on-wrong-select fails loud."

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
                    "spend | lockfile_cache_stats | lockfile_diff | lockfile_orphans | snapshots | " +
                    "timeline_diff | source_binding_stats | stale_clips | validation " +
                    "(case-insensitive).",
            )
        }
        putJsonObject("trackKind") {
            put("type", "string")
            put("description", "video|audio|subtitle|effect. tracks/timeline_clips.")
        }
        putJsonObject("trackId") {
            put("type", "string")
            put("description", "Exact track id. timeline_clips only.")
        }
        putJsonObject("fromSeconds") {
            put("type", "number")
            put("description", "Window lower bound (s). timeline_clips only.")
        }
        putJsonObject("toSeconds") {
            put("type", "number")
            put("description", "Window upper bound (s). timeline_clips only.")
        }
        putJsonObject("onlyNonEmpty") {
            put("type", "boolean")
            put("description", "Skip empty tracks. tracks only.")
        }
        putJsonObject("onlySourceBound") {
            put("type", "boolean")
            put("description", "Keep clips with non-empty sourceBinding (AIGC). timeline_clips only.")
        }
        putJsonObject("onlyPinned") {
            put("type", "boolean")
            put(
                "description",
                "Pin filter. timeline_clips: true=pinned / false=NOT pinned. " +
                    "lockfile_entries: true=only pinned.",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put("description", "video|audio|image|all (default all). assets only.")
        }
        putJsonObject("onlyUnused") {
            put("type", "boolean")
            put("description", "Keep assets with zero clip refs. assets only.")
        }
        putJsonObject("onlyReferenced") {
            put("type", "boolean")
            put(
                "description",
                "true=referenced by clip/filter/lockfile / false=safe-to-delete. assets only.",
            )
        }
        putJsonObject("onlyOrphaned") {
            put("type", "boolean")
            put("description", "Transitions whose flanking video clips are both missing. transitions only.")
        }
        putJsonObject("toolId") {
            put("type", "string")
            put("description", "Producing tool id (e.g. generate_image). lockfile_entries only.")
        }
        putJsonObject("sinceEpochMs") {
            put("type", "integer")
            put("description", "createdAtEpochMs >= sinceEpochMs. lockfile_entries only.")
        }
        putJsonObject("maxAgeDays") {
            put("type", "integer")
            put("description", "Drop snapshots older than now - maxAgeDays. snapshots only.")
        }
        putJsonObject("assetId") {
            put("type", "string")
            put(
                "description",
                "Required for clips_for_asset. Also accepted by lockfile_entry as the " +
                    "reverse-lookup key (\"which generation produced this asset?\"); pair with " +
                    "select=lockfile_entry, mutually exclusive with inputHash there.",
            )
        }
        putJsonObject("sourceNodeId") {
            put("type", "string")
            put(
                "description",
                "Required for clips_for_source / consistency_propagation; " +
                    "optional filter on lockfile_entries.",
            )
        }
        putJsonObject("clipId") {
            put("type", "string")
            put("description", "Required for clip drill-down.")
        }
        putJsonObject("inputHash") {
            put("type", "string")
            put(
                "description",
                "Forward lookup for lockfile_entry drill-down. Pair with select=lockfile_entry; " +
                    "mutually exclusive with assetId.",
            )
        }
        putJsonObject("sortBy") {
            put("type", "string")
            put("description", "Per-select ordering applied before offset+limit. See helpText.")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("description", "Max rows (default 100, [1..500]).")
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("description", "Skip N rows (default 0).")
        }
        putJsonObject("fromSnapshotId") {
            put("type", "string")
            put(
                "description",
                "\"from\" side of timeline_diff or lockfile_diff. Null = live state. " +
                    "≥1 of (fromSnapshotId, toSnapshotId) required.",
            )
        }
        putJsonObject("toSnapshotId") {
            put("type", "string")
            put("description", "\"to\" side of timeline_diff or lockfile_diff. Null = live state.")
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
