package io.talevia.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.agent.SessionTitler
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.db.TaleviaDbFactory
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.metrics.EventBusMetricsSink
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.platform.FileSystem
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.InMemorySecretStore
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
import io.talevia.core.provider.LlmProvider
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
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.TodoWriteTool
import io.talevia.core.tool.builtin.aigc.CompareAigcCandidatesTool
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.GenerateMusicTool
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
import io.talevia.core.tool.builtin.aigc.ReplayLockfileTool
import io.talevia.core.tool.builtin.aigc.SynthesizeSpeechTool
import io.talevia.core.tool.builtin.aigc.UpscaleAssetTool
import io.talevia.core.tool.builtin.fs.EditTool
import io.talevia.core.tool.builtin.fs.GlobTool
import io.talevia.core.tool.builtin.fs.GrepTool
import io.talevia.core.tool.builtin.fs.ListDirectoryTool
import io.talevia.core.tool.builtin.fs.MultiEditTool
import io.talevia.core.tool.builtin.fs.ReadFileTool
import io.talevia.core.tool.builtin.fs.WriteFileTool
import io.talevia.core.tool.builtin.ml.DescribeAssetTool
import io.talevia.core.tool.builtin.ml.TranscribeAssetTool
import io.talevia.core.tool.builtin.project.CreateProjectFromTemplateTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectSnapshotTool
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
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.PruneLockfileTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.project.RemoveAssetTool
import io.talevia.core.tool.builtin.project.RenameProjectTool
import io.talevia.core.tool.builtin.project.RestoreProjectSnapshotTool
import io.talevia.core.tool.builtin.project.SaveProjectSnapshotTool
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
import io.talevia.core.tool.builtin.shell.BashTool
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
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.ApplyFilterTool
import io.talevia.core.tool.builtin.video.ApplyLutTool
import io.talevia.core.tool.builtin.video.AutoSubtitleClipTool
import io.talevia.core.tool.builtin.video.ClearTimelineTool
import io.talevia.core.tool.builtin.video.DuplicateClipTool
import io.talevia.core.tool.builtin.video.DuplicateTrackTool
import io.talevia.core.tool.builtin.video.EditTextClipTool
import io.talevia.core.tool.builtin.video.ExportDryRunTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ExtractFrameTool
import io.talevia.core.tool.builtin.video.FadeAudioClipTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.MoveClipTool
import io.talevia.core.tool.builtin.video.RemoveClipTool
import io.talevia.core.tool.builtin.video.RemoveFilterTool
import io.talevia.core.tool.builtin.video.RemoveTrackTool
import io.talevia.core.tool.builtin.video.RemoveTransitionTool
import io.talevia.core.tool.builtin.video.ReorderTracksTool
import io.talevia.core.tool.builtin.video.ReplaceClipTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.SetClipSourceBindingTool
import io.talevia.core.tool.builtin.video.SetClipTransformTool
import io.talevia.core.tool.builtin.video.SetClipVolumeTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.core.tool.builtin.video.TrimClipTool
import io.talevia.core.tool.builtin.web.WebFetchTool
import io.talevia.core.tool.builtin.web.WebSearchTool
import io.talevia.platform.ffmpeg.FfmpegProxyGenerator
import io.talevia.platform.ffmpeg.FfmpegVideoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath
import java.io.File

/**
 * Composition root for the server, mirrors `apps/desktop/AppContainer.kt`.
 *
 * **Assumes single-tenant.** The whole graph — [sessions], [projects], [media]
 * catalog, [secrets] (API keys from the host env), [permissions] — is process-
 * global and shared across every HTTP request. Trigger conditions for
 * upgrading to a multi-tenant model are recorded in
 * `docs/decisions/2026-04-21-server-auth-multiuser-isolation-recorded.md`;
 * do NOT expose this server to untrusted remote users without first
 * reading that note and implementing the per-tenant isolation it
 * prescribes.
 *
 * Concretely, today's shape that's unsafe to publicly expose:
 *  - `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `REPLICATE_API_TOKEN` are
 *    process-wide — every session spends the same billed account.
 *  - `TALEVIA_PROJECTS_HOME` is a single directory; every session reads/writes
 *    bundles under the same root, and `TALEVIA_RECENTS_PATH` is one shared
 *    catalog file.
 *  - SQLite at `TALEVIA_DB_PATH` holds every user's sessions / messages /
 *    parts in the same tables without tenant column or row-level ACL.
 *
 * Auth: if the `TALEVIA_SERVER_TOKEN` env var is non-empty, every HTTP request
 * must carry `Authorization: Bearer <token>`; otherwise the server runs open
 * (intended for local development only). The token gives access to the whole
 * single-tenant graph — it's an on/off switch, not per-user auth.
 *
 * Permissions: [ServerPermissionService] rejects any tool that would otherwise
 * need to ASK the user — callers must grant the right permissions up-front via
 * a session's `permissionRules` or accept the container's default ruleset.
 */
