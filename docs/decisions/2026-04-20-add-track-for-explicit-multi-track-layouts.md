## 2026-04-20 — `add_track` for explicit multi-track layouts

**Context.** `add_clip` / `add_subtitle` auto-create a track of
the needed kind when none exists, but they pick the *first*
matching track deterministically. That's fine for single-layer
edits ("put this video on the video track") but silently makes
three layouts impossible to express:

- **Picture-in-picture.** Foreground logo over background footage
  needs two parallel `video` tracks — auto-pick always lands both
  clips on the same track, stacking them temporally instead of
  spatially.
- **Multi-stem audio.** "Dialogue, music, and ambient on separate
  tracks so I can level them independently" — auto-pick collapses
  them all into one audio track, where volume changes bleed.
- **Localised subtitles.** Separate `Subtitle` tracks for EN / ES
  / JA variants the render pipeline can toggle — auto-pick makes
  the tracks indistinguishable.

Workaround today: call `add_clip` with a made-up `trackId` on the
expectation that it auto-creates a fresh track. This works for
the first clip per track but is fragile — `add_clip` doesn't
guarantee the trackId is a hint (it depends on the tool's
`pickVideoTrack`-style helper), and nothing exists to let the
agent pre-declare a track for *later* population.

**Decision.** Ship `add_track(projectId, trackKind, trackId?)`:

1. `trackKind` ∈ {`video`, `audio`, `subtitle`, `effect`} —
   case-insensitive, validated against the Track sealed-class
   variants.
2. `trackId` is optional; generated UUID by default. When
   provided, fails loud if it collides with an existing track id.
3. Appends an empty track of the chosen variant — zero clips, no
   effect at render time.
4. Emits a `Part.TimelineSnapshot` so `revert_timeline` can undo
   (the agent may have told the user "I've set up the dialogue
   track for you" before it's populated).

**Permission.** `timeline.write`. Same tier as `add_clip`.

**Alternatives considered.**

- *Add a `createTrack` hint to `add_clip`.* Rejected —
  overloads a clip-authoring tool with layout semantics, and
  does nothing for pre-population flows where no clip exists yet.
- *`add_tracks` plural with a list of kinds.* Possible but
  encourages a planning style ("set up 5 tracks, then start") we
  don't have evidence the agent wants. One-at-a-time is fine;
  three calls is cheaper than the wrong batch semantics.
- *A richer `Track` model with `name` and `role`.* Tempting
  (would let the agent say `"track=dialogue"` without picking
  the id), but bakes ontology into the schema without a concrete
  driver. Defer until a real naming / routing need appears.

**Coverage.** 10 JVM tests:
- Adds a video track with generated id.
- Adds an audio track with explicit id.
- Adds subtitle / effect tracks.
- trackKind is case-insensitive (leading/trailing whitespace OK).
- Rejects duplicate track id.
- Rejects unknown kind (IllegalArgumentException).
- Rejects missing project (IllegalStateException).
- Emits exactly one timeline snapshot.
- Three sequential audio adds produce three distinct audio tracks
  (the PiP / multi-stem workflow).

**Registration.** Registered in all 5 composition roots directly
after `add_transition` — new-track / new-clip authoring verbs
clustered together.

---
