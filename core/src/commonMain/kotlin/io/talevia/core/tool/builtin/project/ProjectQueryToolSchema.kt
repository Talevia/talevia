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
            put("description", "Optional; defaults to session project.")
        }
        putJsonObject("select") {
            put("type", "string")
            put("description", "Discriminator (case-insensitive); see helpText for full list.")
        }
        putJsonObject("trackKind") {
            put("type", "string")
            put("description", "video|audio|subtitle|effect (tracks / timeline_clips).")
        }
        putJsonObject("trackId") {
            put("type", "string")
            put("description", "timeline_clips: exact track id.")
        }
        putJsonObject("fromSeconds") {
            put("type", "number")
            put("description", "timeline_clips: window lower (s).")
        }
        putJsonObject("toSeconds") {
            put("type", "number")
            put("description", "timeline_clips: window upper (s).")
        }
        putJsonObject("onlyNonEmpty") {
            put("type", "boolean")
            put("description", "tracks: skip empty.")
        }
        putJsonObject("onlySourceBound") {
            put("type", "boolean")
            put("description", "timeline_clips: keep AIGC-bound only.")
        }
        putJsonObject("onlyPinned") {
            put("type", "boolean")
            put(
                "description",
                "timeline_clips: pinned/NOT-pinned. lockfile_entries: pinned only.",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put("description", "assets: video|audio|image|all (default all).")
        }
        putJsonObject("onlyUnused") {
            put("type", "boolean")
            put("description", "assets: zero clip refs.")
        }
        putJsonObject("onlyReferenced") {
            put("type", "boolean")
            put("description", "assets: referenced (true) / safe-to-delete (false).")
        }
        putJsonObject("onlyOrphaned") {
            put("type", "boolean")
            put("description", "transitions: both flanking clips missing.")
        }
        putJsonObject("toolId") {
            put("type", "string")
            put("description", "lockfile_entries: producing tool id (e.g. generate_image).")
        }
        putJsonObject("sinceEpochMs") {
            put("type", "integer")
            put("description", "lockfile_entries: createdAtEpochMs ≥ value.")
        }
        putJsonObject("maxAgeDays") {
            put("type", "integer")
            put("description", "snapshots: drop older than now - N days.")
        }
        putJsonObject("assetId") {
            put("type", "string")
            put(
                "description",
                "clips_for_asset: required. lockfile_entry: reverse-lookup key (xor inputHash).",
            )
        }
        putJsonObject("sourceNodeId") {
            put("type", "string")
            put(
                "description",
                "clips_for_source / consistency_propagation: required. lockfile_entries: optional filter.",
            )
        }
        putJsonObject("clipId") {
            put("type", "string")
            put("description", "clip drill-down: required.")
        }
        putJsonObject("inputHash") {
            put("type", "string")
            put("description", "lockfile_entry forward lookup (xor assetId).")
        }
        putJsonObject("sortBy") {
            put("type", "string")
            put("description", "Per-select ordering before offset+limit; see helpText.")
        }
        putJsonObject("limit") { put("type", "integer") }
        putJsonObject("offset") { put("type", "integer") }
        putJsonObject("fromSnapshotId") {
            put("type", "string")
            put("description", "timeline_diff / lockfile_diff: \"from\" side. Null = live; ≥ 1 of from/to required.")
        }
        putJsonObject("toSnapshotId") {
            put("type", "string")
            put("description", "timeline_diff / lockfile_diff: \"to\" side. Null = live.")
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
