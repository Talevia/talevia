## 2026-04-22 — Extract SessionQueryTool's JSON schema to a sibling file (debt; VISION §3.2 hygiene)

Commit: `67e8cfc`

**Context.** `SessionQueryTool.kt` sat at 518 lines — inside the R.5
debt-scan 500–800 "default P1" band. The dispatcher + 10 `select`
branches + 10 row data classes + a big inline JSON-schema block piled
up to the point where every `select` addition made the file materially
longer. The schema block alone (`inputSchema: JsonObject = buildJsonObject { … }`)
was ~75 lines of boilerplate describing each Input field to the LLM;
none of that belongs in the dispatcher file.

This is exactly the move already made on
`ProjectQueryTool.kt` in cycle 13 (see
`docs/decisions/2026-04-22-debt-split-project-query-tool.md`). The
backlog bullet explicitly pointed at that precedent.

**Decision.**

1. **New sibling file
   `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/SessionQueryToolSchema.kt`**
   hosting a single top-level `internal val SESSION_QUERY_INPUT_SCHEMA: JsonObject`.
   All per-field `putJsonObject { … }` blocks moved verbatim; field
   descriptions unchanged so the LLM-visible schema is byte-identical
   (zero-semantic-change refactor).
2. **`SessionQueryTool.inputSchema`** becomes a one-line delegation:
   `override val inputSchema: JsonObject = SESSION_QUERY_INPUT_SCHEMA`.
3. **Imports pruned** from the dispatcher — `buildJsonObject`, `put`,
   `putJsonObject`, `JsonPrimitive` are no longer referenced there.
   `JsonArray` stays because `Output.rows: JsonArray` is still a
   first-class Output field; `JsonObject` stays because
   `inputSchema: JsonObject` is the override's type.

File size: **518 → 439 lines (−79)**. Below the 500-line R.5 debt
threshold. The row data classes (SessionRow / MessageRow / PartRow /
… ≈ 160 lines) are the next-biggest block but, like ProjectQueryTool's
row classes, are publicly referenced by tests + UI decoders via
`SessionQueryTool.SessionRow.serializer()` — extracting them to
top-level requires `typealias` or Swift-shim gymnastics that deserves
its own decision doc. Mirrors the ProjectQueryTool precedent exactly.

**Alternatives considered.**

1. **Extract row classes too** (to `SessionQueryRows.kt` as top-level
   types) — rejected this round for the same reason as
   ProjectQueryTool's row classes: external callers already decode via
   `SessionQueryTool.SessionRow.serializer()`. Breaking that would
   cascade through tests + SKIE Swift bindings + doc prose. A dedicated
   cycle can handle both SessionQueryTool's and ProjectQueryTool's row
   extraction together with a shared migration story.

2. **Extract the `rejectIncompatibleFilters` helper too** — considered,
   rejected. It's ~35 lines, closely-coupled to the select/Input
   combinations, and reads naturally beside `execute()`. Splitting it
   out would cost a cross-file jump for something every reader of the
   dispatcher wants to see right below the routing. The 500-line debt
   threshold is hit without it.

3. **Rewrite as a data-driven schema (Map<field, description> + render
   pass)** — rejected as premature, matching the ProjectQueryTool
   decision rationale. Verbose `putJsonObject` is explicit and
   greppable; a builder DSL would save ~40 lines at the cost of
   indirection every future `select`-writer has to learn.

**Coverage.** Pure mechanical extraction — the Kotlin compiler is the
primary oracle (a byte-change in the schema would change the typed
Output shape consumers depend on). Every existing `SessionQueryTool`
test (`SessionQueryToolTest`, `SessionQuerySpendTest`,
`SessionQueryStatusTest`, etc.) continues to pass unchanged. System
prompt verification (`TaleviaSystemPromptTest`) unaffected — it
asserts on tool id + presence, not on schema internals. iOS Kotlin/
Native compile, Android debug assemble, desktop assemble, ktlint all
green.

**Registration.** No registration churn — the same `SessionQueryTool`
is registered in all 5 AppContainers (CLI / Desktop / Server /
Android / iOS) with the same constructor signature. The extraction is
file-level only.

---
