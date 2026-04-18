package io.talevia.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.SplitClipTool
import io.talevia.platform.ffmpeg.FfmpegVideoEngine

/**
 * Composition root for the server, mirrors `apps/desktop/AppContainer.kt`. Single
 * shared graph because v0 is single-tenant; multi-tenant isolation is a later concern.
 *
 * Permission strategy on the server side defaults to AllowAll because there's no
 * UI to prompt — the API surface should make permission decisions explicit per
 * request once we add auth, but for v0 this matches the stated "极简 headless" scope.
 */
class ServerContainer {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
    val db = TaleviaDb(driver)
    val bus = EventBus(extraBufferCapacity = 1024)
    val sessions: SessionStore = SqlDelightSessionStore(db, bus)
    val projects: ProjectStore = SqlDelightProjectStore(db)
    val media: MediaStorage = InMemoryMediaStorage()
    val engine: VideoEngine = FfmpegVideoEngine()
    val permissions = AllowAllPermissionService()
    val permissionRules = DefaultPermissionRuleset.rules

    val tools: ToolRegistry = ToolRegistry().apply {
        register(ImportMediaTool(media, engine))
        register(AddClipTool(projects, media))
        register(SplitClipTool(projects))
        register(ExportTool(projects, engine))
    }
}
