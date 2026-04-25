package io.talevia.core.tool.builtin.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schema surface for [SourceNodeActionTool]. Extracted to its own
 * file to mirror `ClipActionToolSchema.kt` / `ProjectActionToolSchema.kt`
 * — keeps the dispatcher class focused on shape + business logic and
 * lets the schema grow (per-action enums, future verbs) without
 * re-puffing the tool body past the long-file threshold.
 *
 * Cycle 136 added the `action="import"` verb (folding the standalone
 * `ImportSourceNodeTool`). The schema entries for the import-specific
 * Input fields live here; the action handler routes input shapes
 * (live cross-project vs. portable envelope) at dispatch time.
 */
internal val SOURCE_NODE_ACTION_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("projectId") { put("type", "string") }
        putJsonObject("action") {
            put("type", "string")
            put(
                "description",
                "`add` to create a node, `remove` to delete one, `fork` to duplicate under a new " +
                    "id, `rename` to atomically rewrite an id everywhere it's referenced, " +
                    "`update_body` to replace a body, `set_parents` to replace a parent list, " +
                    "`import` to copy a node + parents from another project (live or via portable JSON envelope).",
            )
            put(
                "enum",
                JsonArray(
                    listOf(
                        JsonPrimitive("add"),
                        JsonPrimitive("remove"),
                        JsonPrimitive("fork"),
                        JsonPrimitive("rename"),
                        JsonPrimitive("update_body"),
                        JsonPrimitive("set_parents"),
                        JsonPrimitive("import"),
                    ),
                ),
            )
        }
        putJsonObject("nodeId") {
            put("type", "string")
            put(
                "description",
                "Required for action=add / remove / update_body / set_parents.",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put(
                "description",
                "action=add only. Dotted-namespace kind string (e.g. narrative.scene, " +
                    "musicmv.track, ad.variant_request). Must match what the genre layer " +
                    "expects — Core does not validate.",
            )
        }
        putJsonObject("body") {
            put("type", "object")
            put(
                "description",
                "action=add: opaque JSON body matching the genre's shape (defaults {}). " +
                    "action=update_body: complete new body — full replacement, not a partial " +
                    "patch (required unless restoreFromRevisionIndex / mergeFromRevisionIndex " +
                    "is set). Kind + body together drive contentHash.",
            )
            put("additionalProperties", true)
        }
        putJsonObject("parentIds") {
            put("type", "array")
            put(
                "description",
                "action=add: optional parent ids; each must already exist; empty default = root. " +
                    "action=set_parents: required full replacement parent list (empty list clears).",
            )
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("sourceNodeId") {
            put("type", "string")
            put("description", "action=fork only. Id of the node to duplicate.")
        }
        putJsonObject("newNodeId") {
            put("type", "string")
            put(
                "description",
                "action=fork or action=import only. Optional new id for the new (forked / imported) " +
                    "node. Auto-generated UUID if blank. Collides with an existing node id -> loud error.",
            )
        }
        putJsonObject("oldId") {
            put("type", "string")
            put("description", "action=rename only. Existing source-node id to rewrite.")
        }
        putJsonObject("newId") {
            put("type", "string")
            put(
                "description",
                "action=rename only. New id. Must be lowercase letters / digits / '-', non-empty, " +
                    "and must not collide with an existing node.",
            )
        }
        putJsonObject("restoreFromRevisionIndex") {
            put("type", "integer")
            put("minimum", 0)
            put(
                "description",
                "action=update_body only. Restore an earlier body from this node's history " +
                    "(0 = most-recent overwritten). Mutually exclusive with body / mergeFromRevisionIndex.",
            )
        }
        putJsonObject("mergeFromRevisionIndex") {
            put("type", "integer")
            put("minimum", 0)
            put(
                "description",
                "action=update_body only. Per-field merge from a historical revision; pair with " +
                    "mergeFieldPaths. Mutually exclusive with body / restoreFromRevisionIndex.",
            )
        }
        putJsonObject("mergeFieldPaths") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put(
                "description",
                "action=update_body + mergeFromRevisionIndex only. Top-level body keys to copy " +
                    "from the historical revision; every named key must exist in that revision.",
            )
        }
        putJsonObject("fromProjectId") {
            put("type", "string")
            put(
                "description",
                "action=import only. Source project id for live cross-project copy. Must be paired " +
                    "with `fromNodeId`; mutually exclusive with `envelope`.",
            )
        }
        putJsonObject("fromNodeId") {
            put("type", "string")
            put(
                "description",
                "action=import only. Source node id for live cross-project copy. Pair with `fromProjectId`.",
            )
        }
        putJsonObject("envelope") {
            put("type", "string")
            put(
                "description",
                "action=import only. Portable JSON envelope produced by `export_source_node`. " +
                    "Mutually exclusive with the (`fromProjectId` + `fromNodeId`) pair. " +
                    "FormatVersion is checked; cross-instance / version-controlled ingestion path.",
            )
        }
    }
    put(
        "required",
        JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("action"))),
    )
    put("additionalProperties", false)
}
