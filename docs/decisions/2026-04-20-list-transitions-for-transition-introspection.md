## 2026-04-20 — `list_transitions` for transition introspection

**Context.** `AddTransitionTool` encodes a transition as a
synthetic `Clip.Video` on an `Track.Effect` track with the
sentinel `assetId = "transition:<name>"`. That encoding is
intentional — keeps the [Clip] type safe — but the encoding
leaks the moment any reader wants to reason about transitions
as transitions. Previously the agent had two options:

- `list_timeline_clips` with `trackKind=effect`, then filter
  by `assetId` prefix in its own logic. Every caller
  re-implements "is this a transition?" and the parse drifts.
- `get_project_state` and pick transitions out by hand.

And neither tool gave the (fromClipId, toClipId) pair — the
agent that earlier called `add_transition("v1","v2",…)` has no
way to go from a `transitionClipId` back to those handles
without walking the timeline by timestamp itself.

**Decision.** Ship `list_transitions(projectId)`:

1. Walks every `Track.Effect` clip that's a `Clip.Video` with
   the `transition:` sentinel assetId. Non-sentinel effect
   clips (hand-parked overlays) are ignored — that keeps the
   contract tight with `remove_transition`.
2. Recovers `fromClipId` / `toClipId` by scanning
   `Track.Video` clips whose boundaries meet the
   transition's midpoint within a **34ms epsilon** (one frame
   at 30fps). `AddTransitionTool` centres the transition on
   the cut, so the midpoint equals `fromClip.end` =
   `toClip.start`. Epsilon survives Duration rounding without
   admitting false positives from unrelated cuts further out.
3. Flags `orphaned=true` when **both** flanking clips are
   missing (agent earlier called `remove_clip` on them and
   forgot to `remove_transition`). Reports a partially-
   resolved pair (`fromClipId=null`, `toClipId="v2"`) without
   marking orphaned — the transition still renders on one
   side.
4. Returns `transitionName` from the first filter (which
   AddTransition always writes), falling back to the
   assetId suffix for resilience.
5. Results sorted by `startSeconds` so the agent's dump
   reads chronologically.
6. Read-only `project.read` — cheap to call repeatedly.

**Alternatives considered.**

- *Add a `transitionOnly` flag to `list_timeline_clips`.*
  Rejected — the two tools differ in *what* they project
  (clip fields vs transition-specific fields with
  resolved pair); mashing them together bloats the output
  schema. Separate verbs per concept scale better as more
  clip-like things (transitions today, effects tomorrow)
  get modelled.
- *Infer orphaned as `fromClipId == null || toClipId ==
  null`.* Rejected — a one-sided resolution still renders on
  the surviving side; "both missing" is the only state the
  agent should GC with `remove_transition`.
- *Use a wider epsilon (e.g. half-frame at 24fps = 20ms).*
  34ms is one frame at 30fps, one-and-a-third at 24fps.
  Going narrower would false-negative on 24fps projects;
  going wider invites matches on neighbouring cuts in
  rapid-cut sequences.
- *Store back-references on the transition clip itself at
  add time.* Tempting, but mutates the [Clip] model for what
  is still a contingent encoding, and it'd drift under
  remove_clip / move_clip on the flanking pair. Recomputing
  at read is cheap and always accurate.

**Coverage.** 10 JVM tests:
- Empty timeline → zero transitions.
- Single transition resolves its flanking pair.
- Multiple transitions sorted by startSeconds.
- Removing both flanking clips → `orphaned=true`.
- Removing one flanking clip → partial resolution, not
  orphaned.
- Transition name echoes filter name (wipe/fade/dissolve).
- Non-sentinel effect clips ignored.
- Rejects missing project.
- `durationSeconds` propagated correctly.
- `transitionClipId` / `trackId` match `AddTransition`
  output.

**Registration.** Registered in all 5 composition roots
directly after `ListTracksTool` — read cluster grows in
layout → tracks → transitions → clips → assets order.

**SHA.** 0e90245f49bb2f2ae2f5191c0f6e9fc2c752d074

---
