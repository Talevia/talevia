package io.talevia.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.agent.Agent
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
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
import io.talevia.core.tool.builtin.TodoWriteTool
import io.talevia.core.tool.builtin.project.CreateProjectFromTemplateTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectTool
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.ExportProjectTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GcClipRenderCacheTool
import io.talevia.core.tool.builtin.project.GcLockfileTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ImportProjectFromJsonTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.OpenProjectTool
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.ProjectSnapshotActionTool
import io.talevia.core.tool.builtin.project.PruneLockfileTool
import io.talevia.core.tool.builtin.project.RemoveAssetTool
import io.talevia.core.tool.builtin.project.RenameProjectTool
import io.talevia.core.tool.builtin.project.SetClipAssetPinnedTool
import io.talevia.core.tool.builtin.project.SetLockfileEntryPinnedTool
import io.talevia.core.tool.builtin.project.SetOutputProfileTool
import io.talevia.core.tool.builtin.project.ValidateProjectTool
import io.talevia.core.tool.builtin.session.ArchiveSessionTool
import io.talevia.core.tool.builtin.session.DeleteSessionTool
import io.talevia.core.tool.builtin.session.EstimateSessionTokensTool
import io.talevia.core.tool.builtin.session.ExportSessionTool
import io.talevia.core.tool.builtin.session.ForkSessionTool
import io.talevia.core.tool.builtin.session.ReadPartTool
import io.talevia.core.tool.builtin.session.RenameSessionTool
import io.talevia.core.tool.builtin.session.RevertSessionTool
import io.talevia.core.tool.builtin.session.SessionQueryTool
import io.talevia.core.tool.builtin.session.SetSessionSpendCapTool
import io.talevia.core.tool.builtin.session.SetToolEnabledTool
import io.talevia.core.tool.builtin.session.SwitchProjectTool
import io.talevia.core.tool.builtin.session.UnarchiveSessionTool
import io.talevia.core.tool.builtin.source.AddSourceNodeTool
import io.talevia.core.tool.builtin.source.DescribeSourceNodeTool
import io.talevia.core.tool.builtin.source.DiffSourceNodesTool
import io.talevia.core.tool.builtin.source.ExportSourceNodeTool
import io.talevia.core.tool.builtin.source.ForkSourceNodeTool
import io.talevia.core.tool.builtin.source.ImportSourceNodeTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.source.RenameSourceNodeTool
import io.talevia.core.tool.builtin.source.SetSourceNodeParentsTool
import io.talevia.core.tool.builtin.source.SourceQueryTool
import io.talevia.core.tool.builtin.source.UpdateSourceNodeBodyTool
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.AddTrackTool
import io.talevia.core.tool.builtin.video.ApplyLutTool
import io.talevia.core.tool.builtin.video.ClearTimelineTool
import io.talevia.core.tool.builtin.video.ConsolidateMediaIntoBundleTool
import io.talevia.core.tool.builtin.video.DuplicateClipTool
import io.talevia.core.tool.builtin.video.DuplicateTrackTool
import io.talevia.core.tool.builtin.video.EditTextClipTool
import io.talevia.core.tool.builtin.video.ExportDryRunTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ExtractFrameTool
import io.talevia.core.tool.builtin.video.FadeAudioClipTool
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.MoveClipTool
import io.talevia.core.tool.builtin.video.RelinkAssetTool
import io.talevia.core.tool.builtin.video.RemoveClipTool
import io.talevia.core.tool.builtin.video.RemoveTrackTool
import io.talevia.core.tool.builtin.video.ReorderTracksTool
import io.talevia.core.tool.builtin.video.ReplaceClipTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.SetClipSourceBindingTool
import io.talevia.core.tool.builtin.video.SetClipTransformTool
import io.talevia.core.tool.builtin.video.SetClipVolumeTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.core.tool.builtin.video.TransitionActionTool
import io.talevia.core.tool.builtin.video.TrimClipTool
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
        register(io.talevia.core.tool.builtin.meta.ListToolsTool(this))
        register(io.talevia.core.tool.builtin.meta.EstimateTokensTool())
        register(TodoWriteTool())
        register(io.talevia.core.tool.builtin.DraftPlanTool())
        register(io.talevia.core.tool.builtin.ExecutePlanTool(this, sessions))
        register(io.talevia.core.tool.builtin.aigc.CompareAigcCandidatesTool(this))
        register(io.talevia.core.tool.builtin.aigc.ReplayLockfileTool(this, projects))
        register(SessionQueryTool(sessions, agentStates, projects, toolRegistry = this))
        register(ExportSessionTool(sessions))
        register(EstimateSessionTokensTool(sessions))
        register(ForkSessionTool(sessions))
        register(RenameSessionTool(sessions))
        register(SetSessionSpendCapTool(sessions))
        register(SetToolEnabledTool(sessions))
        register(SwitchProjectTool(sessions, projects, bus = bus))
        register(RevertSessionTool(sessions, projects, bus))
        register(ArchiveSessionTool(sessions))
        register(UnarchiveSessionTool(sessions))
        register(DeleteSessionTool(sessions))
        register(ReadPartTool(sessions))
        register(ImportMediaTool(engine, projects, proxyGenerator = proxyGenerator))
        register(ExtractFrameTool(engine, projects, bundleBlobWriter))
        register(ConsolidateMediaIntoBundleTool(projects))
        register(RelinkAssetTool(projects))
        register(AddClipTool(projects))
        register(ReplaceClipTool(projects))
        register(SplitClipTool(projects))
        register(RemoveClipTool(projects))
        register(MoveClipTool(projects))
        register(SetClipSourceBindingTool(projects))
        register(DuplicateClipTool(projects))
        register(TrimClipTool(projects))
        register(SetClipVolumeTool(projects))
        register(FadeAudioClipTool(projects))
        register(SetClipTransformTool(projects))
        register(ExportTool(projects, engine))
        register(ExportDryRunTool(projects))
        register(FilterActionTool(projects))
        register(ApplyLutTool(projects))
        register(AddSubtitlesTool(projects))
        register(EditTextClipTool(projects))
        register(TransitionActionTool(projects))
        register(AddTrackTool(projects))
        register(DuplicateTrackTool(projects))
        register(RemoveTrackTool(projects))
        register(ReorderTracksTool(projects))
        register(RevertTimelineTool(sessions, projects))
        register(ClearTimelineTool(projects))
        register(CreateProjectTool(projects))
        register(OpenProjectTool(projects))
        register(CreateProjectFromTemplateTool(projects))
        register(ListProjectsTool(projects))
        register(GetProjectStateTool(projects))
        register(ProjectQueryTool(projects))
        register(RemoveAssetTool(projects))
        register(SetOutputProfileTool(projects))
        register(ValidateProjectTool(projects))
        register(DeleteProjectTool(projects))
        register(RenameProjectTool(projects))
        register(FindStaleClipsTool(projects))
        register(PruneLockfileTool(projects))
        register(GcLockfileTool(projects))
        register(GcClipRenderCacheTool(projects, engine))
        register(SetLockfileEntryPinnedTool(projects))
        register(SetClipAssetPinnedTool(projects))
        register(ProjectSnapshotActionTool(projects))
        register(ForkProjectTool(projects, this))
        register(DiffProjectsTool(projects))
        register(ExportProjectTool(projects))
        register(ImportProjectFromJsonTool(projects))
        register(SourceQueryTool(projects))
        register(DescribeSourceNodeTool(projects))
        register(DiffSourceNodesTool(projects))
        register(RemoveSourceNodeTool(projects))
        register(ImportSourceNodeTool(projects))
        register(ExportSourceNodeTool(projects))
        register(AddSourceNodeTool(projects))
        register(ForkSourceNodeTool(projects))
        register(SetSourceNodeParentsTool(projects))
        register(RenameSourceNodeTool(projects))
        register(UpdateSourceNodeBodyTool(projects))
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
        tools.register(io.talevia.core.tool.builtin.provider.ProviderQueryTool(providers))
        tools.register(io.talevia.core.tool.builtin.session.CompactSessionTool(providers, sessions, bus))
    }

    private val agents = mutableMapOf<String, Agent>()

    fun agentFor(providerId: String): Agent? {
        val provider: LlmProvider = providers.get(providerId) ?: return null
        return agents.getOrPut(providerId) {
            Agent(
                provider = provider,
                registry = tools,
                store = sessions,
                permissions = permissions,
                bus = bus,
                compactor = Compactor(provider, sessions, bus),
                fallbackProviders = providers.all().filter { it.id != provider.id },
            )
        }
    }
}
