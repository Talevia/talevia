## 2026-04-19 — `set_clip_transform` (visual transform editor)

**Context.** `Clip.transforms: List<Transform>` has existed since M0 and
every clip carries it (`translateX/Y`, `scaleX/Y`, `rotationDeg`,
`opacity`). No tool ever set it, so the field was dead state. Requests
like "fade the watermark", "make the title smaller", "move the logo to
the corner for PiP", or "rotate the card 10°" had no completion path —
the only option was `remove_clip` + re-`add_clip`, which `add_clip`
doesn't expose transform knobs for either. Parallel to the
`set_clip_volume` gap for audio: the field was there, the setter
wasn't.

**Decision.** New `core.tool.builtin.video.SetClipTransformTool`. Tool
id `set_clip_transform`, permission `timeline.write` (ALLOW). Input:
`(projectId, clipId, translateX?, translateY?, scaleX?, scaleY?,
rotationDeg?, opacity?)` — every knob optional, at least one must be
set. Emits `Part.TimelineSnapshot`. Registered in all four
composition roots (desktop / server / Android / iOS).

**Why one "setter of many fields" tool, not four sub-tools.**
Considered `set_clip_opacity` / `set_clip_scale` / `set_clip_position` /
`set_clip_rotation` as separate tools (parallel to `set_clip_volume`'s
single-knob shape). Rejected: user intents like "scale the logo to
40% AND position it top-right" are one mental step but would require
two tool calls, each with its own snapshot. One tool with optional
knobs composes the common case naturally. The cost is a fatter
schema; acceptable since the LLM only sends the fields it cares
about.

**Why merge overrides onto the current transform, not replace fully.**
A user saying "fade the watermark to 0.3" means "opacity=0.3, leave
everything else". If the tool replaced the whole transform with
`Transform(opacity=0.3f)`, the clip's existing scale / position would
silently reset. Merging preserves context. Unspecified fields inherit
from `clip.transforms.firstOrNull()` — if absent, they inherit from
`Transform()` defaults.

**Why normalize `transforms` to a single-element list, not append.**
The `List<Transform>` shape was designed for a hypothetical
composition stack (translate-then-scale-then-rotate as ordered passes).
No renderer actually consumes that ordering today. Modeling "the
clip's transform" as one record matches the user's mental model and
keeps the tool idempotent: calling it twice with the same input
produces the same state. If ordered composition ever becomes a real
concern, a second `push_transform` tool can own that semantic without
breaking this one.

**Clamps.**
- `opacity ∈ [0, 1]` — anything outside is meaningless on screen.
- `scaleX` / `scaleY > 0` — zero collapses, negative is an unsupported
  mirror (a real `flip_clip` tool would own that cleanly).
- `rotationDeg` unclamped — float is valid, renderers take mod 360.
- `translateX/Y` unclamped — units are engine-defined (pixels on
  FFmpeg/AVFoundation, normalized on Media3). Clamping here would
  bake the wrong model.

**Why no block on audio clips.** `Clip.Audio` inherits the `transforms`
field from the base class. Writing it is dead state at render time
(audio has no visual), but the data model permits it. Rejected
blocking (symmetrical to `set_clip_volume` blocking video) because
the field is genuinely there, and gating adds a surface-area
inconsistency: "sometimes-allowed field" creates more cognitive load
than "always-allowed, no-op on audio." The system prompt states
explicitly that audio calls are no-op at render time so the model
doesn't reach for it by mistake.

**Alternatives considered.**
1. *Do nothing, wait for a UI that sets transforms directly.* Rejected —
   VISION §4 "agent-first" means every editable field needs a tool;
   tools are the only edit surface. A UI eventually calls the same
   tool, not a parallel path.
2. *Expose the full `List<Transform>` as input (advanced API).*
   Rejected — no current consumer benefits, and the schema becomes
   nested / ambiguous (what does "replace index 2" mean?). Ship the
   simpler setter first; grow if a user actually needs a stack.

**Test coverage.** 10 tests in `SetClipTransformToolTest` using a real
`SqlDelightProjectStore`: happy-path opacity set, partial merge (preserve
inherited fields), list-normalisation from multi-transform state, text-clip
support, filter / source-binding / timeRange preservation, no-op rejection
(all-null inputs), out-of-range opacity (> 1 and < 0), non-positive scale,
missing-clip fail-loud, snapshot emission.

---
