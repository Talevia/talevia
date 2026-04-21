## 2026-04-21 — session-project-binding: Session 持有 currentProjectId + switch_project 工具 (VISION §5.4 rubric)

Commit: `f7f87c2` (pair with `docs(decisions): record choices for session-project-binding`).

**Context.** Before this cycle, `Session` and `Project` were loosely coupled —
a Session carried an originating `projectId`, but the agent's "which project
am I currently editing?" context lived only in the conversation transcript.
Tools that needed a projectId took it as an explicit string input, and the
agent re-derived the right value from prior turns. Two failure modes:
1. **Multi-project workflows**: the user alternates between e.g. a vlog cut
   and a narrative project in the same session. Without a structural
   binding, the agent guesses from the latest natural-language cue and
   drifts (a stale `projectId` carried over from turn N-1 into a tool call
   in turn N+2). At scale this is the #1 source of "why did my edit land
   on the wrong project" UX bug.
2. **Context cost**: every turn's preamble had to re-tell the model the
   current project via prose. That's a per-turn prompt tax that a
   structural field replaces with one deterministic banner line.

VISION §5.4 calls this out as the core write-path gap on the session lane.

**Decision.** Session gains a nullable `currentProjectId: ProjectId?`
field + a `switch_project` tool:
- `Session.currentProjectId: ProjectId? = null` — distinct from the
  originating `projectId` (which is immutable and records lineage); this
  is the mutable "cwd" the agent edits against. Defaulted to null so
  pre-migration rows deserialize cleanly.
- `Agent.run` reads the session once per step (before each LLM turn) and
  injects a `Current project: <id>` (or `<none>`) banner at the top of
  the system prompt. Threaded into `ToolContext.currentProjectId` so tools
  can (in a future cycle) default their `projectId` arg from context.
- `SwitchProjectTool(sessionId, projectId)` — verifies the project exists
  via `ProjectStore.get` **before** committing (unknown id fails loud so
  a ghost binding can't spread across subsequent turns), then writes the
  session via `SessionStore.updateSession` (bumps `updatedAt`, publishes
  `BusEvent.SessionUpdated` via the existing contract). Same-id is a
  no-op (no updatedAt bump, no event).

Permission: reuses `session.write` (same bucket as rename / archive /
fork). Carving a third permission keyword for every verb would balloon
the rule count; "read/write" remains the right granularity.

**Scope intentionally held narrow.** This cycle does **not** change tool
input shapes. Every timeline / AIGC / source tool still takes an
explicit `projectId: String` — the binding lives on session / context,
not on tool inputs. Rationale: rewriting ~40 tool serializers + schemas
in one cycle would blow up review/blast radius. Now that the plumbing
exists, a follow-up cycle can migrate per-tool defaults over time (each
tool gets its own decision and test pass).

**Alternatives considered.**
- **Option A (chosen).** Session holds a nullable `currentProjectId`,
  tools still accept explicit `projectId: String`. Future cycles migrate
  tools one at a time to default from context.
- **Option B.** Change every `projectId: String` tool to default from
  `ToolContext.currentProjectId` in the same cycle. Rejected — blast
  radius too large; ~40 tools' input shapes would flip `projectId` from
  required to optional all at once, each needing its own schema /
  decision / regression pass. Risk concentration. Staged migration is
  cheaper and safer.
- **Option C.** Inject `projectId` at the `ToolRegistry.dispatch`
  boundary (hidden middleware). Rejected — breaks the "tool is its own
  JSON schema" invariant the LLM relies on: the spec would say
  `projectId: required string`, the actual call would succeed without
  it. Silent deviation from the published contract is worse than the
  explicit arg we have today.
- **Option D.** Repurpose existing `Session.projectId` as the mutable
  current pointer. Rejected — that field is used as the session's
  originating-project key in store queries (`listSessions(projectId=…)`)
  and surfaces in UI as "where the session was born." Breaking that
  invariant to overload the field would cascade through
  `SessionStore.listSessions` / server HTTP filters / UI. Cleaner to
  add a sibling field with clear semantics.

**§3a self-check.**
1. **Tool count**: +1, no consolidation candidate. `switch_project` is
   the first **session-state mutation** tool bound to the project lane;
   there is no near-duplicate to absorb. Any future consolidation
   should wait for a second session-state tool to land (so the merge
   has a concrete partner). Noted here so later cycles don't let tool
   count drift unchecked.
2. **Define/Update**: N/A. A single upsert-style verb handles both
   "first bind" and "rebind"; no pair to merge.
3. **Project blob**: no changes to `Project` data class.
4. **State field binary**: `currentProjectId: ProjectId?` is
   present/absent, but "absent" = "session not yet bound" is a
   legitimate third state, not a stale/fresh flag.
5. **Core genre**: N/A — genre-neutral.
6. **Session ↔ Project binding**: THIS IS THE TASK. Pass.
7. **Serialization compat**: default `= null` means old rows decode.
   Covered by `legacySessionJsonWithoutCurrentProjectDecodesCleanly`.
8. **5-end wiring**: `switch_project` registered in cli / desktop /
   server / android / ios containers.
9. **Semantic tests**: null→set, change, no-op on same id, unknown
   project fails loud without mutating, unknown session fails loud,
   blank inputs rejected, ToolContext reflects binding after read,
   serialization compat.
10. **LLM context cost**: system-prompt banner ~30 tokens, new tool
    spec ~200 tokens, so ~230 tokens/turn. Offsets the per-turn prose
    reminders the agent currently needs to re-state the current
    project; structural source of truth is a net win once the user
    has 2+ projects live.

**Coverage.**
- `SwitchProjectToolTest` — 8 tests: default null, switch from null,
  change between projects, no-op on same id, unknown project fails
  loud without mutating, unknown session fails loud, blank inputs
  rejected, legacy JSON decodes cleanly, ToolContext reflects binding.
- `SessionProjectBindingTest` — 4 tests: system prompt carries
  `<none>` banner when unbound, system prompt carries bound banner,
  ToolContext sees binding at dispatch, ToolContext sees null when
  unbound.

**Registration.** `SwitchProjectTool` registered in
`apps/cli/CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. System prompt gained a
"Session-project binding (VISION §5.4)" section pointing the agent at
the banner and `switch_project`. No new permission keyword (reuses
`session.write`).
