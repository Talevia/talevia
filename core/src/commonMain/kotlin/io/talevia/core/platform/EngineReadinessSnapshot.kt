package io.talevia.core.platform

import io.talevia.core.tool.builtin.provider.query.EngineReadinessRow

/**
 * Compose-time builder for `provider_query(select=engine_readiness)`'s
 * snapshot. Each container passes the optional engine references it
 * built — typically `val x: XEngine? = providerAuth.apiKey("foo")
 * ?.let { ... }` — and the helper maps each slot to a row carrying
 * `(engineKind, providerId, wired, missingEnvVar?)`.
 *
 * The provider + env-var mapping is the canonical assignment used
 * across the JVM containers (CLI / Desktop / Server) so each engine
 * kind maps to one (provider, env-var) pair. Containers that disagree
 * with the default mapping (e.g. iOS / Android with no AIGC) can
 * still call this with `null` for every engine to publish the
 * "nothing wired" signal.
 *
 * Keeping the mapping in core (rather than each container's setup
 * code) means the tool's view of "which env var unlocks generate_image"
 * stays consistent across surfaces — an agent retrying on Desktop
 * won't see a different env-var name than the same agent on Server.
 */
fun buildEngineReadinessSnapshot(
    imageGen: ImageGenEngine? = null,
    videoGen: VideoGenEngine? = null,
    musicGen: MusicGenEngine? = null,
    tts: TtsEngine? = null,
    asr: AsrEngine? = null,
    vision: VisionEngine? = null,
    upscale: UpscaleEngine? = null,
    search: SearchEngine? = null,
): List<EngineReadinessRow> {
    fun row(kind: String, providerId: String, engine: Any?, envVar: String): EngineReadinessRow =
        EngineReadinessRow(
            engineKind = kind,
            providerId = providerId,
            wired = engine != null,
            missingEnvVar = if (engine != null) null else envVar,
        )
    // video_gen has two prod impls (openai Sora + volcano-seedance). When
    // wired, label the row with the actual providerId we picked so the
    // agent can tell whose pricing / capabilities it's about to hit. When
    // not wired, list both env-var options so a user setting either one
    // unlocks the slot.
    val videoGenRow = if (videoGen != null) {
        EngineReadinessRow(
            engineKind = "video_gen",
            providerId = videoGen.providerId,
            wired = true,
            missingEnvVar = null,
        )
    } else {
        EngineReadinessRow(
            engineKind = "video_gen",
            providerId = "openai|volcano-seedance",
            wired = false,
            missingEnvVar = "ARK_API_KEY or OPENAI_API_KEY",
        )
    }
    return listOf(
        row("image_gen", "openai", imageGen, "OPENAI_API_KEY"),
        videoGenRow,
        row("tts", "openai", tts, "OPENAI_API_KEY"),
        row("asr", "openai", asr, "OPENAI_API_KEY"),
        row("vision", "openai", vision, "OPENAI_API_KEY"),
        row("music_gen", "replicate", musicGen, "REPLICATE_API_TOKEN"),
        row("upscale", "replicate", upscale, "REPLICATE_API_TOKEN"),
        row("search", "tavily", search, "TAVILY_API_KEY"),
    )
}
