## 2026-04-19 — `set_clip_volume` (audio-clip volume editor)

**Context.** `Clip.Audio.volume` was settable at construction (`add_clip`
records the asset's natural level) but had no post-creation editor. "Lower
the background music to 30%" / "mute the second vocal take" are basic
editing requests that previously required `remove_clip` + re-`add_clip`,
which loses downstream `sourceBinding`, filters, and every other attached
field. The cut/stitch/filter/transition lineup had `trim_clip` and
`move_clip` as in-place edits — volume was the missing knob.

**Decision.** New `core.tool.builtin.video.SetClipVolumeTool`. Tool id
`set_clip_volume`, permission `timeline.write`. Input:
`(projectId, clipId, volume: Float)`. Volume is an absolute multiplier in
`[0, 4]`: `0.0` mutes, `1.0` unchanged, up to `4.0` amplifies. Emits a
`Part.TimelineSnapshot` so `revert_timeline` can undo. Registered in all
four composition roots (server / desktop / Android / iOS).

**Why absolute multiplier, not delta or dB.** Matches `Clip.Audio.volume`'s
native unit exactly — the tool is a setter over a field that already uses
multiplier semantics. A dB surface (e.g. `"-12dB"`) would require parsing +
conversion and introduce sign ambiguity ("+3dB on a 0.5 clip?"). Deltas
would surprise ("add 0.1" to something already at 1.0 turns amplification
on). Absolute matches the sibling edits (`move_clip.newStartSeconds`,
`trim_clip.newSourceStartSeconds`) — "set X to Y" vocabulary across the
board.

**Why cap at 4.0 (≈ +12dB).** Most renderers (ffmpeg's `volume` filter
included) clip beyond that, and clip-level gain above 4× almost always
means the user really wants mix-stage gain staging (compression, EQ, bus
routing) rather than a raw multiplier. Failing loud at 4.0 surfaces the
right conversation instead of silently producing distortion.

**Why fail loud on non-audio clips.** Video clips have no `volume` field
today (track-level mixing is a future concern once we model audio rails),
and Text clips obviously have no audio. A silent no-op would teach the
agent that "apply volume to this clip" can succeed without doing anything;
failing loud keeps the tool's contract honest.

**Why `0.0` mutes instead of removing.** Mute-without-remove preserves the
clip id (stable references from automation / future fades / source
bindings) and keeps the clip visible in the UI as "something the user can
un-mute". The scalpel for full removal is already `remove_clip`.

**Tests.** Exercised by the pre-existing `SetClipVolumeToolTest`. No
architectural decisions exposed there — same `ProjectStore.mutate` + snapshot
shape as `move_clip` / `trim_clip`.

**System prompt.** New "# Audio volume" paragraph teaches the multiplier
range, the audio-only scope, and the mute-vs-remove distinction. Key
phrase `"set_clip_volume"` added to `TaleviaSystemPromptTest`.

---
