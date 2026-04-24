## 2026-04-23 — Per-clip cache-key composition + mutation/invalidation matrix for incremental export (VISION §5.7 rubric axis)

**Context.** P1 bullet `export-incremental-render-phase-1-cache-key-design`
from the cycle-31 repopulate; first of three split from the skipped
`export-incremental-render` that the 2026-04-23 skip-tag asked to be
decomposed into `(1) key design, (2) memoization wiring,
(3) invalidation edge tests`.

During plan-time code reading, the picture is different from what the
bullet premised: the per-clip cache is **already implemented** via
`clipMezzanineFingerprint`
(`core/src/commonMain/.../domain/render/ClipFingerprint.kt`) and
wired through `runPerClipRender`
(`core/src/commonMain/.../tool/builtin/video/export/PerClipRender.kt`).
`ExportTool.execute` dispatches to the per-clip path when
`engine.supportsPerClipCache && timelineFitsPerClipPath(timeline) != null`.
Three of the four key axes the bullet names are live:

1. **Clip spec hash** ✓ — `json.encodeToString(Clip.Video.serializer(), clip)`
   covers assetId, sourceRange, filters, transforms, sourceBinding. Any
   field change perturbs the JSON.
2. **Source binding deep-hash** ✓ — per-bound-node
   `deepContentHashOf(id, cache)` folded in sorted order. Empty map
   (imported media, pre-lockfile clips) is a valid empty segment.
3. **Render profile hash** ✓ — resolution + fps + video/audio codec +
   bitrate. `outputPath` + `container` are explicitly excluded so
   mezzanines survive retarget-to-different-path exports.
4. **Engine id** ✗ — NOT in the fingerprint. See "The missing axis" below.

Plus a fifth axis the bullet didn't mention but the code already
threads through: **transition-fade context** (head/tail fades inherited
from neighbouring transition clips, via
`Timeline.transitionFadesPerClip(videoClips)`). A transition edit that
doesn't touch the clip itself still changes the fade envelope —
fingerprint folds it in as `h=<headFadeNs>,t=<tailFadeNs>` so a
transition move correctly invalidates only the two affected clips.

Rubric delta §5.7 (incremental-export invariant coverage): the live
lane moves from **部分** (fingerprint implemented but un-specified —
readers have to reverse-engineer the invalidation behaviour from
inspection) to **有** (specification landed + missing-axis flagged as
an actionable phase-2 dependency). Phase 2 + 3 bullets can now
reference this file as the contract.

**Decision.** Formalise the per-clip cache-key composition as follows.
Phase 2 (next bullet) adds the missing engine-id axis; phase 3 pins
each matrix row as a regression test.

### Key composition (canonical order)

```
fingerprint = fnv1a64Hex(
    "clip=" <canonical JSON of Clip.Video record>
  + "|fades=h=<headFadeNs>,t=<tailFadeNs>"
  + "|src=" <sorted-by-nodeId>(<nodeId>=<deepContentHash>;)*
  + "|out=res=<W>x<H>,fps=<F>,vc=<vc>,vb=<vb>,ac=<ac>,ab=<ab>"
  + "|engine=<engineId>"   // ← NEW in phase 2
)
```

**Ordering and canonicalisation invariants (load-bearing):**

1. Segments are `|`-separated with fixed lexical order:
   `clip | fades | src | out | engine`. Order is load-bearing — a
   different order would produce a different hash for byte-equivalent
   inputs. Phase 2 must append `engine` after `out`, not insert it
   between existing segments (inserting would break the
   fingerprints of every existing cache entry on every live bundle,
   mass-invalidating caches for no behaviour change).
2. Source-binding map is sorted by `SourceNodeId.value` before
   emission. Kotlin/Native (iOS) and Kotlin/JS have historically
   produced non-deterministic Map iteration; sorted emission means
   fingerprints survive JVM ↔ Native moves.
3. Fades emit `-1` sentinel when absent (null fades). Converting to
   nanoseconds (`Duration.inWholeNanoseconds`) picks a units-stable
   representation; string-formatting a `Duration` changes with locale
   / kotlinx-datetime version.
4. FNV-1a 64-bit hex is the chosen hash — same as whole-timeline
   `fingerprintOf`'s scheme. 2⁶⁴ is plenty for the dozens-to-low-
   thousands per-project entries this cache holds.

### The missing axis: `engineId`

`VideoEngine` today exposes `supportsPerClipCache: Boolean` but not a
stable id. Mezzanines produced by different engines at the same
`OutputSpec` are **not** byte-compatible — FFmpeg's x264 tune differs
from Media3's hardware-accelerated codec which differs from
AVFoundation's AVAssetWriter default. The current protection against
cross-engine reuse is purely incidental: mezzanine paths are
machine-local (`<outputDir>/.talevia-render-cache/<projectId>/<fingerprint>.mp4`)
and `engine.mezzaninePresent(path)` returns false when a FFmpeg-
machine's path doesn't exist on an Android tablet. This works TODAY
but is fragile:

