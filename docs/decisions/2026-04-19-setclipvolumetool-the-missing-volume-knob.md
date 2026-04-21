## 2026-04-19 — `SetClipVolumeTool` — the missing volume knob

**Context.** `Clip.Audio.volume` was settable at construction (e.g.
`add_clip` for an audio asset records the asset's natural level) but had
no post-creation editor. "Lower the background music to 30%" / "mute
this take" / "boost the voiceover" — basic editing requests with no
tool, forcing the agent into `remove_clip` + `add_clip`, which mints a
new ClipId and breaks downstream filter / source-binding state. Same
gap class as `move_clip` / `trim_clip` before they landed.

**Decision.** Add `set_clip_volume` in `core.tool.builtin.video`. Input
`(projectId, clipId, volume: Float)`; volume is an absolute multiplier
in `[0, 4]`. Mutates `Clip.Audio.volume` in place, preserving the clip
id and every other field. Audio clips only — applying it to a video or
text clip fails loud.

**Why absolute, not delta.** Same reasoning as `move_clip` / `trim_clip`:
deltas force the agent to read state before computing the call, doubling
round-trips for no semantic gain. The user usually means an absolute
("set music to 30%") anyway; relative phrasing ("a little quieter") is
something the agent can translate to absolute itself.

**Why a `[0, 4]` cap, not unbounded.** Most renderers (ffmpeg `volume`
filter included) hard-clip beyond ~4× before mix-bus headroom runs out,
and the symptoms of running over are speaker-damaging. If a user really
wants more gain than 4× they almost certainly want it at mix-bus / track
level (a future feature) rather than per clip — capping here surfaces
that earlier rather than letting an unsafe value propagate into the
render.

**Why audio only, why fail loud on video / text.** `Clip.Video` has no
`volume` field today (track-level mixing isn't modeled yet) and
`Clip.Text` obviously has no audio. Silently no-op'ing on the wrong clip
type would let the agent think its edit landed when nothing happened —
worse UX than a loud error. When per-track audio mixing arrives, that
gets its own tool with its own contract.

**Why `0.0` mutes rather than removes.** Mute and remove are different
intents. A `volume=0` clip stays addressable (e.g., for a future fade-in
tool to ramp it back up); a removed clip is gone. The agent calls
`remove_clip` when the user wants the clip *gone*.

**Coverage.** `SetClipVolumeToolTest` — nine cases: happy-path with
non-volume fields preserved, mute (`0.0`) without removal, amplification
above `1.0`, video-clip rejection, text-clip rejection, negative-volume
rejection with state-untouched check, above-cap (`5.0`) rejection,
missing-clip fail-loud, and post-mutation `Part.TimelineSnapshot`.

**Registration.** Added to all four composition roots (desktop, server,
Android, iOS Swift) right after `TrimClipTool`. System prompt gained
a "# Audio volume" section explaining the multiplier semantics, the
mute-vs-remove distinction, and the audio-only contract. Key phrase
`set_clip_volume` added to `TaleviaSystemPromptTest`.
