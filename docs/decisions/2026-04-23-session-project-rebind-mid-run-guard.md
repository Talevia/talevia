## 2026-04-23 — `switch_project` rejects rebind while the target session's agent is mid-run (VISION §5.6 rubric axis)

**Context.** P0 bullet from the cycle-31 repopulate,
`session-project-rebind-mid-run-guard`. Symptom: `SwitchProjectTool`
blindly calls `sessions.updateSession(session.copy(currentProjectId = pid))`
regardless of whether the target session is currently in an
in-flight agent run. If the agent is `Generating` / `AwaitingTool` /
`Compacting`, the current turn's `ToolContext.currentProjectId`
silently changes under its feet — the next tool dispatch reads a
different project than the one it started the turn on, producing
surprise-state errors that are hard to diagnose because the trace
doesn't record "the binding changed mid-turn".

The plumbing for the guard already exists: cycle 30's audit of
`AgentRunStateTracker` (finding B2) established that the tracker
holds the most-recent `AgentRunState` per session, exposed via
`currentState(sid)`. `SwitchProjectTool` just wasn't consulting it.

Rubric delta §5.6 (system invariant coverage): mid-run session
mutations go from **无** (no guard anywhere in the tool surface)
to **部分** (switch_project gated; other session-mutating tools
like `SessionActionTool(action=delete|rename)` remain ungated —
see Alternatives). A future follow-up can apply the same pattern
to other session-mutating tools with a shared helper, once the
pattern settles.

**Decision.** Three changes:

1. `SwitchProjectTool` gets a new optional constructor parameter
   `agentStates: AgentRunStateTracker? = null`. Null (test rigs,
   legacy compositions) preserves pre-guard behaviour (no gate).

2. `execute(input, ctx)` gains a gate between the `projects.get`
   existence check and the `sessions.updateSession` mutation:

   ```kotlin
   agentStates?.currentState(sid)?.let { state ->
       val runTag = agentRunStateSlug(state)
       if (runTag != null) {
           error(
               "Cannot switch_project while agent is $runTag on session ${sid.value}. " +
                   "Wait for the current run to finish, or cancel it first, before rebinding.",
           )
       }
   }
   ```

   `agentRunStateSlug` returns a short string for the three
   non-terminal states (`"generating"` / `"awaiting_tool"` /
   `"compacting"`) and null for the three terminal ones
   (`Idle` / `Cancelled` / `Failed`). Null state (session the
   tracker has never seen — e.g. fresh `open_project` +
   `switch_project` before any agent.run has fired) also passes
   the gate, since there's nothing in flight to disturb.

3. 4 AppContainers (CLI / Desktop / Server / Android) updated to
   pass `agentStates = agentStates` alongside the existing
   `bus = bus`. iOS doesn't register tools (providers only —
   consistent with every prior tool-registration cycle).

The gate sits AFTER the same-id no-op short-circuit. A redundant
`switch_project(currentProjectId=existing)` call during a
mid-run state is still a no-op — nothing changes, nothing
surprises the running turn — so blocking it would produce
false-positive errors on UI re-renders that redundantly re-fire
the switch. The same-id path was the motivation for the §3a #9
counter-intuitive-edge test `sameIdNoOpShortCircuitsBeforeGate`.

**Axis.** Number of session-mutating tools that consult the
agent-run-state gate. Before: 0. After: 1
(`SwitchProjectTool`). Pressure source for re-triggering this
pattern: the first operator report of another mid-run surprise-
mutation (e.g. `SessionActionTool(action=archive)` during a
Generating run flips the archived flag silently, the next turn
sees a rejected save). At that point the natural refactor is
extracting `agentRunStateSlug` + the guard block into a shared
`core/agent/MidRunGuard.kt` helper consumed by every tool that
mutates session state.

**Alternatives considered.**

- **Silently block the rebind instead of erroring.** Reject by
  returning `changed = false` with a "would-have-changed, but
  mid-run" note, mirroring the same-id no-op. Rejected: the
  agent has no visible signal that the request was ignored —
  its planning loop assumes the rebind happened. A loud error
  with "cancel the current run first" remediation is the
  correct shape: the agent can act on the error, or the human
  operator can cancel and retry.

- **Elevate permission tier to `session.destructive` when
  mid-run.** Closer to the bullet's "reject (or asks permission
  tier upgrade)" wording. Would let an operator explicitly ASK
  the user to confirm a mid-run rebind via the normal permission
  dialog. Rejected for now: cycle-21's `PermissionSpec.permissionFrom`
  extension takes the raw input JSON, NOT the runtime state.
  Agent-run state isn't in the tool's input — it's in the
  runtime tracker. Threading runtime state into `permissionFrom`
  is a wider refactor (probably a new `PermissionSpec.permissionFromRuntime(
  inputJson, ctx)` lambda). A clean hard-reject resolves the
  P0 concern today; permission-tier elevation can arrive as a
  follow-up design if operator feedback drives it.

- **Guard in the CLI / Desktop UI layer rather than in Core.**
  Catch the rebind attempt client-side before it reaches the
  tool. Rejected: the server SSE path exposes the same tool via
  HTTP; a UI-only guard leaves the server path unguarded. Core
  is the correct layer — every dispatch route gets the guard
  automatically.

- **Publish a new `BusEvent.SwitchProjectRejected` on the gate
  trip.** Let subscribers (UI, metrics) see the failed attempt
  separately from a generic error. Rejected: the `error(…)`
  path already surfaces through the normal tool-failure channel
  (`ToolState.Failed` + `BusEvent.PartUpdated`); UI renders the
  message verbatim. No need for a dedicated event until a
  consumer specifically needs one.

**Coverage.** New
`core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/session/SwitchProjectMidRunGuardTest.kt`
pins 8 invariants:

1. `rebindIsRejectedDuringGenerating` — Generating state → gate
   trips → `IllegalStateException` with both the state tag
   (`"generating"`) and the session id in the message. Store
   remains unmutated (positive control).
2. `rebindIsRejectedDuringAwaitingTool` — same for AwaitingTool.
3. `rebindIsRejectedDuringCompacting` — same for Compacting.
4. `rebindSucceedsWhenIdle` — Generating → Idle transition, then
   rebind → succeeds. Terminal Idle doesn't block.
5. `rebindSucceedsWhenTrackerHasNeverSeenSession` — null state
   (no run has ever started) must not block first-time bindings.
6. `rebindSucceedsWhenTrackerIsNull` — legacy compositions that
   don't inject a tracker get the pre-guard behaviour (the
   existing `SwitchProjectToolTest` rig exercises this path).
7. `sameIdNoOpShortCircuitsBeforeGate` — the §3a #9 counter-
   intuitive edge. A redundant same-id call during Generating
   must succeed as a no-op, not a gate-trip.
8. `rebindSucceedsAfterCancel` — Cancelled is terminal; rebind
   after cancel works. Protects against a future regression
   where someone narrows the terminal-state set.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck all
green. The existing `SwitchProjectToolTest` (13 test methods)
continues to pass unchanged — all its constructors use the
3-arg form `SwitchProjectTool(sessions, projects, fixedClock)`
and rely on the new optional `agentStates` defaulting to null
(legacy-compat path).

**Registration.** 4 AppContainers updated (CLI / Desktop /
Server / Android). Each adds `agentStates = agentStates` to the
`SwitchProjectTool(...)` registration call — `agentStates` was
already a parameter (or field) in every container from prior
cycles so no new plumbing was needed. iOS doesn't register
tools.
