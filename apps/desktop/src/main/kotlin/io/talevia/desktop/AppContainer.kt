package io.talevia.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.agent.Agent
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.agent.SessionTitler
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.compaction.PerModelCompactionBudget
import io.talevia.core.compaction.PerModelCompactionThreshold
import io.talevia.core.db.TaleviaDb
import io.talevia.core.db.TaleviaDbFactory
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.platform.FilePicker
import io.talevia.core.platform.FileSystem
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.JvmBundleLocker
import io.talevia.core.platform.JvmFileSystem
import io.talevia.core.platform.JvmProcessRunner
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.ProcessRunner
import io.talevia.core.platform.SearchEngine
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.VideoEngine
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VisionEngine
import io.talevia.core.provider.EnvProviderAuth
import io.talevia.core.provider.ProviderAuth
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.openai.OpenAiImageGenEngine
import io.talevia.core.provider.openai.OpenAiSoraVideoGenEngine
import io.talevia.core.provider.openai.OpenAiTtsEngine
import io.talevia.core.provider.openai.OpenAiVisionEngine
import io.talevia.core.provider.openai.OpenAiWhisperEngine
import io.talevia.core.provider.replicate.ReplicateMusicGenEngine
import io.talevia.core.provider.replicate.ReplicateUpscaleEngine
import io.talevia.core.provider.tavily.TavilySearchEngine
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.registerAigcTools
import io.talevia.core.tool.builtin.registerBuiltinFileTools
import io.talevia.core.tool.builtin.registerClipAndTrackTools
import io.talevia.core.tool.builtin.registerMediaTools
import io.talevia.core.tool.builtin.registerProjectTools
import io.talevia.core.tool.builtin.registerSessionAndMetaTools
import io.talevia.core.tool.builtin.registerSourceNodeTools
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.platform.ffmpeg.FfmpegProxyGenerator
import io.talevia.platform.ffmpeg.FfmpegVideoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File

/**
 * Composition root for the desktop app. Holds the full singleton graph: SQLite,
 * stores, the FFmpeg-backed VideoEngine, the ToolRegistry, and (when at least
 * one provider API key is set in the environment) a ProviderRegistry and Agent
 * factory so the chat panel can drive tool dispatch through the real LLM loop.
 * UI consumes this via a single instance constructed at App startup.
 */
class AppContainer(env: Map<String, String> = System.getenv()) {
    private val opened = TaleviaDbFactory.open(env)
    val driver = opened.driver
    val db: TaleviaDb = opened.db

