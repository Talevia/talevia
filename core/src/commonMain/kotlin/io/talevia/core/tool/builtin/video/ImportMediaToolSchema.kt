package io.talevia.core.tool.builtin.video

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [ImportMediaTool.Input] — pulled out of the dispatcher
 * file so the main file stays under the 500-line R.5.4 debt threshold.
 * Byte-identical to the previous inline definition; every field
 * description is preserved verbatim so the LLM-visible schema does not
 * change. Mirrors the `SessionQueryToolSchema` / `ProjectQueryToolSchema`
 * extraction pattern.
 */
internal val IMPORT_MEDIA_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("path") {
            put("type", "string")
            put(
                "description",
                "Absolute path to a single media file. Mutually exclusive with `paths`; exactly one " +
                    "of the two must be supplied.",
            )
        }
        putJsonObject("paths") {
            put("type", "array")
            put(
                "description",
                "Absolute paths for a batch import. Each path is probed concurrently; per-path " +
                    "failures land in Output.failed without aborting the batch. Mutually " +
                    "exclusive with `path`. Must be non-empty and pairwise distinct.",
            )
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("projectId") {
            put("type", "string")
            put(
                "description",
                "Optional — omit to import into the session's current project (set via switch_project).",
            )
        }
        putJsonObject("copy_into_bundle") {
            put("type", "boolean")
            put(
                "description",
                "Tri-state (omit for auto). `true` forces bundle-copy (bytes travel with " +
                    "`git push`). `false` forces reference-by-path (bytes stay in place). " +
                    "Omit entirely for size-based auto: files ≤50 MiB are copied into the " +
                    "bundle, larger files are referenced by absolute path. Auto is the " +
                    "sensible default — explicit values are for overrides.",
            )
        }
    }
    // No hard-required field at the schema level: `path` xor `paths` is enforced in execute().
    put("required", JsonArray(emptyList()))
    put("additionalProperties", false)
}
