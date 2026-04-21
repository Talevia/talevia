## 2026-04-20 — `edit_text_clip` for in-place subtitle edits

**Context.** `add_subtitle` / `add_subtitles` / `auto_subtitle_clip`
lay text onto the timeline fine, but the moment the user wants to
*edit* an existing subtitle — typo fix, color change, size bump —
the only path was `remove_clip` + `add_subtitle`. That sequence
breaks three things: (1) the clip id changes, so any later tool that
referenced it (e.g. `set_clip_transform`) now points at nothing; (2)
transforms get reset to defaults; (3) if the subtitle was created by
`auto_subtitle_clip` the source binding (which links the subtitle to
the video it was transcribed from) gets dropped. All of that to fix
a typo.

**Decision.** Ship `edit_text_clip(projectId, clipId, …)` with
optional per-field overrides matching the `update_character_ref`
idiom used elsewhere in the codebase:

- `null` → keep current value.
- A provided value → replace.
- `""` on `backgroundColor` → clear (set to null, transparent).

Editable fields: `newText`, `fontFamily`, `fontSize`, `color`,
`backgroundColor`, `bold`, `italic`. At least one must be provided;
all-null input fails loud. Works on any `Clip.Text` regardless of
which track it sits on (Subtitle or Effect). `timeRange`, `id`,
`transforms`, and `sourceBinding` are left untouched — use
`move_clip` / `trim_clip` for positional edits, `revert_timeline` to
undo style changes. Emits a snapshot.

**Alternatives considered.**

- *Require `newStyle: TextStyle?` as a whole-object replace.*
  Rejected — forces the agent to fetch the current style (via
  `list_timeline_clips` or similar) just to tweak one field, and
  every roundtrip is a chance to drop a field. Per-field patch
  matches the consistency-node update tools so the agent already
  knows the shape.

- *Separate tools: `edit_text_body` + `edit_text_style`.* Rejected —
  the most common editor intent is "make this caption bigger AND
  change the color"; splitting would force two consecutive tool
  calls for the common case. One tool with optional overrides is
  strictly more capable.

- *Allow editing `timeRange` too.* Rejected — `move_clip` and
  `trim_clip` already cover that and their semantics (especially
  trim with source-range math for media-backed clips) are
  non-trivial. Overlapping responsibilities would invite
  inconsistency between the two code paths for the same operation.

- *Validate hex color format (`#RRGGBB` / `#RRGGBBAA`).* Deferred —
  engines will render garbage color if a malformed string is
  provided, but that's already true for `add_subtitle` and the
  engines are the source of truth for what they accept. Centralize
  color validation later if it becomes a real footgun.

**Reasoning.** This is a ~150-line tool with 10 tests that closes a
surprisingly common paper-cut. Autonomous runs frequently generate
subtitles wrong on the first pass (capitalization, line breaks,
trailing punctuation) and want a cheap in-place fix; today that
costs them a clip id and any attached transforms, which cascades
into more fix-up tool calls. The update-style patch idiom keeps the
schema small and matches the rest of the codebase.

---
