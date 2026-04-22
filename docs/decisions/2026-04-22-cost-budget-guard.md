## 2026-04-22 — Per-session AIGC spend cap enforced via permission ASK (VISION §5.2 rubric)

Commit: `dd7b3b6`

**Context.** `session_query(select=spend)` already aggregates per-session
AIGC cost by filtering `Project.lockfile.entries` on `sessionId` and
summing `costCents`. The user has full **observability** of what's been
spent, but zero **control**: a long-running vlog-loop session will burn
through the OpenAI / Replicate account until the user notices in the UI
and cancels. VISION §5.2 ("新效果接入成本"—the rubric also covers cost
guardrails) has no gate mechanism today.

Backlog bullet sketched two shapes: `set_session_spend_cap(cents)` + an
agent-loop pre-flight check. The scope of "pre-flight" matters — adding
a middleware layer to the agent dispatch path is invasive; putting the
check inside each AIGC tool lands the guard closer to the provider call
so the loaded cap is authoritative.

**Decision.** Four-piece wiring, all in `core/commonMain`, with only one
LLM-visible new tool:

1. **`Session.spendCapCents: Long? = null`** — three-state field (null = no
   cap; 0 = "spend nothing"; positive Long = cents cap). Defaulted so
   legacy sessions deserialize unchanged.
2. **`SetSessionSpendCapTool` (`set_session_spend_cap`)** — upsert tool
   that flips the field. Single tool, no Define/Update pair. Rejects
   negative caps with a dollars-vs-cents hint (the most common mistake
   shape).
3. **`AigcBudgetGuard.enforce(toolId, projectStore, projectId, ctx)`** —
   helper called first thing in each of the 5 AIGC tools'
   `execute()`. Computes cumulative session spend from
   `project.lockfile.entries` filtered by `sessionId`, compares to
   `ctx.spendCapCents`, raises an `aigc.budget` permission ASK when
   cumulative ≥ cap. Reject → throw (clean error path); Once / Always
   → proceed. Silent pass-through arms: null cap, null projectId,
   missing project row, legacy entries with null costCents.
4. **`ToolContext.spendCapCents: Long? = null`** snapshot populated by
   `AgentTurnExecutor` from the session record read per-turn (alongside
   `currentProjectId`). Plumbed via `Agent.runLoop` →
   `executor.streamTurn(…, spendCapCents)` → `dispatchTool(…, spendCapCents)`
   → `ToolContext(…, spendCapCents)`. One session read per turn, reused
   across every tool dispatch in that turn.
5. **`aigc.budget` ASK rule** added to `DefaultPermissionRuleset` so the
   default UX is "confirm each time", matching `aigc.generate`.

LLM sees **one** new tool (`set_session_spend_cap`, ~160 token spec) and
one new permission scope. Cap enforcement is invisible to the LLM
surface — it's internal plumbing until the moment the cap fires, at
which point the existing permission UI kicks in.

**Alternatives considered.**

1. **Agent-dispatch-layer middleware** — put the cap check in
   `AgentTurnExecutor.dispatchTool()` before invoking any tool with
   `permission.permission == "aigc.generate"`. Rejected: the executor
   doesn't know which tools are cost-bearing beyond permission tags,
   and `aigc.generate` is also the scope for free-tier calls (seed-
   locked local models) that shouldn't hit the gate. The guard belongs
   close to the provider, so each AIGC tool opts in explicitly at
   `execute()` start. Six call sites (the 5 AIGC tools) is a tiny
   surface, and adding a 6th paid AIGC tool requires one line.

2. **Hard-reject when over cap (no permission ASK)** — deterministic but
   frustrating: user who set a \$5 cap and wants to spend \$6 on this
   one hero shot has to `set_session_spend_cap(600)`, re-run the tool,
   then potentially `set_session_spend_cap(500)` again. The permission
   flow already supports Once / Always / Reject; using it means
   "continue for this one" is a one-click operation and "Always"
   persists an override rule for the rest of the session.

3. **Preemptive estimate-based gate (cumulative + estimated next call
   ≥ cap)** — would require `AigcPricing.estimateCents` callable
   without `GenerationProvenance`, which pricing is not currently
   structured for (it reads modelVersion / parameters from the
   provenance). Adding estimate-before-call would be a pricing refactor
   in its own right. Today's gate fires when cumulative **already**
   reaches cap, so the user can overshoot by at most one call.
   Documented behaviour.

4. **Event-bus signal instead of permission ASK** — publish a `BusEvent`
   and have the UI decide. Rejected: that's the same permission-flow
   shape minus the built-in Once/Always/Reject plumbing. The permission
   service already integrates with CLI / desktop / server UIs via
   `BusEvent.PermissionAsked`; piggybacking is strictly cheaper than a
   parallel decision lane.

5. **Per-tool caps (`set_tool_spend_cap(toolId, cents)`)** — considered
   and deferred. The current use case is holistic ("stop spending on
   this session"), not per-model tuning. Can be added later as a map
   field without breaking session-level caps.

**Coverage.** New `AigcBudgetGuardTest` (10 cases) and
`SetSessionSpendCapToolTest` (6 cases) — §3a rule 9 coverage is
semantic-surface-complete: null-cap no-op, under-cap no-op, at-cap
boundary ASK, over-cap ASK, Reject throws, Once proceeds, Always
proceeds, cross-session entries don't count, null-cost entries ignored,
missing project silent-pass, null projectId silent-pass, zero-cap blocks
even with zero spend, no-op-when-unchanged (no updatedAt churn),
negative-cap rejected, missing-session errors cleanly. Every existing
AIGC tool test (`GenerateImageToolTest`, `GenerateVideoToolTest`,
`GenerateMusicToolTest`, `UpscaleAssetToolTest`,
`SynthesizeSpeechToolTest`, `CompareAigcCandidatesToolTest`,
`ReplayLockfileToolTest`) continues to pass — null cap default preserves
baseline.

**Registration.** One new tool wired in all 5 AppContainers:

- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift`

Tool count net growth: +1 in `core/tool/builtin/session/` (15 → 16). §3a
rule 1 threshold is +2; +1 is under the bar. The new helper
`AigcBudgetGuard` is not a tool (no registration).

LLM context cost (§3a rule 10): one new tool spec (~160 tokens). Below
500-token threshold. `aigc.budget` permission scope adds zero token cost
(permission descriptors are not in the tool-spec bundle).

---
