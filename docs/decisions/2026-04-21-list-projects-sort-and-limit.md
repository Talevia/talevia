## 2026-04-21 — list_projects gains sortBy + limit (VISION §5.4 Agent/UX orientation)

Commit: `09095e7`

**Context.** `list_projects` is the agent's first orientation call when a user
says "what am I working on?" or "open the tutorial project from yesterday".
Pre-extension the tool took no input and returned every project in the store
in SQL natural order (`selectAll()`). Two common agent questions — "show me
the 5 most recently updated projects" and "sort alphabetically by title" —
were inexpressible without pulling the whole catalog and sorting
client-side, which defeats the point of a lightweight orientation tool
once a user's catalog grows past a handful of projects (common once you
start experimenting with AIGC shorts — each iteration tends to live in its
own project). Sibling tool `list_assets` just landed the same extension
(see `docs/decisions/2026-04-21-list-assets-sort-by.md`); this brings
`list_projects` to parity.

**Decision.** Two new `Input` fields, applied in order `sortBy` → `take(limit)`:

- `sortBy: String? = null`. Case-insensitive (trim + lowercase before
  match). Valid values: `"updated-desc"` (default), `"created-desc"`,
  `"title"`, `"id"`. Invalid values raise `IllegalArgumentException`
  listing the accepted set — same fail-loud stance as `list_assets`. The
  default of `"updated-desc"` is the common "what did I touch last?"
  question; a store-insertion baseline wasn't an option here because
  `ProjectSummary` carries no stable insertion ordinal, only
  `createdAtEpochMs` / `updatedAtEpochMs`, so we pick an explicit default
  rather than leaking SQL's `ORDER BY ROWID`-ish behaviour.
- `limit: Int? = null`. Default 50, silently clamped to [1, 500] via
  `coerceIn` — no exception on overflow. Matches the house pattern from
  `list_lockfile_entries` and `list_assets`. Orientation tools shouldn't
  fail because the agent wrote `limit=1000`.

**Output shape.** Output gains `returnedCount` alongside the existing
`totalCount`. `totalCount` is preserved verbatim (pre-limit full count
from `projects.listSummaries().size`) so the field name stays
backward-compatible: in the pre-extension world `totalCount ==
projects.size` trivially, and that identity still holds whenever no limit
is applied. `returnedCount` is the post-sort-post-limit page size, matching
the `total` / `returned` convention from `list_assets`.

**Case-insensitive title sort.** `"title"` uses
`sortedBy { it.title.lowercase() }`, so `"banana" < "Apple"` by default ASCII
would flip. A user's mental model of "alphabetical" is case-insensitive
("Apple, banana, Cherry" — not "Apple, Cherry, banana"). Locale-sensitive
collation would be more correct for non-ASCII titles but requires a
platform-specific `Collator` that we can't reach from `core/commonMain` —
`.lowercase()` is Unicode-aware on the JVM and iOS Kotlin/Native stdlib
and is a pragmatic good-enough default. If a user turns up with Turkish
titles (`i` vs `İ`) we'll revisit.

**Schema surface.** `inputSchema.properties.sortBy` carries an explicit
`enum` array so the LLM sees the allowed vocabulary at prompt-shape time.
`list_assets` uses a freeform string with only a prose description; in
hindsight `enum` is better (directly validates on the provider side for
some models) and this is the first tool in the family to do so. We'll
retrofit `list_assets` separately if it proves useful — not in this
commit.

**Alternatives considered.**

1. *Only `updated-desc`, no knob.* Rejected: "sort alphabetically" is a
   common orientation question, and extending once here avoids a second
   revisit when the user asks for it. The gap rubric explicitly noted
   this was a user-facing question, not a speculative feature.
2. *Make `sortBy` a typed Kotlin enum, deserialised via
   `kotlinx.serialization`.* Rejected — a string + JSON-schema `enum`
   matches the rest of the codebase (`list_assets`, `list_lockfile_entries`
   variants), and a typed Kotlin enum leaks into the LLM-facing schema
   as an oneOf-of-singletons when `kotlinx.serialization` emits the type;
   the string lane is simpler + flatter for the LLM, and the `require(...)`
   check provides the same fail-loud semantics.
3. *Paired `offset` like `list_assets` has.* Rejected — `list_projects`
   is the "orientation" tool; if a user has 500+ projects and wants the
   *second* page of results, the right answer is to narrow by ID /
   timestamp filter, not paginate. We can add `offset` when a concrete
   driver shows up. Keeping the surface minimal today.
4. *Sort by "title-desc" too.* Rejected — no observed agent query asks
   for "reverse alphabetical". Four values cover the real questions;
   `Z → A` can be added if the data shows a need.
5. *Hard-fail on `limit=0` or `limit=1000` instead of silent clamp.*
   Rejected — breaks the `list_lockfile_entries` / `list_assets` house
   pattern. Orientation tools with a permissive clamp are more agent-friendly
   than strict validators.

**Coverage.** New `ListProjectsToolTest` (10 tests — no existing file):
`emptyStoreReturnsEmpty`, `defaultSortIsUpdatedDescending`,
`sortByCreatedDescOrdersNewestFirst` (exercises the createdAt pin — an
upsert of an existing project bumps `updatedAt` but preserves
`createdAt`), `sortByTitleIsCaseInsensitive`,
`sortByIdIsAlphabeticAscending`, `limitCapsResponse`,
`limitClampedToMax` (999_999 → 500 → returns all seeded), `limitClampedToMinimum`
(0 → 1), `sortByInvalidFailsLoudly` (asserts error message lists every
accepted value so the agent can self-correct), `sortByComposesWithLimit`
(sort title + `limit=3` → alphabetically-first 3), plus
`sortByIsCaseInsensitiveAndTrimmed` covering the lowercase + trim
normalisation. Uses the `MutableClock` pattern from
`ProjectStoreConcurrencyTest` to inject deterministic
`createdAt` / `updatedAt` on seed — `SqlDelightProjectStore`'s ctor
already accepts a `Clock`, so no domain changes were needed.

Backward-compat checked against the pre-existing
`ProjectToolsTest.listProjectsReturnsCatalogMetadata` +
`listProjectsEmptyHasFriendlyMessage` — both still pass (they assert on
`totalCount` and the "create_project" friendly hint, both preserved).

**Registration.** no-op — `ListProjectsTool` is already wired in every
AppContainer (cli, desktop, server, android, ios).
