## 2026-04-19 — `auto_subtitle_clip` tool — ASR → captions in one atomic edit

**Context.** The agent could caption a clip only by chaining `transcribe_asset`
(ASR) → `add_subtitles` (batch timeline write), doing the
`TranscriptSegment.startMs/endMs` → `Segment.startSeconds/durationSeconds +
clip.timeRange.start` arithmetic in the middle. That's three round-trips of
latency + tokens per caption, two snapshots on the revert stack, and the
arithmetic is subtle — segments that straddle the clip window have to be
clamped, segments past the end dropped. Every agent would reinvent this.

**Decision.**
- New tool `AutoSubtitleClipTool` under `core/tool/builtin/video/`. Input:
  `{projectId, clipId, model?, language?, fontSize?, color?, backgroundColor?}`.
  The tool reads the clip's `assetId` + `timeRange` from the project, calls
  `AsrEngine.transcribe(resolve(assetId))`, maps each `TranscriptSegment` to
  an absolute timeline placement (`clip.timeRange.start + seg.startMs`),
  clamps the end to `clip.timeRange.end`, drops segments whose start falls
  past the clip end, and commits every placed caption in one
  `ProjectStore.mutate` → one `TimelineSnapshot`.
- Output echoes `{trackId, clipIds, detectedLanguage, segmentCount,
  droppedSegmentCount, preview}` so the LLM can reason about the outcome
  without re-calling `find_stale_clips` or `transcribe_asset`.
- Permission: `"ml.transcribe"` — audio leaves the machine via the ASR
  provider, same exfiltration concern as `transcribe_asset`. The timeline
  write is implicit to the same intent; a separate `timeline.write` check
  would require a multi-permission `PermissionSpec` we don't have and
  doesn't buy safety over the ASK/allow on `ml.transcribe`.
- Wired into desktop + server containers alongside `TranscribeAssetTool`
  under the shared `asr?.let { … }` gate — no ASR provider ⇒ neither tool
  registered.

**Alternatives considered.**
- **Add a `captionize` flag to `transcribe_asset`.** Rejected — the two tools
  have different side-effect profiles (read-only vs timeline-writing) and
  different sensible defaults (`transcribe` returns segments so the agent
  can plan cuts; `auto_subtitle` wants to commit captions). Conflating them
  via a flag means the agent has to remember to set it, and the tool help
  text grows to describe two behaviours.
- **Accept `assetId` + `startSeconds` directly instead of `clipId`.**
  Considered for ergonomics ("caption this unattached audio at 0:30 on the
  timeline"), but the load-bearing use case is captioning an already-placed
  clip, and pulling `assetId` + `timeRange` from the clip means the agent
  can't mis-enter the offset. Unattached-asset captioning is still reachable
  via `transcribe_asset` → `add_subtitles`, which is where the 10% bespoke-
  offset case belongs.
- **Require the agent to do the clipping arithmetic in `add_subtitles`.**
  Rejected — subtle clamping / drop rules scatter across every caption
  workflow, and every agent reimplements them slightly differently (or
  worse, silently lets captions extend past the clip). Once-in-one-tool
  beats replicated-per-prompt.
- **Make this tool emit one snapshot per segment.** Rejected — matches the
  existing `AddSubtitlesTool` rationale: revert-unit should match user
  intent. "Caption this clip" is one intent; a 30-segment snapshot stack
  would overwhelm `revert_timeline`.

**Why.** VISION §5.2 rubric asks "工具集覆盖面… agent 能不能在一次意图下混合
调度？" The missing piece for ML-driven captioning was the "in one intent"
part — infrastructure existed, composition was the gap. This tool turns
"caption the talking-head clip" from a multi-step agent chain into a single
grounded call, which is exactly the UX VISION §4 (novice path) requires.

**How to apply.** Future "ML → timeline" composites follow the same pattern:
a single tool that reads from `ProjectStore`, invokes the ML engine, writes
back through `ProjectStore.mutate`, emits one snapshot. Permission names the
outward-facing concern (the upload, not the timeline write).

---
