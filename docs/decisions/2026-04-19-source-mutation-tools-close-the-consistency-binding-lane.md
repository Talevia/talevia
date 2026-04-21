## 2026-04-19 — Source-mutation tools close the consistency-binding lane

**Context.** VISION §3.3 + §5.5: the system prompt told the agent to pass
`character_ref` ids in `consistencyBindingIds`, but no tool existed to *create*
those ids. The agent could read the system prompt, plan to apply consistency,
and then have no way to actually write a node — a dormant lane.

**Decision.** Add five tools under `core/tool/builtin/source/`:

- `define_character_ref` — creates/replaces a `core.consistency.character_ref` node.
- `define_style_bible` — creates/replaces `core.consistency.style_bible`.
- `define_brand_palette` — creates/replaces `core.consistency.brand_palette`,
  validates `#RRGGBB` and normalises to uppercase.
- `list_source_nodes` — read-only query, filters by `kind` / `kindPrefix`,
  returns id/kind/revision/contentHash/parents + a kind-aware human summary.
  Optional `includeBody` for full JSON.
- `remove_source_node` — deletes a node by id; doesn't cascade because
  `staleClips()` already treats vanished bindings as always-stale.

All three definers are **idempotent on `nodeId`**: same id + same kind →
`replaceNode` (preserves id, bumps revision, recomputes contentHash). Same id
but *different* kind → `IllegalArgumentException` (loud failure beats silent
shape mismatch). Default `nodeId` is a slugged variant of `name`
(`character-mei`, `style-cinematic-warm`, `brand-acme`) so the LLM rarely needs
to invent ids.

**Why in `core`, not per-app.** The tools mutate `Project.source` via
`ProjectStore.mutateSource(...)` — pure local state, zero I/O, no platform
dependencies. They belong next to the source schema they manipulate. Each
container (`ServerContainer`, `AppContainer`, `AndroidAppContainer`,
iOS `AppContainer.swift`) registers them at composition.

**Why `source.read` / `source.write` default to ALLOW.** Unlike AIGC (external
cost) or media export (filesystem write), source mutations are local-only state
on `Project.source`. Asking the user to confirm every character_ref creation
would be hostile. Apps that want stricter policy can override the rule.

**Why a separate slug helper, not delegate to `nodeId` strings.** The slug
shape (`{prefix}-{a-z0-9-only}`) needs to be consistent across all three
definers, so `SourceIdSlug.kt` owns the rule. Co-locating it with the tools
keeps the surface tight.

**ServerSmokeTest flake.** `submitMessageUsesRequestedProviderInsteadOfDefault`
asserted `openai.requests.size == 1`, but `SessionTitler` runs through the
*same* provider on first turn — adding new tool registrations slowed
`ServerContainer` construction enough to amplify the race. Relaxed to
`>=1` plus a check that the *first* request's `providerId` matches. The point
of the test is "default provider didn't see this," which still holds.

**When to revise.** When a fourth consistency kind lands (e.g. location-bible,
prop-ref), add a sibling definer + extend `ListSourceNodesTool.humanSummary`.
When source nodes need cross-references (one node parents another via
`parentIds`), the definers will need a `parentIds` input — not added now
because no current consistency kind requires it.
