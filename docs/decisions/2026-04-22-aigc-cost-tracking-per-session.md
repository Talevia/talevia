## 2026-04-22 — AIGC cost tracking per session and per project (VISION §5.2 / §5.4 rubric)

Commit: `8964878`

**Context.** `estimate_session_tokens` covers chat-turn token accounting and
`agent.retry.*` counters expose retry cardinality, but the "random compiler"
spend lane — image / music / TTS / video / upscale dollar cost — was
unobservable. Users running a vlog had no way to answer "how much did that
cost?" and operators had no per-project spend rollup. The gap blocked the
VISION §5.2 "ops / budget" story and §5.4 "expert wants a spend breakdown".

Backlog bullet (`docs/BACKLOG.md` P0): `aigc-cost-tracking-per-session`.

**Decision.**

1. **Lockfile entry persists cost at record time.** `LockfileEntry` grows two
   nullable fields — `costCents: Long?` and `sessionId: String?` — both
   defaulted to `null` for forward-compat with pre-existing lockfile blobs.
   `costCents == null` is a load-bearing "unknown pricing" signal distinct from
   `0L`; spend aggregations do **not** coalesce null to 0. Extended
   `AigcPipeline.record(...)` with matching optional params.

2. **`core.cost.AigcPricing` — best-effort pricing table.** Pure-function object
   mapping `(toolId, provenance, baseInputs) → Long?`. Covers the current
   integrations: OpenAI image (`gpt-image-1`, `dall-e-3`, `dall-e-2`), OpenAI
   TTS (`tts-1` / `tts-1-hd` / `gpt-4o-mini-tts` by character count), Sora video
   (flat per-second tier), Replicate musicgen (per-second approximation),
   Replicate real-esrgan upscale (flat ~$0.05). Returns `null` for anything
   else — deliberately refuses to guess a price to keep the "unknown" bucket
   honest.

3. **New `BusEvent.AigcCostRecorded(sessionId, projectId, toolId, assetId,
   costCents?)`**, emitted by every AIGC tool on a cache **miss** (hits reuse
   an already-billed asset). Fed through a new `publishEvent` callback added to
   `ToolContext` — default no-op, production dispatch from `AgentTurnExecutor`
   plumbs the real bus so metrics + SSE subscribers see the signal without
   bespoke per-tool wiring.

4. **Metrics sink rolls cents into `aigc.cost.cents` and
   `aigc.cost.<toolId>.cents`** counters (plus `aigc.cost.unknown` for null
   costs). Server `eventName` + `BusEventDto.from` grow an `aigc.cost.recorded`
   wire type with `toolId / assetId / costCents` fields for SSE clients.

5. **Two new query selects** (no new tools — both ride the consolidated query
   primitives already in the LLM context):
   - `project_query(select=spend)` → single `SpendSummaryRow` with
     `totalCostCents`, `entryCount`, `knownCostEntries`, `unknownCostEntries`,
     `byTool`, `bySession`, `unknownByTool`.
   - `session_query(select=spend, sessionId=X)` → `SpendSummaryRow` with
     `totalCostCents` / `byTool` / `unknownByTool` / `projectResolved`.
     Scope-limited to the session's currently-bound project (documented).
     `SessionQueryTool` grew an optional `projects: ProjectStore?` constructor
     param; all 5 AppContainers (CLI / Desktop / Server / Android / iOS) wire
     it through.

**Alternatives considered.**

- **A new top-level `get_spend` tool** — rejected by §3a rule 1 (don't net-grow
  the tool count). `spend` as a select on the existing `project_query` /
  `session_query` reuses an already-paid-for tool spec in the LLM's context
  window. Incremental context cost: ~70 tokens for the two help-text lines.
- **Coalesce `null` cost to `0`** — rejected. A model that silently reads $0
  for every untracked provider misrepresents spend as cheaper than it is.
  Keeping null as a separate signal (`unknownCostEntries`, `aigc.cost.unknown`
  counter) preserves honesty about coverage gaps at the expense of one extra
  field in the query row — a trade-off aligned with §3a rule 4 (don't make
  binary what is genuinely ternary).
- **Pricing as a per-provider registered config** — rejected for now. A config
  surface means runtime tuning, staleness detection, change log. A hard-coded
  table in one file means a one-line PR when OpenAI reprices and `git blame`
  is the audit trail. Re-evaluate when we have more than 3 providers with live
  pricing.
- **Derive session spend by walking message → tool_call → project lockfile
  without a sessionId stamp** — possible but expensive and brittle (each
  lookup walks all messages × all lockfile entries). Stamping `sessionId` on
  the entry at record time is the O(1) anchor for later rollup.
- **Cross-project session spend rollup** — deferred. Scope-limiting to the
  session's current project keeps this cycle bounded. Follow-up is a
  `select=spend_all_projects` or a `projectId` override on `select=spend`.

**Coverage.**

- `core.cost.AigcPricingTest` — 11 tests pinning the three-state shape (known
  price, unknown → null, wrong-type field → null), verifying OpenAI image /
  TTS / Sora and Replicate musicgen / upscale price points.
- `tool.builtin.project.ProjectQuerySpendTest` — 3 tests covering known+unknown
  aggregation, empty lockfile, and the 0L-vs-null distinction (free tier
  counts as known-zero, not unknown).
- `tool.builtin.session.SessionQuerySpendTest` — 4 tests: stamped-session
  filter, empty result, missing project store (`projectResolved=false`),
  missing sessionId input rejected.
- `domain.lockfile.LockfileTest` — added forward-compat cases: pre-cost blobs
  deserialize to null, new fields round-trip.

**Registration.** `SessionQueryTool` constructor grew an optional `projects`
param wired in CliContainer / desktop AppContainer / ServerContainer /
AndroidAppContainer / iOS `AppContainer.swift`. No new tools registered. Metrics
sink and `BusEventDto.from` updated once in core + once in the server wire DTO;
agent's `ToolContext` construction in `AgentTurnExecutor` plumbs the bus
through a new `publishEvent` callback.

---
