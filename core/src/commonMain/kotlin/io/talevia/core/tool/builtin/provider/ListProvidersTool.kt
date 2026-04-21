package io.talevia.core.tool.builtin.provider

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Enumerate LLM providers wired in the current runtime — the
 * introspection verb that was missing from the provider lane.
 *
 * The Agent internally resolves providers by id (via
 * `ModelRef.providerId`) but the agent itself had no programmatic way
 * to answer "which providers are available in this container?" That
 * matters when:
 *  - The agent wants to suggest a provider for a cost-sensitive task
 *    ("use Gemini for bulk summarisation, Claude for reasoning").
 *  - A deploy has only one provider configured and the agent should
 *    know to stop asking "which provider?" questions.
 *  - The user says "switch to OpenAI" and the agent wants to verify
 *    that provider is actually registered before acting.
 *
 * Lightweight read — the tool returns just `providerId` + `isDefault`
 * for each registered provider. Model-catalog discovery requires an
 * HTTP call per provider, which belongs in a separate tool when the
 * concrete flow needs it.
 *
 * Permission: `provider.read` (new keyword, default ALLOW — purely
 * local container state, no external call). Matches the `session.read`
 * / `source.read` silent-default convention for metadata-only reads.
 */
class ListProvidersTool(
    private val providers: ProviderRegistry,
) : Tool<ListProvidersTool.Input, ListProvidersTool.Output> {

    @Serializable class Input

    @Serializable data class Summary(
        val providerId: String,
        val isDefault: Boolean,
    )

    @Serializable data class Output(
        val total: Int,
        val defaultProviderId: String?,
        val providers: List<Summary>,
    )

    override val id: String = "list_providers"
    override val helpText: String =
        "List LLM providers wired in this runtime container. Each entry reports providerId and " +
            "whether it's the default. Model discovery (listModels) hits external APIs and is " +
            "intentionally out of scope here — this tool is pure local introspection."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("provider.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val defaultId = providers.default?.id
        val all = providers.all().map { Summary(providerId = it.id, isDefault = it.id == defaultId) }

        val summary = when {
            all.isEmpty() ->
                "No LLM providers configured in this runtime — set ANTHROPIC_API_KEY / " +
                    "OPENAI_API_KEY / GEMINI_API_KEY in the environment or via SecretStore."
            all.size == 1 -> "1 provider: ${all.single().providerId} (default)."
            else -> {
                val names = all.joinToString(", ") { s -> s.providerId + if (s.isDefault) "*" else "" }
                "${all.size} providers: $names (default marked *)."
            }
        }
        return ToolResult(
            title = "list providers (${all.size})",
            outputForLlm = summary,
            data = Output(
                total = all.size,
                defaultProviderId = defaultId,
                providers = all,
            ),
        )
    }
}
