## 2026-04-21 — list_lockfile_entries gains onlyPinned filter (VISION §3.1 产物可 pin)

Commit: `253d536`

**Context.** VISION §3.1 surfaces pinning on AIGC lockfile entries, and an
earlier cycle exposed the `pinned` flag on each `Entry` in
`list_lockfile_entries`. What was still missing was a server-side filter
for "show me only my pinned shots". Today the agent has to list every
entry (capped at 200 via `MAX_LIMIT`) and filter client-side, which
wastes tokens on long-running projects and silently drops pinned entries
older than the cap. This is the symmetric follow-up to the earlier
`pinned`-field addition — read-only, same tool, tiny additional surface.

**Decision.** Add `onlyPinned: Boolean? = null` to
`ListLockfileEntriesTool.Input`. When `true`, the filter pipeline
restricts results to `entry.pinned == true`; when `null` or `false` the
tool behaves exactly as before. `onlyPinned` composes with `toolId`
(both filters apply) so "show me pinned image generations" works in one
call. The summary line now surfaces the active scope (e.g. `toolId=…,
pinned`) so the LLM understands what was applied without re-reading the
input. `inputSchema` advertises the new field with a boolean type plus a
description that mentions `pin_lockfile_entry` and the compose-with-
`toolId` semantics.

**Alternatives considered.**

1. *Introduce a new `list_pinned_lockfile_entries` tool.* Rejected —
   would duplicate `projectId` / `toolId` / `limit` / schema / ordering
   logic, and the existing tool's purpose ("orientation — what have I
   generated?") is already the right mental home for this audit. The
   precedent is `find_stale_clips` keeping its `Input(projectId)` shape
   rather than spawning parallel tools per status.
2. *Make `onlyPinned` a three-way enum (`any` | `pinned` | `unpinned`)*.
   Rejected for YAGNI — no current flow wants "only the entries I have
   **not** pinned". The boolean stays simple, defaults to `null` (which
   deserialises on existing payloads that omit the field), and can be
   widened to a string-discriminated enum later without breaking
   callers who pass `false` (which collapses to default).
3. *Filter client-side in each caller (desktop / CLI panels) after
   calling the unfiltered tool.* Rejected — the agent itself is a
   caller, and asking the LLM to do post-filtering costs tokens and
   risks silently dropping entries that fall past the server-side
   `limit` cap (default 20, max 200) before the filter runs.

**Coverage.** `ListLockfileEntriesToolTest` — three new tests:
`onlyPinnedTrueReturnsOnlyPinnedEntries` (mixed pinned/unpinned across
multiple toolIds),
`onlyPinnedComposesWithToolIdFilter` (both filters applied together
reduce to the single matching entry), and
`onlyPinnedFalseAndNullMatchDefaultBehaviour` (explicit `false` and
`null` behave identically to the pre-change tool). Existing tests
(`pinnedFlagIsSurfaced`, `toolIdFilterScopesToModality`,
`limitTakesFromTheRecentTail`, …) continue to pass unchanged.

**Registration.** No-op — no new tool registered. Change is additive on
an existing `Tool<I, O>` already wired into every `AppContainer`.
