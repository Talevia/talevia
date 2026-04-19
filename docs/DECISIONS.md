# DECISIONS

Running log of design decisions made while implementing the VISION.md north star.

Each entry records the **decision**, the **alternatives considered**, and the **reasoning**
— the reasoning is what matters when someone comes back in six months and wants to
revisit. "We did X" without "because Y" rots.

Ordered reverse-chronological (newest on top).

---

## 2026-04-19 — Canonical Talevia system prompt lives in Core

**Context.** The Agent's `systemPrompt` defaulted to `null`. Tool schemas alone don't
teach the model the invariants the VISION depends on — build-system mental model,
consistency bindings, seed discipline, cache hits, dual-user distinction.

**Decision.** `core/agent/TaleviaSystemPrompt.kt` defines a terse canonical prompt +
a `taleviaSystemPrompt(extraSuffix)` composer. Both `AppContainer` (desktop) and
`ServerContainer` wire it into their Agent construction. Server appends a
headless-runtime note so the model doesn't plan around interactive ASK prompts.

**Why a single canonical prompt, not per-app.** The VISION invariants are about the
*system*, not a delivery surface. Forking prompts per app risks prompt drift. Apps
compose on top via `extraSuffix` for runtime-specific nuance (server = headless,
desktop = interactive).

**Regression guard.** `TaleviaSystemPromptTest` asserts each key-phrase (`Source`,
`Compiler`, `consistencyBindingIds`, `character_ref`, `cacheHit`, etc.) still appears.
If someone edits out a rule by accident, the test fails loudly.

**When to revise.** When a new VISION invariant lands (a new tool category, a new
consistency kind), the prompt is the second place to update after the tool itself.
Terseness matters more than exhaustiveness — every token ships on every turn.

## 2026-04-19 — Incremental render v1: full-timeline memoization, not per-clip

**Context.** VISION §3.2 calls for "只重编译必要的部分". Two levels of caching could
realize this:
1. **Coarse:** memoize the whole export — same `(timeline, outputSpec)` → reuse the
   previous output.
2. **Fine-grained:** render only the clips whose `sourceBinding` intersects the stale
   closure; reuse the rest at the clip level.

**Decision.** Ship the coarse cache (Level 1) now; defer Level 2 to per-platform work.

- `Project.renderCache: RenderCache` stores `RenderCacheEntry(fingerprint, outputPath,
  resolution, duration, createdAtEpochMs)`.
- `ExportTool` hashes the canonical `Timeline` JSON + `OutputSpec` fields with
  `fnv1a64Hex` to produce the fingerprint. A second call with identical inputs hits
  the cache — zero engine invocations, same `Output.outputPath`.
- `Input.forceRender` bypasses the cache for debugging / user-driven re-render.

**Why this respects the DAG without a separate stale check.** `Clip.sourceBinding` is
inside the Timeline. When an upstream source node changes, AIGC tools rewrite the
corresponding asset id on cache miss (Task 3 lockfile), which changes the timeline JSON,
which changes the fingerprint. So the DAG's stale-propagation lane flows through
`Source.stale → AIGC rewrite → timeline rewrite → render cache miss` without this
layer knowing anything about source nodes directly. Clean separation.

**Why Level 2 is deferred.** Per-clip render requires the `VideoEngine` to expose a
per-clip render path (render one clip to an intermediate file; compose at the end).
FFmpeg-JVM could do this today; AVFoundation and Media3 can too, but each is a distinct
compositing pipeline. Doing it uniformly across engines is a multi-week engine-side
project, not a core-side abstraction. Better to ship the coarse cache first so VISION §3.2
has *some* realization end-to-end, then revisit fine-grained once we're exercising the
coarse cache in real workflows.

**Staleness of the file on disk.** Cache entries aren't re-validated against the file
system. If the user deletes `/tmp/out.mp4` between exports, a cache hit returns the
path anyway. The user's remedy is `forceRender=true` — adding a file-existence check
in `commonMain` requires platform-specific plumbing we don't need yet.

## 2026-04-19 — AIGC lockfile on Project, cache key = canonical inputHash

**Context.** VISION §3.1 calls for "产物可 pin": key AIGC artifacts should be fixable so
a second call with identical inputs reuses the same output rather than re-抽卡. We need
a place to store the pin + a cache-lookup key shape.

**Decision.**
- `Project.lockfile: Lockfile` stores an ordered list of `LockfileEntry(inputHash,
  toolId, assetId, provenance, sourceBinding)`. Default empty; pre-lockfile projects
  decode cleanly.
