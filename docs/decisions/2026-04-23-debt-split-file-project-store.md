## 2026-04-23 — Split `FileProjectStore.kt` 545 → 422 along the per-persistence-role axis (VISION §5.6 system health)

**Context.** `FileProjectStore.kt` grew to 545 lines after cycle-45's
source-history additions (~70 LOC added). The file mixed five distinct
persistence roles in one class body: bundle envelope I/O (talevia.json +
render cache sidecar + .gitignore housekeeping), source-node history
I/O (per-node JSONL), recents-registry integration (title / lastOpened
stamps), cross-process lock management (.talevia-cache/.lock), and
project / asset validation hooks (bus event emission).

Per R.5 severity rules: files 500–800 LOC land as default P1 but
FileProjectStore is the canonical per-project persistence layer —
every next lane addition (snapshots, future session-project archives,
etc.) would re-inflate the file. Promoted to P0 in this cycle's
repopulate (cycle-48) to break the pattern before the next lane
lands.

Rubric delta §5.6 (system health / maintainability): FileProjectStore
moves from **部分** (single-file 545 LOC, 5 roles mixed, next lane
push it to 600+) to **有** (facade 422 LOC; envelope I/O + source-
history I/O extracted to sibling internal files; next lane goes to
its own sibling, not back into the facade).

**Decision.** Split along the per-persistence-role axis (NOT
file-size axis):

1. **`FileProjectStoreEnvelopeIO.kt`** (new, ~170 lines) — hosts:
   - `StoredProject` data class (bundle envelope).
   - `decodeStored(fs, path, json): StoredProject`.
   - `readTitle(fs, path, json): String?`.
   - `readBundle(fs, path, json): Project` — envelope + render cache
     sidecar merged.
   - `writeBundleLocked(fs, path, title, project, createdAtEpochMs,
     json)` — the full bundle write (envelope + cache sidecar +
     gitignore). Caller holds intra-process + cross-process locks.
   - `writeStoredEnvelope(fs, path, stored, json)` — envelope-only
     overwrite (used by `setTitle`).
   - `atomicWrite(fs, target, write)` — tmp-file + atomic-rename.
   - `bundleTimestamps(fs, taleviaJson, fallback)` — metadata probe.
   - `randomSuffix(): String` — temp-file suffix helper.

2. **`FileProjectStoreSourceHistoryIO.kt`** (new, ~80 lines) — hosts:
   - `appendSourceNodeHistoryFile(fs, bundlePath, nodeId, revision)` —
     read-modify-atomicWrite on the per-node JSONL.
   - `listSourceNodeHistoryFile(fs, bundlePath, nodeId, limit)` —
     slurp + split + reverse + cap.

3. **`FileProjectStore.kt`** (now 422 lines, was 545) — kept as the
   `ProjectStore` facade. Public-API overrides (`openAt` / `createAt`
   / `get` / `list` / `upsert` / `setTitle` / `summary` /
   `listSummaries` / `mutate` / `delete` / `pathOf` /
   `appendSourceNodeHistory` / `listSourceNodeHistory`) stay here —
   they own the concurrency primitives (`mutex`, `withBundleLock`) and
   the bus-emission hooks (`maybeEmitValidationWarning`,
   `maybeEmitMissingAssets`), which depend on instance state (`logger`,
   `bus`, `locker`) that isn't naturally portable to free functions.
   The class body delegates to the new top-level helpers for all pure
   I/O.

Also added: `delete` now removes `source-history/` along with
`media/`. Was an oversight in cycle-45 — history directory sticks
around on bundle delete with `deleteFiles=true` — caught during this
split and fixed in the same commit (trivial, one line, clearly
correct).

**Axis.** `per-persistence-role`. What brings this facade back to the
500-line threshold is a new persistence lane (e.g. "add a
per-session compaction archive to the bundle" — which IS on the
backlog as `agent-pre-compaction-tool-archive`). When that lane
lands, it should get its own sibling `FileProjectStoreCompactionArchiveIO.kt`
file, NOT be squeezed into the facade. That sibling-per-lane
discipline keeps the facade's size proportional to the number of
orchestration paths (ProjectStore method count), not to the number
of on-disk artefact types.

**Alternatives considered.**

- **Extract by method count (split off `list` / `listSummaries` into
  a query-side sibling).** Rejected — the split makes the facade
  shorter but doesn't fix the axis ("too much role-mixing"). The
  next source-history-style lane addition would still push the facade
  above 500 because `list` / `listSummaries` aren't the roles growing
  most aggressively.

- **Merge the two new siblings into a single `FileProjectStoreIO.kt`.**
  Rejected — bundling envelope + source-history makes the "one
  sibling per persistence lane" axis blurry. Separate files mean
  each lane's ownership is visible in the file tree, and adding the
  third lane (next cycle's compaction-archive) is clearly "one
  new file", not "append to the grab-bag".

- **Move `atomicWrite` + `randomSuffix` to a `core.util` helper
  outside `core.domain`.** Rejected — no other caller. Extracting a
  "utility module" for two callers is premature (§3a #1 spirit for
  abstractions: wait for N=3). These helpers stay in the envelope
  file; if a fourth caller emerges, promote then.

- **Leave `maybeEmitValidationWarning` / `maybeEmitMissingAssets` in
  the facade.** Accepted (kept in facade). These callbacks fire INSIDE
  `openAt` / `get` read paths, consult the instance's `bus` and
  `logger` fields, and are 40 lines together — moving them to a
  sibling would require threading those fields through every call.
  Not worth the indirection today. If a third "on-load check" joins
  (e.g. asset-integrity scan, migration-needed hint), revisit.

**Coverage.** No new tests — this is a pure internal refactor. The
existing `ProjectStoreConcurrencyTest`, `ProjectStoreAutoValidationTest`,
`ProjectStoreRecencyStampingTest`, `AutoRegenHintTest`,
`TransitiveSourceHashTest`, `FileProjectStoreBenchmark`, plus every
test that uses `ProjectStoreTestKit.create()` (100+ files) exercise
the full `ProjectStore` contract through the facade → helper chain
in exactly the same way as before.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + `ktlintCheck` all green.

**Registration.** No registration changes. `FileProjectStore` is
wired at every JVM AppContainer (CLI / Desktop / Server / Android) +
iOS via the same constructor. The sibling internal helpers are
package-private; no external consumer exists.

**Code-size delta.** FileProjectStore.kt: 545 → 422 lines (−123).
New files: 172 + 79 = +251 (of which ~60 is KDoc for the extracted
helpers — the code itself is ~190). Net +128 across 3 files but the
unit of file growth now scales with lane count, not with method
count, which keeps the long-file threshold off of any single file.