    /** Resolved DB location — `":memory:"` or an absolute filesystem path. Useful for logs. */
    val dbPath: String = opened.path
    val bus: EventBus = EventBus()
    /**
     * Tracks the most recent [io.talevia.core.agent.AgentRunState] per session
     * by subscribing to the bus. `session_query(select=status)` reads from it.
     */
    val agentStates: AgentRunStateTracker = AgentRunStateTracker(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val fallbackStates: AgentProviderFallbackTracker = AgentProviderFallbackTracker(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val warmupStats: io.talevia.core.provider.ProviderWarmupStats = io.talevia.core.provider.ProviderWarmupStats(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    val sessions = SqlDelightSessionStore(db, bus)

    /**
     * File-bundle [ProjectStore]. `TALEVIA_PROJECTS_HOME` is the default
     * directory for newly-created bundles; `TALEVIA_RECENTS_PATH` is the
     * per-machine catalog of which bundles this user has opened. Both are
     * filled in by [io.talevia.desktop.desktopEnvWithDefaults] so the fields
     * are always non-blank by construction.
     */
    val recentsRegistry: RecentsRegistry = RecentsRegistry(
        path = env["TALEVIA_RECENTS_PATH"]!!.toPath(),
    )
    val projectsHome = env["TALEVIA_PROJECTS_HOME"]!!.toPath()
    val projects: ProjectStore = FileProjectStore(
        registry = recentsRegistry,
        defaultProjectsHome = projectsHome,
        bus = bus,
        locker = JvmBundleLocker(),
    )

    /**
     * Bundle-local blob writer for AIGC + import tools — persists generated
     * bytes under the project bundle's `media/` directory so `git push` ships
     * them along with the rest of the project.
     */
    val bundleBlobWriter: BundleBlobWriter = FileBundleBlobWriter(projects)

    /**
     * TODO(file-bundle-migration): the engine takes a [MediaPathResolver] but
     * [io.talevia.core.tool.builtin.video.ExportTool] now hands a per-render
     * [io.talevia.core.platform.BundleMediaPathResolver] through `render(...)`.
     * The constructor-time resolver is therefore unreachable on the happy
     * path; this stub yells if anything ever falls through to it.
     */
    val engine: VideoEngine = FfmpegVideoEngine(
        pathResolver = MediaPathResolver { _ ->
            error("call site must pass per-render BundleMediaPathResolver via render(resolver=...)")
        },
    )
    val fileSystem: FileSystem = JvmFileSystem()
    val processRunner: ProcessRunner = JvmProcessRunner()
    val permissions = DefaultPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules.toMutableList()
    val filePicker: FilePicker = AwtFilePicker()
    val secrets: SecretStore = FileSecretStore()

    val httpClient: HttpClient = HttpClient(CIO)

    val providerAuth: ProviderAuth = EnvProviderAuth(env::get)

    /**
     * Image-generation engine, only wired when `OPENAI_API_KEY` is set. No
     * registry yet — one provider doesn't warrant one, and a premature
     * `GenerativeProviderRegistry` would just be scaffolding.
     */
    val imageGen: ImageGenEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiImageGenEngine(httpClient, it) }

    /** Whisper-backed ASR engine, wired alongside [imageGen] when OpenAI key is set. */
    val asr: AsrEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiWhisperEngine(httpClient, it) }

    /** TTS engine for the AIGC audio lane, gated on the same `OPENAI_API_KEY`. */
    val tts: TtsEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiTtsEngine(httpClient, it) }

