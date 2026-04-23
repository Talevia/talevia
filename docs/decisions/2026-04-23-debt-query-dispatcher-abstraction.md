## 2026-04-23 — QueryDispatcher base class + top-level row convention for the 4 unified-query tools (VISION §5.6)

**Context.** `ProjectQueryTool` / `SessionQueryTool` / `SourceQueryTool` /
`ProviderQueryTool` all share the same shape — `(select, filter, sort,
limit)`-keyed dispatcher over an `Output.rows: JsonArray` — and all four
had independently reinvented:

1. `val select = input.select.trim().lowercase(); if (select !in ALL_SELECTS) error(...)`
   — the same 4-line block opening `execute()`.
2. 79 test call sites writing
   `JsonConfig.default.decodeFromJsonElement(ListSerializer(XRow.serializer()), out.rows)`
   — a 3-line helper duplicated across 18 test files.
3. Divergent conventions for where row data classes live: Project and
   Session already moved them to top-level `query/<Select>Query.kt`
   siblings after successive long-file resplits
   (`2026-04-22-debt-resplit-project-query-tool.md`,
   `2026-04-23-debt-resplit-session-query-tool.md`), but Source kept four
   nested rows on `SourceQueryTool` (`NodeRow` / `DagSummaryRow` /
   `Hotspot` / `DotRow`) and Provider kept two (`ProviderRow` /
   `ModelRow`).

Rubric delta §5.6: both previous resplits converged on the same cause —
the "rows stay nested on the dispatcher class" convention pushing files
past 500 lines as selects accumulate. Without a shared abstraction, the
next query tool (ProviderQueryTool was already the 4th) keeps paying the
same tax, and test churn compounds with every new select.

**Decision.** Introduce `io.talevia.core.tool.query.QueryDispatcher<I, O>`
as a `Tool<I, O>` abstract base that owns:

- `abstract val selects: Set<String>` — canonical lowercase select names.
- `abstract fun rowSerializerFor(select: String): KSerializer<*>` — the
  registry that lets consumers decode `Output.rows` without reaching
  into each tool's row types manually.
- `protected fun canonicalSelect(raw: String): String` — the shared
  trim-lowercase-validate helper that replaces the 4-line block each
  dispatcher used to inline.

All four query tools now extend `QueryDispatcher`:

- `ProjectQueryTool`, `SessionQueryTool` — rows were already top-level;
  migration is additive (extend base, publish `selects` +
  `rowSerializerFor`, replace inline select validation with
  `canonicalSelect(input.select)`).
- `SourceQueryTool` — `NodeRow` (shared across `nodes` / `descendants` /
  `ancestors` / `scope=all_projects`) moved to new
  `source/query/NodeRow.kt`; `Hotspot` + `DagSummaryRow` moved to
  `source/query/DagSummaryQuery.kt`; `DotRow` moved to
  `source/query/DotQuery.kt`. SourceQueryTool.kt drops from 441 → 358
  lines.
- `ProviderQueryTool` — `ProviderRow` + `runProvidersQuery` moved to new
  `provider/query/ProvidersQuery.kt`; `ModelRow` + `runModelsQuery`
  moved to new `provider/query/ModelsQuery.kt`. Brings Provider into the
  same sibling-file convention as the other three, establishing the
  pattern for any 5th query tool.

Test-kit helper `io.talevia.core.tool.query.decodeRowsAs` collapses the
3-line JsonArray decode idiom to one line:

```kotlin
val rows = out.rows.decodeRowsAs(TrackRow.serializer())
```

79 test call sites across 18 files migrated via a regex pass (no manual
rewrites — each site is structurally identical). A companion registry-
keyed variant `decodeRowsByRegistry(dispatcher, select, rows)` is
exposed for future smoke tests that want to exercise every select on a
dispatcher without importing each row type.

New `QueryDispatcherConventionTest` (commonTest) asserts:

