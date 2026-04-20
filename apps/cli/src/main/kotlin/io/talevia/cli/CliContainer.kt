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
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.MusicGenEngine
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
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.TodoWriteTool
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.GenerateMusicTool
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
import io.talevia.core.tool.builtin.aigc.SynthesizeSpeechTool
import io.talevia.core.tool.builtin.aigc.UpscaleAssetTool
import io.talevia.core.tool.builtin.fs.GlobTool
import io.talevia.core.tool.builtin.fs.ListDirectoryTool
import io.talevia.core.tool.builtin.fs.ReadFileTool
import io.talevia.core.tool.builtin.fs.WriteFileTool
import io.talevia.core.tool.builtin.ml.DescribeAssetTool
import io.talevia.core.tool.builtin.ml.TranscribeAssetTool
import io.talevia.core.tool.builtin.project.CreateProjectFromTemplateTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectTool
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ListClipsForSourceTool
import io.talevia.core.tool.builtin.project.ListLockfileEntriesTool
import io.talevia.core.tool.builtin.project.ListProjectSnapshotsTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.RegenerateStaleClipsTool
import io.talevia.core.tool.builtin.project.RestoreProjectSnapshotTool
import io.talevia.core.tool.builtin.project.SaveProjectSnapshotTool
import io.talevia.core.tool.builtin.source.DefineBrandPaletteTool
import io.talevia.core.tool.builtin.source.DefineCharacterRefTool
import io.talevia.core.tool.builtin.source.DefineStyleBibleTool
import io.talevia.core.tool.builtin.source.ImportSourceNodeTool
import io.talevia.core.tool.builtin.source.ListSourceNodesTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.source.UpdateBrandPaletteTool
import io.talevia.core.tool.builtin.source.UpdateCharacterRefTool
import io.talevia.core.tool.builtin.source.UpdateStyleBibleTool
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.AddSubtitleTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.ApplyFilterToClipsTool
import io.talevia.core.tool.builtin.video.ApplyFilterTool
import io.talevia.core.tool.builtin.video.ApplyLutTool
import io.talevia.core.tool.builtin.video.AutoSubtitleClipTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ExtractFrameTool
import io.talevia.core.tool.builtin.video.FadeAudioClipTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.MoveClipTool
import io.talevia.core.tool.builtin.video.RemoveClipTool
import io.talevia.core.tool.builtin.video.ReplaceClipTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.SetClipTransformTool
import io.talevia.core.tool.builtin.video.SetClipVolumeTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.core.tool.builtin.video.TrimClipTool
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

    val blobWriter: MediaBlobWriter = FileBlobWriter(mediaRootDir)

    val tools: ToolRegistry = ToolRegistry().apply {
        register(TodoWriteTool())
        register(ImportMediaTool(media, engine))
        register(ExtractFrameTool(engine, media, blobWriter))
        register(AddClipTool(projects, media))
        register(ReplaceClipTool(projects, media))
        register(SplitClipTool(projects))
        register(RemoveClipTool(projects))
        register(MoveClipTool(projects))
        register(TrimClipTool(projects, media))
        register(SetClipVolumeTool(projects))
        register(FadeAudioClipTool(projects))
        register(SetClipTransformTool(projects))
        register(ExportTool(projects, engine))
        register(ApplyFilterTool(projects))
        register(ApplyFilterToClipsTool(projects))
        register(ApplyLutTool(projects, media))
        register(AddSubtitleTool(projects))
        register(AddSubtitlesTool(projects))
        register(AddTransitionTool(projects))
        register(RevertTimelineTool(sessions, projects))
        register(CreateProjectTool(projects))
        register(CreateProjectFromTemplateTool(projects))
        register(ListProjectsTool(projects))
        register(GetProjectStateTool(projects))
        register(DeleteProjectTool(projects))
        register(FindStaleClipsTool(projects))
        register(ListClipsForSourceTool(projects))
        register(RegenerateStaleClipsTool(projects, this))
        register(ListLockfileEntriesTool(projects))
        register(SaveProjectSnapshotTool(projects))
        register(ListProjectSnapshotsTool(projects))
        register(RestoreProjectSnapshotTool(projects))
        register(ForkProjectTool(projects))
        register(DiffProjectsTool(projects))
        register(DefineCharacterRefTool(projects))
        register(UpdateCharacterRefTool(projects))
        register(DefineStyleBibleTool(projects))
        register(UpdateStyleBibleTool(projects))
        register(DefineBrandPaletteTool(projects))
        register(UpdateBrandPaletteTool(projects))
        register(ListSourceNodesTool(projects))
        register(RemoveSourceNodeTool(projects))
        register(ImportSourceNodeTool(projects))
        register(ReadFileTool(fileSystem))
        register(WriteFileTool(fileSystem))
        register(ListDirectoryTool(fileSystem))
        register(GlobTool(fileSystem))
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
            systemPrompt = io.talevia.core.agent.taleviaSystemPrompt(extraSuffix = cliRuntimeContext()),
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

    fun close() {
        runCatching { httpClient.close() }
        runCatching { driver.close() }
    }
}
