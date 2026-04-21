## 2026-04-19 — Persistent SQLite for JVM apps (`TaleviaDbFactory`)

**Context.** Both `AppContainer` (desktop) and `ServerContainer` opened the
SQLite database with `JdbcSqliteDriver.IN_MEMORY`. Every project, session,
source DAG entry, lockfile row, and snapshot was wiped on process exit,
which directly contradicts VISION §3.4 ("Project / Timeline is a codebase:
可读 / 可 diff / 可版本化 / 可组合"). Task 1 of the current gap list.

**Decision.**
- New JVM-only helper `core.db.TaleviaDbFactory` owns driver lifecycle:
  - Path resolution order: explicit arg → `TALEVIA_DB_PATH` env →
    `<TALEVIA_MEDIA_DIR>/talevia.db` → in-memory. `":memory:"` /
    `"memory"` force in-memory even when other env is set.
  - Schema cookie: `PRAGMA user_version`. `0` → `Schema.create` + stamp
    version; `< target` → `Schema.migrate` + stamp; `> target` → refuse to
    open (downgrade protection).
  - `PRAGMA journal_mode = WAL` on file-backed DBs for tolerance to
    occasional concurrent readers (e.g. desktop + server both pointed at
    the same file during dev).
- `AppContainer` (desktop) + `ServerContainer` now delegate to the factory
  and expose `dbPath: String` for logs.
- Desktop `Main.kt` layers two defaults onto `System.getenv()` before
  handing it to `AppContainer`: `TALEVIA_DB_PATH=~/.talevia/talevia.db`
  and `TALEVIA_MEDIA_DIR=~/.talevia/media`. User-supplied env wins;
  defaults only fill the blanks. Result: desktop is persistent
  out-of-the-box, and `TALEVIA_DB_PATH=:memory:` opts back into ephemeral.
- **Server stays in-memory by default.** Server is headless and intended
  for batch / stateless deployments; operators who want persistence set
  `TALEVIA_DB_PATH` (or `TALEVIA_MEDIA_DIR`, which the factory also picks
  up) explicitly.
- **Tests preserved.** No retrofit: every test that touches SQLite
  constructs its own `JdbcSqliteDriver` directly with
  `TaleviaDb.Schema.create` — they never went through the container
  defaults, so the factory change is invisible to them. The new
  `TaleviaDbFactoryTest` covers the factory surface.

**Alternatives considered.**
- **OS-idiomatic data dirs** (`~/Library/Application Support/Talevia` on
  macOS, `$XDG_DATA_HOME/talevia` on Linux, `%APPDATA%\Talevia` on
  Windows). Rejected for v0: per `CLAUDE.md` platform priority the current
  target is macOS; a single cross-OS default (`~/.talevia`) keeps the
  helper free of `System.getProperty("os.name")` branches, and the user
  can always point `TALEVIA_DB_PATH` anywhere they like. Revisit when
  Windows / Linux desktop becomes a first-class target.
- **Per-project SQLite files.** Rejected: the DB holds cross-project state
  (sessions, all projects, all snapshots). A global DB is the VISION §3.4
  "codebase" analogue; per-project files would fragment the codebase.
- **Make the server persistent by default too.** Rejected: server is a
  deployment target, not a user app. Defaulting to a file write when the
  operator didn't configure one surprises ops. Opt-in is the safer default.
- **Point the factory at a user-supplied `Path`, not an env map.** Wanted
  a zero-wiring default for desktop Main ("works out of the box") and a
  zero-persistence default for tests + CI. Env is the easiest lever to
  pull differently per-caller without forcing each caller to reconstruct
  the path resolution logic.

**Migration story.** First file-backed open against a fresh path writes
`user_version = <Schema.version>`. If the Kotlin schema ever bumps, the
factory's `Schema.migrate` branch will handle it automatically and
re-stamp. No migration SQL exists yet because the schema has only had one
version — the hook is in place for when it grows a second.

**Follow-ups.**
- Concurrent-writer story. WAL helps readers, but two JVMs writing to the
  same DB would still clash. Not a near-term concern (one desktop, one
  optional server), but should be thought through before we do any
  multi-process scenario.
- `TALEVIA_DB_PATH` needs a `docs/` mention; added a note in `CLAUDE.md`
  under Observability so operators find it alongside `TALEVIA_MEDIA_DIR`.

---
