package io.talevia.core.tool.builtin.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schema surface for [SourceNodeActionTool]. Extracted to its own
 * file to mirror `ClipActionToolSchema.kt` / `ProjectLifecycleActionToolSchema.kt`
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
            put("description", "add / remove / update_body / set_parents: required.")
        }
        putJsonObject("kind") {
            put("type", "string")
            put(
                "description",
                "add: dotted kind (e.g. narrative.scene, musicmv.track). Genre-validated, not Core.",
            )
        }
        putJsonObject("body") {
            put("type", "object")
            put(
                "description",
                "add: opaque JSON matching genre shape (default {}). " +
                    "update_body: full replacement (required unless restoreFromRevisionIndex / " +
                    "mergeFromRevisionIndex set). kind + body drive contentHash.",
            )
            put("additionalProperties", true)
        }
        putJsonObject("parentIds") {
            put("type", "array")
            put(
                "description",
                "add: optional parent ids (must exist; empty = root). " +
                    "set_parents: required full replacement (empty clears).",
            )
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("sourceNodeId") {
            put("type", "string")
            put("description", "fork: required. Node to duplicate.")
        }
        putJsonObject("newNodeId") {
            put("type", "string")
            put(
                "description",
                "fork / import: optional new id (UUID if blank). Collision fails.",
            )
        }
        putJsonObject("oldId") {
            put("type", "string")
            put("description", "rename: required existing id.")
        }
        putJsonObject("newId") {
            put("type", "string")
            put("description", "rename: required new id (lowercase / digits / '-'; no collision).")
        }
        putJsonObject("restoreFromRevisionIndex") {
            put("type", "integer")
            put("minimum", 0)
            put(
                "description",
                "update_body: restore earlier body (0 = most-recent overwritten). " +
                    "Excludes body / mergeFromRevisionIndex.",
            )
        }
        putJsonObject("mergeFromRevisionIndex") {
            put("type", "integer")
            put("minimum", 0)
            put(
                "description",
                "update_body: per-field merge from historical revision (with mergeFieldPaths). " +
                    "Excludes body / restoreFromRevisionIndex.",
            )
        }
        putJsonObject("mergeFieldPaths") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put(
                "description",
                "update_body + mergeFromRevisionIndex: top-level keys to copy; each must exist in revision.",
            )
        }
        putJsonObject("fromProjectId") {
            put("type", "string")
            put("description", "import: source project id (with fromNodeId); excludes envelope.")
        }
        putJsonObject("fromNodeId") {
            put("type", "string")
            put("description", "import: source node id (with fromProjectId).")
        }
        putJsonObject("envelope") {
            put("type", "string")
            put(
                "description",
                "import: portable envelope from export_source_node. Excludes fromProjectId + fromNodeId. " +
                    "formatVersion checked.",
            )
        }
    }
    put(
        "required",
        JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("action"))),
    )
    put("additionalProperties", false)
}
