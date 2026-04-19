package io.talevia.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.SessionTitler
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.metrics.EventBusMetricsSink
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.FileBlobWriter
import io.talevia.core.platform.FileMediaStorage
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.InMemorySecretStore
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.VideoEngine
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.openai.OpenAiImageGenEngine
import io.talevia.core.provider.openai.OpenAiTtsEngine
import io.talevia.core.provider.openai.OpenAiWhisperEngine
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.SynthesizeSpeechTool
import io.talevia.core.tool.builtin.ml.TranscribeAssetTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.source.DefineBrandPaletteTool
import io.talevia.core.tool.builtin.source.DefineCharacterRefTool
import io.talevia.core.tool.builtin.source.DefineStyleBibleTool
import io.talevia.core.tool.builtin.source.ListSourceNodesTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.AddSubtitleTool
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.ApplyFilterTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.platform.ffmpeg.FfmpegVideoEngine
import java.io.File

/**
 * Composition root for the server, mirrors `apps/desktop/AppContainer.kt`. Single
 * shared graph because v0 is single-tenant; multi-tenant isolation is a later concern.
 *
 * Auth: if the `TALEVIA_SERVER_TOKEN` env var is non-empty, every HTTP request
 * must carry `Authorization: Bearer <token>`; otherwise the server runs open
 * (intended for local development only).
 *
 * Permissions: [ServerPermissionService] rejects any tool that would otherwise
 * need to ASK the user — callers must grant the right permissions up-front via
 * a session's `permissionRules` or accept the container's default ruleset.
 */
