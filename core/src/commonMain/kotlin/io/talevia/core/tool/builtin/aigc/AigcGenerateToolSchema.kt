package io.talevia.core.tool.builtin.aigc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Hand-written `oneOf` JSON Schema reflecting the sealed
 * [AigcGenerateTool.Input] variants. kotlinx.serialization can produce
 * the polymorphic JSON at runtime, but the LLM-facing schema is
 * hand-built so per-field descriptions land in the prompt. Each
 * variant has its own `properties` block so the LLM sees only the
 * fields valid for that kind — no kitchen-sink optional bag.
 *
 * Extracted from `AigcGenerateTool.kt` (`debt-split-aigc-generate-tool`,
 * cycle 31). Schema-only file: a new AIGC kind adds one `oneOf`
 * branch here; everything else (Input variant, Output projection,
 * dispatch helper) lives in the structural files. Keeping this
 * declarative block separate also means LLM-facing description
 * tweaks (the most-edited part during prompt-engineering passes)
 * don't churn the dispatch logic file.
 */
internal fun buildOneOfInputSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("oneOf") {
        // image
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "image")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Text description of the image.")
                    }
                    putJsonObject("model") { put("type", "string"); put("description", "Provider-scoped model id (default gpt-image-1).") }
                    putJsonObject("width") { put("type", "integer"); put("description", "Width px (default 1024).") }
                    putJsonObject("height") { put("type", "integer"); put("description", "Height px (default 1024).") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
        // video
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "video")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Text description of the video.")
                    }
                    putJsonObject("model") { put("type", "string"); put("description", "Default sora-2.") }
                    putJsonObject("width") { put("type", "integer"); put("description", "Width px (default 1280).") }
                    putJsonObject("height") { put("type", "integer"); put("description", "Height px (default 720).") }
                    putJsonObject("durationSeconds") { put("type", "number"); put("description", "Clip length seconds (default 5.0).") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
        // music
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "music")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Mood / style description for the music.")
                    }
                    putJsonObject("durationSeconds") { put("type", "number"); put("description", "Clip length seconds (default 8.0).") }
                    putJsonObject("model") { put("type", "string"); put("description", "Default musicgen.") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
        // speech
        add(
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("const", "speech")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Spoken text (read verbatim by the TTS engine).")
                    }
                    putJsonObject("voice") { put("type", "string"); put("description", "Default alloy.") }
                    putJsonObject("model") { put("type", "string"); put("description", "Default tts-1.") }
                    putJsonObject("format") { put("type", "string"); put("description", "Audio format (default mp3).") }
                    putJsonObject("speed") { put("type", "number"); put("description", "Default 1.0.") }
                    putJsonObject("language") { put("type", "string"); put("description", "Optional BCP-47 hint.") }
                    sharedProps()
                }
                put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
                put("additionalProperties", false)
            },
        )
    }
    put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("prompt"))))
}

/**
 * Properties present on every variant — `seed`, `projectId`,
 * `consistencyBindingIds`, `variantCount`. Extracted so each
 * `oneOf` branch doesn't duplicate the description prose.
 */
private fun JsonObjectBuilder.sharedProps() {
    putJsonObject("seed") {
        put("type", "integer")
        put("description", "Optional reproducibility seed; auto-minted client-side if omitted.")
    }
    putJsonObject("projectId") {
        put("type", "string")
        put("description", "Defaults to session's current project binding.")
    }
    putJsonObject("consistencyBindingIds") {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put(
            "description",
            "Source-node ids to fold. null = auto-fold all consistency nodes; [] = no folding; non-empty = fold only listed.",
        )
    }
    putJsonObject("variantCount") {
        put("type", "integer")
        put(
            "description",
            "How many distinct variants to generate (default 1, max ${AigcGenerateTool.MAX_VARIANT_COUNT}). " +
                "Each variant lands as its own lockfile entry — useful when the user wants to pick from " +
                "several options. N variants = N provider calls = N × cost; pin the chosen one with " +
                "project_pin_action(target=lockfile_entry).",
        )
    }
}
