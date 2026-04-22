## 2026-04-22 — Extract `Project.clipRenderCache` to a sibling SQLDelight table (schema v2 → v3, VISION §5.3)

Commit: `99910c5`

**Context.** Cycle 30 landed `per-clip-incremental-render` which added
`Project.clipRenderCache: ClipRenderCache` — an append-only list of
`ClipRenderCacheEntry` rows (one per cache-miss clip render). Every
cache miss appends through `ClipRenderCache.append(entry)` into the
Project, and every `ProjectStore.mutate` call re-encodes the full
blob into the `Projects.data` column. The failure mode is identical
to what the v1 → v2 split (`docs/decisions/2026-04-21-split-project-json-blob.md`)
diagnosed for `snapshots` + `lockfile.entries`: every unrelated tool
call (`add_clip`, `set_clip_volume`, even metadata tools) writes out
the whole accumulated cache history, turning O(1)-user-intent into
O(project-age × mutations)-bytes. §3a rule 3 flags this as a hard
block the moment an append-only `List<X>` field lands on `Project`.

The backlog repopulate this cycle (commit `24ade91`) picked the new
field up via the R.5.2 scan (`Project.kt` went 17 → 18 `val` fields;
the new field's type parameter is an append-only List). Promoted to
the P0 slot — the same rubric rationale the earlier lockfile /
snapshot extract carried.

**Decision.** Schema v2 → v3 with one new sibling table:

- **`ProjectClipRenderCache(project_id, fingerprint, mezzanine_path, created_at, payload)`**
  — PRIMARY KEY `(project_id, fingerprint)`, INDEX
  `(project_id, created_at ASC)`. `payload` stores the full
  `ClipRenderCacheEntry` JSON; `mezzanine_path` + `created_at` are
  duplicated out of the payload for future prune-by-age queries
  (P2 backlog `per-clip-render-cache-gc`). `INSERT OR REPLACE` on
  upsert so a repeat cache-miss for the same fingerprint (happens
  when the user deletes the `.talevia-render-cache` directory
  between exports) rewrites the row instead of stacking duplicates.

`Project` data class — **zero changes**. `clipRenderCache:
ClipRenderCache = ClipRenderCache.EMPTY` default keeps forward-compat
(§3a rule 7). `SqlDelightProjectStore` changes:

- `upsert` now strips `clipRenderCache` to `EMPTY` inside the slim
  blob, then walks `stamped.clipRenderCache.entries` and upserts one
  row per entry. Wrapped in the existing `db.transaction {}` block
  so the three sibling tables move atomically with the Projects row.
- `get`'s `assembleProject` reads the sibling table alongside
  snapshots + lockfile entries; empty-table + non-empty blob still
  serves the blob values (legacy pre-v3 fallback, same pattern).
- `delete` adds `deleteAllForProject` on the new table so
  `delete(pid)` doesn't leak orphan cache rows (no FK cascade —
  SQLite's `foreign_keys` pragma is off project-wide, matching the
  precedent).

Migration is **lazy + DDL-only** — same shape as 2026-04-21's
split. `2.sqm` just creates the table + the one index; no Kotlin
migration step. A pre-v3 project's cache entries still live inline
in the blob and serve through the legacy fallback until the next
`upsert` rewrites them into the new table.

**Alternatives considered.**

1. **Defer the split until cache eviction / GC lands.** Rejected.
   §3a rule 3 is structural — "append-only `List<X>` on `Project`" is
   a write-amplification vector the moment it lands, not a
   problem-conditional-on-growth. The earlier lockfile extract made
   this the explicit Core invariant; every cycle since has respected
   it. Postponing this one would reintroduce the inconsistency the
   backlog bullet exists to close. The GC tool can layer on top of
   the sibling table (delete-by-age query) without touching the
   extraction pattern.

2. **Upsert-keyed by `(project_id, created_at)` or `rowid` with a
   secondary `fingerprint` index.** Rejected — fingerprints are
   already unique-by-construction (clip JSON + transition fades +
   source hashes + output profile). An ordinal/rowid key would
   allow accidental dup rows (see the thrash scenario: delete
   cache dir, re-render same fingerprint with a new mezzanine path
   → two rows with same fingerprint, different paths, `findByFingerprint`
   returns the wrong one depending on order). Fingerprint-as-PK +
   `INSERT OR REPLACE` makes the storage semantics match the in-memory
   `ClipRenderCache.findByFingerprint(...)` contract (`lastOrNull` →
   "latest write wins").

