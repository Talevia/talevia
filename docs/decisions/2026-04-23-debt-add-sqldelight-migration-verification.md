## 2026-04-23 — debt-add-sqldelight-migration-verification (VISION §3a-9 / 正确性)

**Context.** `core/src/commonMain/sqldelight/io/talevia/core/db/` ships 3 migration scripts (`1.sqm` / `2.sqm` / `3.sqm`) plus 3 current-schema tables (`Sessions.sq` / `Messages.sq` / `Parts.sq`). Production code in `TaleviaDbFactory.openFile` relies on `TaleviaDb.Schema.migrate(driver, current, target)` to carry users across schema upgrades, but nothing automatically verifies the migration chain actually works. A single-user desktop/CLI app is high-stakes for this: users store irreplaceable session history in that DB. Breakage today would be detected only on a user's machine, where recovery means shipping a hotfix and hoping no one has opened the app since. Rubric delta §3a-9: SqlDelight migration path 无 guardrail → 有 runtime guardrail (unit-test-level; dynamic schema verification still deferred, see alternatives).

**Decision.** Added `core/src/jvmTest/kotlin/io/talevia/core/db/TaleviaDbMigrationTest.kt` with 6 test cases:
- `schemaCreateYieldsCurrentVersionAndSessionTables` — `Schema.create` on a fresh driver produces `user_version == Schema.version` and the 3 current-schema tables; asserts none of the 4 legacy Projects-* tables leak in (catches a future .sq-file that accidentally resurrects them).
- `migrateFromV1ToCurrentDoesNotThrowAndEndsAtCurrentVersion` — empty DB seeded at v1, run `Schema.migrate(driver, 1, Schema.version)`; verifies each transition (v1→v2 create sibling tables, v2→v3 add clipRenderCache, v3→v4 drop all) doesn't throw. End state: legacy project tables absent.
- `migrateFromV2ToCurrentDoesNotThrow` — same, starting at v2, pre-seeding the two v1-introduced tables so the migrations have antecedents matching the real "at v2" invariant.
- `migrateFromV3ToCurrentDropsAllFourProjectTables` — pre-creates all 4 legacy tables and verifies v3→v4's `DROP TABLE IF EXISTS` actually drops them. Catches an accidental regression where a typo in 3.sqm leaves a table alive.
- `migrateFromCurrentToCurrentIsNoop` — `Schema.migrate(v, v)` produces the same table set. Protects against someone putting SQL in the wrong `.sqm` file and having it run on same-version opens.
- `schemaVersionIsMonotonic` — belt-and-suspenders sanity check that `Schema.version >= 4`. Catches the scenario of adding a `4.sqm` without the SqlDelight plugin picking it up (or vice versa).

Tests use `JdbcSqliteDriver(IN_MEMORY)` — no filesystem I/O, no fixture files, runs on every `:core:jvmTest`.

**Alternatives considered.**
- *Enable SqlDelight's `verifyMigrations.set(true)` in `core/build.gradle.kts`.* Partially rejected for this cycle: `verifyMigrations` compares migrated schemas against binary `.db` snapshots stored at `src/commonMain/sqldelight/databases/N.db`. These snapshots are produced by `./gradlew generateCommonMainTaleviaDbSchema` and must be regenerated each time the current schema changes — they're a maintenance-cost trade-off. A runtime unit test catches the most common failure modes (throws during migrate, wrong `user_version`, missing / lingering tables) without requiring a new asset class to track. The `verifyMigrations` path remains a reasonable future upgrade for stricter column-level diffing.
- *Snapshot each historical schema as a `*.sql` file in test resources and load it before migrate.* Rejected for similar reasons: high upfront maintenance cost for a project that deleted the v1–v3 Projects tables already (the v3→v4 migration is one-way with documented data-loss). Simulating v1 exactly is archaeological work unlikely to pay off — all users on current builds are past v1, so a perfect historical replay mostly proves the past still works.
- *Skip v3→v4 and test only v1→v2 / v2→v3.* Rejected: v3→v4 is the most recent migration and the most likely to have a subtle bug. The "pre-create 4 tables, migrate, assert drops" shape in `migrateFromV3ToCurrentDropsAllFourProjectTables` exercises exactly this.

**Coverage.** 6 new tests in `core/jvmTest`. `./gradlew :core:jvmTest --tests 'io.talevia.core.db.TaleviaDbMigrationTest'` green. No production code changed — this is pure test-side infrastructure. `./gradlew :apps:server:test` stays green (previously unbroken in cycle 17).

**Registration.** No `Tool<I, O>` added, no `AppContainer` touched — tests only.
