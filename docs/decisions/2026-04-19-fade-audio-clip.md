## 2026-04-19 — `fade_audio_clip` (audio envelope editor)

**Context.** `set_clip_volume` ships a steady-state level knob for audio
clips but no attack/release. The natural follow-up requests ("fade the
music in over 2s", "2s fade-out", "swell in, duck for dialogue, fade
out") had no completion path — an agent could mute a clip but not shape
how it starts or ends. `Clip.Audio` carried `volume` but no fade fields,
so the envelope had no place to live even if a tool existed.

**Decision.** Two coordinated changes:

1. Extend `Clip.Audio` with `fadeInSeconds: Float = 0f` and
   `fadeOutSeconds: Float = 0f`. Default `0f` means "no fade", backward
   compatible with every existing stored project (JSON blob columns with
   `ignoreUnknownKeys = true` + Kotlin default values roll forward
   cleanly).
2. New `core.tool.builtin.video.FadeAudioClipTool` → id
   `fade_audio_clip`, permission `timeline.write` (ALLOW). Input:
   `(projectId, clipId, fadeInSeconds?, fadeOutSeconds?)`. Each field
   optional; at least one must be set. Unspecified fields keep the
   clip's current value. Emits `Part.TimelineSnapshot` for
   `revert_timeline` parity. Registered in all four composition roots
   (desktop / server / Android / iOS).

**Why two fields on the clip, not a single `AudioEnvelope` struct.**
Considered modelling `fadeIn` / `fadeOut` as a nested `AudioEnvelope(...)`
object (room to grow: ramp shape enum, pre-delay, sidechain duck). Rejected
for today: no concrete driver for those extensions, and a nested object
requires a new serializer while two primitive floats roll forward from
existing stored blobs for free. If ramp shape or ducking becomes real, a
follow-up can lift these into a struct — the field names are already the
ones an envelope would carry.

**Why "keep current on omit", not "default to 0 on omit".** A user
saying "add a 2s fade-in" should not silently clobber a fade-out that
was set earlier. The setter merges input onto the clip's existing
values, matching `set_clip_transform`'s established pattern. `0.0`
explicitly disables a side — that's the in-band "remove the fade"
signal, distinguishable from omission.

**Why `fadeIn + fadeOut ≤ duration`.** Overlapping fades have no
well-defined envelope — what does "fade in for 3s, fade out for 3s" on
a 4-second clip render as? Rejecting loudly beats silently clamping,
which would hide the agent's / user's miscount. The guard uses a 1e-3
epsilon to let equal-duration fades (fadeIn + fadeOut == duration,
common for short stings) pass despite float noise.

**Why audio-only, with no sibling for video.** Video clips don't carry
audio fields in the current data model (a clip's "audio" is either its
source track, mixed opaquely by the renderer, or a separate audio
clip). Extending fade-in/out to `Clip.Video` would cross into the
missing "video clip audio track mixer" territory — a bigger scope that
deserves its own design. Rejecting video here with a loud error keeps
the abstraction honest: the tool is about the `Clip.Audio` envelope,
not a cross-clip audio mixer.

**Why text clips are also rejected.** `Clip.Text` has no notion of
amplitude, so a fade is meaningless. Rejecting matches
`set_clip_volume`'s established shape and keeps the agent from
reaching for the tool to "fade a subtitle" (the caller probably wants
`transforms.opacity` via `set_clip_transform`).

**Why the field is captured in Project state even though no engine
renders it yet.** Same "compiler captures intent, renderer catches up"
pattern as `set_clip_volume` and `set_clip_transform`: the Project is
the canonical edit state, and renderers are free to lag. Shipping the
tool first means the agent can accept and record fade requests today;
the FFmpeg / AVFoundation / Media3 engine passes are tracked as known
follow-ups (the system prompt discloses this explicitly so the agent
doesn't over-promise render fidelity).

**Alternatives considered.**
1. *Fold fade into `set_clip_volume` as optional `fadeInSeconds` /
   `fadeOutSeconds` fields.* Rejected — overloads "set level" with "set
   envelope", produces a tool whose name no longer describes its job.
   Separate tools stay composable (agent can mute now, add fade later).
2. *Model fade as a timeline-side automation curve instead of a clip
   field.* Rejected for now — automation curves are a bigger scope
   (needs keyframe infra, curve editor, cross-clip automation targets).
   Clip-bound fade fields cover the 80% case cheaply; curves can live
   in the same clip later via a new `volumeCurve` field without
   breaking the fade shorthand.

---
