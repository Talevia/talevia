package io.talevia.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.talevia.core.db.TaleviaDb

/**
 * Helpers exposed to Swift via SKIE so the iOS app composition root can stand up
 * the SQLDelight database without touching Kotlin/Native APIs directly.
 *
 * For M3 we only ship in-memory mode; persistent on-device storage (with proper
 * file-based driver and Documents-directory paths) lands when the iOS UX needs it.
 */
class TaleviaDatabaseFactory {
    fun createInMemoryDriver(): app.cash.sqldelight.db.SqlDriver {
        val driver = NativeSqliteDriver(TaleviaDb.Schema, name = "talevia.db", onConfiguration = { it })
        return driver
    }
}
