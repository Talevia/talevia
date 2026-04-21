## 2026-04-19 — `create_project_from_template` — genre scaffolding

**Context.** `create_project` bootstraps an empty project: no source
nodes, no character refs, no style bible. Before the first AIGC call the
agent (or user) has to run multiple `define_*` tools to reach a usable
state, which is a lot of friction for the novice path ("make me a short"
should not require memorising six tool names). VISION §5.4 novice-path
requires the system to "infer a reasonable source structure, pick sensible
defaults, produce a first draft" — no tool did this.

**Decision.**
- New `CreateProjectFromTemplateTool` under `tool/builtin/project/`.
  Input: `{title, template, projectId?, resolutionPreset?, fps?}`. Output
  echoes `seededNodeIds`.
- Two templates:
  - `narrative` → 6 nodes: `protagonist` (character_ref),
    `style` (style_bible), `world-1` (parent: style), `story-1` (parent:
    world), `scene-1` (parents: story + protagonist), `shot-1` (parent:
    scene). Every edit cascades correctly through the DAG from day zero.
  - `vlog` → 4 nodes: `style`, `footage`, `intent`, `style-preset`.
- All body fields are `"TODO: …"` placeholders — the template is
  scaffolding, not opinion. Users replace placeholders via
  `update_character_ref` / `update_style_bible` / `import_source_node`
  before the first AIGC call.
- Reuses existing genre ext helpers + consistency builders; the tool
  only composes them atomically. No genre logic duplicated.
- Wired in desktop + server containers right after `CreateProjectTool`.

**Alternatives considered.**
- **Opinionated placeholders** ("graduation day", "sunset walk"). Rejected
  — biases creative direction and encourages the user to accept defaults
  that aren't theirs.
- **Expose templates as JSON files** the user can edit. Rejected for v1
  as scope creep — two in-code templates is enough to validate the
  pattern; filesystem templates can come when a third genre shows up.
- **Merge into `CreateProjectTool` via an optional `template` field.**
  Rejected — the two tools have different required-inputs (template
  required on one, not the other) and different outputs (seededNodeIds
  is nonsense for the empty variant). A flag would muddle both.
- **Auto-trigger on `create_project` when title matches a genre hint**
  ("narrative short"). Rejected — heuristic magic that fails confusingly
  when the title happens to contain the word "vlog".

**Why.** Novice path friction drops from "six tool calls to usable state"
to "one call." Experts can still use `create_project` + manual
definitions when they want precise control. The macOS-desktop-first
priority applies here: the chat pane now handles "start a new narrative
project" in a single agent turn instead of three.

**How to apply.** When a third genre lands (MV, tutorial, ad), add a new
branch under the `when (template)` switch and a sibling `seedX()` method.
Keep placeholder copy as literal `"TODO: …"` so downstream `find_stale_clips`
/ `list_clips_for_source` / search workflows can surface half-configured
projects.

---
