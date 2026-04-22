## 2026-04-22 — Extract ProjectQueryTool's JSON schema to a sibling file (debt; VISION §3.2 hygiene)

Commit: `96bca96`

**Context.** `ProjectQueryTool.kt` hit 800 lines after 13 consolidations
piled new `select`s onto the dispatcher. R.5 debt scan's "long files"
threshold (`≥ 800` forced P0 / P1) caught it in this cycle's repopulate,
and it was the P0 top of the fresh backlog. The shape that bulked it up
most was the per-field `putJsonObject { … }` JSON-schema block — ~170
lines of boilerplate that has nothing to do with routing, just describes
each Input field to the LLM.

The `2026-04-22 debt-fold-list-project-snapshots` cycle just added
`SELECT_SNAPSHOTS`, which alone contributed ~15 schema lines. The curve
is visible.

**Decision.**

1. **New sibling file `ProjectQueryToolSchema.kt`** hosting a single
   top-level `internal val PROJECT_QUERY_INPUT_SCHEMA: JsonObject`. All
   per-field `putJsonObject(...)` blocks moved verbatim; field
   descriptions unchanged so the LLM-visible schema is byte-identical.
2. **`ProjectQueryTool.inputSchema`** now a one-line delegation:
   `override val inputSchema: JsonObject = PROJECT_QUERY_INPUT_SCHEMA`.
3. **Imports pruned** — `buildJsonObject`, `put`, `putJsonObject`,
   `JsonPrimitive` no longer referenced from the dispatcher file, only
   from the schema sibling.

File size drops 800 → 638 lines (−162 lines). Not below the 500-line
P1 threshold yet — row data classes (lines 176–467 ≈ 290 lines) are
still the next-biggest block — but out of the R.5 "forced P0" zone.
The `debt-split-session-query-tool` bullet queued in this cycle's
repopulate covers the same consolidation for the 518-line
`SessionQueryTool.kt`; the row-class extraction for both is a
follow-up whose scope exceeds one cycle (row classes are
publicly-referenced by downstream decoders via
`ProjectQueryTool.TrackRow.serializer()` etc., so moving them to
top-level requires `typealias` or Swift-shim gymnastics that deserve
their own decision doc).

**Alternatives considered.**

1. **Extract row classes too (to `ProjectQueryRows.kt` as top-level
   types)** — rejected this round. External callers decode via
   `ProjectQueryTool.TrackRow.serializer()`, so breaking-out-of-class
   would cascade through tests + SKIE Swift bindings + doc prose.
   Doable but a dedicated cycle; documented in the P1 bullet
   `debt-split-project-query-tool` follow-up wording.
2. **Extract helpText alongside schema** — rejected. helpText is a
   single-string constant (~40 lines) that reads naturally next to
   the select-enum constants in the companion; splitting it gains
   little and adds a cross-reference cost.
3. **Rewrite the schema as a data-driven `Map<field, description>`
   + a render pass** — rejected as premature. The verbose
   `putJsonObject` is explicit and greppable; a builder DSL would
   save ~40 lines at the cost of indirection that every future
   select-writer has to learn.
4. **Leave it alone; long files aren't always bad** — rejected per
   R.5's hard rule ("`≥ 800` 强制 P0 / P1"). The scan's whole
   purpose is to arrest accumulation before a refactor becomes a
   weekend project.

**Coverage.** Pure mechanical extraction — every
`ProjectQueryToolTest` + `ProjectQuerySpendTest` +
`ProjectQueryLockfileFiltersTest` + `ProjectSnapshotToolsTest`
continues to pass unchanged. System prompt verification
(`TaleviaSystemPromptTest`) unaffected. iOS Kotlin/Native compile,
Android debug assemble, desktop assemble, ktlint all green.

**Registration.** No registration churn — the same `ProjectQueryTool`
is registered in all 5 AppContainers with the same constructor
signature. The extraction is file-level only.

---
