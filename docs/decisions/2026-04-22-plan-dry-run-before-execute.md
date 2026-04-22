## 2026-04-22 — `draft_plan` tool surfaces pre-commit plans for user review (VISION §5.4 rubric)

Commit: `44ea043`

**Context.** A long-horizon intent ("make me a 30s graduation vlog")
connects 10+ consequential tool dispatches (AIGC burn, timeline reshuffle,
file writes). Today the agent just runs them in sequence — the user sees
actions fly by but cannot intercept one before it hits a provider or
rearranges the timeline. VISION §5.4 "expert path" asks for the
"kubectl diff before kubectl apply" ergonomics.

Previous cycle skipped `per-clip-incremental-render` (P1 top) for the
second time because its 2026-04-19 deferral doc explicitly calls it a
multi-day refactor that half-built would regress correctness. That bullet
stays in the backlog for a dedicated multi-day cycle. This cycle takes the
next P1: `plan-dry-run-before-execute`.

**Decision.**

1. **Single new tool: `draft_plan`.** The backlog bullet proposed two
   tools (`plan_next_actions` + `execute_plan`) — consolidated into one
   per §3a rule 1 ("tool count doesn't net-grow"). Execution is not a
   new tool but the agent's regular tool-dispatch loop after the user
   approves; the plan is the contract the agent follows. Net +1 tool.

2. **New `Part.Plan` subtype** parallel to `Part.Todos`. Fields: `goalDescription`,
   `steps: List<PlanStep>`, `approvalStatus: PlanApprovalStatus`. Each
   `PlanStep` carries `(step, toolName, inputSummary, status, note?)`.
   The distinction from `Todos` is that steps are structured
   tool-dispatch previews, not free-text — UI renders them as a
   reviewable batch ("I'm about to run these 7 calls"), not a
   checklist.

3. **Approval state is ternary-plus**: `PENDING_APPROVAL`, `APPROVED`,
   `APPROVED_WITH_EDITS`, `REJECTED`. §3a rule 4 — we resisted a binary
   flag; `APPROVED_WITH_EDITS` is load-bearing so the UI can surface
   "this diverges from the original draft".

4. **Step status is five-valued**: `PENDING`, `IN_PROGRESS`, `COMPLETED`,
   `FAILED`, `CANCELLED`. Same rationale: `FAILED` is distinct from
   `CANCELLED` so a broken step doesn't silently disappear under
   "cancelled".

5. **Permission = `"draft_plan"` ALLOW by default**, mirroring
   `"todowrite"`. The tool is pure session-local state (no external
   side effects); gating it would defeat its purpose (it IS the confirm
   step). `DefaultPermissionRuleset` gains one line.

6. **System prompt teaches the flow** in
   `PromptEditingAndExternal.kt` (right after the todos section): when
   to choose `draft_plan` over `todowrite`, the draft→approve→dispatch
   loop, per-step status updates via re-emission. Added ~45 lines of
   guidance (~350 tokens); justified by the user-observable batch
   approval UX — without explicit guidance the model won't know to
   prefer this tool for consequential multi-call batches.

7. **Registered in all five AppContainers** (CLI / Desktop / Server /
   Android / iOS). `TokenEstimator`, `QueryHelpers`, `MessageDetailQuery`,
   `ReadPartTool`, `SqlDelightSessionStore` all grew a `Part.Plan` arm —
   compiler-enforced exhaustiveness caught every site.

**Alternatives considered.**

- **Two tools `plan_next_actions` + `execute_plan`** — what the backlog
  bullet literally proposed. Rejected on §3a rule 1 grounds. The
  "execute_plan" mechanic was really "agent dispatches tools after
  approval" — the regular agent-loop path. Splitting added LLM context
  cost (two tool specs) for no functional gain.
- **Extend `todowrite` with a `kind` field to distinguish
  free-text todos from structured plans** — polluted todowrite's
  `Input` schema with branches that behave differently. Cleaner to
  split since the structured preview lane carries extra fields
  (`toolName`, `inputSummary`) that free-text todos don't want. Each
  tool stays focused (mirrors OpenCode's `todoread` / `todowrite`
  split choice).
- **Store plan steps outside `Part` (side-table)** — rejected. Plans
  are session-scoped state with the same lifecycle as other Parts;
  they need to survive compaction, round-trip through the JSON blob,
  and participate in the bus pubsub. Minting a separate table would
  duplicate infrastructure without gain. Same call OpenCode makes for
  its `TodoTable` — ride the existing Parts mechanism.
- **Dispatch steps automatically once approved** — tempting but
  violates the "the agent is in charge of its own tool calls" model
  of every other tool. Auto-dispatching from inside a tool would also
  fight permission gating of individual steps (the user might approve
  the plan but still want per-step permission for AIGC burns). The
  current shape defers step dispatch back to the normal agent loop,
  keeping permission enforcement per-step.

**Coverage.**

- `tool.builtin.DraftPlanToolTest` — 6 tests: emitted `Part.Plan` has
  numbered steps + correct goal + default `PENDING_APPROVAL`; empty
  steps rejected with a message pointing at `todowrite`; blank goal
  rejected; blank toolName in a step pinpoints the offending step
  index; mid-run re-emission correctly counts pending steps;
  rendered LLM preview surfaces all five status markers +
  `approved_with_edits` label + per-step notes; `compactedAt`
  defaults null.
- Existing `SessionQueryTool`, `ReadPartTool`, `TokenEstimator`,
  `SqlDelightSessionStore` tests continue to pass — each got a new
  `Part.Plan` branch and the suite runs unchanged otherwise.
- Full JVM build (`:core:jvmTest :apps:server:test :apps:cli:compileKotlin
  :apps:desktop:assemble :apps:android:assembleDebug`) +
  `:core:compileKotlinIosSimulatorArm64` + `ktlintCheck` all green.

**Registration.** `DraftPlanTool` registered in CliContainer,
AppContainer (desktop), ServerContainer, AndroidAppContainer, and
iOS Swift `AppContainer.swift`. Permission ruleset gains one ALLOW
entry. System prompt grows one section on the draft-plan flow.

---
