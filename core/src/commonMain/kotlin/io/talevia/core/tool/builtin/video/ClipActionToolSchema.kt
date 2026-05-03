package io.talevia.core.tool.builtin.video

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [ClipActionTool.Input]. Per-property descriptions
 * trimmed cycle 11 (m6-audit-subset-strict-15k) — `enum` array carries
 * the action value list (no need to duplicate in description); itemArray
 * descriptions drop the `action=X.` prefix (the property name `XItems`
 * already conveys it); per-property descriptions kept terse but
 * preserve any non-obvious default / constraint info.
 */
internal val CLIP_ACTION_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("projectId") {
            put("type", "string")
            put("description", "Optional; defaults to session project.")
        }
        putJsonObject("action") {
            put("type", "string")
            put(
                "enum",
                JsonArray(
                    listOf(
                        "add", "remove", "duplicate", "move", "split", "trim", "replace", "fade", "edit_text",
                        "set_volume", "set_transform", "set_sourceBinding",
                    ).map(::JsonPrimitive),
                ),
            )
        }
        putJsonObject("addItems") {
            itemArray("Clips to append.", required = listOf("assetId")) {
                stringProp("assetId")
                numberProp("timelineStartSeconds", "Default: append after last clip.")
                numberProp("sourceStartSeconds", "Source offset (s).")
                numberProp("durationSeconds", "Default: asset's remaining.")
                stringProp("trackId", "Default: first Video track (auto-create).")
            }
        }
        putJsonObject("clipIds") {
            put("type", "array")
            put("description", "Clip ids to delete.")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("ripple") {
            put("type", "boolean")
            put("description", "remove: close gap on the removed clip's track (default false).")
        }
        putJsonObject("duplicateItems") {
            itemArray("Clones.", required = listOf("clipId", "timelineStartSeconds")) {
                stringProp("clipId")
                numberProp("timelineStartSeconds", "Start (s, >= 0).")
                stringProp("trackId", "Same-kind target; default: source's track.")
            }
        }
        putJsonObject("moveItems") {
            itemArray("Reposition.", required = listOf("clipId")) {
                stringProp("clipId")
                numberProp("timelineStartSeconds", "Start (s, >= 0). Omit only with toTrackId.")
                stringProp("toTrackId", "Same-kind target; omit for same-track reposition.")
            }
        }
        putJsonObject("splitItems") {
            itemArray("Splits.", required = listOf("clipId", "atTimelineSeconds")) {
                stringProp("clipId")
                numberProp("atTimelineSeconds", "Split point (inside clip).")
            }
        }
        putJsonObject("trimItems") {
            itemArray("Trims.", required = listOf("clipId")) {
                stringProp("clipId")
                numberProp("newSourceStartSeconds", "Source offset (s, >= 0). Omit = keep.")
                numberProp("newDurationSeconds", "New duration (s, > 0); rewrites timeRange + sourceRange. Omit = keep.")
            }
        }
        putJsonObject("replaceItems") {
            itemArray("Asset swaps.", required = listOf("clipId", "newAssetId")) {
                stringProp("clipId")
                stringProp("newAssetId", "Must be in asset catalog.")
            }
        }
        putJsonObject("fadeItems") {
            itemArray("Audio-clip fade edits.", required = listOf("clipId")) {
                stringProp("clipId")
                numberProp("fadeInSeconds", "Ramp (s); 0 disables; omit = keep.")
                numberProp("fadeOutSeconds", "Ramp (s); 0 disables; omit = keep.")
            }
        }
        putJsonObject("editTextItems") {
            itemArray(
                "Text-clip body / style patch (≥ 1 field/item).",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                stringProp("newText", "Replace body; non-blank.")
                stringProp("fontFamily")
                numberProp("fontSize", "Point size; > 0.")
                stringProp("color", "Hex (#FFFFFF).")
                stringProp("backgroundColor", "Hex; '' clears.")
                boolProp("bold")
                boolProp("italic")
            }
        }
        putJsonObject("volumeItems") {
            itemArray(
                "set_volume: audio-clip volume multipliers (≥ 1 item).",
                required = listOf("clipId", "volume"),
            ) {
                stringProp("clipId")
                numberProp("volume", "Multiplier in [0, 4]. 1 = unchanged, 0 = mute.")
            }
        }
        putJsonObject("transformItems") {
            itemArray(
                "set_transform: visual-transform partial overrides (≥ 1 field/item).",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp("translateX")
                numberProp("translateY")
                numberProp("scaleX", "Horizontal scale; > 0. 1 = unchanged.")
                numberProp("scaleY", "Vertical scale; > 0. 1 = unchanged.")
                numberProp("rotationDeg", "Degrees; renderer takes mod 360.")
                numberProp("opacity", "[0, 1]; 0 = transparent, 1 = opaque.")
            }
        }
        putJsonObject("sourceBindingItems") {
            itemArray(
                "set_sourceBinding: full-replacement rebinds (≥ 1 item).",
                required = listOf("clipId", "sourceBinding"),
            ) {
                stringProp("clipId")
                putJsonObject("sourceBinding") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Source-node ids; empty list clears the binding.")
                }
            }
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}

// DSL helpers for the JSON Schema builders in this package. `internal`
// (not file-private) because the merged-in set_* schema arms (cycle 44
// `debt-tool-consolidation-clip-action-phase1`) and other video-package
// schemas may also call them — two file-private definitions of the same
// `JsonObjectBuilder` extension signature in one package break
// Kotlin/Native's `$default`-arg synthesizer (compile passes, link
// fails with an `IrSimpleFunction` AssertionError on iOS framework
// link). One canonical `internal` copy in this file avoids that
// collision.
internal fun JsonObjectBuilder.stringProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "string")
    if (description != null) put("description", description)
}

internal fun JsonObjectBuilder.numberProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "number")
    if (description != null) put("description", description)
}

internal fun JsonObjectBuilder.boolProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "boolean")
    if (description != null) put("description", description)
}

/** `type=array` + `items=object(properties, required, additionalProperties=false)` — one shape per *Items payload. */
private fun JsonObjectBuilder.itemArray(
    description: String,
    required: List<String>,
    props: JsonObjectBuilder.() -> Unit,
) {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties", props)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}
