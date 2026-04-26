package io.talevia.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.cli.permission.cliPermissionRules
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
import io.talevia.core.permission.FilePermissionRulesPersistence
import io.talevia.core.permission.PermissionRulesPersistence
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.FileBundleBlobWriter
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
import io.talevia.core.provider.volcano.SeedanceVideoGenEngine
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.registerAigcTools
import io.talevia.core.tool.builtin.registerBuiltinFileTools
import io.talevia.core.tool.builtin.registerClipAndTrackTools
import io.talevia.core.tool.builtin.registerMediaTools
import io.talevia.core.tool.builtin.registerProjectTools
import io.talevia.core.tool.builtin.registerSessionAndMetaTools
import io.talevia.core.tool.builtin.registerSourceNodeTools
import io.talevia.platform.ffmpeg.FfmpegProxyGenerator
import io.talevia.platform.ffmpeg.FfmpegVideoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath

/**
 * Composition root for the CLI app. Structurally identical to
 * `apps/desktop/AppContainer` — same stores, same tool registry, same provider
 * resolution order — with two deliberate differences:
 *
 *  1. No `FilePicker`. CLI users pass paths as tool arguments; there is no
 *     modal picker to wire up.
 *  2. Permission prompts are routed to stdin by [StdinPermissionPrompt] rather
 *     than the Compose permission dialog. The underlying
 *     [DefaultPermissionService] is unchanged.
 *
 * DB and media dir defaults are intentionally the same as desktop
 * (`~/.talevia/...`) so sessions created in one app show up in the other.
 *
 * Tool registration is grouped into category-scoped extension functions in
 * `io.talevia.core.tool.builtin.DefaultBuiltinRegistrations` (shared with
 * every other composition root — see `debt-cross-container-tool-list-builder`);
 * see the `apply { ... }` block on [tools] below.
 */
class CliContainer(env: Map<String, String> = System.getenv()) {
    private val opened = TaleviaDbFactory.open(env)
    val driver = opened.driver
    val db: TaleviaDb = opened.db