class ServerContainer(
    env: Map<String, String> = System.getenv(),
    providerRegistryOverride: ProviderRegistry? = null,
) {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
    val db = TaleviaDb(driver)
    val bus = EventBus(extraBufferCapacity = 1024)
    val sessions: SessionStore = SqlDelightSessionStore(db, bus)
    val projects: ProjectStore = SqlDelightProjectStore(db)
    /**
     * When `TALEVIA_MEDIA_DIR` is set we persist the asset catalog to that
     * directory so AssetIds referenced by saved Projects survive restarts.
     * Unset → keep the M2 in-memory behaviour (dev / tests).
     */
    /**
     * Media root dir backing both the asset catalog and AIGC blob output.
     * `TALEVIA_MEDIA_DIR` persists under that path; unset falls back to
     * `<java.io.tmpdir>/talevia-media` so AIGC tools still have somewhere
     * to write even with an in-memory catalog.
     */
    val mediaRootDir: File = env["TALEVIA_MEDIA_DIR"]
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?: File(System.getProperty("java.io.tmpdir"), "talevia-media")
    val media: MediaStorage = env["TALEVIA_MEDIA_DIR"]
        ?.takeIf { it.isNotBlank() }
        ?.let { FileMediaStorage(File(it)) }
        ?: InMemoryMediaStorage()
    val engine: VideoEngine = FfmpegVideoEngine(pathResolver = media)
    val permissions = ServerPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules

    /** Bearer token required on every request when set. Empty = auth disabled. */
    val authToken: String = env["TALEVIA_SERVER_TOKEN"].orEmpty()

    /**
     * Env-backed secret store. Seeds from `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`
     * so tools that look up provider credentials through [SecretStore] see the
     * same values as [ProviderRegistry]. Writes go to an in-memory map only —
     * operators are expected to manage server secrets out-of-band.
     */
    val secrets: SecretStore = InMemorySecretStore(
        buildMap {
            env["ANTHROPIC_API_KEY"]?.takeIf(String::isNotEmpty)?.let { put("anthropic", it) }
            env["OPENAI_API_KEY"]?.takeIf(String::isNotEmpty)?.let { put("openai", it) }
        },
    )

    val httpClient: HttpClient = HttpClient(CIO)

    /**
     * Image-generation engine, only wired when `OPENAI_API_KEY` is set. The
     * tool itself registers unconditionally via [imageGen]?.let so headless
     * deployments without an OpenAI key simply don't expose it.
     */
    val imageGen: ImageGenEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiImageGenEngine(httpClient, it) }

    /**
     * Whisper-backed [AsrEngine], wired alongside [imageGen] when the OpenAI key
     * is present. Same conditional pattern: no key → no ml-transcribe tool.
     */
    val asr: AsrEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiWhisperEngine(httpClient, it) }

    /**
     * TTS engine for the AIGC audio lane. Same conditional pattern as [imageGen]
     * and [asr] — present only when `OPENAI_API_KEY` is set, so headless
     * deployments without it simply don't expose `synthesize_speech`.
     */
    val tts: TtsEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiTtsEngine(httpClient, it) }

    /** JVM blob writer backing AIGC tools. */
    val blobWriter: MediaBlobWriter = FileBlobWriter(mediaRootDir)

    val tools: ToolRegistry = ToolRegistry().apply {
        register(ImportMediaTool(media, engine))
        register(AddClipTool(projects, media))
        register(SplitClipTool(projects))
        register(ExportTool(projects, engine))
        register(ApplyFilterTool(projects))
        register(AddSubtitleTool(projects))
        register(AddTransitionTool(projects))
        register(RevertTimelineTool(sessions, projects))
        register(CreateProjectTool(projects))
        register(ListProjectsTool(projects))
        register(GetProjectStateTool(projects))
        register(DeleteProjectTool(projects))
        register(FindStaleClipsTool(projects))
        register(DefineCharacterRefTool(projects))
        register(DefineStyleBibleTool(projects))
        register(DefineBrandPaletteTool(projects))
        register(ListSourceNodesTool(projects))
        register(RemoveSourceNodeTool(projects))
        imageGen?.let { register(GenerateImageTool(it, media, blobWriter, projects)) }
        tts?.let { register(SynthesizeSpeechTool(it, media, blobWriter, projects)) }
        asr?.let { register(TranscribeAssetTool(it, media)) }
    }

    /** Provider registry built from `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env vars. */
    val providers: ProviderRegistry =
        providerRegistryOverride ?: ProviderRegistry.Builder().addEnv(httpClient, env).build()

    /** Counter registry scraped by GET /metrics. See [EventBusMetricsSink]. */
    val metrics: MetricsRegistry = MetricsRegistry()
    val metricsSink: EventBusMetricsSink = EventBusMetricsSink(bus, metrics)

    private val agentsByProvider = mutableMapOf<String, Agent>()

    private fun buildAgent(provider: LlmProvider): Agent =
        Agent(
            provider = provider,
            registry = tools,
            store = sessions,
            permissions = permissions,
            bus = bus,
            systemPrompt = io.talevia.core.agent.taleviaSystemPrompt(
                // Server runs headless: permission prompts default to deny, so the model
                // should not plan around interactive approval loops for ASK permissions.
                extraSuffix = "Runtime: headless server. ASK-scoped permissions resolve to deny; " +
                    "if a tool needs an ASK permission, surface that to the caller rather than retrying.",
            ),
            compactor = Compactor(
                provider = provider,
                store = sessions,
                bus = bus,
            ),
            titler = SessionTitler(provider = provider, store = sessions),
        )

    /**
     * One Agent per provider so callers can choose a backend per request while
     * `/cancel` still sees long-lived Agent state for already-started runs.
     */
    fun agentFor(providerId: String): Agent? {
        val provider = providers[providerId] ?: return null
        return agentsByProvider.getOrPut(providerId) { buildAgent(provider) }
    }

    suspend fun cancel(sessionId: SessionId): Boolean {
        for (agent in agentsByProvider.values) {
            if (agent.cancel(sessionId)) return true
        }
        return false
    }

    /**
     * Release resources owned by the container. Safe to call multiple times.
     *
     * Wired into Ktor's `ApplicationStopped` monitor in [serverModule] so
     * `Application.stop()` or a SIGTERM-driven shutdown closes the underlying
     * HttpClient connection pool and SQL driver instead of leaking them across
     * reloads and tests.
     */
    fun close() {
        runCatching { httpClient.close() }
        runCatching { driver.close() }
    }
}
