package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Per-engine slot readiness as seen by the live container — answers
 * "which generative engines can I dispatch in this process?"
 *
 * Each row corresponds to one engine kind ([engineKind]). [providerId]
 * is the producer the container picked for this kind (e.g. openai for
 * image_gen, replicate for music_gen). [wired] is true iff the
 * container actually constructed an instance — typically gated on the
 * provider's API key being present in env. [missingEnvVar] names the
 * env var the agent / operator can set to unlock the slot; null when
 * already wired.
 *
 * Engine kinds (kebab-snake-case to match the tool ids that consume
 * them): `image_gen`, `video_gen`, `music_gen`, `tts`, `asr`, `vision`,
 * `upscale`, `search`. Containers that don't expose AIGC at all (Android,
 * iOS today) emit all-false rows so the agent sees `wired=false`
 * everywhere rather than getting an empty table that's ambiguous with
 * "not wired by container".
 *
 * The snapshot is captured at container bootstrap and never refreshed
 * — env-var changes mid-process don't flip wiring without restart.
 * That matches reality: each engine is `val engine: X? = key?.let
 * { ... }`, so a key arriving later wouldn't be picked up anyway.
 */
@Serializable
data class EngineReadinessRow(
    val engineKind: String,
    val providerId: String,
    val wired: Boolean,
    val missingEnvVar: String? = null,
)

/**
 * `select=engine_readiness` — per-engine wiring snapshot.
 *
 * Backstory: the agent re-attaching to a session, or an operator
 * preparing a plan, needs "can I call generate_image / synthesize_speech
 * / generate_music in this process?" Today that requires the agent to
 * try the tool and parse the failure (expensive + side-effecting on
 * tools that have already partially dispatched). This select reports
 * the snapshot directly.
 *
 * No filters — returns one row per engine kind the container knows
 * about, sorted by engineKind for stable diffing. When the container
 * doesn't wire engine readiness (test rigs that skip composition),
 * the query reports zero rows with a "not wired" note rather than
 * failing — same convention as warmup_stats / rate_limit_history.
 */
internal fun runEngineReadinessQuery(
    snapshot: List<EngineReadinessRow>?,
): ToolResult<ProviderQueryTool.Output> {
    val rows = (snapshot ?: emptyList()).sortedBy { it.engineKind }
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(EngineReadinessRow.serializer()),
        rows,
    ) as JsonArray

    val summary = when {
        snapshot == null ->
            "Engine readiness snapshot not wired in this rig — query reports zero rows. " +
                "(Production containers always wire it; this only fires in non-snapshot test rigs.)"
        rows.isEmpty() ->
            "Engine readiness snapshot is empty — container exposes no AIGC engines."
        else -> {
            val wired = rows.count { it.wired }
            "Engine readiness: $wired/${rows.size} wired. " +
                rows.joinToString(", ") {
                    "${it.engineKind}=${if (it.wired) "✓" else "✗(${it.missingEnvVar ?: "?"})"}"
                }
        }
    }

    return ToolResult(
        title = "provider_query engine_readiness (${rows.size} engine${if (rows.size == 1) "" else "s"})",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_ENGINE_READINESS,
            total = rows.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
