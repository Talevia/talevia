package io.talevia.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.agent.Agent
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.compaction.PerModelCompactionBudget
import io.talevia.core.compaction.PerModelCompactionThreshold
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.platform.InMemorySecretStore
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.VideoEngine
import io.talevia.core.provider.EnvProviderAuth
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.ProviderAuth
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.registerAigcTools
import io.talevia.core.tool.builtin.registerClipAndTrackTools
import io.talevia.core.tool.builtin.registerMediaTools
import io.talevia.core.tool.builtin.registerProjectTools
import io.talevia.core.tool.builtin.registerSessionAndMetaTools
import io.talevia.core.tool.builtin.registerSourceNodeTools
import io.talevia.core.tool.builtin.video.ExportTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath

/**
 * Composition root for the Android app. Mirrors `apps/desktop/AppContainer.kt` and
 * `apps/ios/AppContainer.swift`. Persistent storage uses SQLDelight's Android
 * driver with the app's data directory.
 */
class AndroidAppContainer(context: Context) {
    val driver = AndroidSqliteDriver(TaleviaDb.Schema, context, "talevia.db")
    val db = TaleviaDb(driver)
    val bus = EventBus()
    val agentStates = AgentRunStateTracker(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val fallbackStates = AgentProviderFallbackTracker(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val warmupStats = io.talevia.core.provider.ProviderWarmupStats(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val permissionHistory: io.talevia.core.permission.PermissionHistoryRecorder =
        io.talevia.core.permission.PermissionHistoryRecorder(
            bus,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    val sessions: SessionStore = SqlDelightSessionStore(db, bus)

    /**
     * File-bundle [ProjectStore]. Bundles default to `<filesDir>/projects/...`
     * and the per-machine recents catalog lives at `<filesDir>/recents.json`.
     * Both directories are app-private (Android scoped storage) — `git` and
     * inter-app access are out of scope for the phone form factor.
     */
    val recentsRegistry: RecentsRegistry = RecentsRegistry(
        path = context.filesDir.resolve("recents.json").absolutePath.toPath(),
    )
    val projectsHome = context.filesDir.resolve("projects").absolutePath.toPath()
    val projects: ProjectStore = FileProjectStore(
        registry = recentsRegistry,
        defaultProjectsHome = projectsHome,
        bus = bus,
    )

    /**
     * Bundle-local blob writer for AIGC + import tools. Persists generated
     * bytes under `<bundleRoot>/media/`.
     */
    val bundleBlobWriter: BundleBlobWriter = FileBundleBlobWriter(projects)

    /**
     * The Media3 engine takes a [MediaPathResolver] for source-clip path
     * resolution, but [io.talevia.core.tool.builtin.video.ExportTool] now
     * hands a per-render [io.talevia.core.platform.BundleMediaPathResolver]
     * through `render(...)`. The constructor-time resolver is therefore
     * unreachable on the happy path; this stub yells if anything ever falls
     * through to it.
     */
    private val stubResolver: MediaPathResolver = MediaPathResolver { _ ->
        error("call site must pass per-render BundleMediaPathResolver via render(resolver=...)")
    }
    val engine: VideoEngine = Media3VideoEngine(context, stubResolver)

    /**
     * Media3-backed proxy generator — pulls a mid-duration thumbnail
     * for video assets via `MediaMetadataRetriever.getFrameAtTime`.
     * VISION §5.3 parity with desktop/CLI/server which use
     * `FfmpegProxyGenerator`. Cache-tier output under
     * `<cacheDir>/talevia-proxies/`, recoverable via re-import.
     */
    val proxyGenerator = io.talevia.android.Media3ProxyGenerator(
        proxyDir = java.io.File(context.cacheDir, "talevia-proxies"),
    )
    val permissions = DefaultPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules.toMutableList()
    /**
     * In-memory [SecretStore] stub. Android should eventually back this with
     * EncryptedSharedPreferences or Keystore; in-memory is placeholder so the
     * composition root can satisfy downstream dependencies today.
     */
    val secrets: SecretStore = InMemorySecretStore()
    val httpClient: HttpClient = HttpClient(CIO)

    val tools: ToolRegistry = ToolRegistry().apply {
        registerSessionAndMetaTools(sessions, agentStates, projects, bus, fallbackStates, permissionHistory)
        registerMediaTools(engine, projects, bundleBlobWriter, proxyGenerator)
        registerClipAndTrackTools(projects, sessions)
        registerProjectTools(projects, engine)
        registerSourceNodeTools(projects)
        // Android deliberately skips registerBuiltinFileTools — phone UI has
        // no fs / shell / web surface. AIGC: all engines null on mobile
        // (no cloud-AIGC today); the two always-on tools
        // (CompareAigcCandidatesTool, ReplayLockfileTool) still land through
        // registerAigcTools so the LLM can inspect past runs even without a
        // live generator.
        registerAigcTools(
            imageGen = null,
            videoGen = null,
            musicGen = null,
            upscale = null,
            tts = null,
            asr = null,
            vision = null,
            bundleBlobWriter = bundleBlobWriter,
            projects = projects,
        )
    }

    /**
     * Centralised auth-status lookup. Reads system properties (adb shell
     * `setprop` or BuildConfig injection) first, falls back to OS env.
     * UI code can `providerAuth.authStatus("openai")` to render "missing key"
     * banners without knowing env var names.
     */
    val providerAuth: ProviderAuth = EnvProviderAuth(
        envLookup = { name -> System.getProperty(name) ?: System.getenv(name) },
    )

    /**
     * LLM provider registry. Reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` /
     * `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) via [providerAuth]. Callers should
     * check [providers.default] before calling [agentFor].
     */
    val providers: ProviderRegistry = ProviderRegistry.Builder()
        .addEnv(httpClient, buildMap {
            for (key in listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")) {
                val value = System.getProperty(key) ?: System.getenv(key)
                value?.takeIf(String::isNotEmpty)?.let { put(key, it) }
            }
        })
        .build()

    init {
        tools.register(io.talevia.core.tool.builtin.provider.ProviderQueryTool(providers, warmupStats, projects))
        tools.register(io.talevia.core.tool.builtin.session.CompactSessionTool(providers, sessions, bus))
    }

    private val agents = mutableMapOf<String, Agent>()

    /**
     * Per-model auto-compaction threshold prefetched from the wired
     * providers (see [PerModelCompactionThreshold]). Hardcoded
     * ModelInfo lists make `runBlocking` effectively synchronous at
     * container construction.
     */
    private val compactionThreshold: PerModelCompactionThreshold = kotlinx.coroutines.runBlocking {
        PerModelCompactionThreshold.fromRegistry(providers)
    }

    /** Per-model compaction-budget resolver — complements [compactionThreshold]. */
    private val compactionBudget: PerModelCompactionBudget = kotlinx.coroutines.runBlocking {
        PerModelCompactionBudget.fromRegistry(providers)
    }

    fun agentFor(providerId: String): Agent? {
        val provider: LlmProvider = providers.get(providerId) ?: return null
        return agents.getOrPut(providerId) {
            Agent(
                provider = provider,
                registry = tools,
                store = sessions,
                permissions = permissions,
                bus = bus,
                compactor = Compactor(provider, sessions, bus, budgetResolver = compactionBudget),
                compactionThreshold = compactionThreshold,
                fallbackProviders = providers.all().filter { it.id != provider.id },
            )
        }
    }
}