- `inputHash` is `fnv1a64Hex` over a canonical `key=value|key=value…` string the tool
  builds from its full input surface (tool id, model + version, seed, dimensions,
  effective prompt after consistency fold, applied binding ids). Any field that can
  change the output goes into the hash.
- `AigcPipeline` (stateless object) exposes `ensureSeed`, `foldPrompt`, `inputHash`,
  `findCached`, `record`. Every AIGC tool consumes these — no inheritance.
- Lookup semantics: `findCached` returns the *last* matching entry, so a regenerate
  with identical inputs later in the project supersedes earlier matches for audit
  ordering but yields the same asset id.

**Alternatives considered.**
- **Composition-based `AigcPipeline` helper vs abstract `AigcToolBase` class.**
  Picked helpers: Kotlin inheritance for tools with different I/O shapes forces
  covariance tricks and "call super" discipline. Plain functions compose.
- **Content-addressed asset blobs (store keyed by file-hash).** Rejected for v1: the
  artifact layer already persists blobs through `MediaBlobWriter`; reusing an
  existing asset id by lockfile is enough. Content-addressing becomes interesting
  when we move to a **shared remote cache** — at that point swap `fnv1a64Hex`
  → SHA-256 and promote the lockfile to the content-hash'd layer.
- **Separate table for the lockfile (not on Project).** Rejected: the lockfile is
  per-project state, naturally travels with the `Project` serialized JSON blob in
  SqlDelight; separating it would add a second source of truth for the same project.

**Seed + caching interaction.** Cache hits are meaningful only when the seed is stable.
If the caller omits `seed`, `AigcPipeline.ensureSeed` mints one client-side and the
entry is recorded with that seed — a later call with `seed=null` mints a *different*
seed, producing a miss. The agent coach prompt (Task 5) should nudge toward explicit
seeds when the user wants determinism.

## 2026-04-19 — Consistency nodes live in Core, not in a genre extension

**Context.** VISION §3.3 demands first-class "character reference / style bible / brand
palette" source abstractions so AIGC tools have something to condition on for cross-shot
consistency. The question is: do these live under `core/domain/source/consistency/` or
inside every genre that needs them (`genre/vlog/`, `genre/narrative/`, …)?

**Decision.** Consistency nodes live in Core under
`core/domain/source/consistency/` with kinds in the `core.consistency.*` namespace.

- `CharacterRefBody(name, visualDescription, referenceAssetIds, loraPin)`
- `StyleBibleBody(name, description, lutReference, negativePrompt, moodKeywords)`
- `BrandPaletteBody(name, hexColors, typographyHints)`

**Why this does *not* violate "no hardcoded genre schemas in Core".** The anti-requirement
forbids *genre* schemas in Core — narrative, vlog, MV, etc. Consistency nodes are
*cross-cutting mechanisms* that every genre reuses to solve the same problem (identity
lock across shots). Defining them per-genre would either duplicate the schema or force
each genre to reinvent the wheel, neither of which serves VISION §3.3's goal of "a
single `character_ref` that transits all the way to every AIGC call in the project."

