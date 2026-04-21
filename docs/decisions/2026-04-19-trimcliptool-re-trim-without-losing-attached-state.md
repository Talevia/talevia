## 2026-04-19 — `TrimClipTool` — re-trim without losing attached state

**Context.** The agent had no way to re-trim a clip after creation. The
only available paths were destructive: `remove_clip` + `add_clip` (loses
any filters / transforms / consistency-bindings attached to the clip id,
because a fresh clip gets a fresh id) or `split_clip` + `remove_clip`
(loses one half and produces residue on the other). Both are wrong tools
for the user's intent — "trim a second off the start" is a single edit,
not a destructive recreate. A video editor without a working trim is not
one, so this is a fundamental primitive gap.

**Decision.** Add `trim_clip` in `core.tool.builtin.video`. Input is
`{projectId, clipId, newSourceStartSeconds?, newDurationSeconds?}`. At
least one of the new* fields must be set; omitted = preserve current.
Mutates the clip's `sourceRange` (and `timeRange.duration`) in place,
preserving the clip id and everything else attached to it (filters,
transforms, sourceBinding, audio volume).

**Why absolute values, not deltas.** Mirrors `add_clip` vocabulary
exactly. Delta-based input ("trim 1.5s off the start") forces the agent
to read the clip's current state before computing the call, doubling
round-trips for no semantic gain. Absolute is also what the user usually
means when they say "make it 8 seconds long" or "start at 00:03".

**Why preserve `timeRange.start`.** Trimming and repositioning are two
intents. If `trim_clip` also slid the timeline anchor, the user would
have to know how to undo the slide to keep alignment with subtitles or
transitions on adjacent tracks. Keeping the timeline anchor stable means
the rest of the timeline doesn't reflow. The agent chains `move_clip`
when it actually wants to slide.

**Why duration applies to BOTH `timeRange` and `sourceRange`.** Talevia
doesn't model speed changes (no time-stretching tool). Until that arrives,
clip duration on the timeline always equals duration in source media.
Letting the two diverge here would let the tool produce timeline state
that no engine knows how to render. When speed becomes a thing, it gets
its own tool.

**Why reject `Clip.Text`.** Subtitle clips have no `sourceRange` (they're
synthetic, generated from `text` + `style`). A trim that only changes
`timeRange` is just a "move the boundaries" op — different semantics
from trimming a media-backed clip. Forcing the agent to use
`add_subtitle` (which it already knows) for subtitle timing keeps the
two tools' contracts crisp.

**Validate against asset duration.** The mutation block looks up the
bound asset's `metadata.duration` via `MediaStorage.get` so we refuse
trims that extend `sourceRange.end` past the source media — failing
loud at tool-dispatch time beats letting a broken `sourceRange` reach
the renderer.

**Coverage.** `TrimClipToolTest` — eleven cases: tail-only trim
(shrink with `timeRange.start` preserved), head-only trim
(`sourceRange.start` advances, `timeRange.start` preserved), simultaneous
head + tail, audio-clip parity (volume preserved through trim),
Text-clip rejection, both-fields-omitted rejection,
trim-past-asset-duration guard with state-untouched-on-failure check,
negative `newSourceStartSeconds` rejection, zero-duration rejection,
missing-clip fail-loud with state-untouched-on-failure check, and
post-mutation `Part.TimelineSnapshot` for `revert_timeline` parity.

**Registration.** Added to all four composition roots (desktop, server,
Android, iOS Swift) right after `MoveClipTool`. System prompt gains a
"# Trimming clips" section explaining the absolute-values vocabulary,
the timeline-anchor preservation, and the Text-clip carve-out.
`trim_clip` added to `TaleviaSystemPromptTest`'s key phrases (parallel
session beat me to the prompt-test edit, pleasant collision).