class ServerContainer(
    env: Map<String, String> = System.getenv(),
    providerRegistryOverride: ProviderRegistry? = null,
) {
    private val opened = TaleviaDbFactory.open(env)
    val driver = opened.driver
    val db: TaleviaDb = opened.db

    /** Resolved DB location — `":memory:"` or an absolute filesystem path. Logged at startup. */
    val dbPath: String = opened.path
    val bus = EventBus(extraBufferCapacity = 1024)
    val agentStates = AgentRunStateTracker(
        bus,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val sessions: SessionStore = SqlDelightSessionStore(db, bus)

    /**
     * File-bundle [ProjectStore]. `TALEVIA_PROJECTS_HOME` is the default
     * directory for newly-created bundles; `TALEVIA_RECENTS_PATH` is the
     * per-machine catalog of which bundles this server has opened. Both are
     * filled in by [io.talevia.server.serverEnvWithDefaults] so the fields
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
    val providerAuth: ProviderAuth = EnvProviderAuth(env::get)

    val secrets: SecretStore = InMemorySecretStore(
        buildMap {
            providerAuth.apiKey("anthropic")?.let { put("anthropic", it) }
            providerAuth.apiKey("openai")?.let { put("openai", it) }
        },
    )

    val httpClient: HttpClient = HttpClient(CIO)

    /**
     * Image-generation engine, only wired when `OPENAI_API_KEY` is set. The
     * tool itself registers unconditionally via [imageGen]?.let so headless
     * deployments without an OpenAI key simply don't expose it.
     */
    val imageGen: ImageGenEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiImageGenEngine(httpClient, it) }

    /**
     * Whisper-backed [AsrEngine], wired alongside [imageGen] when the OpenAI key
     * is present. Same conditional pattern: no key → no ml-transcribe tool.
     */
    val asr: AsrEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiWhisperEngine(httpClient, it) }

    /**
     * TTS engine for the AIGC audio lane. Same conditional pattern as [imageGen]
     * and [asr] — present only when `OPENAI_API_KEY` is set, so headless
     * deployments without it simply don't expose `synthesize_speech`.
     */
    val tts: TtsEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiTtsEngine(httpClient, it) }

    /**
     * Sora-backed text-to-video engine. Same conditional pattern — present only
     * when `OPENAI_API_KEY` is set; deployments without it don't expose
     * `generate_video`.
     */
    val videoGen: VideoGenEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiSoraVideoGenEngine(httpClient, it) }

    /**
     * Vision-describe engine for the ML lane. Same conditional pattern; headless
     * deployments without an OpenAI key simply don't expose `describe_asset`.
     */
    val vision: VisionEngine? = providerAuth.apiKey("openai")
        ?.let { OpenAiVisionEngine(httpClient, it) }

    /**
     * Music-generation engine (VISION §2). Wired to Replicate-hosted MusicGen
     * when `REPLICATE_API_TOKEN` is set; otherwise null and `generate_music`
     * stays unregistered. `REPLICATE_MUSICGEN_MODEL` overrides the default
     * `meta/musicgen` model slug.
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
     * Super-resolution engine (VISION §2 "ML 加工: 超分"). Wired to
     * Replicate-hosted Real-ESRGAN when `REPLICATE_API_TOKEN` is set;
     * otherwise null and `upscale_asset` stays unregistered.
     * `REPLICATE_UPSCALE_MODEL` overrides the default slug.
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
     * Web-search engine for the `web_search` tool. Wired to Tavily when
     * `TAVILY_API_KEY` is set; otherwise null and the tool stays unregistered.
     */
    val search: SearchEngine? = providerAuth.apiKey("tavily")
        ?.let { TavilySearchEngine(httpClient, it) }

    val tools: ToolRegistry = ToolRegistry().apply {
        register(io.talevia.core.tool.builtin.meta.ListToolsTool(this))
        register(io.talevia.core.tool.builtin.meta.EstimateTokensTool())
        register(TodoWriteTool())
        register(io.talevia.core.tool.builtin.DraftPlanTool())
        register(io.talevia.core.tool.builtin.ExecutePlanTool(this, sessions))
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
        register(ImportMediaTool(engine, projects, proxyGenerator = FfmpegProxyGenerator()))
        register(ExtractFrameTool(engine, projects, bundleBlobWriter))
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
        register(ApplyFilterTool(projects))
        register(RemoveFilterTool(projects))
        register(ApplyLutTool(projects))
        register(AddSubtitlesTool(projects))
        register(EditTextClipTool(projects))
        register(AddTransitionTool(projects))
        register(RemoveTransitionTool(projects))
        register(AddTrackTool(projects))
        register(DuplicateTrackTool(projects))
        register(RemoveTrackTool(projects))
        register(ReorderTracksTool(projects))
        register(RevertTimelineTool(sessions, projects))
        register(ClearTimelineTool(projects))
        register(CreateProjectTool(projects))
        register(CreateProjectFromTemplateTool(projects))
        register(ListProjectsTool(projects))
        register(GetProjectStateTool(projects))
        register(DeleteProjectTool(projects))
        register(RenameProjectTool(projects))
        register(FindStaleClipsTool(projects))
        register(ProjectQueryTool(projects))
        register(RemoveAssetTool(projects))
        register(SetOutputProfileTool(projects))
        register(ValidateProjectTool(projects))
        register(RegenerateStaleClipsTool(projects, this))
        register(PruneLockfileTool(projects))
        register(GcLockfileTool(projects))
        register(GcClipRenderCacheTool(projects, engine))
        register(SetLockfileEntryPinnedTool(projects))
        register(SetClipAssetPinnedTool(projects))
        register(SaveProjectSnapshotTool(projects))
        register(RestoreProjectSnapshotTool(projects))
        register(DeleteProjectSnapshotTool(projects))
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
        register(ReadFileTool(fileSystem))
        register(WriteFileTool(fileSystem))
        register(EditTool(fileSystem))
        register(MultiEditTool(fileSystem))
        register(ListDirectoryTool(fileSystem))
        register(GlobTool(fileSystem))
        register(GrepTool(fileSystem))
        register(BashTool(processRunner))
        register(WebFetchTool(httpClient))
        search?.let { register(WebSearchTool(it)) }
        // All AIGC tools persist into the project bundle via bundleBlobWriter;
        // reference-asset resolution goes through BundleMediaPathResolver inside
        // each tool.
        imageGen?.let { register(GenerateImageTool(it, bundleBlobWriter, projects)) }
        videoGen?.let { register(GenerateVideoTool(it, bundleBlobWriter, projects)) }
        musicGen?.let { register(GenerateMusicTool(it, bundleBlobWriter, projects)) }
        upscale?.let { register(UpscaleAssetTool(it, bundleBlobWriter, projects)) }
        tts?.let { register(SynthesizeSpeechTool(it, bundleBlobWriter, projects)) }
        register(CompareAigcCandidatesTool(this))
        register(ReplayLockfileTool(this, projects))
        asr?.let {
            register(TranscribeAssetTool(it, projects))
            register(AutoSubtitleClipTool(it, projects))
        }
        vision?.let { register(DescribeAssetTool(it, projects)) }
    }

    /** Provider registry built from `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env vars. */
    val providers: ProviderRegistry =
        providerRegistryOverride ?: ProviderRegistry.Builder().addEnv(httpClient, env).build()

    init {
        tools.register(io.talevia.core.tool.builtin.provider.ProviderQueryTool(providers))
        tools.register(io.talevia.core.tool.builtin.session.CompactSessionTool(providers, sessions, bus))
    }

    /** Counter registry scraped by GET /metrics. See [EventBusMetricsSink]. */
    val metrics: MetricsRegistry = MetricsRegistry()
    val metricsSink: EventBusMetricsSink = EventBusMetricsSink(bus, metrics)

    init {
        // Re-register list_tools with the live MetricsRegistry so the tool
        // can surface per-tool avg-cents hints. Same id → replaces the
        // metrics-less instance registered up in the `tools.apply` block.
        tools.register(io.talevia.core.tool.builtin.meta.ListToolsTool(tools, metrics))
    }

    private val agentsByProvider = mutableMapOf<String, Agent>()

    /**
     * Server-side AGENTS.md / CLAUDE.md discovery — walks from `user.dir` up
     * so a deployment that `cd`s into a specific project folder (e.g. a
     * systemd unit's `WorkingDirectory=`) inherits that project's rules
     * alongside the headless-runtime note above. Cached at container init.
     */
    private val projectInstructionsSuffix: String by lazy {
        val cwd = System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: return@lazy ""
        val found = io.talevia.core.agent.InstructionDiscovery.discover(startDir = cwd)
        io.talevia.core.agent.formatProjectInstructionsSuffix(found)
    }

    private fun buildAgent(provider: LlmProvider): Agent =
        Agent(
            provider = provider,
            registry = tools,
            store = sessions,
            permissions = permissions,
            bus = bus,
            metrics = metrics,
            systemPrompt = io.talevia.core.agent.taleviaSystemPrompt(
                extraSuffix = listOf(
                    // Server runs headless: permission prompts default to deny, so the model
                    // should not plan around interactive approval loops for ASK permissions.
                    "Runtime: headless server. ASK-scoped permissions resolve to deny; " +
                        "if a tool needs an ASK permission, surface that to the caller rather than retrying.",
                    projectInstructionsSuffix,
                ).filter { it.isNotBlank() }.joinToString("\n\n"),
            ),
            compactor = Compactor(
                provider = provider,
                store = sessions,
                bus = bus,
            ),
            titler = SessionTitler(provider = provider, store = sessions),
            fallbackProviders = providers.all().filter { it.id != provider.id },
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