- A shared-filesystem export target (NFS, cloud sync, Git-tracked
  cache) would allow `mezzaninePresent` to return true across
  machines, silently serving a FFmpeg-rendered mp4 to a Media3
  request.
- A future "pin the rendered cache into the bundle" feature
  (`copy_into_bundle` semantics extended to mezzanines) would
  definitely break cross-engine safety.

Phase 2 decision (next cycle): add `VideoEngine.engineId: String` as
a non-optional member with these stable values:
- `"ffmpeg-jvm"` for `platform-impls/video-ffmpeg-jvm`
- `"media3-android"` for the Android Media3 engine
- `"avfoundation-ios"` for the iOS AVFoundation engine
- `"noop"` for test fakes (`NoopVideoEngine`)

Append `|engine=<engineId>` to `clipMezzanineFingerprint`. Changing
the fingerprint composition mass-invalidates existing
`ClipRenderCacheEntry` rows on every live bundle — that's a ONE-TIME
acceptable cost for correctness (the entries' underlying mp4 files
become unreachable and get GC'd by the next
`project_maintenance_action(action=gc-render-cache)`). Document in the
phase-2 decision file as a migration note.

Alternative (rejected): partition the mezzanine *directory* by
engineId rather than the *fingerprint*. Would leave the fingerprint
unchanged but isolate the on-disk files. Problem: two engines with
the same fingerprint still produce incompatible mp4s; the cache rows
would collide in `ClipRenderCache.findByFingerprint(...)` and hand
back whichever engine happened to write last. The fingerprint is the
index; correctness needs the index to include engine.

### Mutation / invalidation matrix

Each row names (a) the edit, (b) which clips' fingerprints change,
(c) which mezzanines must be re-rendered. Phase 3 pins each with a
regression test. The "why" column explains the fingerprint segment
that participates.

| Edit | Fingerprints changed | Must re-render | Why (segment) |
|---|---|---|---|
| `add_clip` (new `Clip.Video` on video track) | 1 — the new clip (no prior entry) | 1 | no entry to invalidate; first-render on next export |
| `remove_clips(ids)` | 0 | 0 | removed clips' entries orphan in cache (age out via `gc-render-cache`); untouched clips keep their entries |
| `move_clip(id, newStart)` (changes `timeRange.start` but not `sourceRange`) | 0 — `sourceRange` is in the JSON, `timeRange` is not | 0 | concat step re-applies at new time without re-render. **Counter-intuitive**: phase 3 test (a) in the bullet |
| `trim_clip(id, newSourceRange)` | 1 — clip's JSON changes | 1 | clip-spec segment changes; re-render needed |
| `set_clip_transforms(id, …)` | 1 — transforms are in the JSON | 1 | clip-spec segment |
| `apply_filter(ids, "brightness", …)` | len(ids) — each affected clip's filters list grows | len(ids) | clip-spec segment |
| `remove_filter` | len(ids) | len(ids) | clip-spec segment |
| `add_transition(fromId, toId, fade, duration)` | 2 — the adjacent pair's fade envelope flips from `-1,-1` to `X,Y` | 2 | fades segment. **Counter-intuitive**: changing a transition changes two clips, not three |
| `remove_transition(id)` | 2 — adjacent pair's fades revert | 2 | fades segment |
| `reorder_tracks(newOrder)` | 0 — `Track` order isn't in `Clip.Video` JSON | 0 | **Counter-intuitive**: phase 3 test (a) in the bullet pins this — same clip JSON → same fingerprint → cache hit even after track reorder |
| `duplicate_clip(id)` | 0 for the original, 0 for the copy (same JSON, same fingerprint) | 0 | **Both clips share one cache entry**. If rendered once, exported twice. Bonus memoization win |
| `update_source_node_body(characterRefId, …)` — character_ref's body changes | N where N = clips transitively bound to the character_ref | N | deep-hash segment. Descendant clips (see `source-consistency-propagation-runtime-test` decision) are pulled in via `deepContentHashOf` |
| `update_source_node_body(styleBibleId, …)` — grandparent edit, transitively bound clips | M where M includes both direct binders AND descendants | M | deep-hash segment + transitive closure via `deepContentHashOf` (cycle-27 test pinned) |
| `add_source_node` without binding it | 0 | 0 | unbound source node has no clip effect; deep hashes of bound nodes unchanged |
| Export to different `width`/`height`/`frameRate`/`videoCodec`/`audioCodec`/`videoBitrate`/`audioBitrate` | ALL (N clips) | N | render-profile segment |
| Export to same profile, different `outputPath` | 0 | 0 | `outputPath` + `container` excluded from fingerprint by design. Concat step writes to new path from cached mezzanines. **Counter-intuitive win**: re-export same cut to a second file == free concat |
| Export to different `container` (e.g. `.mp4` → `.mov`) | 0 (fingerprint unchanged) | 0 at the clip level; concat stage writes the new container | same mezzanines work — container is a wrap |
| Cross-engine: FFmpeg-rendered mezzanine, Media3 re-export | (phase 2) ALL | N | engine segment. **Counter-intuitive**: phase 3 test (c) in the bullet pins this — MUST miss the cache even when every other segment matches |
| Move the project bundle to a different machine / filesystem | 0 (fingerprints stable) | N-hits where hits = cached mezzanines still reachable at their recorded paths | `mezzaninePresent(path)` check in `runPerClipRender:139` — fingerprint matches but file isn't there → re-render. Stale `ClipRenderCacheEntry` ages out on next GC |

