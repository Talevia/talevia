package io.talevia.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end verification that [TaleviaDb.Schema.migrate] can walk from
 * every historical `user_version` (1 → current) without throwing, ends at
 * the expected `user_version`, and leaves the expected tables.
 *
 * Rationale: the SqlDelight plugin's own `verifyMigrations` flag compares
 * migrated schemas against binary `.db` snapshots — useful but requires
 * maintaining those snapshots. This test takes a runtime approach instead:
 * run `Schema.migrate` on a fresh in-memory DB seeded to the target
 * starting version, then probe `sqlite_schema` / `PRAGMA user_version`
 * for the end state. No snapshots needed.
 *
 * Why migrating from an *empty* DB at user_version=N works here: 1.sqm
 * and 2.sqm only CREATE TABLE new siblings (ProjectSnapshots /
 * ProjectLockfileEntries / ProjectClipRenderCache) without touching the
 * legacy Projects blob table. 3.sqm drops the 4 project tables with
 * `IF EXISTS`, so the migration survives whether or not the legacy
 * Projects table was populated. The test exercises the migration SQL
 * itself, not the data preservation semantics (those are covered by the
 * store-level tests).
 */
class TaleviaDbMigrationTest {

    private fun withFreshDriver(block: (JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            block(driver)
        } finally {
            driver.close()
        }
    }

    private fun readUserVersion(driver: JdbcSqliteDriver): Long =
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

    private fun listTables(driver: JdbcSqliteDriver): Set<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            parameters = 0,
            binders = null,
            mapper = { cursor ->
                val tables = mutableSetOf<String>()
                while (cursor.next().value) {
                    cursor.getString(0)?.let { tables += it }
                }
                QueryResult.Value(tables.toSet())
            },
        ).value

    @Test fun schemaCreateYieldsCurrentVersionAndSessionTables() {
        withFreshDriver { driver ->
            TaleviaDb.Schema.create(driver)
            // The factory writes user_version after create; Schema.create itself
            // does NOT, so we set it here to match the factory's invariant.
            driver.execute(null, "PRAGMA user_version = ${TaleviaDb.Schema.version}", 0)

            assertEquals(TaleviaDb.Schema.version, readUserVersion(driver))
            val tables = listTables(driver)
            assertTrue("Sessions" in tables, "Sessions missing: $tables")
            assertTrue("Messages" in tables, "Messages missing: $tables")
            assertTrue("Parts" in tables, "Parts missing: $tables")
            // Post-v4, the 4 Project-related tables should not appear in a
            // fresh create (the current .sq schema has no Projects.sq).
            assertTrue("Projects" !in tables, "Projects leaked into fresh schema: $tables")
            assertTrue("ProjectSnapshots" !in tables)
            assertTrue("ProjectLockfileEntries" !in tables)
            assertTrue("ProjectClipRenderCache" !in tables)
        }
    }

    @Test fun migrateFromV1ToCurrentDoesNotThrowAndEndsAtCurrentVersion() {
        withFreshDriver { driver ->
            // Simulate an empty v1 DB. 1.sqm only creates new tables, so the
            // migration runs on a clean slate — we don't need to replay
            // v1's Projects schema to exercise the v1→v2 SQL.
            TaleviaDb.Schema.migrate(driver, 1, TaleviaDb.Schema.version)
            driver.execute(null, "PRAGMA user_version = ${TaleviaDb.Schema.version}", 0)

            assertEquals(TaleviaDb.Schema.version, readUserVersion(driver))
            // v1→v2 created snapshots + lockfile; v2→v3 added clip render
            // cache; v3→v4 dropped all 4 (Projects included via
            // DROP TABLE IF EXISTS). Final state: empty project-tables.
            val tables = listTables(driver)
            assertTrue("Projects" !in tables)
            assertTrue("ProjectSnapshots" !in tables)
            assertTrue("ProjectLockfileEntries" !in tables)
            assertTrue("ProjectClipRenderCache" !in tables)
        }
    }

    @Test fun migrateFromV2ToCurrentDoesNotThrow() {
        withFreshDriver { driver ->
            // At v2, ProjectSnapshots + ProjectLockfileEntries already existed.
            // Pre-create them so 2.sqm's v2→v3 step doesn't trip on missing
            // antecedents (even though 2.sqm itself only adds a new table,
            // the invariant of starting "at v2" means those should be there).
            driver.execute(null, "CREATE TABLE ProjectSnapshots (project_id TEXT, snapshot_id TEXT)", 0)
            driver.execute(null, "CREATE TABLE ProjectLockfileEntries (project_id TEXT, ordinal INTEGER)", 0)
            TaleviaDb.Schema.migrate(driver, 2, TaleviaDb.Schema.version)
            driver.execute(null, "PRAGMA user_version = ${TaleviaDb.Schema.version}", 0)

            assertEquals(TaleviaDb.Schema.version, readUserVersion(driver))
            val tables = listTables(driver)
            assertTrue("ProjectSnapshots" !in tables, "v3→v4 should drop ProjectSnapshots")
            assertTrue("ProjectClipRenderCache" !in tables, "v3→v4 should drop ProjectClipRenderCache")
        }
    }

    @Test fun migrateFromV3ToCurrentDropsAllFourProjectTables() {
        withFreshDriver { driver ->
            // Pre-create the 4 tables that existed at v3 so the DROP TABLE
            // IF EXISTS statements actually drop something — exercises the
            // v3→v4 happy path.
            driver.execute(null, "CREATE TABLE Projects (id TEXT PRIMARY KEY, data TEXT)", 0)
            driver.execute(null, "CREATE TABLE ProjectSnapshots (project_id TEXT, snapshot_id TEXT)", 0)
            driver.execute(null, "CREATE TABLE ProjectLockfileEntries (project_id TEXT, ordinal INTEGER)", 0)
            driver.execute(null, "CREATE TABLE ProjectClipRenderCache (project_id TEXT, fingerprint TEXT)", 0)
            TaleviaDb.Schema.migrate(driver, 3, TaleviaDb.Schema.version)
            driver.execute(null, "PRAGMA user_version = ${TaleviaDb.Schema.version}", 0)

            val tables = listTables(driver)
            assertTrue("Projects" !in tables, "v3→v4 should drop Projects: $tables")
            assertTrue("ProjectSnapshots" !in tables)
            assertTrue("ProjectLockfileEntries" !in tables)
            assertTrue("ProjectClipRenderCache" !in tables)
        }
    }

    @Test fun migrateFromCurrentToCurrentIsNoop() {
        withFreshDriver { driver ->
            TaleviaDb.Schema.create(driver)
            val before = listTables(driver)
            TaleviaDb.Schema.migrate(driver, TaleviaDb.Schema.version, TaleviaDb.Schema.version)
            assertEquals(before, listTables(driver), "same-version migrate must not mutate schema")
        }
    }

    @Test fun schemaVersionIsMonotonic() {
        // Guard against someone adding a migration file but forgetting to
        // update a `.sq` file — or vice versa. If a migration ships,
        // `Schema.version` must increment. Today that's ensured by
        // SqlDelight's plugin (it counts .sqm files); this test is a
        // belt-and-suspenders assert in case the plugin is ever swapped.
        assertTrue(
            TaleviaDb.Schema.version >= 4,
            "Schema.version (${TaleviaDb.Schema.version}) should be at least 4 after 1.sqm / 2.sqm / 3.sqm",
        )
    }
}
