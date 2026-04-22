## 2026-04-22 — source_query adds `select=dot` for whole-graph visualisation

Commit: `(pending)`

**Context.** Backlog bullet `source-graph-export-dot`: the Source DAG was
inspectable (`source_query select=nodes`) and summarisable (`select=dag_summary`),
but there was no single-glance view. Expert-path debugging ("why isn't this
`character_ref` flowing to that clip?") forced grep + mental merge of
`parentIds` across rows. The bullet proposed either a new
`export_source_graph_dot(projectId) -> String` tool OR an extension of
`source_query(select=dot)`.

**Decision.** Extend `SourceQueryTool` with `select=dot`. No new tool.kt, no
new `AppContainer` registration, no new schema surface — just one new row
type (`SourceQueryTool.DotRow`) and a sibling `query/DotQuery.kt` runner
alongside the existing `NodesQuery` / `DagSummaryQuery` / `NodesAllProjectsQuery`.

The row payload is `{dot: String, nodeCount: Int, edgeCount: Int}`. Core
emits the Graphviz DOT text; rendering happens outside Core via
`dot -Tsvg` (or any Graphviz front-end). No KMP dependency on Graphviz —
we're a text generator.

**§3a structural check.**

| # | Check | Result |
|---|---|---|
| 1 | Net tool count growth | **+0.** `source_query` already exists; we add a `select` value, not a tool. A sibling `export_source_graph_dot` tool would have violated "no net tool growth without removing ≥1 equivalent". |
| 2 | LLM context cost | Added ≈90 tokens to `source_query.helpText` + one string in the schema enum description. `ALL_SELECTS` grows from 2 → 3 values. A new tool would have added ≈180 tokens (own helpText + Input/Output schema + two `ALL_*` constants in a new companion). |
| 3 | AppContainer churn | Zero. `SourceQueryTool` is already registered on all 5 platforms (desktop, CLI, server, iOS, android). |
| 4 | Default-behavior divergence | None — `select=dot` is a new discriminator value, existing callers unaffected. |
| 5 | Output-shape divergence vs. siblings | `DotRow` has 3 fields, `DagSummaryRow` has 8. Different shape, same envelope (`Output.rows: JsonArray`). Consumers branch on `select` and decode the matching row serializer — same pattern as today. |
| 6 | Dependency asymmetry | None — `DotQuery` only needs `Project`, same as `DagSummaryQuery`. |
| 7 | Kotlin idiom | `internal fun runDotQuery(project: Project): ToolResult<...>` — matches existing dispatch runners. |
| 8 | Platform sync | KMP-compatible; no platform APIs used. |
| 9 | VISION alignment | §5.1 "source 层可用" — improves expert-path observability. §5.4 "专家能接管" — gives power users a mechanical handle on the DAG that's not just a JSON dump. |
| 10 | Failure mode visibility | Filter misuse + `scope=all_projects` + `dot` combinations fail loud in `rejectIncompatibleFilters` (tested). |

**Alternatives considered.**

1. **New `export_source_graph_dot` tool** — rejected per §3a Rule 1
   (net tool growth). The backlog bullet allowed either shape; the
   extension path is mechanically cheaper (no new registration, no new
   helpText doubling context cost) and semantically tighter (DOT is a
   projection of the Source DAG — living next to `nodes` and
   `dag_summary` under one query tool is the right mental model).

2. **Render to SVG inside Core** — rejected. Would force a KMP-
   compatible Graphviz binding (none exists today), and the expert
   path already has `dot` on PATH. Core stays a pure string generator.

3. **Per-clip binding annotations in DOT** — deferred. Today nodes
   with no downstream clip binding render `style=dashed, color=gray50`
   so orphans are visible at a glance. Richer annotation (edge labels
   carrying transitive-clip counts, hotspot highlighting) is an
   easy follow-up extension to `buildDot` when a concrete driver asks.

4. **Accept a `rootNodeId` subgraph filter** — deferred. `select=nodes`
   already answers "show me this node + its ancestry" via `id` +
   manual follow-up; the whole-DAG view is what the backlog bullet
   was asking for. Adding filter fields would have required another
   round of `rejectIncompatibleFilters` branches; kept the surface
   minimal.

**Shape of the output.** For a DAG with `style-warm → mei`,
`style-warm → lily`:

```
digraph SourceDAG {
  rankdir=LR;
  node [shape=box, fontname="Helvetica"];
  "lily" [label="lily\ncore.consistency.character_ref", style=dashed, color=gray50];
  "mei" [label="mei\ncore.consistency.character_ref", style=dashed, color=gray50];
  "style-warm" [label="style-warm\ncore.consistency.style_bible", style=dashed, color=gray50];
  "style-warm" -> "lily";
  "style-warm" -> "mei";
}
```

Nodes sorted by id for deterministic output. Edge direction is
parent → child so `dot -Tsvg` renders upstream → downstream, matching
the data-flow intuition a reader brings.

**Impact.**

- `core/src/commonMain/.../source/SourceQueryTool.kt`: +1 `select`
  constant, +1 nested `DotRow` class, +1 helpText branch, +1 dispatch
  arm, +1 import. `Input` shape unchanged.
- `core/src/commonMain/.../source/query/DotQuery.kt`: new, 93 lines.
  `runDotQuery` + private `buildDot` + two private quote helpers.
- `core/src/jvmTest/.../source/SourceQueryDotTest.kt`: new, 8 tests
  (empty DAG, single node, diamond DAG edges, orphan dashed style,
  id with embedded quote/backslash, filter rejection, scope
  rejection, sourceRevision echo).
- `:core:jvmTest` green. `:core:ktlintCheck` green. No other modules
  touched; existing test suite unchanged.
- Backlog bullet `source-graph-export-dot` removed.

**Follow-ups.** None required. If a concrete driver asks for
subgraph rooted at a node id, or for edge-label clip counts, the
extension point is `buildDot(project)` in `DotQuery.kt` — no API
break needed because `DotRow` can gain optional fields.
