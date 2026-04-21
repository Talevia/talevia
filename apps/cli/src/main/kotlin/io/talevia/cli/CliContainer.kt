package io.talevia.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.agent.Agent
import io.talevia.core.agent.SessionTitler
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.db.TaleviaDbFactory
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.FileBlobWriter
import io.talevia.core.platform.FileMediaStorage
import io.talevia.core.platform.FileSystem
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.InMemoryMediaStorage
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
import io.talevia.core.tool.builtin.project.FindPinnedClipsTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.FindUnreferencedAssetsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GcLockfileTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ListAssetsTool
import io.talevia.core.tool.builtin.project.ListClipsBoundToAssetTool
import io.talevia.core.tool.builtin.project.ListClipsForSourceTool
import io.talevia.core.tool.builtin.project.ListLockfileEntriesTool
import io.talevia.core.tool.builtin.project.ListProjectSnapshotsTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.ListTimelineClipsTool
import io.talevia.core.tool.builtin.project.ListTracksTool
import io.talevia.core.tool.builtin.project.ListTransitionsTool
import io.talevia.core.tool.builtin.project.PinClipAssetTool
import io.talevia.core.tool.builtin.project.PinLockfileEntryTool
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
import io.talevia.core.tool.builtin.session.DescribeMessageTool
import io.talevia.core.tool.builtin.session.DescribeSessionTool
import io.talevia.core.tool.builtin.session.ForkSessionTool
import io.talevia.core.tool.builtin.session.ListMessagesTool
import io.talevia.core.tool.builtin.session.ListSessionsTool
import io.talevia.core.tool.builtin.session.RenameSessionTool
import io.talevia.core.tool.builtin.session.RevertSessionTool
import io.talevia.core.tool.builtin.session.UnarchiveSessionTool
import io.talevia.core.tool.builtin.shell.BashTool
import io.talevia.core.tool.builtin.source.AddSourceNodeTool
import io.talevia.core.tool.builtin.source.DefineBrandPaletteTool
import io.talevia.core.tool.builtin.source.DefineCharacterRefTool
import io.talevia.core.tool.builtin.source.DefineStyleBibleTool
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
import io.talevia.core.tool.builtin.source.SetSourceNodeParentsTool
import io.talevia.core.tool.builtin.source.UpdateBrandPaletteTool
import io.talevia.core.tool.builtin.source.UpdateCharacterRefTool
import io.talevia.core.tool.builtin.source.UpdateSourceNodeBodyTool
import io.talevia.core.tool.builtin.source.UpdateStyleBibleTool
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
import kotlinx.coroutines.runBlocking
import java.io.File

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
 */
class CliContainer(env: Map<String, String> = System.getenv()) {
    private val opened = TaleviaDbFactory.open(env)
    val driver = opened.driver
    val db: TaleviaDb = opened.db

    /** Resolved DB location — `":memory:"` or an absolute filesystem path. Useful for logs. */
    val dbPath: String = opened.path
    val bus: EventBus = EventBus()

    val sessions = SqlDelightSessionStore(db, bus)
    val projects: ProjectStore = SqlDelightProjectStore(db)

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
    val permissions = DefaultPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules.toMutableList()
    val secrets: SecretStore = FileSecretStore()

    val httpClient: HttpClient = HttpClient(CIO)

    val imageGen: ImageGenEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiImageGenEngine(httpClient, it) }

    val asr: AsrEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiWhisperEngine(httpClient, it) }

    val tts: TtsEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiTtsEngine(httpClient, it) }

    val videoGen: VideoGenEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiSoraVideoGenEngine(httpClient, it) }

    val vision: VisionEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiVisionEngine(httpClient, it) }

    val musicGen: MusicGenEngine? = env["REPLICATE_API_TOKEN"]
        ?.takeIf { it.isNotBlank() }
        ?.let { token ->
            ReplicateMusicGenEngine(
                httpClient = httpClient,
                apiKey = token,
                modelSlug = env["REPLICATE_MUSICGEN_MODEL"]?.takeIf { it.isNotBlank() } ?: "meta/musicgen",
            )
        }

    val upscale: UpscaleEngine? = env["REPLICATE_API_TOKEN"]
        ?.takeIf { it.isNotBlank() }
        ?.let { token ->
            ReplicateUpscaleEngine(
                httpClient = httpClient,
                apiKey = token,
                modelSlug = env["REPLICATE_UPSCALE_MODEL"]?.takeIf { it.isNotBlank() } ?: "nightmareai/real-esrgan",
            )
        }

    val search: SearchEngine? = env["TAVILY_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { TavilySearchEngine(httpClient, it) }

    val blobWriter: MediaBlobWriter = FileBlobWriter(mediaRootDir)

    val tools: ToolRegistry = ToolRegistry().apply {
        register(TodoWriteTool())
        register(ListSessionsTool(sessions))
        register(DescribeSessionTool(sessions))
        register(ListMessagesTool(sessions))
        register(DescribeMessageTool(sessions))
        register(ForkSessionTool(sessions))
        register(RenameSessionTool(sessions))
        register(RevertSessionTool(sessions, projects, bus))
        register(ArchiveSessionTool(sessions))
        register(UnarchiveSessionTool(sessions))
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
        register(ListTimelineClipsTool(projects))
        register(DescribeClipTool(projects))
        register(ListTracksTool(projects))
        register(ListTransitionsTool(projects))
        register(ListAssetsTool(projects))
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
        register(DefineCharacterRefTool(projects))
        register(UpdateCharacterRefTool(projects))
        register(DefineStyleBibleTool(projects))
        register(UpdateStyleBibleTool(projects))
        register(DefineBrandPaletteTool(projects))
        register(UpdateBrandPaletteTool(projects))
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

    val providers: ProviderRegistry = runBlocking {
        ProviderRegistry.Builder()
            .addSecretStore(httpClient, secrets)
            .addEnv(httpClient, env)
            .build()
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
            ),
            titler = SessionTitler(provider = provider, store = sessions),
        )
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