    /** Resolved DB location — `":memory:"` or an absolute filesystem path. Useful for logs. */
    val dbPath: String = opened.path
    val bus: EventBus = EventBus()
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
    val rateLimitHistory: io.talevia.core.provider.RateLimitHistoryRecorder =
        io.talevia.core.provider.RateLimitHistoryRecorder(
            bus,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    /**
     * In-process metrics registry. Counters are fed from the bus via
     * [io.talevia.core.metrics.EventBusMetricsSink]; histograms (tool /
     * agent wall-times) are populated by the Agent when it's given a
     * reference to this same registry. Exposed so `/metrics` can dump the
     * contents on demand — CLI has no HTTP endpoint so the slash command
     * is the only read surface.
     */
    val metrics: io.talevia.core.metrics.MetricsRegistry =
        io.talevia.core.metrics.MetricsRegistry()

    private val metricsSink: io.talevia.core.metrics.EventBusMetricsSink =
        io.talevia.core.metrics.EventBusMetricsSink(bus, metrics).also {
            it.attach(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }

    val sessions = SqlDelightSessionStore(db, bus)

    val permissionHistory: io.talevia.core.permission.PermissionHistoryRecorder =
        io.talevia.core.permission.PermissionHistoryRecorder(
            bus = bus,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            store = sessions,
        )
    val busTrace: io.talevia.core.bus.BusEventTraceRecorder =
        io.talevia.core.bus.BusEventTraceRecorder(
            bus,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

    /**
     * File-bundle [ProjectStore]. `TALEVIA_PROJECTS_HOME` is the default
     * directory for newly-created bundles; `TALEVIA_RECENTS_PATH` is the
     * per-machine catalog of which bundles this user has opened. Both are
     * filled in by [io.talevia.cli.envWithDefaults] so the fields are
     * always non-blank by construction.
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

    /**
     * Per-user persistence for interactive "Always" permission grants.
     * Lives at `<TALEVIA_RECENTS_PATH>.parent/permission-rules.json` (i.e.
     * next to `recents.json` under `~/.talevia/` by default). Grants
     * written here survive CLI process restarts so operators don't
     * re-answer the same prompts every launch.
     */
    val permissionRulesPath: Path = env["TALEVIA_RECENTS_PATH"]!!.toPath()
        .let { it.parent ?: "/".toPath() }
        .resolve("permission-rules.json")
    val permissionRulesPersistence: PermissionRulesPersistence =
        FilePermissionRulesPersistence(permissionRulesPath)

    /**
     * CLI auto-approves every ASK by default — see [cliPermissionRules] for the
     * rationale. Opt out with `TALEVIA_CLI_PROMPT_ON_ASK=1`. Interactive
     * "Always" grants from prior CLI sessions merge in at startup via
     * [permissionRulesPersistence].
     */
    val permissionRules: MutableList<io.talevia.core.permission.PermissionRule> =
        cliPermissionRules(
            base = DefaultPermissionRuleset.rules +
                runBlocking { permissionRulesPersistence.load() },
            autoApprove = env["TALEVIA_CLI_PROMPT_ON_ASK"] != "1",
        ).toMutableList()
    val secrets: SecretStore = FileSecretStore()

    val httpClient: HttpClient = HttpClient(CIO)

    /**
     * Env vars backfilled from the [secrets] store — same lookup the chat
     * [providers] registry uses, so AIGC / ML engines don't go dark when the
     * user only set up keys through `/setkey`.
     *
     * Why this matters: [ProviderRegistry.Builder.addSecretStore] threads the
     * secrets store into chat provider construction, so the OpenAI chat model
     * works fine with a key in `~/.talevia/secrets.properties`. But
     * `providerAuth` feeds a **second** set of clients — [imageGen], [videoGen],
     * [asr], [tts], [vision], and the Replicate lane — and those only ran if
     * the raw `OPENAI_API_KEY` env var was set. Result: a CLI user who did the
     * normal interactive setup saw chat work but `generate_image` /
     * `generate_video` / `transcribe_asset` / `synthesize_speech` /
     * `describe_asset` silently unregister, and the agent had no way to
     * produce an asset → no way to [add_clip] → no way to export.
     *
     * We eagerly read the known provider-id slots from [secrets] (each has a
     * short, stable, CI-friendly env var name) and backfill them into the env
     * map before constructing [providerAuth]. Explicit env vars still win —
     * only empty / missing slots fall back to the secret store.
     */
    private val envWithSecrets: Map<String, String> = run {
        val backfill = mutableMapOf<String, String>()
        kotlinx.coroutines.runBlocking {
            // SecretStore key -> env var name. Mirrors DEFAULT_ENV_VARS in EnvProviderAuth.
            val map = listOf(
                ProviderRegistry.SecretKeys.OPENAI to "OPENAI_API_KEY",
                ProviderRegistry.SecretKeys.ANTHROPIC to "ANTHROPIC_API_KEY",
                ProviderRegistry.SecretKeys.GEMINI to "GEMINI_API_KEY",
                ProviderRegistry.SecretKeys.GOOGLE to "GOOGLE_API_KEY",
                "replicate" to "REPLICATE_API_TOKEN",
                "tavily" to "TAVILY_API_KEY",
            )
            for ((secretKey, envName) in map) {
                if (env[envName].isNullOrBlank()) {
                    secrets.get(secretKey)?.takeIf { it.isNotBlank() }?.let { backfill[envName] = it }
                }
            }
        }
        env + backfill
    }

    val providerAuth: ProviderAuth = EnvProviderAuth(envWithSecrets::get)

    val imageGen: ImageGenEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiImageGenEngine(httpClient, it) }

    val asr: AsrEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiWhisperEngine(httpClient, it) }

    val tts: TtsEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiTtsEngine(httpClient, it) }

    // Seedance via ARK_API_KEY preferred (M2 criterion 2 — second prod impl);
    // fallback to OpenAI Sora when only OPENAI_API_KEY is set.
    val videoGen: VideoGenEngine? =
        providerAuth.apiKey("volcano")?.let { SeedanceVideoGenEngine(httpClient, it) }
            ?: providerAuth.apiKey("openai")?.let { OpenAiSoraVideoGenEngine(httpClient, it) }

