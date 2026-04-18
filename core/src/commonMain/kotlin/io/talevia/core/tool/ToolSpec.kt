package io.talevia.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * The view of a Tool that the [io.talevia.core.provider.LlmProvider] sees and forwards
 * to the LLM. Strips away Kotlin-side serializer wiring; only the LLM-facing JSON Schema
 * is included.
 *
 * [helpText] is serialised as `"description"` when sent to LLM APIs (per the OpenAI /
 * Anthropic tool-calling schemas); the Kotlin property name avoids colliding with
 * NSObject.description on iOS.
 */
data class ToolSpec(
    val id: String,
    val helpText: String,
    val inputSchema: JsonObject,
)
