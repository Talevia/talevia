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
