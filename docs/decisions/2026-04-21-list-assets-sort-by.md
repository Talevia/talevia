## 2026-04-21 — list_assets gains sortBy (VISION §5.3 Artifact/编译过程)

Commit: `ad457d4`

**Context.** `list_assets` already supports `kind` / `onlyUnused` / `limit` /
`offset` but returned store-insertion order. An agent asking "show me the
longest video" or "the shortest audio cue" had to pull the entire matching
set and sort client-side, which negates the whole point of `limit` — you
can't cap the page size *and* answer an ordering question without pulling
everything. The asymmetry was especially painful on projects with dozens of
AIGC-generated shorts where the catalog grows faster than a transcript can
absorb.

**Decision.** Add a `sortBy: String?` input, applied **before**
`offset` + `limit` so the page reflects the sorted-top-N, not a sorted
slice of an unsorted head. Vocabulary is intentionally tiny:

- `"duration"` — `metadata.duration` DESC (longest first).
- `"duration-asc"` — ascending.
- `"id"` — asset id ASC (lexicographic; stable for pagination).

Null / omitted preserves today's store-insertion order so existing callers
don't change semantics. Unknown values fail loud via `require`, listing the
accepted set in the error — an invalid `sortBy` would silently degrade to
insertion order otherwise, which is the worst kind of wrong answer on a
sort question.

**"newest" is deliberately absent.** The gap write-up proposed a
`"newest"` value, conditional on `MediaAsset.metadata` carrying a creation
timestamp. Reading `core/src/commonMain/kotlin/io/talevia/core/domain/MediaAsset.kt`
confirms no such field exists today — `MediaMetadata` holds duration,
resolution, codec strings, sample rate, channels, bitrate, and nothing
temporal. Rather than fake it (e.g. hash-derived ordering, or tacking on an
epoch field just for this tool), we drop `"newest"` from the vocabulary
until there's a concrete driver to add `createdAtEpochMs` to the domain.
That keeps the domain model honest — "no hypothetical future fields"
mirrors the anti-requirement against designing for hypothetical needs.

Ties under `"duration"` / `"duration-asc"` resolve by Kotlin's stable sort
(`sortedByDescending` / `sortedBy`), so the tiebreaker is insertion order —
deterministic, and matches what the caller would expect coming from the
null-sort baseline.

**Alternatives considered.**

1. *Extend the domain model with `createdAtEpochMs` to honour `"newest"`.*
   Rejected for now — no other caller needs the timestamp, and
   back-filling it for existing catalog entries is undefined (file mtime?
   import time? first-referenced time? each answer is a different
   semantic). A standalone decision with a driver (e.g. UI sorting the
   asset drawer chronologically) is the right venue, not a piggyback here.
2. *A generic `orderBy: List<SortKey>` with `field` + `direction` records
   for arbitrary composite orderings.* Rejected — list_assets is a
   narrowly-typed projection, not a SQL query builder. Three enum values
   cover the real "sort the asset list" queries, and a stringly-typed
   composite schema invites the agent to compose orders the underlying
   storage can't honour efficiently.
3. *A regex / free-form sort expression (à la `"-duration"` for DESC, `"+id"`
   for ASC).* Rejected — cute, but the LLM has to learn a
   Talevia-specific micro-DSL that doesn't compose with anything else. An
   enum with hyphenated `-asc` suffix is self-explanatory from the schema
   description alone.
4. *Always sort by id when `sortBy` is null (stable default).* Rejected
   because the existing `paginationWithLimitAndOffset` test documents the
   current "insertion-order" contract and other tools in this family
   (`list_timeline_clips`, `list_source_nodes`) likewise preserve
   insertion order as the null-sort baseline. Changing the default would
   silently shuffle every existing caller's result.

**Coverage.** `ListAssetsToolTest` gains six tests:
`sortByNullPreservesStoreInsertionOrder`,
`sortByDurationOrdersLongestFirst`,
`sortByDurationAscOrdersShortestFirst`,
`sortByIdOrdersAscending`,
`sortByAppliedBeforeOffsetAndLimit` (confirms the top-2 of the fixture is
the two audio assets, not the two insertion-order leaders), and
`rejectsInvalidSortBy` (asserts the error lists all three accepted values
so the agent can self-correct).

**Registration.** no-op — no new tool registered. `list_assets` is already
wired in every AppContainer.
