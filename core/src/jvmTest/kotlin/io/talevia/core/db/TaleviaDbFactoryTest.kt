package io.talevia.core.db

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaleviaDbFactoryTest {

    @Test
    fun `empty env opens an in-memory database`() = runTest {
        val opened = TaleviaDbFactory.open(env = emptyMap())
        try {
            assertEquals(":memory:", opened.path)
            // Any query works → schema got created.
            opened.db.sessionsQueries.selectAll().executeAsList()
        } finally {
            opened.driver.close()
        }
    }

    @Test
    fun `memory literal opens in-memory`() = runTest {
        val a = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to ":memory:"))
        val b = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to "memory"))
        assertEquals(":memory:", a.path)
        assertEquals(":memory:", b.path)
        a.driver.close()
        b.driver.close()
    }

    @Test
    fun `file-backed db persists schema across reopen`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("taleviadb-test-").toFile()
        val dbPath = java.io.File(tmp, "nested/talevia.db").absolutePath
        try {
            val first = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to dbPath))
            first.driver.close()
            assertTrue(java.io.File(dbPath).exists(), "db file should be on disk after close")

            val second = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to dbPath))
            try {
                assertEquals(dbPath, second.path)
                // Schema still there — query doesn't throw.
                second.db.sessionsQueries.selectAll().executeAsList()
                // user_version sticks across reopen.
                val version = readUserVersion(second.driver)
                assertEquals(TaleviaDb.Schema.version, version)
            } finally {
                second.driver.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `media dir default used when db path not set`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("taleviadb-mediadir-").toFile()
        try {
            val opened = TaleviaDbFactory.open(env = mapOf("TALEVIA_MEDIA_DIR" to tmp.absolutePath))
            try {
                assertEquals(java.io.File(tmp, "talevia.db").absolutePath, opened.path)
            } finally {
                opened.driver.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `explicit path argument wins over env`() = runTest {
        // Cycle 81 follow-up: kdoc states "Explicit [path] argument wins"
        // is precedence rule #1. Passing a different path explicitly must
        // override TALEVIA_DB_PATH.
        val opened = TaleviaDbFactory.open(
            env = mapOf("TALEVIA_DB_PATH" to "/tmp/should-not-win.db"),
            path = ":memory:",
        )
        try {
            assertEquals(":memory:", opened.path, "explicit path argument must override env")
        } finally {
            opened.driver.close()
        }
    }

    @Test
    fun `TALEVIA_DB_PATH wins over TALEVIA_MEDIA_DIR`() = runTest {
        // Kdoc precedence: TALEVIA_DB_PATH (#2) wins over
        // TALEVIA_MEDIA_DIR (#3). Pin so a future env-resolution refactor
        // doesn't accidentally swap them.
        val tmp = kotlin.io.path.createTempDirectory("taleviadb-precedence-").toFile()
        try {
            val opened = TaleviaDbFactory.open(
                env = mapOf(
                    "TALEVIA_DB_PATH" to ":memory:",
                    "TALEVIA_MEDIA_DIR" to tmp.absolutePath,
                ),
            )
            try {
                assertEquals(":memory:", opened.path, "TALEVIA_DB_PATH must win over TALEVIA_MEDIA_DIR")
            } finally {
                opened.driver.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `blank TALEVIA_DB_PATH falls through to media dir`() = runTest {
        // The `takeIf { it.isNotBlank() }` chain in resolvePathFromEnv
        // means an empty / whitespace TALEVIA_DB_PATH is treated as
        // unset, falling through to TALEVIA_MEDIA_DIR. Pin so a deploy
        // setting TALEVIA_DB_PATH="" doesn't accidentally pin to
        // in-memory and lose persistence.
        val tmp = kotlin.io.path.createTempDirectory("taleviadb-blank-").toFile()
        try {
            val opened = TaleviaDbFactory.open(
                env = mapOf(
                    "TALEVIA_DB_PATH" to "",
                    "TALEVIA_MEDIA_DIR" to tmp.absolutePath,
                ),
            )
            try {
                assertEquals(java.io.File(tmp, "talevia.db").absolutePath, opened.path)
            } finally {
                opened.driver.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `whitespace-only TALEVIA_DB_PATH also falls through`() = runTest {
        // `isNotBlank()` rejects whitespace-only too. Pin separately
        // from the empty-string case to anchor `String.isBlank()`
        // semantics — a future refactor switching to `isNotEmpty()`
        // would silently regress.
        val tmp = kotlin.io.path.createTempDirectory("taleviadb-whitespace-").toFile()
        try {
            val opened = TaleviaDbFactory.open(
                env = mapOf(
                    "TALEVIA_DB_PATH" to "   ",
                    "TALEVIA_MEDIA_DIR" to tmp.absolutePath,
                ),
            )
            try {
                assertEquals(java.io.File(tmp, "talevia.db").absolutePath, opened.path)
            } finally {
                opened.driver.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `blank TALEVIA_MEDIA_DIR with no DB_PATH falls through to in-memory`() = runTest {
        // Both blank → null → in-memory default. The "safe default for
        // tests" branch from the kdoc.
        val opened = TaleviaDbFactory.open(
            env = mapOf(
                "TALEVIA_DB_PATH" to "",
                "TALEVIA_MEDIA_DIR" to "  ",
            ),
        )
        try {
            assertEquals(":memory:", opened.path, "all-blank env defaults to in-memory")
        } finally {
            opened.driver.close()
        }
    }

    @Test
    fun `case-insensitive memory literal accepts MIXED-case`() = runTest {
        // Pin the kdoc's "case-insensitive" promise. A regression
        // making it case-sensitive would mismatch CI configs that
        // pass `MEMORY` or `Memory`.
        val a = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to "MEMORY"))
        val b = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to ":Memory:"))
        try {
            assertEquals(":memory:", a.path)
            assertEquals(":memory:", b.path)
        } finally {
            a.driver.close()
            b.driver.close()
        }
    }

    @Test
    fun `schema-version downgrade refuses to open`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("taleviadb-downgrade-").toFile()
        val dbPath = java.io.File(tmp, "talevia.db").absolutePath
        try {
            val first = TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to dbPath))
            // Pretend a future version wrote this file.
            first.driver.execute(null, "PRAGMA user_version = 9999", 0)
            first.driver.close()

            val ex = assertFailsWith<IllegalStateException> {
                TaleviaDbFactory.open(env = mapOf("TALEVIA_DB_PATH" to dbPath))
            }
            assertTrue(ex.message!!.contains("newer than this build"), "msg was: ${ex.message}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun readUserVersion(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver): Long =
        driver.executeQuery(
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