3. **Eager migration — iterate every project on `Factory.open()`,
   rewrite blob + populate rows.** Rejected for the same reasons
   2026-04-21's lockfile/snapshot split rejected it: variable startup
   cost, partial-failure recovery complexity, need for a migration
   completion marker. Lazy migration amortises to the first mutate
   call on each project — the user is already in that project, so
   adding a few ms is unobservable.

4. **Leave `ClipRenderCache` inline and instead patch
   `SqlDelightProjectStore.upsert` to skip the clipRenderCache
   serialization when the entries list equals the prior-read
   version.** Rejected — the optimization is conditional on identity
   (only cache-miss renders change the cache, and those are the
   writes you want cheap). The sibling-table approach is
   unconditional: all mutations get the cheap-encode property because
   the expensive field is no longer on the blob at all.

**Coverage.** New test file
`core/src/jvmTest/kotlin/io/talevia/core/domain/SqlDelightProjectStoreClipRenderCacheTest.kt`
— 8 cases, all §3a rule 9 semantic-boundary oriented:

- `upsertPersistsClipCacheEntriesAndEmptiesBlob` — write 2 entries,
  verify row count + blob field emptied.
- `getReassemblesClipRenderCacheFromTable` — round-trip two-entry
  project, equality of the `entries` list.
- `legacyBlobWithInlineClipCacheReadsCorrectly` — directly write a
  pre-v3 blob (inline entry, empty table) via the raw
  `projectsQueries.upsert`, verify `store.get` returns the inline
  entry.
- `firstUpsertAfterLegacyReadMigratesToTable` — legacy read → store
  upsert → blob field cleared + table populated. The key contract
  behind "lazy migration".
- `deleteRemovesClipRenderCacheRows` — `delete(pid)` zeros the
  sibling table (no orphan rows).
- `duplicateFingerprintUpsertCollapsesToOneRow` — upsert with two
  entries sharing fingerprint collapses to one row (the later one
  wins). Protects against the thrash scenario.
- `shrinkingClipCacheRemovesRowsFromTable` — 3 entries → upsert
  with 1 entry → table has 1 row. Protects against the "delete
  stale rows" path a future GC tool will take.
- `emptyClipRenderCacheRoundTripsCleanly` — default-empty cache has
  zero rows, survives round-trip without phantom inserts.

The pre-existing `SqlDelightProjectStoreSplitTest.schemaVersionIsTwo`
was renamed and bumped to assert `schemaVersionIsThree` — it's the
canonical trip-wire for "someone added an `.sqm` without updating the
guard", and this change is exactly that intentional bump.

Existing tests (`SqlDelightProjectStoreSplitTest`,
`ProjectStoreRecencyStampingTest`, `ProjectStoreConcurrencyTest`)
pass unchanged — the `clipRenderCache` split is additive; snapshot +
lockfile + recency behaviour is preserved.

**Registration.** Pure Core storage refactor — zero
`AppContainer` change. All 5 app binaries pick up the new schema
version via `TaleviaDb.Schema` automatically:

- JVM (desktop / server / cli): `TaleviaDbFactory.open()` reads
  `PRAGMA user_version`, compares to `Schema.version` (now `3`),
  runs `Schema.migrate(driver, 2, 3)` on an existing v2 DB — which
  executes `2.sqm` (one `CREATE TABLE` + one `CREATE INDEX`,
  milliseconds).
- Android: `AndroidSqliteDriver(TaleviaDb.Schema, ...)` internalises
  the version compare and triggers the same migration.
- iOS: `NativeSqliteDriver` equivalently; iOS currently boots from
  an in-memory driver so `Schema.create` handles the new DDL in one
  pass.

No `.sq` file semantics changed beyond adding `ProjectClipRenderCache.sq`
for the queries the Kotlin store uses.

**Non-goals / follow-ups.**

- **Cache eviction / GC.** The P2 backlog bullet
  `per-clip-render-cache-gc` still stands — this cycle lands the
  storage substrate, not the policy for bounding cache size.
  Implementation will read the new table's `created_at` index
  directly.
- **Reading cache stats without decoding the full `Project`.** A
  hypothetical "how many mezzanines has this project accumulated?"
  tool can now `SELECT COUNT(*)` straight against
  `ProjectClipRenderCache` without going through `ProjectStore.get`.
  Not wired up — no concrete driver yet.

---
