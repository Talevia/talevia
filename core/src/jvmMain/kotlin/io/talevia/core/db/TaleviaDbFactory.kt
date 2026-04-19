package io.talevia.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * Opens a [TaleviaDb] backed by SQLite — either in-memory (tests, CI) or
 * persistent (desktop, server when configured).
 *
 * Path resolution:
 * 1. Explicit [path] argument wins.
 * 2. Else `TALEVIA_DB_PATH` env var — absolute path to the `.db` file, or
 *    `":memory:"` / `"memory"` to force in-memory.
 * 3. Else `<TALEVIA_MEDIA_DIR>/talevia.db` when that dir is set.
 * 4. Else null → in-memory (safe default for tests).
 *
 * Schema bootstrap uses `PRAGMA user_version` as the SqlDelight version
 * cookie: version 0 means empty DB → run `Schema.create`; lower version
 * than `Schema.version` → `Schema.migrate`; higher version → refuse to open.
 * WAL journal mode is enabled for file-backed DBs to tolerate occasional
 * concurrent readers (e.g. running the desktop app against a path the
 * server also touches).
 */
object TaleviaDbFactory {

    /** Shape of the resolved db target — captures both the driver and the path (for logs). */
    data class Opened(val driver: JdbcSqliteDriver, val db: TaleviaDb, val path: String)

    fun open(env: Map<String, String> = System.getenv(), path: String? = null): Opened {
        val resolved = path ?: resolvePathFromEnv(env)
        return when {
            resolved == null ||
                resolved.equals(":memory:", ignoreCase = true) ||
                resolved.equals("memory", ignoreCase = true) -> openInMemory()
            else -> openFile(resolved)
        }
    }

    private fun openInMemory(): Opened {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return Opened(driver, TaleviaDb(driver), ":memory:")
    }

    private fun openFile(path: String): Opened {
        File(path).absoluteFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        val current = readUserVersion(driver)
        val target = TaleviaDb.Schema.version
        when {
            current == 0L -> {
                TaleviaDb.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = $target", 0)
            }
            current < target -> {
                TaleviaDb.Schema.migrate(driver, current, target)
                driver.execute(null, "PRAGMA user_version = $target", 0)
            }
            current > target -> {
                driver.close()
                error(
                    "TaleviaDb at $path has schema version $current, newer than this build ($target). " +
                        "Refusing to open — upgrade the app or point TALEVIA_DB_PATH at a different file.",
                )
            }
        }
        return Opened(driver, TaleviaDb(driver), path)
    }

    private fun readUserVersion(driver: JdbcSqliteDriver): Long {
        return driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            parameters = 0,
            binders = null,
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
                )
            },
        ).value
    }

    private fun resolvePathFromEnv(env: Map<String, String>): String? {
        env["TALEVIA_DB_PATH"]?.takeIf { it.isNotBlank() }?.let { return it }
        env["TALEVIA_MEDIA_DIR"]
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it, "talevia.db").absolutePath }
        return null
    }
}
