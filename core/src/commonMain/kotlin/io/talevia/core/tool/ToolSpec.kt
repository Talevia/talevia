package io.talevia.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * The view of a Tool that the [io.talevia.core.provider.LlmProvider] sees and forwards
 * to the LLM. Strips away Kotlin-side serializer wiring; only the LLM-facing JSON Schema
 * is included.
 */
data class ToolSpec(
    val id: String,
    val description: String,
    val inputSchema: JsonObject,
)
