package io.talevia.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.platform.ffmpeg.FfmpegVideoEngine

/**
 * Composition root for the desktop app. Holds the full singleton graph: SQLite,
 * stores, the FFmpeg-backed VideoEngine, and the ToolRegistry. UI consumes this
 * via a single instance constructed at App startup.
 */
class AppContainer {
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        TaleviaDb.Schema.create(it)
    }
    val db: TaleviaDb = TaleviaDb(driver)
    val bus: EventBus = EventBus()

    val sessions = SqlDelightSessionStore(db, bus)
    val projects: ProjectStore = SqlDelightProjectStore(db)
    val media: MediaStorage = InMemoryMediaStorage()
    val engine: VideoEngine = FfmpegVideoEngine()
    val permissions = DefaultPermissionService(bus)
    val permissionRules = DefaultPermissionRuleset.rules.toMutableList()

    val tools: ToolRegistry = ToolRegistry().apply {
        register(ImportMediaTool(media, engine))
        register(AddClipTool(projects, media))
        register(SplitClipTool(projects))
        register(ExportTool(projects, engine))
    }
}
