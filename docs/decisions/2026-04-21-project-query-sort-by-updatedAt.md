## 2026-04-21 — project_query sortBy="recent" backed by store-level recency stamping (VISION §5.2 rubric)

Commit: `50b7b8a`

**Context.** `project_query(select=tracks | timeline_clips | assets)` had no
"most-recently-mutated first" ordering — sortBy was always domain-specific
(index / clipCount / span / startSeconds / duration / id). Agent orientation
calls ("what did I just touch?") had to infer recency from the Project-level
`updatedAt` (too coarse — same stamp for every entity) or re-read the whole
blob and diff manually. The backlog bullet
(`project-query-sort-by-updatedAt`, P2) called for per-entity
`updatedAtEpochMs` + `sortBy="recent"`. It sat skipped for five cycles
because the obvious implementation — stamp inside each of ~14 mutation tools
— would violate §3a #9 (test-surface explosion) and §3a #1 (net-zero tool
count is fine, but touching 14 implementations fights the "make the minimum
change" principle).

**Decision.** Central stamping in `SqlDelightProjectStore.upsert` via a
structural diff against the prior blob. Four moving parts:

1. `Clip`, `Track`, `MediaAsset` each gain `abstract val updatedAtEpochMs:
   Long? = null` (nullable default preserves backward compat with
   pre-recency blobs — §3a #7).
2. `SqlDelightProjectStore.upsert` computes new stamps:
   - brand-new entity (id absent in prior blob) → `now`,
   - structurally unchanged entity (all non-recency fields equal) →
     preserve prior stamp (or `now` if prior was null / blob predated
     recency),
   - content-changed entity → `now`.
   Track stamping cascades from its clips: a track whose own fields AND
   clip-list membership are unchanged but whose individual clip content
   changed still counts as "touched". That matches how agents use
   `sortBy="recent"` — orientation for "what did I just edit?".
3. `ProjectQueryTool.TrackRow / ClipRow / AssetRow` gain nullable
   `updatedAtEpochMs`; row builders propagate the stamp.
4. `TracksQuery / TimelineClipsQuery / AssetsQuery` each accept
   `sortBy="recent"`, delegating to a shared `recentComparator` in
   `QueryHelpers.kt` that does DESC-by-stamp with nulls tailed and a
   stable per-select id tiebreaker.

No mutation tool was modified. Tools mutate via `ProjectStore.mutate(...)
{ project.copy(...) }`, which ends up calling `upsert` internally — the
stamping hook catches every write path. Zero new tools, zero new
`AppContainer` registrations.

**Alternatives considered.**

- *Per-tool stamping*: each of the ~14 mutation tools (`add_clip`,
  `set_clip_time_range`, `apply_filter`, `set_volume`, etc.) wraps its
  returned clip with `.copy(updatedAtEpochMs = now)`. Rejected — 14×
  (test + impl change) violates §3a #9 and forces every new mutation
  tool in the future to remember this bookkeeping. One forgotten site =
  silent under-stamping.
- *Treat `Project.updatedAt` as the per-entity stamp*: trivially
  cheap, zero new fields. Rejected — within a single project, every
  entity would carry the same stamp, so `sortBy="recent"` inside a
  project becomes a no-op (just reshuffles to tied order). Useless for
  the orientation use case the bullet identifies.
- *Stamp everything with `now` on every upsert, no diff*: cheapest
  implementation, loses all history. Rejected — "recent" would mean
  "ordered by position in the last write", not "recently mutated".
- *Separate recency table keyed by (projectId, entityId, kind)*:
  considered under §3a #3 (append-only data should live outside the
  blob). Rejected for now — stamps are scalar-per-entity, not
  append-only history, so inlining them on the entity is correct.
  Revisit if/when we want a full audit trail (who/what/when) rather
  than a single "latest" stamp.

Industry consensus referenced: this is the `Stat#mtime` / `DynamoDB
last_modified` pattern — one timestamp per entity, updated on
structural change, used for tail-first ordering. The diff-based
implementation mirrors how Postgres's `pg_catalog.pg_stat_all_tables`
bumps `last_seq_scan` / `last_idx_scan` only on real activity.

**Coverage.**

- `ProjectStoreRecencyStampingTest` (new) — 6 scenarios: first-upsert
  stamps everything; no-op re-upsert preserves stamps; content change
  restamps only the changed entity (t1 untouched, t2 cascaded);
  brand-new entity stamps to `now`; track membership change cascades to
  track stamp with surviving clip preserved; pre-recency null input on
  unchanged content resolves to `old.stamp ?: now` (preserves when
  available).
- `ProjectQueryToolTest.tracksSortByRecentOrdersMutatedTracksFirst` —
  fires the full tool surface, asserts sort order + stable id
  tiebreaker in the non-null tier.
- `ProjectQueryToolTest.clipsSortByRecentTailsUnstampedLegacyRows` —
  confirms tiebreaker for same-tier stamps.
- `ProjectQueryToolTest.assetsSortByRecentMixesStampedAndNulls` —
  confirms cross-tier ordering over three distinct stamp values.
- `SqlDelightProjectStoreSplitTest.getReassemblesFullProjectFromBlobAndTables`
  — updated to clear stamps before field-for-field equality, since
  upsert now stamps on write.

**Registration.** No new tools — `project_query` is already registered
in all five `AppContainer`s (CLI / Desktop / Server / Android / iOS),
and the new sortBy value flows through the same registration. Pure
schema + behavior extension.

§3a checklist pass:
- #1 zero new tools, ✓
- #2 no new Define/Update pair, ✓
- #3 scalar `Long?` per entity (not append-only — no new table needed), ✓
- #4 `Long?` not a boolean state flag, ✓
- #5 no genre-specific vocabulary, ✓
- #6 no session-binding surface added, ✓
- #7 nullable default preserves backward compat with blobs written
  before this change, ✓
- #8 no AppContainer changes (see Registration above), ✓
- #9 tests exercise the diff / stamp rule at entity granularity plus
  cascade and null-preservation edges, ✓
- #10 helpText adds ~80 tokens (one sentence + three "| recent"
  alternates across three selects). Acceptable — the whole point of
  `project_query` is being the one query primitive that keeps
  LLM-side context flat; adding a sort mode to it is much cheaper than
  shipping a new `list_recent_*` tool would be. ✓
