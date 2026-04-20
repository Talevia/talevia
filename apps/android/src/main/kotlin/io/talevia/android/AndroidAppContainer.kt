package io.talevia.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.agent.Agent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.InMemorySecretStore
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.VideoEngine
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.TodoWriteTool
import io.talevia.core.tool.builtin.project.CreateProjectTool
import io.talevia.core.tool.builtin.project.DeleteProjectTool
import io.talevia.core.tool.builtin.project.DiffProjectsTool
import io.talevia.core.tool.builtin.project.FindStaleClipsTool
import io.talevia.core.tool.builtin.project.ForkProjectTool
import io.talevia.core.tool.builtin.project.GetProjectStateTool
import io.talevia.core.tool.builtin.project.ListLockfileEntriesTool
import io.talevia.core.tool.builtin.project.ListProjectSnapshotsTool
import io.talevia.core.tool.builtin.project.ListProjectsTool
import io.talevia.core.tool.builtin.project.ListTimelineClipsTool
import io.talevia.core.tool.builtin.project.RestoreProjectSnapshotTool
import io.talevia.core.tool.builtin.project.SaveProjectSnapshotTool
import io.talevia.core.tool.builtin.project.ValidateProjectTool
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
import io.talevia.core.tool.builtin.video.ApplyFilterTool
import io.talevia.core.tool.builtin.video.ApplyLutTool
import io.talevia.core.tool.builtin.video.DuplicateClipTool
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

/**
 * Composition root for the Android app. Mirrors `apps/desktop/AppContainer.kt` and
 * `apps/ios/AppContainer.swift`. Persistent storage uses SQLDelight's Android
 * driver with the app's data directory.
 */
class AndroidAppContainer(context: Context) {
    val driver = AndroidSqliteDriver(TaleviaDb.Schema, context, "talevia.db")
    val db = TaleviaDb(driver)
    val bus = EventBus()
    val sessions: SessionStore = SqlDelightSessionStore(db, bus)
    val projects: ProjectStore = SqlDelightProjectStore(db)
    val media: MediaStorage = AndroidPersistentMediaStorage(
        java.io.File(context.filesDir, "talevia-media"),
    )
    val engine: VideoEngine = Media3VideoEngine(context, media)
    /**
     * Cache-tier blob writer. Generated frames live under the app cache dir;
     * Project state holds the canonical asset reference, so OS eviction is
     * recoverable by re-running the source tool.
     */
    val blobWriter: MediaBlobWriter = AndroidFileBlobWriter(
        java.io.File(context.cacheDir, "talevia-generated"),
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
        register(TodoWriteTool())
        register(ImportMediaTool(media, engine))
        register(ExtractFrameTool(engine, media, blobWriter))
        register(AddClipTool(projects, media))
        register(ReplaceClipTool(projects, media))
        register(SplitClipTool(projects))
        register(RemoveClipTool(projects))
        register(MoveClipTool(projects))
        register(DuplicateClipTool(projects))
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
        register(ListTimelineClipsTool(projects))
        register(ValidateProjectTool(projects))
        register(DeleteProjectTool(projects))
        register(FindStaleClipsTool(projects))
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
    }

    /**
     * LLM provider registry. Reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` /
     * `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) from system properties (set via
     * adb shell `setprop` or BuildConfig injection). Callers should check
     * [providers.default] before calling [agentFor].
     */
    val providers: ProviderRegistry = ProviderRegistry.Builder()
        .addEnv(httpClient, buildMap {
            for (key in listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")) {
                System.getProperty(key)?.takeIf(String::isNotEmpty)?.let { put(key, it) }
                System.getenv(key)?.takeIf(String::isNotEmpty)?.let { put(key, it) }
            }
        })
        .build()

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
            )
        }
    }
}
