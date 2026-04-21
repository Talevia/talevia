## 2026-04-20 — `list_timeline_clips` for clip-level agent introspection

**Context.** The agent could see *counts* of a project (`get_project_state`
returns track / clip / asset totals) and could enumerate source nodes and
lockfile entries, but had no way to list the clips themselves with their
ids, times, and bindings. Every "lower the volume on the music after
00:30" request either leaned on the user to give an id or forced the
agent to dump the full Project JSON via `get_project_state`'s stringified
payload. Both paths are worse than a purpose-built tool.

**Decision.** Ship a read-only `list_timeline_clips` tool that walks
`Project.timeline.tracks`, flattens the clips, and returns a compact
`ClipInfo` per row: clip id, track id/kind, clip kind (video / audio /
text), start / duration / end seconds, bound `assetId`, filter count,
`sourceBinding` node ids, plus per-kind extras (audio: `volume`,
`fadeIn/Out`; text: 80-char `textPreview`). Optional filters:
`trackId`, `trackKind` ∈ {video, audio, subtitle, effect}, and
`fromSeconds` / `toSeconds` for `[start, end]`-intersection against a
time window. Results are ordered by track-position then
`timeRange.start` so adjacent rows correspond to adjacent playback.
Default limit 100; `truncated: Boolean` flips true when capped.
`outputForLlm` is a line-per-clip summary (kind/track, id, @start
+duration, asset, filters) so the agent spends ~20 tokens per clip
instead of several hundred on a JSON blob. Permission is
`project.read` (matches the other list tools).

**Alternatives considered.**

- **`get_clip_info(clipId)`.** Closer to the coding-agent vocabulary
  (per-node detail fetch) but forces a preceding list anyway — and
  listing covers the single-clip case via a trackId/time filter.
- **Folding it into `get_project_state`.** Makes that tool's payload
  quadratically larger (we'd need full clip lists on every state
  poll) and violates "one tool, one reason to call it". The split
  mirrors how we already have `list_source_nodes` separate from
  `get_project_state` counts.
- **Returning the full `Clip` JSON.** Richest but most expensive, and
  most fields (e.g. `sourceRange.duration` when equal to
  `timeRange.duration`, transform defaults) are redundant for the
  "find the clip id" use case. ClipInfo is intentionally lossy so the
  LLM-visible payload stays small.

**Why this now.** Part of closing the "agent introspection" gap on the
VISION §4 professional path — once the agent can answer "what's on the
timeline?" without a full state dump, targeted edits on long projects
become feasible. Also unblocks the natural "show me the clips between
10s and 20s" question the user keeps implicitly asking.

**Files.**
- `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ListTimelineClipsTool.kt`
- `core/src/commonMain/kotlin/io/talevia/core/agent/TaleviaSystemPrompt.kt` — new section
- `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ListTimelineClipsToolTest.kt` — 8 tests
- Registered in `apps/cli`, `apps/desktop`, `apps/server`, `apps/android`, `apps/ios`.

---
