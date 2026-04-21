## 2026-04-21 — find_unreferenced_assets gains sortBy + limit (VISION §5.4 Agent/UX — decluttering)

Commit: `04aa6a8`

**Context.** `find_unreferenced_assets` is the read-only counterpart to
`prune_lockfile` — the tool an agent reaches for when answering "I imported
20 clips, used 3, what can I safely delete?". Until this change it returned
every orphan in whatever order `project.assets` yielded, with no cap. On a
50-import project where 20 are orphans, the agent burns 20 rows of
transcript listing the small fish before surfacing the one 5-minute clip
worth deleting. That's backwards — the whole point of the tool is to
prioritise the big wins, and the user's "which KB are easiest to reclaim?"
intent is a sorted top-N query, not a linear scan.

The gap mirrors what `list_assets` hit last iteration (see
`docs/decisions/2026-04-21-list-assets-sort-by.md`): the tool was paginated
in shape but its ordering was store-insertion, so a `limit` truncated the
head of an unsorted list rather than returning the top-N of a useful
ordering. This decision applies the same treatment, but with a *defaulted*
sort instead of an opt-in one — the decluttering workflow has a single
dominant intent (biggest first), and omitting a default would waste the
agent's first call teaching it the vocabulary.

**Decision.** Add two inputs, applied BEFORE any truncation:

- `sortBy: String?` — accepted values (case-insensitive, trim+lowercase):
  `"duration-desc"` (default), `"duration-asc"`, `"id"`. Invalid values
  raise `IllegalArgumentException` listing the accepted set. Normalization
  + enum-validate pattern matches `ListAssetsTool`.
- `limit: Int?` — default 50, silently `coerceIn(1, 500)`. No exception
  on overflow: `limit=0` and `limit=999_999` both get clamped. The
  agent's intent is unambiguous ("as many as possible, bounded") and
  failing loud here would regress decluttering UX the first time the
  agent guesses a round number.

Pre-limit totals (`totalAssets`, `referencedCount`, `unreferencedCount`)
are preserved in the output — they reflect the real scan, not the returned
slice. A new `returnedCount: Int` field reports the post-sort, post-limit
slice size so callers can tell when more orphans exist beyond the window.
The `outputForLlm` tail now includes a scope label ("biggest first" /
"shortest first" / "id asc") and a "N of M shown" banner when the limit
truncates, so the agent's natural-language summary reflects both the
ordering it got and whether more remain.

Sort semantics:

- `"duration-desc"` — `sortedByDescending { it.metadata.duration }`.
  `Duration.ZERO` is the smallest value, so durationless assets (LUTs,
  images) naturally sort last — which is what we want: an LUT with
  `Duration.ZERO` is not a big KB win, a 5-minute video is.
- `"duration-asc"` — ascending; rarely useful but cheap to offer for
  symmetry with `list_assets`.
- `"id"` — lexicographic `sortedBy { it.id.value }`, stable for
  pagination-style follow-ups.

**Why `"duration-desc"` is the default (not `"id"`).** The tool's whole
point is decluttering; the agent's dominant question is "what's the easiest
KB win?", not "show me the alphabet". Making the caller opt in to
biggest-first defeats the ergonomic purpose — every agent that doesn't know
about `sortBy` would get a less useful default. Alphabetical is only useful
for pagination, and callers who need deterministic pagination explicitly
pass `sortBy="id"`.

**Alternatives considered.**

1. *Default sort by id alphabetically for deterministic ordering.*
   Rejected — the tool's whole point is decluttering; biggest-first
   matches the "what's the easiest KB win?" flow the agent is already
   running. Alphabetical-by-default makes every default call strictly
   less useful and forces callers to learn the `sortBy` vocabulary just
   to get back to the obvious ordering. Callers who need deterministic
   pagination still pass `sortBy="id"` explicitly.
2. *Compute total bytes on disk and sort by storage size.* Rejected —
   `MediaAsset.metadata` doesn't track on-disk byte sizes today (it
   carries duration, resolution, codec, bitrate, sample rate, channels,
   nothing filesystem-derived). Duration is the proxy that matches the
   data we already have, and a 5-minute h264 clip is almost always the
   bigger KB win than a 5-second one at the same resolution. Adding a
   `byteLength: Long` field to `MediaMetadata` just for this sort would
   bloat every in-memory asset and require a back-fill strategy for the
   stored catalog; revisit when a concrete driver needs byte-accurate
   sizing (e.g. a free-space dashboard).
3. *`limit` raises on out-of-range instead of silent clamp.* Rejected —
   `list_assets` raises because its range is the agent's contract
   (pagination is an agent-driven contract). Here the agent's intent
   ("give me as many orphans as fit") is unambiguous, and failing the
   call on `limit=999_999` costs the user a retry for no diagnostic
   gain. `list_assets`'s stricter stance makes sense because `offset`
   exists there and an invalid `limit` changes pagination semantics;
   this tool has no `offset`, so there's no silent wrong answer to
   defend against.
4. *No default for `sortBy`; null preserves today's insertion-order.*
   Rejected — `list_assets` kept null=insertion because it has dozens of
   non-decluttering callers (general browsing) where changing the
   baseline would shuffle existing agent outputs. `find_unreferenced_assets`
   has one dominant use (prune orphans), so changing the baseline is a
   free improvement, not a breaking surprise. The only pre-existing test
   that covered ordering (`mixedCaseWithMultipleUnreferenced`) already
   used `.toSet()` comparison, so the new default doesn't break it.
5. *Expose `totalBytes` or `percentageOfCatalog` on the orphan summary.*
   Rejected for scope — keeps the decision focused on ordering + cap.
   Those fields would be useful context but require the byte-size
   tracking rejected in (2); revisit together.

**Coverage.** `FindUnreferencedAssetsToolTest` keeps all existing tests
(`emptyCatalogReturnsEmptyList`, `allAssetsReferencedByClipsReturnsEmptyList`,
`oneAssetUnreferencedSurfacesInList`, `lockfileOnlyReferenceCountsAsReferenced`,
`filterAssetReferenceCountsAsReferenced`, `mixedCaseWithMultipleUnreferenced`,
`missingProjectThrows`) and gains nine new ones:
`defaultSortIsDurationDesc`, `sortByDurationAscReverses`,
`sortByIdIsAlphanumeric`, `sortByInvalidFailsLoudly` (asserts the error
message lists all three accepted values), `limitCapsResponse` (confirms
`unreferencedCount` stays at the pre-limit total while `returnedCount`
reports the slice), `limitClampedToMax` (silent 999_999 → 500 clamp),
`limitAtZeroClampsToOne`, `sortComposesWithLimit` (top-2 of
duration-desc on a 5-orphan fixture), and `referencedAssetsStillExcluded`
(sanity iteration over every `sortBy` value confirming referenced assets
never leak through — referenced fixtures have the *longest* durations, so
a bug in the sort would surface them immediately in `duration-desc`).

**Registration.** no-op — `find_unreferenced_assets` is already wired in
every AppContainer (cli, desktop, server, android, ios).
