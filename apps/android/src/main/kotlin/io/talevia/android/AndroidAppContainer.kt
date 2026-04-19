package io.talevia.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.InMemorySecretStore
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.SecretStore
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.source.DefineBrandPaletteTool
import io.talevia.core.tool.builtin.source.DefineCharacterRefTool
import io.talevia.core.tool.builtin.source.DefineStyleBibleTool
import io.talevia.core.tool.builtin.source.ListSourceNodesTool
import io.talevia.core.tool.builtin.source.RemoveSourceNodeTool
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import io.talevia.core.tool.builtin.video.SplitClipTool

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
    val media: MediaStorage = InMemoryMediaStorage()
    val engine: VideoEngine = Media3VideoEngine(context, media)
    val permissions = DefaultPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules.toMutableList()
    /**
     * In-memory [SecretStore] stub. Android should eventually back this with
     * EncryptedSharedPreferences or Keystore; in-memory is placeholder so the
     * composition root can satisfy downstream dependencies today.
     */
    val secrets: SecretStore = InMemorySecretStore()
    val tools: ToolRegistry = ToolRegistry().apply {
        register(ImportMediaTool(media, engine))
        register(AddClipTool(projects, media))
        register(SplitClipTool(projects))
        register(ExportTool(projects, engine))
        register(RevertTimelineTool(sessions, projects))
        register(DefineCharacterRefTool(projects))
        register(DefineStyleBibleTool(projects))
        register(DefineBrandPaletteTool(projects))
        register(ListSourceNodesTool(projects))
        register(RemoveSourceNodeTool(projects))
    }
}
