## 2026-04-22 — execute_plan batches approved draft_plan steps (VISION §5.4 rubric)

Commit: `8c5145f`

**Context.** `draft_plan` captures "here's what I'm about to do" as a
typed `Part.Plan` with `(toolName, inputSummary)` per step + an
`approvalStatus` gate (`pending_approval → approved / approved_with_edits
/ rejected`). The VISION §5.4 expert path is: user approves the batch,
agent runs it end-to-end without per-step bab ysitting. Today the agent
still has to emit each tool call one at a time — post-approval, the loop
is no more automated than "no plan at all". Batch approval UX is half
done.

The lockfile was in this shape too pre-2026-04; a gap bullet's direction
solves it explicitly.

**Decision.** Three coordinated changes, concentrated in
`core/commonMain` + five AppContainers:

1. **`PlanStep.input: JsonObject? = null`** — new nullable field on the
   existing `PlanStep` data class (`core/session/Part.kt`). When
   non-null, the step carries an executable Input payload alongside its
   human-readable `inputSummary`. Default is `null` so (a) legacy plans
   keep their serialized shape (§3a rule 7), and (b) agents can still
   draft preview-only plans (steps whose inputs depend on intermediate
   results) without populating this field. `DraftPlanTool.PlanStepInput`
   + its JSON Schema updated to accept the field from the LLM.

2. **New `ExecutePlanTool(registry, sessions)`** in
   `core/tool/builtin/ExecutePlanTool.kt`. Input:
   `(planId: String, dryRun: Boolean = false)`. Behaviour:
   - Looks up the `Part.Plan` via `sessions.getPart(PartId(planId))`.
   - Rejects if the part is missing / not a Plan / not APPROVED
     (APPROVED or APPROVED_WITH_EDITS accepted).
   - Iterates steps **sequentially** — no parallelism (timeline ops have
     causal ordering requirements the agent is relying on).
   - For each step with a non-null `input`: re-emit Plan with
     `IN_PROGRESS`, `registry[toolName].dispatch(step.input, ctx)`,
     re-emit with `COMPLETED` or `FAILED`.
   - First failure halts remaining steps (marked `CANCELLED` with a
     `"halted after step N failed"` note). Plan is re-emitted once at
     the end of the loop so cancellation state is persisted even if the
     halt happened mid-loop.
   - Steps with null `input` or with a `toolName` not registered in
     this container: skip with a diagnostic note, do NOT halt (those
     are agent / container config concerns, not failure modes).
   - `dryRun=true` returns the would-be execution report without
     dispatching anything and without mutating the Plan part.
   - Already-COMPLETED steps are left alone (supports re-running a
     partially-applied plan).

3. **Registered in all 5 AppContainers** next to `DraftPlanTool`:
   - `apps/cli/.../CliContainer.kt`
   - `apps/desktop/.../AppContainer.kt`
   - `apps/server/.../ServerContainer.kt`
   - `apps/android/.../AndroidAppContainer.kt`
   - `apps/ios/Talevia/Platform/AppContainer.swift`

Constructor takes `(registry, sessions)` — registry for dispatch (same
pattern as `CompareAigcCandidatesTool` / `ReplayLockfileTool`),
SessionStore for `getPart` + `upsertPart` mutation of the Plan record.

Permission scope: `session.write`. Inner-tool dispatches inherit this
grant (matches prior art — compare_aigc_candidates + replay_lockfile
also dispatch target tools without re-permission per step, since the
batch approval was the "consent point" the user gave).

**Alternatives considered.**

1. **Let the agent loop auto-dispatch approved plans via a runtime
   hook** — rejected for this cycle. Would require a new post-turn
   callback in `AgentTurnExecutor` that reads the latest Part.Plan and
   decides to continue. That's a much bigger surface (state-machine
   tangled into the main dispatch path, permission inheritance
   subtleties, cancellation semantics on new user messages) for the
   same user-visible behaviour. `execute_plan` as a first-class tool
   is the smallest primitive; if we later want automatic invocation on
   approval, the hook can just call this tool.

2. **Re-derive Input at dispatch time by re-prompting the LLM** —
   rejected. Doubles turn count for every batched step and reopens
   the "LLM slight-drift between draft and dispatch" hole the typed
   `input` field closes. Typed payload at draft time is the robust
   choice; the cost is agents have to produce full JSON at draft time
   (OK — they already produce it at dispatch time anyway). Matches
   the kubectl-diff / kubectl-apply split: the diff is full
   pre-computed state, the apply is mechanical.

3. **Parallelise independent steps** — deferred. A correct
   "independent" classification is non-trivial — a timeline op
   depends on asset import that finished earlier; AIGC candidates are
   I/O-independent but the lockfile mutation isn't. Sequential
   matches user expectations for "walk the plan", and the
   `compare_aigc_candidates` tool is already there for parallel AIGC
   fan-out. Revisit when a concrete use case surfaces where serial
   execution blocks UX.

4. **Single `draft_plan(autoExecute: Boolean = false)` rather than
   two tools** — rejected. §3a rule 1 counts net tool growth; +1 is
   acceptable with rationale but combining into one dual-mode tool
   would bloat `draft_plan`'s Input schema with execute-specific
   params (dryRun, planId-to-continue, …) that have zero meaning in
   the draft path. Two narrow tools is cleaner for the LLM's
   schema-reading budget.

5. **Treat "tool not registered" as a halt, not a skip** — rejected.
   A container-configuration issue (e.g. user ran the same plan in a
   container without the `replicate` engine) shouldn't abort a batch
   the user has already approved; the unreachable step is skipped
   with a note so the user can swap containers and re-run with the
   remaining steps intact. This matches how `regenerate_stale_clips`
   handles the same case ("tool 'X' is not registered in this
   container; skipped").

**Coverage.** New `ExecutePlanToolTest` — 7 cases along the semantic
surface:

- `refusesExecutionWhenPlanNotApproved` — approval gate, doesn't
  dispatch on pending_approval.
- `approvedPlanDispatchesEveryStepInOrder` — happy path, verifies
  sequential dispatch order + payloads reached each stub + final Plan
  is re-emitted with all COMPLETED.
- `stepWithoutInputIsSkippedButDoesNotHalt` — skip-semantic for null
  input, subsequent step still dispatches.
- `failedStepHaltsRemaining` — failure halts, remaining steps
  CANCELLED with note, failedAtStep set.
- `dryRunDoesNotDispatchOrMutatePlan` — dryRun mode doesn't call the
  target tool or touch the Plan part.
- `missingPlanPartErrorsCleanly` — unknown planId error.
- `missingToolMarksStepSkippedNotFailed` — container-config skip,
  does NOT halt the batch.

All 7 pass. Existing `DraftPlanTool` tests continue to pass; the new
`input` field defaults null and doesn't affect prior Output shape.

**Registration.** New tool wired in 5 AppContainers (CLI / Desktop /
Server / Android / iOS). Tool count net growth: +1 in
`core/tool/builtin/` (top-level area, not under a subdirectory like
`aigc/` or `session/`).

LLM context cost (§3a rule 10): one new tool spec ~230 tokens + 5
lines added to `DraftPlanTool`'s step schema (~35 tokens) ≈ 265
tokens per turn. Below the 500-token threshold. The value unlocked
(LLM's per-step round-trips collapse into one `execute_plan` call)
recovers that multiple times over for any batch of 3+ steps.

---