    val vision: VisionEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiVisionEngine(httpClient, it) }

    val musicGen: MusicGenEngine? = providerAuth.apiKey("replicate")
        ?.let { token ->
            ReplicateMusicGenEngine(
                httpClient = httpClient,
                apiKey = token,
                modelSlug = env["REPLICATE_MUSICGEN_MODEL"]?.takeIf { it.isNotBlank() } ?: "meta/musicgen",
            )
        }

    val upscale: UpscaleEngine? = providerAuth.apiKey("replicate")
        ?.let { token ->
            ReplicateUpscaleEngine(
                httpClient = httpClient,
                apiKey = token,
                modelSlug = env["REPLICATE_UPSCALE_MODEL"]?.takeIf { it.isNotBlank() } ?: "nightmareai/real-esrgan",
            )
        }

    val search: SearchEngine? = providerAuth.apiKey("tavily")
        ?.let { TavilySearchEngine(httpClient, it) }

    val tools: ToolRegistry = ToolRegistry().apply {
        registerSessionAndMetaTools(
            sessions, agentStates, projects, bus, fallbackStates, permissionHistory,
            permissionRulesPersistence, busTrace = busTrace,
        )
        registerMediaTools(engine, projects, bundleBlobWriter, FfmpegProxyGenerator())
        registerClipAndTrackTools(projects, sessions)
        registerProjectTools(projects, engine, sessions = sessions)
        registerSourceNodeTools(projects)
        registerBuiltinFileTools(fileSystem, processRunner, httpClient, search)
        registerAigcTools(imageGen, videoGen, musicGen, upscale, tts, asr, vision, bundleBlobWriter, projects)
    }

    /**
     * On-disk credential bundle for the OAuth `openai-codex` provider. Lives at
     * `~/.talevia/openai-codex-auth.json` (POSIX 0600). Populated by the
     * `/login openai-codex` slash command; unaffected by API-key based
     * `OPENAI_API_KEY` / `secrets.properties`.
     */
    val openAiCodexCredentials: io.talevia.core.provider.openai.codex.OpenAiCodexCredentialStore =
        io.talevia.core.provider.openai.codex.FileOpenAiCodexCredentialStore()

    val providers: ProviderRegistry = runBlocking {
        ProviderRegistry.Builder()
            .addSecretStore(httpClient, secrets, env)
            .addEnv(httpClient, env)
            .addOpenAiCodex(httpClient, openAiCodexCredentials)
            .build()
    }

    init {
        // Tools that depend on the ProviderRegistry land after providers
        // is initialised — the property-initialiser ordering puts them
        // past the main `tools` block.
        tools.register(
            io.talevia.core.tool.builtin.provider.ProviderQueryTool(
                providers, warmupStats, projects, rateLimitHistory,
                engineReadiness = io.talevia.core.platform.buildEngineReadinessSnapshot(
                    imageGen = imageGen,
                    videoGen = videoGen,
                    musicGen = musicGen,
                    tts = tts,
                    asr = asr,
                    vision = vision,
                    upscale = upscale,
                    search = search,
                ),
            ),
        )
        // Re-register SessionActionTool with `providers` wired in so
        // action="compact" dispatches against the live ProviderRegistry.
        // The first-pass `registerSessionAndMetaTools` registration
        // can't pass providers (the registry is built from this same
        // container in the second pass). `ToolRegistry.register` is
        // overwrite-by-id so this replaces the first-pass registration.
        tools.register(
            io.talevia.core.tool.builtin.session.SessionActionTool(
                sessions = sessions,
                permissionRulesPersistence = permissionRulesPersistence,
                projects = projects,
                busTrace = busTrace,
                bus = bus,
                providers = providers,
            ),
        )

        // Eager-warm every configured LLM provider so first AIGC dispatch
        // doesn't pay TLS + auth + model-handshake latency. Best-effort;
        // failures swallowed (logged).
        io.talevia.core.provider.kickoffEagerProviderWarmup(
            providers,
            bus,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    fun newAgent(): Agent? {
        val provider = providers.default ?: return null
        return Agent(
            provider = provider,
            registry = tools,
            store = sessions,
            permissions = permissions,
            bus = bus,
            systemPrompt = io.talevia.core.agent.taleviaSystemPrompt(
                extraSuffix = composeExtraSuffix(cliRuntimeContext(), projectInstructionsSuffix()),
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
            projects = projects,
            metrics = metrics,
            routingPolicy = io.talevia.core.agent.resolveProviderRoutingPolicy(
                System.getenv("TALEVIA_PROVIDER_ROUTING"),
            ),
        )
    }

    /**
     * Per-model auto-compaction threshold resolver — prefetched once at
     * init from the wired providers. `runBlocking` is fine here because
     * all current providers return hardcoded ModelInfo lists (no IO);
     * init cost is microseconds.
     */
    private val compactionThreshold: PerModelCompactionThreshold = kotlinx.coroutines.runBlocking {
        PerModelCompactionThreshold.fromRegistry(providers)
    }

    /**
     * Per-model compaction-budget resolver — complements
     * [compactionThreshold]. Threshold decides **when** to compact (85 %
     * of the model's context window); budget decides **how aggressively**
     * to compact (keep 30 % after the pass). Both scale per-model so a
     * 64k-context session doesn't try to keep the 40k legacy default.
     */
    private val compactionBudget: PerModelCompactionBudget = kotlinx.coroutines.runBlocking {
        PerModelCompactionBudget.fromRegistry(providers)
    }

    /**
     * Runtime context appended to the system prompt. Tells the model which
     * directory the user launched `talevia` from so natural-language "here" /
     * "this folder" / "the current dir" resolves correctly when composing fs
     * tool inputs. Without this the agent has no choice but to ask the user
     * for an absolute path on every "show me what's here" request.
     *
     * CLI-only on purpose — desktop runs in `$HOME` / `/` by default (whichever
     * the launcher app sets) and server is headless, so a cwd reference would
     * be misleading there.
     */
    private fun cliRuntimeContext(): String {
        val cwd = System.getProperty("user.dir").orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        return buildString {
            append("# CLI runtime context\n\n")
            append("You are running inside the Talevia terminal CLI.\n")
            if (cwd.isNotEmpty()) append("- Current working directory: ").append(cwd).append('\n')
            if (home.isNotEmpty()) append("- User home: ").append(home).append('\n')
            append('\n')
            append(
                "When the user says \"here\" / \"this folder\" / \"the current dir\" — or gives any " +
                    "path-like reference without an absolute prefix — resolve it against the current " +
                    "working directory above before calling any fs tool. The tools still reject relative " +
                    "paths at the boundary, so always hand them a fully absolute path you derived from cwd.",
            )
        }
    }

    /**
     * Discover `AGENTS.md` / `CLAUDE.md` files from the user's cwd upward plus
     * global home-dir spots, then format them as a "# Project context"
     * suffix. Empty string if nothing was found, so the caller can unconditionally
     * concatenate. Loaded once per container — an identical cycle per
     * [newAgent] call would re-read disk on every `/new` session, which isn't
     * what users expect from an explicit per-session reset.
     */
    private val projectInstructionsSuffixCache: String by lazy {
        val cwd = System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: return@lazy ""
        val found = io.talevia.core.agent.InstructionDiscovery.discover(startDir = cwd)
        io.talevia.core.agent.formatProjectInstructionsSuffix(found)
    }

    private fun projectInstructionsSuffix(): String = projectInstructionsSuffixCache

    /** Join non-blank suffix fragments with a blank line separator. */
    private fun composeExtraSuffix(vararg fragments: String): String =
        fragments.filter { it.isNotBlank() }.joinToString("\n\n")

    fun close() {
        runCatching { httpClient.close() }
        runCatching { driver.close() }
    }
}
