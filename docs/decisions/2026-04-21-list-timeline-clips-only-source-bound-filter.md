## 2026-04-21 — list_timeline_clips gains onlySourceBound filter (VISION §5.1 Source → Clip traceability)

Commit: `be536cb`

**Context.** VISION §5.1 asks for Source → Clip traceability: given a
source-DAG node, can the agent find every timeline clip compiled from
it, and given a clip can it walk back to the source nodes? The symmetric
query already worked — every `Clip` carries a `sourceBinding: Set<SourceNodeId>`
and `ListTimelineClipsTool` already surfaces it on each row. What was
missing was the other leg of the filter: "only AIGC-derived clips",
i.e. clips with a non-empty `sourceBinding`. Stale-clip / regeneration
flows care exactly about this set — imported footage (user-uploaded
B-roll, dropped-in audio) has an empty sourceBinding and is never "stale"
against the DAG, while compiled AIGC output is the whole target surface
for `regenerate_stale_clips`. Before this change the agent had to
`list_timeline_clips` then client-side filter on
`sourceBindingNodeIds.isEmpty()` — cheap, but noisy in the tool-use
trace and easy to get wrong when composing with `trackKind` / time window.

**Decision.** Add `onlySourceBound: Boolean? = null` to
`ListTimelineClipsTool.Input`. When `true`, drop every clip whose
`clip.sourceBinding` is empty before counting toward `totalClipCount` /
`limit`. `null` (omitted) and `false` preserve today's behaviour —
return every clip that matches the other filters. `totalClipCount`
reflects the post-filter matched set (not the pre-filter universe), so
`truncated` accounting stays consistent with the existing `trackKind`
/ time-window filters which already exclude before counting. The schema
surface describes it as "If true, only return clips with a non-empty
sourceBinding (AIGC-derived)." so the LLM knows both what it does and
when to reach for it.

**Alternatives considered.**

1. *Inverse filter only: `onlyImported: Boolean?`.* Rejected — the
   stale-clip / regeneration use case is the hot path and "AIGC-derived"
   is the semantically positive framing ("things the DAG owns"). An
   inverse filter would force the agent to think in negations on the
   common case. If "only imported" becomes useful later we can add a
   second flag or promote to a tri-state enum without breaking the
   current surface.
2. *Tri-state enum (`sourceBound: "only" | "exclude" | "any"`).*
   Rejected for v1 — no caller today needs "only imported", and adding
   an enum when a boolean suffices front-loads optionality we'd have to
   maintain. The `Boolean?` shape is easy to extend to an enum later
   (deprecate the bool, add the enum, accept both for one release).
3. *Leave it to client-side filtering over `sourceBindingNodeIds`.*
   Rejected — client-side filtering works but forces the agent to pull
   every row through context just to throw half away. For a 200-clip
   narrative project that's a real token cost on every stale-check
   turn. Server-side filter keeps the response tight.
4. *Expose `hasSourceBinding: Boolean` per-row and let the agent
   filter on it in a follow-up `jq`-style pass.* Rejected — same
   context-cost critique as alt 3, plus we don't ship a post-query
   projection layer and don't want to grow one just for this.

**Coverage.** Three new cases in `ListTimelineClipsToolTest`:

- `onlySourceBoundReturnsOnlyAigcDerivedClips` — two clips on one
  track, one with `sourceBinding = {n1}` and one without; the filter
  returns only the AIGC clip and `totalClipCount == 1`.
- `onlySourceBoundComposesWithTrackKind` — four clips across video +
  audio tracks, mixed source-binding; `trackKind = "audio"` combined
  with `onlySourceBound = true` yields exactly the AIGC audio clip,
  proving orthogonal composition.
- `onlySourceBoundFalseOrNullMatchesDefaultBehaviour` — same project
  run with the filter omitted, explicit `null`, and explicit `false`
  returns identical clip lists and counts, proving backwards-compat.

**Registration.** No-op — no new tool registered. `ListTimelineClipsTool`
is already wired in `CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`; this is a pure input
extension.