1. Every advertised `select` has a registered row serializer (drift
   between `selects` and `rowSerializerFor` would otherwise fail inside
   a caller's decode instead of at registration time).
2. Row types are top-level — the serializer's `descriptor.serialName`
   must not contain `.<DispatcherClassName>.`. Catches "a new row
   accidentally nested on `SourceQueryTool`" the moment the test runs.
3. Unknown selects throw from `rowSerializerFor`.
4. `canonicalSelect` normalizes case and lists known selects in the
   error message.

**Axis.** Long-file signal on dispatcher classes. What would grow this
back toward the 500-line threshold: (a) a new query dispatcher that
nests its rows (guarded by `QueryDispatcherConventionTest`), (b) a
handler-map abstraction that pushes all per-select logic into a single
file per dispatcher. Explicitly rejected the handler-map path this
cycle (see Alternatives).

**Alternatives considered.**

- **Full handler-map dispatch in the base class**
  (`abstract val selectHandlers: Map<String, suspend (I, ToolContext) -> ToolResult<O>>`).
  Would let the base subsume `execute()` entirely, saving the `when
  (select)` branch in each tool. Rejected: per-tool prefix logic
  (`ctx.resolveProjectId(input.projectId)`, limit/offset clamping,
  agent-state / provider / registry lookups) varies enough that every
  lambda would re-do the prefix, or the base would need a
  per-tool-context hook. Saves ~10 lines per tool at a cost of ~30 lines
  of new abstraction surface — and the `when` branch is ~20 lines in the
  largest tool (15 selects), well short of a long-file contributor. The
  bullet explicitly names this shape, but the evidence from the two
  prior resplits was about **row types being nested**, not about the
  dispatch branch. Revisit if a 5th query tool shows the `when` branch
  itself is a problem.

- **Top-level `decodeRowsAs` helper without a registry interface.** The
  3-line → 1-line collapse is the bulk of the test-ergonomic win — a
  free function accepting `(JsonArray, KSerializer)` would suffice for
  that half. Rejected in favour of adding `rowSerializerFor` on the
  dispatcher because publishing the registry enables
  `QueryDispatcherConventionTest`'s "every select has a serializer"
  check AND a future CLI `--json` formatter / IDE preview that wants to
  decode `Output.rows` without importing each concrete row type.
  Registry is cheap (one `when` per tool) and pays back in convention
  enforcement.

- **Type-safe reified `rowSerializerFor<T>(select)`.** A reified API
  forces callers to carry the row type at the call site, defeating the
  registry's purpose (the whole point is "given a select string, give
  me the serializer"). The existential `KSerializer<*>` is the right
  shape; callers who know the concrete row use `decodeRowsAs(T.serializer())`
  directly for compile-time safety.

- **Migrate tests via manual Edits instead of regex.** 79 sites across
  18 files, all structurally identical
  (`JsonConfig.default.decodeFromJsonElement(ListSerializer(X.serializer()), Y)`
  → `Y.decodeRowsAs(X.serializer())`). Mechanical transformation; the
  regex pass + compile-driven verification is faster than 79 Edits and
  the test compile + green run certifies correctness.

- **Move Provider rows without creating a `provider/query/`
  subpackage.** Could move `ProviderRow` / `ModelRow` to
  `provider/ProviderRow.kt` / `provider/ModelRow.kt` alongside the tool
  instead of a new subpackage. Rejected for symmetry — the other three
  tools already use `<area>/query/` for row types + handlers, and
  establishing the same shape for Provider means any 5th query tool
  lands in a predictable spot. The two new files are small (< 80 lines
  each) and co-locate each row with its handler.

**Coverage.** `./gradlew :core:jvmTest` green (includes the 4 query-
tool test suites plus `QueryDispatcherConventionTest`). `:apps:server:test`
+ `:apps:cli:test` green. Cross-platform: `:core:compileKotlinIosSimulatorArm64`
+ `:apps:android:assembleDebug` green. `ktlintCheck` green.

**Wire format.** Unchanged. Rows aren't polymorphic on the wire (no
sealed discriminator, no `@Polymorphic`); moving a concrete
`@Serializable data class` across packages keeps the emitted JSON
identical. `QueryDispatcherConventionTest.rowTypesAreTopLevel` pins the
class-location invariant but doesn't touch the wire shape.

**Registration.** No tool registration change — the 4 query tools keep
their ids (`project_query` / `session_query` / `source_query` /
`provider_query`); every `AppContainer` still registers them by
constructor. Row-type relocations are source-only; callers that import
`NodeRow` etc. by package (post-migration tests) are the only consumers.
