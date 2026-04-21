## 2026-04-19 — Clip → Source binding on the `Clip` sealed class

**Context.** Incremental compilation needs to know "which source nodes does this clip
depend on?". We could model that as a side-table keyed by `ClipId`, or as a field on
`Clip` itself.

**Decision.** Add `sourceBinding: Set<SourceNodeId> = emptySet()` as an abstract field
on `Clip`, defaulted on every variant. Empty set means "unbound" — the clip opts out of
incremental compilation and will always be treated as stale. AIGC tools that produce
clips populate this with the ids whose contentHash went into their prompt.

**Alternatives considered.**
- **Side-table on `Project`.** Rejected: drifts out of sync with clips when clips move
  between tracks or get split. Keeping the binding on the clip makes it travel with
  the clip through all mutations for free.
- **Optional `SourceNodeId?` (single binding).** Rejected: a clip can depend on
  multiple nodes (e.g., AIGC call conditioned on `character_ref` + `style_bible` +
  `edit_intent`). Set is the right shape.

**Why "empty = always stale" and not "empty = never stale"?** Pre-DAG clips from legacy
workflows should not silently become cache-fresh just because nobody bound them. The
conservative default is "re-render unless we can prove otherwise." Tools opt into
caching by declaring their inputs.

---