    /** Sora-backed text-to-video engine, gated on the same `OPENAI_API_KEY`. */
    val videoGen: VideoGenEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiSoraVideoGenEngine(httpClient, it) }

    /** Vision-describe engine for the ML lane, gated on the same `OPENAI_API_KEY`. */
    val vision: VisionEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiVisionEngine(httpClient, it) }

    /**
     * Music-generation engine for the AIGC music lane (VISION §2). Wired to
     * Replicate-hosted MusicGen when `REPLICATE_API_TOKEN` is set; otherwise
     * stays null and `generate_music` stays unregistered. Override
     * `REPLICATE_MUSICGEN_MODEL` to point at a different model slug
     * (default `meta/musicgen`).
     */
    val musicGen: MusicGenEngine? = providerAuth.apiKey("replicate")
        ?.let { token ->
            ReplicateMusicGenEngine(
                httpClient = httpClient,
                apiKey = token,
                modelSlug = env["REPLICATE_MUSICGEN_MODEL"]?.takeIf { it.isNotBlank() } ?: "meta/musicgen",
            )
        }

    /**
     * Super-resolution engine (VISION §2 "ML 加工: 超分"). Wired to Replicate-
     * hosted Real-ESRGAN when `REPLICATE_API_TOKEN` is set; otherwise stays
     * null and `upscale_asset` stays unregistered. `REPLICATE_UPSCALE_MODEL`
     * overrides the default `nightmareai/real-esrgan` slug (SUPIR, CodeFormer,
     * etc.).
     */
    val upscale: UpscaleEngine? = providerAuth.apiKey("replicate")
        ?.let { token ->
            ReplicateUpscaleEngine(
                httpClient = httpClient,
                apiKey = token,
                modelSlug = env["REPLICATE_UPSCALE_MODEL"]?.takeIf { it.isNotBlank() } ?: "nightmareai/real-esrgan",
            )
        }

    /**
     * Web-search backing for the `web_search` tool. Wired to Tavily when
     * `TAVILY_API_KEY` is set; otherwise stays null and `web_search` stays
     * unregistered, mirroring the gating posture of the AIGC engines.
     */
    val search: SearchEngine? = providerAuth.apiKey("tavily")
        ?.let { TavilySearchEngine(httpClient, it) }

    val tools: ToolRegistry = ToolRegistry().apply {
        registerSessionAndMetaTools(sessions, agentStates, projects, bus, fallbackStates)
        registerMediaTools(engine, projects, bundleBlobWriter, FfmpegProxyGenerator())
        registerClipAndTrackTools(projects, sessions)
        registerProjectTools(projects, engine)
        registerSourceNodeTools(projects)
        registerBuiltinFileTools(fileSystem, processRunner, httpClient, search)
        registerAigcTools(imageGen, videoGen, musicGen, upscale, tts, asr, vision, bundleBlobWriter, projects)
    }

    /**
     * Provider registry. Resolution order (first source wins per provider id):
     *   1. [FileSecretStore] at `~/.talevia/secrets.properties` — UI-entered /
     *      persisted keys, canonical for the "paste your key once" flow.
     *   2. Process environment (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` /
     *      `GEMINI_API_KEY` · `GOOGLE_API_KEY`) — useful for dev and CI.
     * The secret store read is `suspend`; we block on it once at startup so
     * the rest of the container stays synchronous for Compose.
     */
    val providers: ProviderRegistry = runBlocking {
        ProviderRegistry.Builder()
            .addSecretStore(httpClient, secrets)
            .addEnv(httpClient, env)
            .build()
    }

    init {
        tools.register(io.talevia.core.tool.builtin.provider.ProviderQueryTool(providers, warmupStats))
        tools.register(io.talevia.core.tool.builtin.session.CompactSessionTool(providers, sessions, bus))
    }

    /**
     * Build a new Agent if at least one provider is configured, else null —
     * the UI uses this to decide whether to show the chat pane or a
     * "set ANTHROPIC_API_KEY / OPENAI_API_KEY to enable chat" placeholder.
     *
     * Wires the Compactor and SessionTitler so long chats get compacted and
     * new sessions auto-title from the first user turn — matching what the
     * server container builds.
     */
    fun newAgent(): Agent? {
        val provider = providers.default ?: return null
        return Agent(
            provider = provider,
            registry = tools,
            store = sessions,
            permissions = permissions,
            bus = bus,
            systemPrompt = io.talevia.core.agent.taleviaSystemPrompt(
                extraSuffix = projectInstructionsSuffix,
            ),
            compactor = Compactor(
                provider = provider,
                store = sessions,
                bus = bus,
                budgetResolver = compactionBudget,
            ),
            compactionThreshold = compactionThreshold,
            titler = SessionTitler(provider = provider, store = sessions),
            fallbackProviders = providers.all().filter { it.id != provider.id },
        )
    }

    /**
     * Per-model auto-compaction threshold resolver (see [PerModelCompactionThreshold]).
     * Prefetched from providers once at init; `runBlocking` is fine because
     * every in-tree provider returns hardcoded ModelInfo lists (no IO).
     */
    private val compactionThreshold: PerModelCompactionThreshold = kotlinx.coroutines.runBlocking {
        PerModelCompactionThreshold.fromRegistry(providers)
    }

    /** Per-model compaction-budget resolver — see CLI container for rationale. */
    private val compactionBudget: PerModelCompactionBudget = kotlinx.coroutines.runBlocking {
        PerModelCompactionBudget.fromRegistry(providers)
    }

    /**
     * Desktop-side AGENTS.md / CLAUDE.md discovery. Runs from the JVM `user.dir`
     * (the Compose Desktop launcher's working directory) plus user-home globals.
     * Cached at container init so `/new` sessions inherit the same injected
     * rules rather than re-walking the disk per-session.
     */
    private val projectInstructionsSuffix: String by lazy {
        val cwd = System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: return@lazy ""
        val found = io.talevia.core.agent.InstructionDiscovery.discover(startDir = cwd)
        io.talevia.core.agent.formatProjectInstructionsSuffix(found)
    }

    fun close() {
        runCatching { httpClient.close() }
        runCatching { driver.close() }
    }
}
