## 2026-04-23 — CLI "Always" permission grants persist across process restarts (VISION §5.4 rubric axis)

**Context.** Pre-cycle-53, the CLI's interactive `[A]lways` reply
appended a `PermissionRule(permission, pattern, ALLOW)` to
`CliContainer.permissionRules` (a `MutableList`) at runtime. That
list was built fresh at each container construction from
`DefaultPermissionRuleset.rules` — every process restart discarded
the user's prior grants. CLI users re-answered the same prompts on
every launch (file-write to project directories, web-fetch to a
trusted API, etc.).

OpenCode persists equivalent grants via `permissions.storage`
(`packages/opencode/src/permission/index.ts`). The behavior gap was
operator-facing friction, not a correctness issue.

Rubric delta §5.4 (permission friction for interactive UIs):
CLI "Always" grants move from **部分** (in-process-only, grant scope
= process lifetime) to **有** (per-user, grant scope = until explicit
revoke / file edit).

**Decision.** New abstraction
`core/src/commonMain/.../permission/PermissionRulesPersistence.kt`:

- `interface PermissionRulesPersistence { suspend fun load():
  List<PermissionRule>; suspend fun save(rules: List<PermissionRule>) }`.
- `PermissionRulesPersistence.Noop` sentinel — platforms / tests that
  don't want file persistence.
- `FilePermissionRulesPersistence(path, fs, json)` — Okio-based JSON
  read/write; atomic save via tmp-file + rename (mirrors
  `FileProjectStore.atomicWrite`); load / save both `runCatching` so
  a corrupt file or read-only fs degrades to "empty list" /
  "silently dropped write" rather than bricking CLI init.

Wiring (CLI this cycle):

- `CliContainer.permissionRulesPath` derives from
  `TALEVIA_RECENTS_PATH`'s parent (defaulting to `~/.talevia/`) —
  lives alongside `recents.json` so the per-user config directory
  stays coherent.
- `CliContainer.permissionRulesPersistence` = `FilePermissionRulesPersistence`
  over that path.
- `CliContainer.permissionRules` merges `DefaultPermissionRuleset.rules
  + persistence.load()` at init (sync `runBlocking` — single-
  millisecond Okio read; container is constructed off-request-path).
- `StdinPermissionPrompt` gains optional `persistence:
  PermissionRulesPersistence = Noop` parameter; on `[A]lways` reply
  the newly-appended rule + the whole current list is saved via
  `persistence.save(permissionRules.toList())`.

**Axis.** n/a — net-new feature, not a refactor.

**Alternatives considered.**

- **Platform-native secret store (macOS Keychain, Windows
  Credential Manager).** Rejected — `PermissionRule` is plain
  structured data (permission id + glob pattern + action), not a
  credential. Cross-platform Okio JSON is the right abstraction
  tier. If a future variant DOES carry credentials (e.g.
  `permission.provider.openai.key-grant`), that's a separate
  SecretStore lane.

- **Persist ALL rules (including defaults) on first launch, operator
  edits the file to customize.** Rejected — `DefaultPermissionRuleset`
  is code-owned; persisting it would mean every code update that
  adds / changes a default rule requires a migration of user-copied
  files. Keep defaults in code, persist ONLY the delta the user adds
  interactively.

- **Per-project rules instead of per-user.** Rejected — the
  permission grants the user actually adds ("allow web.fetch to
  api.github.com *", "deny shell.exec to rm") are personal-workflow
  decisions, not project-metadata. They should follow the user
  across projects, not live inside each bundle. Per-project could be
  a future lane if a real use case emerges (e.g. "this project
  needs broader fs access than my default"), but §3a #1 (abstraction
  without driver) discourages adding it pre-emptively.

- **Slash-command `/revoke-permission`.** Deferred. The bullet
  mentioned it but this cycle lands the persistence foundation
  only. A slash-command would be the natural UX but the file is
  operator-editable in any editor (JSON list with 3 fields per
  entry — `permission`, `pattern`, `action`); that's enough of a
  manual revoke path for now. Filed as follow-up P2
  (`cli-revoke-permission-command`) if operator feedback warrants.

- **Wire Desktop + Server + Android persistence this cycle too.**
  Deferred. Desktop has its own `PermissionDialog` UI that appends to
  `container.permissionRules` via `PermissionDialog.kt`; Server and
  Android today have no interactive permission UI (auto-approve /
  default-rules only). CLI is the one platform where users actually
  hit the re-answer tax. When Desktop / Android add interactive
  prompts (or when §3a #8 "five-platform gap" forces sync), they
  extend the same `PermissionRulesPersistence` path. Follow-up P2
  bullet filed.

**Coverage.** 7 new tests in
`core/src/jvmTest/.../permission/FilePermissionRulesPersistenceTest.kt`:

1. `missingFileLoadsEmpty` — new install / first launch.
2. `saveThenLoadRoundTripsRuleList` — happy path.
3. `malformedFileLoadsEmpty` (§3a #9) — corrupt JSON doesn't throw.
4. `saveOverwritesExistingFile` — whole-list rewrite, not append.
5. `saveIsAtomicViaTmpRename` — no `.tmp.*` stragglers after
   successful save.
6. `saveErrorIsSwallowed` (§3a #9) — read-only fs path doesn't throw
   into CLI interactive path.
7. `noopPersistenceIsInertButCallable` — Noop sentinel load=empty,
   save=inert.

Integration tests for `StdinPermissionPrompt` on the "Always-triggers-
save" path are covered implicitly by the unit test's round-trip
assertion + direct inspection of the StdinPermissionPrompt code diff
(single `persistence.save(...)` call site).

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintCheck all green.

**Registration.** No new tool. CLI container wires
`FilePermissionRulesPersistence(~/.talevia/permission-rules.json)` +
passes it into `StdinPermissionPrompt`. Desktop / Server / Android /
iOS containers unchanged this cycle — they either don't have
interactive permission prompts yet (Server / Android / iOS) or have
their own UI path that can be wired in a follow-up (Desktop).

**§3a arch-tax check (#12).** No new tool. No new select. No new
reject rule. Nothing triggers.

**File:** `<TALEVIA_RECENTS_PATH parent>/permission-rules.json`.
JSON list shape, one object per rule:
```json
[
  {"permission":"fs.write","pattern":"/tmp/**","action":"allow"},
  {"permission":"web.fetch","pattern":"https://api.github.com/*","action":"allow"}
]
```
Operators can hand-edit; malformed JSON degrades to "no persisted
rules" rather than erroring.
