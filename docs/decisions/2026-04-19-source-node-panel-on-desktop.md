## 2026-04-19 — Source-node panel on desktop (VISION §5.1 expert surface)

**Context.** Before this change every source-DAG operation — defining a
character reference, listing style bibles, removing a brand palette — had
to go through the chat tab. VISION §4 explicitly names the expert path
("用户直接编辑 source 的每个字段") as a first-class user flow, not a
fallback. Task 3 of the current gap list.

**Decision.**
- New `SourcePanel.kt` composable. The right-hand column in the desktop
  UI gets a two-tab workbench (`Chat` / `Source`) via `TabRow`; the Source
  tab renders the DAG grouped by node kind (characters, style bibles,
  brand palettes, other) with per-row expand / remove and a "Define new"
  form at the bottom.
- **Refresh strategy: subscribe to `BusEvent.PartUpdated` + re-read the
  project on every part event.** Every tool call ends with a part
  emission; re-reading the project blob is O(one SQLite indexed PK fetch +
  one JSON decode). The alternative — a finer-grained `source.changed`
  signal — would be premature; we haven't hit the scale where polling
  matters, and this also picks up edits the agent made in the Chat tab
  without the panel having to know about them.
- **Edits dispatch through the same `ToolRegistry` the agent uses.** The
  panel's "Define character / style / palette" buttons construct the same
  JSON input the LLM would, and route through
  `registry[toolId]!!.dispatch(...)`. That gets us permission checks,
  bus events, and the exact validation the agent path gets — for free.
  No separate mutation path.
- **Unified `uiToolContext` helper.** The old `dummyToolContext` lived in
  `Main.kt`; two direct-dispatch call sites now exist (centre-panel
  buttons + `SourcePanel`), so it's moved to `SourcePanel.kt` as an
  `internal fun AppContainer.uiToolContext(ProjectId)`. Same behavior,
  one definition.

**Alternatives considered.**
- **Read-only inspector, edits via chat only.** Would ship faster but
  doesn't meet the VISION §4 expert-path bar ("用户直接编辑"). Rejected.
- **Full-fat modal dialog per kind (character ref picker with LoRA pin,
  reference-image browser, palette colour swatches, etc.).** Better UX,
  but several days of polish. v0 trades feature depth for coverage: you
  can create all three consistency kinds with a single name + description
  field, then round-trip through chat for the advanced knobs. Follow-up
  tracked below.
- **Cache `project` in `AppContainer` and let multiple panels share
  one observed state.** The right thing eventually, but premature: there
  is exactly one place that reads `Project` for the UI today (the
  panel). Introducing a shared `StateFlow<Project?>` before we have two
  consumers would be speculative. Promote when the Timeline inspector
  (task 4) lands and we actually have two.

**Known limitations.**
- Creation form is minimum-viable — no reference-asset picker, no LoRA
  pin, no `parentIds`. Editing an existing node via the panel isn't
  wired yet; "Remove then re-add" is the current loop. Both are lifted
  in the follow-up.
- No inline search / filter over the node list — a project with hundreds
  of nodes would get unwieldy. Nothing does that today; revisit if it
  does.
- The JSON body view is a plain `Text` with `SelectionContainer` — fine
  for copy-paste, but no syntax highlighting. That's what "it works"
  looks like; polish later.

**Follow-ups.**
- Inline-edit dialogs per kind (Character ref: name / description /
  voiceId / LoRA pin / reference assets). Closes the VISION §5.5
  cross-shot-consistency loop inside the panel without chat.
- Show downstream staleness: click a source node → highlight which clips
  in the Timeline view have bindings to it. Prerequisite: Task 4
  (timeline inspector with clip → source binding visibility).
- Extract `observeProject(projectId)` to `AppContainer` once we have two
  UI consumers (Source + Timeline inspector).

---
