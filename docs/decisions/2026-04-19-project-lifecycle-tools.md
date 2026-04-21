## 2026-04-19 — Project lifecycle tools (VISION §5.2 — agent as project manager)

**Context.** `ProjectStore` already had `get / upsert / list / delete / mutate`,
but no agent tool exposed any of them. A new conversation could not bootstrap a
project; the desktop app hard-coded one at startup; the server assumed projects
pre-existed. The agent literally could not answer "make a graduation vlog from
scratch" — it had no `projectId` to thread into `add_clip`.

**Decision.** Add four tools under `core/tool/builtin/project/`:

- `create_project` — title-required, optional explicit `projectId` (default
  slug = `proj-{slug}`), optional `resolutionPreset` (720p/1080p/4k) + `fps`
  (24/30/60). Defaults to 1080p/30. Fails loud on duplicate id.
- `list_projects` — catalog metadata only, no Project JSON decode.
- `get_project_state` — single-project snapshot: title, output profile, asset /
  source-node / lockfile / render-cache / track counts, source revision,
  timeline duration, timestamps. The agent uses this to *plan* before edits
  rather than guess what already exists.
- `delete_project` — destructive; permission `project.destructive` defaults to
  ASK. Does not auto-prune sessions that referenced the project (the prompt
  tells the agent to warn the user instead).

**Why a `ProjectSummary` extension to `ProjectStore`.** `Project.title` lives in
the SQL row, not in the canonical `Project` model — bloating the model just for
listings would invert the storage layout. Added `ProjectSummary` data class +
`summary(id)` / `listSummaries()` methods so list_projects can return titles
without decoding every Project's full JSON. `list_projects` reads metadata only;
`get_project_state` is the heavy single-project read.

**Why the tools live in `core`, not per-app.** Same reasoning as the source
tools — pure local state mutation through `ProjectStore`, no I/O, no platform
deps. Composition root in each container (server, desktop, Android,
iOS-Swift) registers them.

**Why `project.read` / `project.write` default ALLOW; `project.destructive`
defaults ASK.** Reading the catalog and creating an empty project are no-cost
local mutations (parallel to `source.read` / `source.write`). Deletion drops the
Source DAG, Timeline, Lockfile, and RenderCache — irreversible from the user's
perspective and there's no undo lane below the store. ASK-by-default protects
the user without hand-holding the read path.

**Why duplicate-id is a hard failure, not silent overwrite.** `create_project`
on an existing id would silently obliterate the source DAG — exactly the data
loss `delete_project` asks the user about. Failing loud forces the agent to
call `list_projects` first and either pick a fresh id or operate on the
existing project explicitly.

**OutputProfile shorthand, not full struct.** The LLM picks one of three
resolution presets and one of three frame rates rather than constructing an
`OutputProfile` JSON blob. Keeps the input schema compact and the failure modes
obvious (`unknown resolutionPreset` is a clearer error than a partially-filled
profile). Custom profiles can be added later via a `mutate_output_profile` tool
if real users need them — not now.

**System prompt + regression guard.** Added a "Project lifecycle" paragraph to
`TaleviaSystemPrompt.kt` and extended the rule about timeline tools to direct
the agent to bootstrap a project when the catalog is empty.
`TaleviaSystemPromptTest` now asserts `create_project` / `list_projects` /
`get_project_state` still appear in the prompt.

**When to revise.** When a session needs to be associated with a newly-created
project automatically (today the user/caller provides `projectId` separately to
`POST /sessions`), the natural next step is either a server endpoint surface
for project CRUD or a `bootstrap_session` tool that returns both `projectId`
and `sessionId`. Defer until session-level UX feedback motivates it.