**Alternatives considered.**
- **One copy per genre.** Rejected: encourages drift (vlog's character looks slightly
  different from narrative's character), breaks the "change one character → all its
  references refactor" promise.
- **A generic `ConstraintNode` with a free-form JSON body.** Rejected: loses the
  guardrails on field names (every downstream fold function would string-match on
  keys). Typed bodies make the prompt folder's behavior auditable.

## 2026-04-19 — Consistency-binding injection via tool input, not ToolContext

**Context.** AIGC tools need access to consistency nodes at execution time. We could
surface them on `ToolContext` (every tool sees them) or on each tool's typed input
(only tools that want them declare them).

**Decision.** Each AIGC tool declares `projectId: String?` + `consistencyBindingIds:
List<String>` on its typed input. The tool carries a `ProjectStore?` via its
constructor and resolves bindings via `Source.resolveConsistencyBindings(ids)` during
`execute`. Tools without bindings / without a store fall back to prompt-only behavior.

**Why input, not context.**
- **Discoverability for the LLM.** Input fields appear in the tool's JSON schema, which
  is what the model reads. A field on `ToolContext` would be invisible to the model.
- **Narrow blast radius.** `ToolContext` is shared by every tool (timeline edits, echo,
  etc.); loading the project source eagerly for every dispatch would be wasted work
  for the vast majority of calls.
- **Tool-by-tool opt-in.** Some AIGC tools (e.g. future TTS on a named character) want
  bindings; some (e.g. a generic SFX synth) don't. Input declaration lets each tool
  own that choice.

## 2026-04-19 — Prompt folding order: style → brand → character → base

**Context.** When multiple consistency nodes apply, what order do they appear in the
folded prompt?

**Decision.** `foldConsistencyIntoPrompt` emits fragments in the order `[style] [brand]
[character] + base prompt`. Negative prompts are merged separately (comma-joined) and
returned to the caller; LoRA pins and reference asset ids are surfaced as separate
structured fields (they're provider-specific hooks, not prompt text).

**Why this ordering.** Diffusion models weight the tail of the prompt more heavily
(well-known inference-time behavior). The base prompt is the most specific signal
("what does this shot look like"), so it goes last. Identity (character) sits right
before it so the model enters the shot-specific portion already thinking about the
subject. Global look (style, brand) goes first because it sets the scene before
identity and content arrive.

## 2026-04-19 — Content hash for Source DAG: FNV-1a 64-bit hex (upgradable to SHA-256)

**Context.** VISION §3.2 calls for cache keys indexed by `(source_hash, toolchain_version)`.
That needs a deterministic, cross-platform content fingerprint for every `SourceNode`.
The stubbed `contentHash = revision.toString()` prevents any downstream caching from
working correctly — two unrelated edits can produce the same revision string.

**Decision.** Compute `SourceNode.contentHash` as a 16-char lowercase hex of FNV-1a 64-bit
over the canonical JSON encoding of `(kind, body, parents)`, delimited by `|`. See
`core/util/ContentHash.kt`.

**Alternatives considered.**
- **SHA-256 via expect/actual.** Industry standard for content-addressed storage (Nix,
  Git-LFS, Bazel). Rejected for v1: requires a crypto dependency or platform-specific
  actuals, and FNV-1a is sufficient inside a single project (≤10³ nodes, collision
  probability negligible).
- **`String.hashCode()` (Java 32-bit MurmurHash-ish).** Rejected: not guaranteed stable
  across Kotlin / Java versions; 32-bit is too narrow for future cross-project caching.
- **Keep the revision stub.** Rejected: blocks Task 3–4 (lockfile + incremental render).

**When to upgrade.** When we build a content-addressed **remote** artifact cache — shared
across projects or users — swap `fnv1a64Hex` for SHA-256. The upgrade path is a
single-function change because every caller goes through `contentHashOf`, and every
`SourceNode` recomputes its hash on write via `SourceMutations.bumpedForWrite`. Existing
projects will see their hashes re-derive on first write, which is fine: cache entries are
keyed by the hash, so a new hash just produces a cold cache.

---

## 2026-04-19 — Stale propagation: transitive downstream BFS, cycle-tolerant

**Context.** "Change traveling downstream through the DAG" is the primitive the rest of
the system needs. It has to be cheap to call per mutation (mutations happen on every
tool run) and robust to malformed graphs.

**Decision.** `Source.stale(changed: Set<SourceNodeId>)` returns the transitive closure
over the reverse-parent edges, including the seeds. Uses a BFS with a visited set, so
cycles don't hang. Unknown ids in `changed` are silently dropped from the seed set
rather than throwing — callers pass in "things I think changed" and get back "things
that are actually stale in the current graph."

**Alternatives considered.**
- **Reject cycles at the data layer.** Rejected: cycles are a *semantic* error, not a
  data-shape error. The loader can stay tolerant; a separate linter can surface cycles
  when a genre schema knows they're invalid.
- **Cache the child index on `Source`.** Rejected for v1: `Source` is an immutable
  value; callers that need the index across many `stale()` calls can hoist
  `Source.childIndex` themselves. Premature caching would complicate equality.

---

## 2026-04-19 — Clip → Source binding on the `Clip` sealed class

**Context.** Incremental compilation needs to know "which source nodes does this clip
depend on?". We could model that as a side-table keyed by `ClipId`, or as a field on
`Clip` itself.

**Decision.** Add `sourceBinding: Set<SourceNodeId> = emptySet()` as an abstract field
on `Clip`, defaulted on every variant. Empty set means "unbound" — the clip opts out of
incremental compilation and will always be treated as stale. AIGC tools that produce
clips populate this with the ids whose contentHash went into their prompt.

**Alternatives considered.**
- **Side-table on `Project`.** Rejected: drifts out of sync with clips when clips move
  between tracks or get split. Keeping the binding on the clip makes it travel with
  the clip through all mutations for free.
- **Optional `SourceNodeId?` (single binding).** Rejected: a clip can depend on
  multiple nodes (e.g., AIGC call conditioned on `character_ref` + `style_bible` +
  `edit_intent`). Set is the right shape.

**Why "empty = always stale" and not "empty = never stale"?** Pre-DAG clips from legacy
workflows should not silently become cache-fresh just because nobody bound them. The
conservative default is "re-render unless we can prove otherwise." Tools opt into
caching by declaring their inputs.
