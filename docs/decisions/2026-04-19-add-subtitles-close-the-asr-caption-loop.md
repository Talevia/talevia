## 2026-04-19 — `add_subtitles` (batch) — close the ASR → caption loop

**Context.** `transcribe_asset` (VISION §5.2 ML lane) returns a list of
time-aligned `TranscriptSegment`s — the natural next move is to drop them
onto the subtitle track. The only available primitive was `add_subtitle`
(singular), which adds one caption per call. A 60-second clip typically has
30+ transcript segments, so the agent had to issue 30 sequential tool calls
— 30× the tokens, 30× the latency, and 30 separate `revert_timeline`
snapshots stacked on top of each other. Revert-one-caption undo is noise;
the user's intent was "caption the whole clip" as a single unit.

**Decision.** Add `add_subtitles` (plural) as a sibling tool in
`core.tool.builtin.video`. Input is `{projectId, segments: [{text,
startSeconds, durationSeconds}], fontSize?, color?, backgroundColor?}`;
all segments are committed in one `ProjectStore.mutate` and one
`Part.TimelineSnapshot` is emitted. Style applies uniformly to every
segment. The manual one-off path keeps `add_subtitle` (singular) for the
case where a user wants per-line styling.

**Why a sibling tool and not a field on `add_subtitle`.** Overloading the
singular tool with an optional `segments[]` would confuse schema
validation ("text is required" + "segments is required" would race) and
muddle the permission / snapshot semantics (one-clip-per-call vs
many-clips-per-call differ on the undo stack). Two tools with clear,
non-overlapping shapes keep the prompt teachable — "batch caption? use
add_subtitles. single manual line? use add_subtitle."

**Seconds at the tool surface.** `TranscriptSegment` is `startMs`/`endMs`,
but every timeline-mutating tool in the codebase takes seconds. Matching
the sibling's unit keeps the two tools substitutable and avoids a second
unit-system at the tool boundary. The agent is taught explicitly to divide
the ASR `startMs` / `endMs` by 1000 in the prompt.

**Atomic edit, single snapshot.** The user's mental model of "caption the
clip" is one edit. Matching that with one snapshot makes `revert_timeline`
a natural undo for the whole caption pass — which is the behavior a user
who said "undo the captions" expects. Per-segment snapshots would have
required the user to revert 30 times to unwind the captioning.

**Coverage.** `AddSubtitlesToolTest`: atomic multi-segment insertion with
single-snapshot verification, unsorted input sorting by start, subtitle
track auto-creation when absent, timeline duration extension to cover tail
segment, empty-segments rejection (`IllegalArgumentException`),
existing-track-order preservation.

**Registration.** Added to all four composition roots (desktop, server,
Android, iOS Swift) alongside the existing `AddSubtitleTool` registration.
System prompt gained a paragraph under "# ML enhancement" teaching the
`transcribe_asset` → `add_subtitles` chain and explicitly discouraging the
old N×`add_subtitle` loop. `add_subtitles` added to the Compiler = Tool
calls list and to `TaleviaSystemPromptTest`'s key phrases.
