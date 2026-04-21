## 2026-04-21 — list_project_snapshots gains maxAgeDays + limit + newest-first sort (VISION §3.4 可版本化)

Commit: `6cc2318`

**Context.** `list_project_snapshots` returned *every* saved snapshot
on a project in whatever order `ProjectStore` happened to store them.
Two concrete failure modes show up once a project has a real history:

1. **Token waste on ancient captures.** A long-running project easily
   accumulates 30+ snapshots (`before re-color`, `v1`, `v2`, daily
   autosaves, etc.). Surfacing all of them every time the agent wants
   "the most recent one" burns context on rows the user doesn't care
   about.
2. **Implicit ordering.** The tool previously called
   `sortedByDescending` on the payload before building summaries, but
   its contract ("most recent first") was only a comment — nothing
   in the schema or tests guaranteed it. Callers writing
   `snapshots[0]` were relying on a property the registration never
   committed to.

**Decision.** Add two orthogonal optional inputs and promote the sort
to a guaranteed contract:

- `maxAgeDays: Int? = null` — drop snapshots whose
  `capturedAtEpochMs` is strictly less than `now - maxAgeDays*86.4M`.
  Validated `>= 0` (`maxAgeDays=0` means "only snapshots at-or-after
  this moment"). The cutoff uses an injected `Clock` (default
  `Clock.System`) following the `SaveProjectSnapshotTool` /
  `GcLockfileTool` pattern so tests stay deterministic.
- `limit: Int? = null` — default 50, max 500, rejected otherwise.
  Applied **after** the age filter so "the 5 most recent" is a simple
  `limit=5` call regardless of how many survived the cutoff.
- Sort **descending by `capturedAtEpochMs`** is now explicit — both
  in docstring/helpText ("newest-first") and in a dedicated test
  that seeds snapshots in ascending order and asserts the tool
  returns them descending.

Execute pipeline: `filter(age) -> sortedByDescending(capturedAt) -> take(cap)`.
Keeping the sort between filter and limit is deliberate: the age
filter is cheap (one `capturedAtEpochMs` read), and sorting the
already-narrowed set is slightly cheaper when most snapshots are
ancient.

**Alternatives considered.**

1. *Keep the store's order, require callers to sort client-side.*
   Rejected — every caller wants newest-first; forcing each one to
   re-sort duplicates work and risks subtle bugs when a caller forgets
   (which is exactly how we got here).
2. *Make `limit` default to unlimited.* Rejected — the whole point of
   this change is that "return everything" is the behavior we're
   stepping away from. A default of 50 is generous (covers the recent
   month for most projects) while capping worst-case output. Callers
   who genuinely want more can pass up to 500; beyond that they're
   iterating, not auditing.
3. *Add `offset` for pagination.* Rejected for this cycle — YAGNI.
   The gap is "stop returning ancient snapshots by default," not "let
   the agent walk every page." If a real paginate-through-history
   flow surfaces we can add it without breaking this schema (new
   optional field).
4. *Use a `since: Instant` / `since: Long` input instead of
   `maxAgeDays`.* Rejected — the agent reasons in human durations
   ("last week", "past 3 days"), and an integer-days knob is both
   easier to validate and easier for the LLM to pick. An absolute
   cutoff can be rebuilt on top of this one if needed (derive
   `maxAgeDays` from `now - since`) without introducing a new field.
5. *Let `maxAgeDays` be a float.* Rejected — integer days are the
   natural unit here; sub-day windows on a snapshot history that
   updates at human timescales buy nothing and complicate JSON schema.

**Coverage.** `ProjectSnapshotToolsTest` gains five tests:
`listMaxAgeDaysDropsOlderSnapshots`,
`listMaxAgeDaysZeroKeepsOnlyNowOrFuture`,
`listLimitCapsOutputAfterNewestFirstSort`,
`listDefaultSortIsNewestFirst`,
`listNegativeMaxAgeDaysRejected`,
`listRejectsLimitOutsideRange`. Uses the existing `FixedClock` test
double; no new rig plumbing.

**Registration.** No change — `ListProjectSnapshotsTool` is already
wired in every `AppContainer`, and the new constructor param defaults
to `Clock.System` so no call-site breaks.
