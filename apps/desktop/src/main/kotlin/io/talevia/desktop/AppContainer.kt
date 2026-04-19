package io.talevia.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.agent.Agent
import io.talevia.core.agent.SessionTitler
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.FileBlobWriter
import io.talevia.core.platform.FileMediaStorage
import io.talevia.core.platform.FilePicker
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.VideoEngine
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VisionEngine
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.openai.OpenAiImageGenEngine
import io.talevia.core.provider.openai.OpenAiSoraVideoGenEngine
import io.talevia.core.provider.openai.OpenAiTtsEngine
import io.talevia.core.provider.openai.OpenAiVisionEngine
import io.talevia.core.provider.openai.OpenAiWhisperEngine
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
import io.talevia.core.tool.builtin.aigc.SynthesizeSpeechTool
import io.talevia.core.tool.builtin.ml.DescribeAssetTool
import io.talevia.core.tool.builtin.ml.TranscribeAssetTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectTool
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ListLockfileEntriesTool
import io.talevia.core.tool.builtin.project.ListProjectSnapshotsTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.RestoreProjectSnapshotTool
import io.talevia.core.tool.builtin.project.SaveProjectSnapshotTool
import io.talevia.core.tool.builtin.source.DefineBrandPaletteTool
import io.talevia.core.tool.builtin.source.DefineCharacterRefTool
import io.talevia.core.tool.builtin.source.DefineStyleBibleTool
import io.talevia.core.tool.builtin.source.ImportSourceNodeTool
import io.talevia.core.tool.builtin.source.ListSourceNodesTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.AddSubtitleTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.ApplyFilterTool
import io.talevia.core.tool.builtin.video.ApplyLutTool
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
import java.io.File

/**
 * Composition root for the desktop app. Holds the full singleton graph: SQLite,
 * stores, the FFmpeg-backed VideoEngine, the ToolRegistry, and (when at least
 * one provider API key is set in the environment) a ProviderRegistry and Agent
 * factory so the chat panel can drive tool dispatch through the real LLM loop.
 * UI consumes this via a single instance constructed at App startup.
 */
class AppContainer(env: Map<String, String> = System.getenv()) {
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        TaleviaDb.Schema.create(it)
    }
    val db: TaleviaDb = TaleviaDb(driver)
    val bus: EventBus = EventBus()

    val sessions = SqlDelightSessionStore(db, bus)
    val projects: ProjectStore = SqlDelightProjectStore(db)
    /**
     * Media root dir for both the asset catalog and AIGC blob output. When
     * `TALEVIA_MEDIA_DIR` is set we persist the asset catalog under it (so
     * AssetIds referenced by saved Projects survive app restarts); otherwise
     * we fall back to `<java.io.tmpdir>/talevia-media` so AIGC still has
     * somewhere to write and the catalog stays in-memory (M2 behaviour).
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
    val permissions = DefaultPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules.toMutableList()
    val filePicker: FilePicker = AwtFilePicker()
    val secrets: SecretStore = FileSecretStore()

    val httpClient: HttpClient = HttpClient(CIO)

    /**
     * Image-generation engine, only wired when `OPENAI_API_KEY` is set. No
     * registry yet — one provider doesn't warrant one, and a premature
     * `GenerativeProviderRegistry` would just be scaffolding.
     */
    val imageGen: ImageGenEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiImageGenEngine(httpClient, it) }

    /** Whisper-backed ASR engine, wired alongside [imageGen] when OpenAI key is set. */
    val asr: AsrEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiWhisperEngine(httpClient, it) }

    /** TTS engine for the AIGC audio lane, gated on the same `OPENAI_API_KEY`. */
    val tts: TtsEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiTtsEngine(httpClient, it) }

    /** Sora-backed text-to-video engine, gated on the same `OPENAI_API_KEY`. */
    val videoGen: VideoGenEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiSoraVideoGenEngine(httpClient, it) }

    /** Vision-describe engine for the ML lane, gated on the same `OPENAI_API_KEY`. */
    val vision: VisionEngine? = env["OPENAI_API_KEY"]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiVisionEngine(httpClient, it) }

    /** JVM blob writer backing AIGC tools. Paired with [mediaRootDir]. */
    val blobWriter: MediaBlobWriter = FileBlobWriter(mediaRootDir)

    val tools: ToolRegistry = ToolRegistry().apply {
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
        register(ApplyLutTool(projects, media))
        register(AddSubtitleTool(projects))
        register(AddSubtitlesTool(projects))
        register(AddTransitionTool(projects))
        register(RevertTimelineTool(sessions, projects))
        register(CreateProjectTool(projects))
        register(ListProjectsTool(projects))
        register(GetProjectStateTool(projects))
        register(DeleteProjectTool(projects))
        register(FindStaleClipsTool(projects))
        register(ListLockfileEntriesTool(projects))
        register(SaveProjectSnapshotTool(projects))
        register(ListProjectSnapshotsTool(projects))
        register(RestoreProjectSnapshotTool(projects))
        register(ForkProjectTool(projects))
        register(DiffProjectsTool(projects))
        register(DefineCharacterRefTool(projects))
        register(DefineStyleBibleTool(projects))
        register(DefineBrandPaletteTool(projects))
        register(ListSourceNodesTool(projects))
        register(RemoveSourceNodeTool(projects))
        register(ImportSourceNodeTool(projects))
        imageGen?.let { register(GenerateImageTool(it, media, blobWriter, projects)) }
        videoGen?.let { register(GenerateVideoTool(it, media, blobWriter, projects)) }
        tts?.let { register(SynthesizeSpeechTool(it, media, blobWriter, projects)) }
        asr?.let { register(TranscribeAssetTool(it, media)) }
        vision?.let { register(DescribeAssetTool(it, media)) }
    }

    /** Provider registry built from `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env. */
    val providers: ProviderRegistry =
        ProviderRegistry.Builder().addEnv(httpClient, env).build()

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
            systemPrompt = io.talevia.core.agent.taleviaSystemPrompt(),
            compactor = Compactor(
                provider = provider,
                store = sessions,
                bus = bus,
            ),
            titler = SessionTitler(provider = provider, store = sessions),
        )
    }

    fun close() {
        runCatching { httpClient.close() }
        runCatching { driver.close() }
    }
}
