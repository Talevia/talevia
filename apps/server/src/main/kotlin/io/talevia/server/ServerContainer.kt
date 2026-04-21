package io.talevia.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.SessionTitler
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.db.TaleviaDbFactory
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.metrics.EventBusMetricsSink
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.FileBlobWriter
import io.talevia.core.platform.FileMediaStorage
import io.talevia.core.platform.FileSystem
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.InMemorySecretStore
import io.talevia.core.platform.JvmFileSystem
import io.talevia.core.platform.JvmProcessRunner
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.ProcessRunner
import io.talevia.core.platform.SearchEngine
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.VideoEngine
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VisionEngine
import io.talevia.core.provider.LlmProvider
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
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.GenerateMusicTool
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
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
import io.talevia.core.tool.builtin.project.DescribeClipTool
import io.talevia.core.tool.builtin.project.DescribeLockfileEntryTool
import io.talevia.core.tool.builtin.project.DescribeProjectTool
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.ExportProjectTool
import io.talevia.core.tool.builtin.project.FindPinnedClipsTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.FindUnreferencedAssetsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GcLockfileTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ImportProjectFromJsonTool
import io.talevia.core.tool.builtin.project.ListClipsBoundToAssetTool
import io.talevia.core.tool.builtin.project.ListClipsForSourceTool
import io.talevia.core.tool.builtin.project.ListLockfileEntriesTool
import io.talevia.core.tool.builtin.project.ListProjectSnapshotsTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.ListTransitionsTool
import io.talevia.core.tool.builtin.project.PinClipAssetTool
import io.talevia.core.tool.builtin.project.PinLockfileEntryTool
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.PruneLockfileTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.project.RemoveAssetTool
import io.talevia.core.tool.builtin.project.RenameProjectTool
import io.talevia.core.tool.builtin.project.RestoreProjectSnapshotTool
import io.talevia.core.tool.builtin.project.SaveProjectSnapshotTool
import io.talevia.core.tool.builtin.project.SetOutputProfileTool
import io.talevia.core.tool.builtin.project.UnpinClipAssetTool
import io.talevia.core.tool.builtin.project.UnpinLockfileEntryTool
import io.talevia.core.tool.builtin.project.ValidateProjectTool
import io.talevia.core.tool.builtin.session.ArchiveSessionTool
import io.talevia.core.tool.builtin.session.DeleteSessionTool
import io.talevia.core.tool.builtin.session.DescribeMessageTool
import io.talevia.core.tool.builtin.session.DescribeSessionTool
import io.talevia.core.tool.builtin.session.EstimateSessionTokensTool
import io.talevia.core.tool.builtin.session.ForkSessionTool
import io.talevia.core.tool.builtin.session.ListMessagesTool
import io.talevia.core.tool.builtin.session.ListPartsTool
import io.talevia.core.tool.builtin.session.ListSessionAncestorsTool
import io.talevia.core.tool.builtin.session.ListSessionForksTool
import io.talevia.core.tool.builtin.session.ListSessionsTool
import io.talevia.core.tool.builtin.session.ListToolCallsTool
import io.talevia.core.tool.builtin.session.ReadPartTool
import io.talevia.core.tool.builtin.session.RenameSessionTool
import io.talevia.core.tool.builtin.session.RevertSessionTool
import io.talevia.core.tool.builtin.session.UnarchiveSessionTool
import io.talevia.core.tool.builtin.shell.BashTool
import io.talevia.core.tool.builtin.source.AddSourceNodeTool
import io.talevia.core.tool.builtin.source.DescribeSourceDagTool
import io.talevia.core.tool.builtin.source.DescribeSourceNodeTool
import io.talevia.core.tool.builtin.source.DiffSourceNodesTool
import io.talevia.core.tool.builtin.source.ExportSourceNodeTool
import io.talevia.core.tool.builtin.source.ForkSourceNodeTool
import io.talevia.core.tool.builtin.source.ImportSourceNodeFromJsonTool
import io.talevia.core.tool.builtin.source.ImportSourceNodeTool
import io.talevia.core.tool.builtin.source.ListSourceNodesTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.source.RenameSourceNodeTool
import io.talevia.core.tool.builtin.source.SearchSourceNodesTool
import io.talevia.core.tool.builtin.source.SetBrandPaletteTool
import io.talevia.core.tool.builtin.source.SetCharacterRefTool
import io.talevia.core.tool.builtin.source.SetSourceNodeParentsTool
import io.talevia.core.tool.builtin.source.SetStyleBibleTool
import io.talevia.core.tool.builtin.source.UpdateSourceNodeBodyTool
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.AddSubtitleTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.AddTrackTool
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.ApplyFilterToClipsTool
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
import io.talevia.core.tool.builtin.video.MoveClipToTrackTool
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
    private val opened = TaleviaDbFactory.open(env)
    val driver = opened.driver
    val db: TaleviaDb = opened.db

    /** Resolved DB location — `":memory:"` or an absolute filesystem path. Logged at startup. */
    val dbPath: String = opened.path
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

    /**
     * Sora-backed text-to-video engine. Same conditional pattern — present only
     * when `OPENAI_API_KEY` is set; deployments without it don't expose
     * `generate_video`.
     */
    val videoGen: VideoGenEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiSoraVideoGenEngine(httpClient, it) }

    /**
     * Vision-describe engine for the ML lane. Same conditional pattern; headless
     * deployments without an OpenAI key simply don't expose `describe_asset`.
     */
    val vision: VisionEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiVisionEngine(httpClient, it) }

    /**
     * Music-generation engine (VISION §2). Wired to Replicate-hosted MusicGen
     * when `REPLICATE_API_TOKEN` is set; otherwise null and `generate_music`
     * stays unregistered. `REPLICATE_MUSICGEN_MODEL` overrides the default
     * `meta/musicgen` model slug.
     */
    val musicGen: MusicGenEngine? = env["REPLICATE_API_TOKEN"]
        ?.takeIf { it.isNotBlank() }
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
    val upscale: UpscaleEngine? = env["REPLICATE_API_TOKEN"]
        ?.takeIf { it.isNotBlank() }
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
    val search: SearchEngine? = env["TAVILY_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { TavilySearchEngine(httpClient, it) }

    /** JVM blob writer backing AIGC tools. */
    val blobWriter: MediaBlobWriter = FileBlobWriter(mediaRootDir)

    val tools: ToolRegistry = ToolRegistry().apply {
        register(io.talevia.core.tool.builtin.meta.ListToolsTool(this))
        register(io.talevia.core.tool.builtin.meta.EstimateTokensTool())
        register(TodoWriteTool())
        register(ListSessionsTool(sessions))
        register(DescribeSessionTool(sessions))
        register(ListMessagesTool(sessions))
        register(EstimateSessionTokensTool(sessions))
        register(DescribeMessageTool(sessions))
        register(ForkSessionTool(sessions))
        register(RenameSessionTool(sessions))
        register(RevertSessionTool(sessions, projects, bus))
        register(ArchiveSessionTool(sessions))
        register(UnarchiveSessionTool(sessions))
        register(DeleteSessionTool(sessions))
        register(ListSessionForksTool(sessions))
        register(ListSessionAncestorsTool(sessions))
        register(ReadPartTool(sessions))
        register(ListToolCallsTool(sessions))
        register(ListPartsTool(sessions))
        register(ImportMediaTool(media, engine))
        register(ExtractFrameTool(engine, media, blobWriter))
        register(AddClipTool(projects, media))
        register(ReplaceClipTool(projects, media))
        register(SplitClipTool(projects))
        register(RemoveClipTool(projects))
        register(MoveClipTool(projects))
        register(MoveClipToTrackTool(projects))
        register(SetClipSourceBindingTool(projects))
        register(DuplicateClipTool(projects))
        register(TrimClipTool(projects, media))
        register(SetClipVolumeTool(projects))
        register(FadeAudioClipTool(projects))
        register(SetClipTransformTool(projects))
        register(ExportTool(projects, engine))
        register(ExportDryRunTool(projects))
        register(ApplyFilterTool(projects))
        register(ApplyFilterToClipsTool(projects))
        register(RemoveFilterTool(projects))
        register(ApplyLutTool(projects, media))
        register(AddSubtitleTool(projects))
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
        register(DescribeProjectTool(projects))
        register(DeleteProjectTool(projects))
        register(RenameProjectTool(projects))
        register(FindStaleClipsTool(projects))
        register(FindPinnedClipsTool(projects))
        register(FindUnreferencedAssetsTool(projects))
        register(ListClipsForSourceTool(projects))
        register(ListClipsBoundToAssetTool(projects))
        register(ProjectQueryTool(projects))
        register(DescribeClipTool(projects))
        register(ListTransitionsTool(projects))
        register(RemoveAssetTool(projects))
        register(SetOutputProfileTool(projects))
        register(ValidateProjectTool(projects))
        register(RegenerateStaleClipsTool(projects, this))
        register(ListLockfileEntriesTool(projects))
        register(DescribeLockfileEntryTool(projects))
        register(PruneLockfileTool(projects))
        register(GcLockfileTool(projects))
        register(PinLockfileEntryTool(projects))
        register(UnpinLockfileEntryTool(projects))
        register(PinClipAssetTool(projects))
        register(UnpinClipAssetTool(projects))
        register(SaveProjectSnapshotTool(projects))
        register(ListProjectSnapshotsTool(projects))
        register(RestoreProjectSnapshotTool(projects))
        register(DeleteProjectSnapshotTool(projects))
        register(ForkProjectTool(projects))
        register(DiffProjectsTool(projects))
        register(ExportProjectTool(projects))
        register(ImportProjectFromJsonTool(projects))
        register(SetCharacterRefTool(projects))
        register(SetStyleBibleTool(projects))
        register(SetBrandPaletteTool(projects))
        register(DescribeSourceDagTool(projects))
        register(DescribeSourceNodeTool(projects))
        register(DiffSourceNodesTool(projects))
        register(ListSourceNodesTool(projects))
        register(RemoveSourceNodeTool(projects))
        register(ImportSourceNodeTool(projects))
        register(ExportSourceNodeTool(projects))
        register(ImportSourceNodeFromJsonTool(projects))
        register(AddSourceNodeTool(projects))
        register(SearchSourceNodesTool(projects))
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
        imageGen?.let { register(GenerateImageTool(it, media, blobWriter, projects)) }
        videoGen?.let { register(GenerateVideoTool(it, media, blobWriter, projects)) }
        musicGen?.let { register(GenerateMusicTool(it, media, blobWriter, projects)) }
        upscale?.let { register(UpscaleAssetTool(it, media, media, blobWriter, projects)) }
        tts?.let { register(SynthesizeSpeechTool(it, media, blobWriter, projects)) }
        asr?.let {
            register(TranscribeAssetTool(it, media))
            register(AutoSubtitleClipTool(it, media, projects))
        }
        vision?.let { register(DescribeAssetTool(it, media)) }
    }

    /** Provider registry built from `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env vars. */
    val providers: ProviderRegistry =
        providerRegistryOverride ?: ProviderRegistry.Builder().addEnv(httpClient, env).build()

    init {
        tools.register(io.talevia.core.tool.builtin.provider.ListProvidersTool(providers))
        tools.register(io.talevia.core.tool.builtin.provider.ListProviderModelsTool(providers))
        tools.register(io.talevia.core.tool.builtin.session.CompactSessionTool(providers, sessions, bus))
    }

    /** Counter registry scraped by GET /metrics. See [EventBusMetricsSink]. */
    val metrics: MetricsRegistry = MetricsRegistry()
    val metricsSink: EventBusMetricsSink = EventBusMetricsSink(bus, metrics)

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
