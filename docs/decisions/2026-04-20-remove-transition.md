## 2026-04-20 — `remove_transition` (AddTransition counterpart)

**Context.** [AddTransitionTool] records a transition as a
synthetic [Clip.Video] parked on an [Track.Effect] track, with
a sentinel `assetId = "transition:<name>"` and the engine-side
transition parameters carried in `Clip.filters[0]`. There was
no paired `remove_transition`; the agent either:

- Used `remove_clip` on the transition's clipId — which works,
  but accepts **any** clip id. On handle confusion it silently
  deletes a regular video clip instead of the transition.
- Called `revert_timeline` to unwind to a pre-transition
  snapshot — throws away every later edit too.
- Did nothing, and the "remove that fade" request failed.

Every `add_*` verb we've landed in this session has a paired
`remove_*` — `add_clip`/`remove_clip`, `add_filter` (via
`apply_filter`) / `remove_filter`, `add_track`/`remove_track`.
Transitions were the outlier.

**Decision.** Ship `remove_transition(projectId, transitionClipId)`:

1. Validates the target is **on an [Track.Effect]** track.
2. Validates the clip is a [Clip.Video] whose `assetId.value`
   starts with `"transition:"`. Anything else is rejected with
   a message that points the agent at `remove_clip`.
3. If the id matches a clip on a non-effect track, the error
   message tells the agent specifically ("clip X is on a video
   track, not a transition"), so typos don't generate a
   generic "not found" and force a retry.
4. **No ripple.** Transitions live in the overlap window of
   two adjacent clips; the two flanking clips are unchanged.
   The engine falls back to a hard cut at render.
5. Emits `Part.TimelineSnapshot` so `revert_timeline` can
   re-insert the transition in one step.
6. Output includes `transitionName` (echoed from the first
   filter, falling back to the sentinel suffix) and
   `remainingTransitionsOnTrack` so the agent doesn't have to
   re-query.

**Permission.** `timeline.write`, same tier as the add.

**Alternatives considered.**

- *Accept `(fromClipId, toClipId)` instead of a transition
  clip id.* Rejected — the add tool already returns
  `transitionClipId` in its output; this paired verb keying
  off the same handle is the tighter contract.
- *Let `remove_clip` handle it.* Rejected — that tool's
  permissive contract is the problem; a distinct verb makes
  the error budget right.
- *Implicitly fall back to a hard cut if the only remaining
  clip gap is zero.* Nothing to do — adjacent clips already
  butt up against each other; the engines cut on the boundary
  automatically once the transition is gone.

**Coverage.** 10 JVM tests:
- Happy path: removes transition, flanking video clips
  unchanged, effect track count drops to 0.
- Exactly one `TimelineSnapshot` emitted per removal.
- Rejects a regular clip id on a Video track (error mentions
  `remove_clip`).
- Rejects an unknown id.
- Rejects a missing project.
- Rejects an effect-track clip that lacks the `transition:`
  sentinel assetId (hand-authored effect-clip case).
- Multi-transition case: remove one, the other remains with
  remainingTransitionsOnTrack=1.
- `transitionName` echoes the filter name (dissolve vs fade).
- Snapshot timeline reflects the removal.
- Snapshot id is fresh (not a reused callId).

**Registration.** Registered in all 5 composition roots
directly after `AddTransitionTool` — add/remove pairs are
kept adjacent in the registry for grep-ability.

**SHA.** 26a8842fc80e08af531453b086dced1e9de28557

---
