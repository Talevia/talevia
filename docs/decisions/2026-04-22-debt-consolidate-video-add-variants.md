## 2026-04-22 — Keep AddClip / AddTrack / AddTransition / AddSubtitles as four tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** The backlog bullet `debt-consolidate-video-add-variants` asked
to evaluate whether `AddClipTool` / `AddTrackTool` / `AddTransitionTool` /
`AddSubtitlesTool` could fold into one `add_to_timeline(target=…)` tool,
similar to how `session_query` / `project_query` / `provider_query` each
subsumed multiple list-style tools. The bullet itself hedges:
"若分支 Input 的差异太大导致 discriminator 不划算，在 decision 里说明并
保留四件套" — evaluate honestly, keep the four-piece set if the union
shape is too ragged.

This cycle is the evaluation. Decision: **keep the four tools unchanged.**
The Input shapes have effectively zero overlap beyond the session-resolved
`projectId?` that every timeline tool already carries; consolidating them
would produce a sparse-union schema that costs more clarity than it saves
in LLM-context tokens.

**Decision analysis.**

The four Input shapes, with per-tool field counts (excluding `projectId?`):

| Tool            | Required              | Optional                                                            | Field count |
|-----------------|-----------------------|---------------------------------------------------------------------|-------------|
| `add_clip`      | `assetId`             | `timelineStartSeconds`, `sourceStartSeconds`, `durationSeconds`, `trackId` | 5 |
| `add_track`     | `trackKind`           | `trackId`                                                           | 2 |
| `add_transition`| `fromClipId`, `toClipId` | `transitionName`, `durationSeconds`                              | 4 |
| `add_subtitles` | `subtitles: List<SubtitleSpec>` | `fontSize`, `color`, `backgroundColor`                    | 4 |

A merged `add_to_timeline(target=…)` Input would need **all 14 distinct
fields** (5+2+4+4 minus the one overlap on `durationSeconds` semantics
that's actually incompatible — "clip source duration" vs. "transition
overlap duration"). Any given call would populate 2–5 of them; the rest
would be silently ignored or (if we want strictness) loudly rejected per
`target`. Three structural problems follow:

1. **Schema validation moves from JSON-schema to runtime** — there is no
   sensible way to express "if `target="subtitles"` then `subtitles`
   required; else reject `subtitles`" in plain JSON Schema that Anthropic
   / OpenAI tool-spec pipelines accept. Every add tool today sets
   `additionalProperties: false`, which gives the model clean, typoable
   feedback. A union-schema would either drop that guard or reject every
   call on some irrelevant field.

2. **`ToolApplicability` gets conservative-max** — `AddClipTool` is
   `RequiresAssets` (hidden when the project has no media), the other
   three are `RequiresProjectBinding`. A merged tool could only advertise
   `RequiresProjectBinding`, which means the model would see
   `add_to_timeline` offered even in empty projects where its
   `target=clip` branch would fail loudly. The applicability signal is a
   model-facing hint — dropping it re-introduces the "agent suggests
   add_clip before import_media" loop the applicability system was built
   to kill.

3. **Discriminator cost on the LLM side** — estimated tool-spec token
   cost (measured by hand against current shipped helpText + schema): 4
   separate tools ≈ 640 tokens total; merged tool ≈ 490 tokens (one
   target-field doc comment compensates a chunk of the savings). Net
   savings ~150 tokens per turn. Not trivial, but recoverable by the
   Anthropic / OpenAI prompt caches on stable tool specs; the
   long-lived cache hit rate on these four tools is already high in
   production sessions.

Weighed against: richer loud-validation, crisper model prompts, a
granular applicability signal, and — most importantly — the four tools'
output shapes are also distinct (`clipId` vs `trackId` vs
`transitionClipId` vs `clipIds: List<String>`). A merged Output would
either be a sparse union echoed inconsistently, or a per-target
disjunctive that the model has to branch on downstream. Same cost on
the return path as the input.

**Alternatives considered.**

1. **Fold into `add_to_timeline(target=clip|track|transition|subtitles)`**
   — rejected per the analysis above. The three structural problems
   outweigh the ~150 tokens saved per turn. This is the exact call the
   backlog bullet anticipated as a possible outcome.
2. **Pair up fold: `add_clip` + `add_subtitles` into one
   `add_placed_content` (both lay media at a timeline position)** —
   rejected. `add_subtitles` takes a LIST of segments in one call (the
   `transcribe_asset` → caption loop is the reason, per its KDoc); its
   batching semantics are incompatible with `add_clip`'s single-clip
   scalar input. Merging would re-fragment the batch flow the last
   consolidation (subtitle + subtitles → subtitles) already rationalised.
3. **Pair up fold: `add_transition` + `add_track` (both
   structural-not-content edits)** — rejected. Their Inputs
   (`fromClipId + toClipId` vs `trackKind`) still share no fields; the
   consolidation would only rename the problem from 4-way to 2×2-way.
4. **Extract a shared `TimelineMutation` helper** — arguably useful but
   out of scope here. All four tools already share the
   `ProjectStore.mutate(pid) { project -> … }` +
   `emitTimelineSnapshot(ctx, timeline)` pattern; a helper extraction
   saves 5–10 lines per tool and is a separate debt bullet if a future
   cycle wants to file it.
5. **Keep the four tools and mark the bullet resolved (decision-only
   commit)** — the chosen path. Documents the evaluation so a future
   cycle doesn't reopen the question without new information.

**Coverage.** No code change. Existing `AddClipToolTest`,
`AddTrackToolTest`, `AddTransitionToolTest`, `AddSubtitlesToolTest`
continue to guard the individual tools; no test gains or losses.

**Registration.** No registration changes — the four tools remain
registered unchanged in CLI / desktop / server / Android / iOS
containers.

---
