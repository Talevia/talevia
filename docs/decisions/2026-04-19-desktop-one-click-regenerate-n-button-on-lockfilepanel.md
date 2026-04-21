## 2026-04-19 — Desktop: one-click "regenerate N" button on LockfilePanel

**Context.** `regenerate_stale_clips` tool landed but the Mac desktop panel
that shows stale clips still required the user to type an agent command to
trigger it. The VISION §6 end-to-end loop ("edit character → stale badge →
regenerate → export") was only reachable via chat.

**Decision.**
- Add a single `Regenerate N` TextButton at the "Stale clips" section
  header in `LockfilePanel`. Clicking it dispatches `regenerate_stale_clips`
  with the active `projectId` through `container.tools[...]` using the
  existing `uiToolContext` helper (same path `TimelinePanel` / `SourcePanel`
  use for their inline buttons).
- While the dispatch is in flight the button shows `regenerating…` and is
  disabled to avoid double-fires. On completion the button returns and
  `project` state is re-fetched from the store so the lockfile entry list
  shows the refreshed entries.
- Result text from the tool (count regenerated, skip reasons) is appended
  to the shared `log` list so the user sees what happened without opening
  chat.
- **Single batch button, not per-row buttons.** The tool is batch-only and
  exposing per-row "regenerate just this one" would need a `clipIds` filter
  on the tool that nobody's asked for; YAGNI until a concrete workflow
  demands it.

**Alternatives considered.**
- **Add `clipIds` filter to the tool and render per-row buttons.** Rejected
  for now — no user story demands partial regeneration; we can add it when
  the "I want to skip one of these" case shows up.
- **Auto-trigger when stale count goes non-zero.** Rejected — AIGC
  regeneration costs money; surprising the user with a spontaneous batch
  of provider calls is the opposite of the §4 "agent is your collaborator,
  not your overlord" stance.
- **Put the button on TimelinePanel stale badges.** Considered. Rejected
  because the stale summary already lives on LockfilePanel, and putting the
  action next to the summary keeps the "see → act" loop tight in one panel.

**Why.** Mac desktop is the priority platform; the §3.2 loop works but
required chat typing. One button converts "discoverable for agent users"
to "discoverable for mouse users" — which is what VISION §4's dual-user
path requires (experts click, novices chat; same mutation, different
surface).

**How to apply.** When future compound tools land (e.g. a future
`regenerate_stale_clips_in_scene`), wire a similar panel-header button
next to the relevant summary. Reuse `uiToolContext` + the `log`
SnapshotStateList pattern; don't reinvent dispatch plumbing per panel.

---
