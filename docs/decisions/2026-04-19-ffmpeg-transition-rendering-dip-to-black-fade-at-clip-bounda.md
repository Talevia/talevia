## 2026-04-19 — FFmpeg transition rendering — dip-to-black fade at clip boundaries

**Context.** `AddTransitionTool` wrote a synthetic `Clip.Video` to the Effect
track with `assetId = "transition:{name}"`, but no engine rendered it — the
exported mp4 had hard cuts regardless of `transitionName`. Behavioral parity
called for at least FFmpeg to honor transitions so the data model wasn't
lying to users.

**Decision.** Render **every** transition name (`fade`, `dissolve`, `slide`,
`wipe`, …) as a dip-to-black fade: the outgoing clip fades to black over
`duration/2`; the incoming clip fades in from black over `duration/2`.
Concretely, `FfmpegVideoEngine`:
1. Scans `Track.Effect` for clips with `assetId.value.startsWith("transition:")`
   and computes each transition's boundary = `transitionRange.start + duration/2`.
2. Maps each affected `Clip.Video.id` to a `ClipFades(headFade, tailFade)`
   where `halfDur = duration / 2`.
3. Emits `fade=t=in:st=0:d={halfDur}:c=black` for `headFade` and
   `fade=t=out:st={clipDur - halfDur}:d={halfDur}:c=black` for `tailFade`,
   comma-joined with any pre-existing filter chain inside `[N:v:0]…[vN];`.

**Why not a proper crossfade?**
- A crossfade (ffmpeg's `xfade` filter) requires the two clips to *overlap*
  on the timeline. Our `AddTransitionTool` keeps clips sequential and
  encodes the transition as a separate Effect-track clip at the boundary —
  there's no overlap to crossfade across, and changing the tool to produce
  overlap would cascade into Android/iOS engines that already assume the
  sequential model.
- Dip-to-black maps cleanly to per-clip opacity ramps on **all three**
  engines (FFmpeg `fade`, Media3 per-clip alpha effect, AVFoundation
  `setOpacityRamp(fromStartOpacity:toEndOpacity:timeRange:)`), so picking
  it as the cross-engine parity floor unblocks Task 7b / 7c without
  forcing a timeline-model rewrite.
- Users who specifically ask for a crossfade can get that later under a
  dedicated `overlap: true` tool option; for v1, naming a transition
  triggers the parity-floor fade.

**Alternatives considered.**
- **`xfade` filter for FFmpeg only.** Rejected: diverges from native engines,
  and requires restructuring the filtergraph (split, overlap, merge) vs.
  the current simple concat pipeline.
- **Render only `fade` and ignore other names.** Rejected: silent failure is
  worse than slight semantic mismatch. An LLM calling `transitionName="slide"`
  gets *some* transition instead of a hard cut.

**Scope.** The `fade` filter with `c=black` ships with every ffmpeg build
(part of `libavfilter`'s core, no libfreetype-style dependency), so no
feature detection / skip logic needed. Regression coverage:
- `TransitionFadesTest` — unit-level verification of `transitionFadesFor`
  boundary matching and `buildFadeChain` filtergraph output.
- `FfmpegEndToEndTest.renderWithTransitionProducesVideo` — drives
  import → add → add → add_transition → export through the real tool
  registry and asserts the output mp4 exists and is non-trivial.

---