### Counter-intuitive edges (targets for phase 3)

Phase 3 bullet lists three counter-intuitive edge cases; this matrix
lays them out with the mechanism:

- **(a) Same clip on two tracks — changing one track order must not
  invalidate the other's cache entry.** Today the per-clip path
  requires exactly one video track (`timelineFitsPerClipPath`
  rejects multi-video), so "same clip on two tracks" is out of scope
  for the per-clip cache — phase 3 test should cover "multi-track
  timeline falls back to whole-timeline render + whole-timeline cache
  entry is invalidated on order change", NOT "per-clip cache survives
  order change".
- **(b) Identical clip spec, different source DAG ancestors — must
  miss.** Covered by the deep-hash segment: the clip's JSON
  sourceBinding points at the same node ids, but
  `deepContentHashOf(nodeId)` differs when the ancestors differ. Phase
  3 test: two projects with identical character_ref bodies but
  different style_bible ancestors produce different mezzanine
  fingerprints for clips bound to the character_ref.
- **(c) Cross-engine cache reuse — FFmpeg-rendered artefact must NOT
  serve a Media3 request.** THIS IS THE MISSING-AXIS CASE. Today,
  `mezzaninePresent(path)` is the only barrier and it's path-based,
  not engine-compatibility-based. Phase 2 must ship before phase 3's
  test (c) can pass cleanly — the test currently would pass
  incidentally because machine-local paths don't cross-pollinate, but
  a shared-filesystem variant of the test would expose the leak.

### Non-goals of this decision

- **Do not change the fingerprint composition this cycle.** That's
  phase 2 (engine-id addition). Phase 1 is pure specification.
- **Do not add new axes beyond engine-id.** Phase 2 + 3 can evaluate
  whether `platform` / `codec-implementation-version` / `bundle-
  schema-version` need their own segments; none are load-bearing
  today.
- **Do not touch the fallback path** (whole-timeline render's
  `fingerprintOf(timeline, output)` in `ExportTool`). Whole-timeline
  caching is a separate concern (memoizes the full final .mp4) and
  its fingerprint covers the Timeline's composition including track
  order — not relevant to per-clip invalidation. Phase 2 might or
  might not add engine-id there too; the phase-2 decision will
  explicitly call out the whole-timeline question.

**Axis.** Spec + matrix coverage of the per-clip cache-key. Before:
implicit in code; reader has to reverse-engineer. After: explicit
spec + mutation/invalidation matrix + named missing axis (engine-id).
Pressure source that would re-trigger this decision file: a new
axis becoming load-bearing (e.g. if Kotlin Multiplatform ever adds
a codec-version surfaced through `OutputSpec`, the decision
"should it go in clip spec / out spec / its own segment?" would
warrant a phase-1b decision).

**Alternatives considered.**

- **Skip-close the bullet as "stale-no-op: already implemented".**
  3 of 4 axes are implemented, so the bullet's phase-1 goal of
  "define the composition" is partly moot. Rejected: the engine-id
  gap is a real correctness hole that benefits from being named
  explicitly + scoped to phase 2. Skip-closing would leave the
  gap undocumented. Also, the mutation/invalidation matrix isn't
  anywhere in code or prior decisions — phase 3's test design
  needs it as the contract to test against.

- **Roll phase 1 + 2 into one cycle** (land the engine-id addition
  now). Rejected: phase 1's skip-tag explicitly said "decision-only
  cycle, no code". Respecting the decomposition keeps each cycle's
  diff reviewable and means phase 2's implementation has this
  decision file as its specification to test against.

- **Drop the matrix, just write the 4-axis spec.** Rejected: the
  matrix is the specification in action. A reviewer who picks up
  phase 3 without the matrix has to re-derive each invalidation
  case from the source — same cost as phase 1 had. Writing the
  matrix once ≤ N re-derivations by later cycles.

- **Include engine-id in this cycle's "code change section" after
  all.** Tempting — it's a one-line addition to
  `clipMezzanineFingerprint` + one new field on `VideoEngine`. But
  the bullet explicitly says "No code changes yet; phase 2 + 3
  bullets depend on this cycle's decision file". Respecting scope.

**Coverage.** No code changes; no tests to run for new code. The
existing `clipMezzanineFingerprint` + `runPerClipRender` continue to
pass their existing tests. This decision's coverage is its usefulness
as the phase 2 + 3 contract; the proof of coverage is the
mutation/invalidation matrix mapping to concrete test names in
phase 3.

**Registration.** None — pure documentation cycle. No tool, no
AppContainer, no schema, no serialization surface change.
