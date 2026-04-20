package io.talevia.core.tool.builtin.fs

import io.talevia.core.JsonConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extract a string field from a tool's raw-JSON input for use as a
 * [io.talevia.core.permission.PermissionSpec] pattern. The fs tools all
 * expose a single path-like field (`path` or `pattern`) that should scope
 * the permission check; returning `"*"` on any parse failure keeps the
 * dispatcher safe rather than blocking on malformed input.
 */
internal fun extractPathPattern(inputJson: String, field: String, json: Json = JsonConfig.default): String =
    runCatching {
        json.parseToJsonElement(inputJson).jsonObject[field]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "*"
