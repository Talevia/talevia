package io.talevia.core.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Provenance record for an AIGC ("random compiler") output — the minimum set of
 * facts required to, in principle, re-run the same generation and get the same
 * artifact. Maps to VISION §3.1 "把 AIGC 驯服成『随机编译器』": seed must be
 * explicit, model + version must be pinned, parameters must be recorded.
 *
 * This type is shared across all generative modalities (image / video / TTS /
 * music) — each `*GenEngine` returns this alongside its native artifact. It is
 * also the shape P1-3 "Lockfile" will persist per-generation into the Project.
 *
 * - [providerId]: the `ImageGenEngine.providerId` / equivalent — e.g. "openai".
 * - [modelId]: provider-scoped model identifier — e.g. "gpt-image-1".
 * - [modelVersion]: a finer-grained checkpoint/version pin when the provider
 *   exposes one. Nullable because some providers (OpenAI) do not echo a
 *   version back. When null, "the model identified by [modelId] at the time
 *   [createdAtEpochMs] was stamped" is the only lock we have.
 * - [seed]: the seed actually used. Tools are expected to pass an explicit
 *   seed to the engine — if the caller did not supply one, the tool must
 *   generate one client-side BEFORE calling the engine so this field is
 *   always populated.
 * - [parameters]: the full request body (or a canonicalised projection of it)
 *   as sent to the provider. Everything that could affect the output — prompt,
 *   size, n, response_format, guidance, style, etc. — should end up here.
 * - [createdAtEpochMs]: wall-clock stamp; used for eyeballing provenance and
 *   as a weak tiebreaker when [modelVersion] is null.
 */
@Serializable
data class GenerationProvenance(
    val providerId: String,
    val modelId: String,
    val modelVersion: String?,
    val seed: Long,
    val parameters: JsonObject,
    val createdAtEpochMs: Long,
)
