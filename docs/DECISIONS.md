# DECISIONS

Running log of design decisions made while implementing the VISION.md north star.

Each entry records the **decision**, the **alternatives considered**, and the **reasoning**
— the reasoning is what matters when someone comes back in six months and wants to
revisit. "We did X" without "because Y" rots.

Ordered reverse-chronological (newest on top).

---

## 2026-04-19 — TTS synthesis tool (VISION §5.2 — AIGC audio lane)

**Context.** With image-gen (visual) + ASR (audio→text) shipped, the AIGC
audio lane (VISION §2 — "AIGC: TTS / 声音克隆") was the last empty quadrant of
the compiler matrix. Without TTS, the agent can't author voiceovers for
narrative shorts (VISION §6.1: "TTS 对白"), can't generate placeholder
narration for vlogs while the user records the real take, and the
ASR↔synthesis round-trip needed to make `add_subtitle` + voice replacement
workflows possible doesn't exist. TTS is the highest-leverage next gap by a
clear margin.

**Decision.** Three pieces, mirroring the image-gen lane shape:

- `core.platform.TtsEngine` (commonMain) — `synthesize(TtsRequest): TtsResult`
  with `TtsRequest(text, modelId, voice, format="mp3", speed=1.0, parameters)`
  and `TtsResult(SynthesizedAudio(audioBytes, format), provenance)`.
- `core.provider.openai.OpenAiTtsEngine` (commonMain — JSON in, bytes out, no
  file IO) — `POST /v1/audio/speech` with `{model, input, voice,
  response_format, speed}`. Reads response body as raw bytes via
  `readRawBytes()`.
- `core.tool.builtin.aigc.SynthesizeSpeechTool` (commonMain) — input
  `(text, voice="alloy", model="tts-1", format="mp3", speed=1.0, projectId?)`,
  persists bytes via `MediaBlobWriter`, registers as a `MediaAsset`, surfaces
  the `assetId` for `add_clip`. Lockfile cache keyed on
  `(tool, model, voice, format, speed, text)`.

**Why TTS engine lives in commonMain (not jvmMain like Whisper).** The OpenAI
TTS endpoint is JSON-in / bytes-out — no file upload, no `java.io.File`
needed. Same shape as `OpenAiImageGenEngine`, which is also commonMain.
Whisper is jvmMain only because it uploads multipart-form raw audio bytes
from a path. iOS will get TTS for free as soon as a Darwin HttpClient is
wired into its container.

**Why under `aigc/` (not `ml/`).** VISION §2 explicitly lists TTS under
"AIGC", not "ML enhancement". ML enhancement consumes existing assets and
derives data (transcripts, masks, color); AIGC produces *new* assets from a
prompt. TTS consumes a text prompt and produces a new audio asset → AIGC.
Permission `aigc.generate` (same bucket as image-gen) reflects the same
distinction.

**Why no consistency-binding fold in v1.** OpenAI TTS takes a fixed voice id
with no character-conditioned cloning. `consistencyBindingIds` would have to
either (a) silently ignore character_ref bodies (lying about behavior),
(b) pick the OpenAI voice from a `character_ref.voiceHint` field that doesn't
exist yet, or (c) wait for a real cloning provider (ElevenLabs, future
OpenAI). Picked (c). When the cloning provider lands, `CharacterRefBody`
gains a `voiceId` (or similar) field and this tool starts consuming it the
same way `generate_image` consumes visual descriptions.

**Why seed=0L (sentinel) rather than minting a seed.** The TTS endpoint has
no seed parameter; identical inputs produce identical (or perceptually
identical) audio without one. Minting a client-side seed and recording it
would be theatrical — the value would be ignored by the provider and would
just clutter provenance. A sentinel makes "TTS provenance has no seed" a
visible fact rather than a hidden one. The lockfile hash deliberately omits
the seed so cache hits work on the inputs that actually matter.

**Why `MediaMetadata.duration = Duration.ZERO` on import.** OpenAI TTS
doesn't echo a duration, and there's no portable audio probe in commonMain.
The image engine makes the same compromise for non-image dimensions. Real
duration falls out of the next ffprobe pass when the asset gets used in a
clip — leaving it ZERO until then is honest. Adding an `expect/actual` audio
probe just for this would be premature.

**Cache hit semantics.** Identical
`(tool, model, voice, format, speed, text)` → same `assetId`, no engine
call. Verified by `secondCallWithIdenticalInputsIsLockfileCacheHit`. Mutating
*any* of those fields busts the cache (`changingTextOrSpeedOrFormatBustsTheCache`
covers text + speed + format). Without `projectId` the lockfile is bypassed
entirely (`withoutProjectIdEveryCallHitsTheEngine`) — same opt-in shape as
image-gen.

**System prompt.** Added a short "AIGC audio (TTS)" paragraph naming
`synthesize_speech` and the round-trip with `transcribe_asset`. Regression
test asserts the phrase still appears so a refactor can't silently drop it.

**When to revise.** When voice cloning lands → add `voiceId` to
`CharacterRefBody` + start folding consistency bindings into the engine call.
When TTS responses start arriving with structured metadata (duration,
sample-rate) from any provider → promote `SynthesizedAudio` from a bytes blob
to include the metadata so `MediaMetadata.duration` doesn't have to lie.
When chunking long scripts (>4096 chars for OpenAI) becomes a real workflow
→ add a `SynthesizeLongFormSpeechTool` that splits, calls in parallel,
concatenates via the existing audio-stitch path, rather than putting chunking
into the engine itself.

## 2026-04-19 — ASR transcription tool (VISION §5.2 — ML enhancement lane)

**Context.** VISION §5.2 splits the AIGC compiler into two lanes: pure generation
(text→image, text→video, TTS, …) and **ML enhancement** of existing assets
(transcription, denoise, super-resolution, beat detection). Generation has
`OpenAiImageGenEngine` + `GenerateImageTool`; the enhancement lane was empty,
which means any "subtitle this vlog" or "cut to the beat" workflow had no entry
point. ASR is the highest-leverage first enhancement: it unblocks subtitle
authoring (`AddSubtitleTool` already exists but gets text from where?) and is a
clean provider story (one OpenAI endpoint, well-defined output shape).

**Decision.** Three pieces, mirroring the generation lane shape:

- `core.platform.AsrEngine` (commonMain) — `transcribe(AsrRequest): AsrResult`
  with `AsrRequest(audioPath, modelId, languageHint?, parameters)` and
  `AsrResult(text, language?, segments, provenance)`. `TranscriptSegment(startMs,
  endMs, text)` uses ms units to line up directly with `Clip.timeRange`.
- `core.provider.openai.OpenAiWhisperEngine` (jvmMain) — multipart upload to
  `/v1/audio/transcriptions` with `response_format=verbose_json` +
  `timestamp_granularities[]=segment`, parses the verbose JSON into the common
  result type. Sniffs `Content-Type` from the file extension (mp3/wav/m4a/flac/
  ogg/webm/mp4/mov/mpeg → matching mime, else octet-stream).
- `core.tool.builtin.ml.TranscribeAssetTool` (commonMain) — input
  `(assetId, model="whisper-1", language?)`, resolves assetId via
  `MediaPathResolver`, returns the segments + detected language + a short LLM
  preview. Permission `ml.transcribe` defaults to ASK.

**Why a separate `AsrEngine` interface, not `*GenEngine`-shaped.**
`ImageGenEngine` / `VideoEngine` produce *new* assets keyed by prompt + seed.
ASR consumes an existing asset and produces text + structured timestamps — no
seed (the model is deterministic given the audio), no `MediaBlobWriter`
involvement. Shoehorning it into the generation interface would force fake
seeds and a no-op blob writer, both of which are smells. Keeping it parallel
but separate keeps each interface honest about what it does.

**Why JVM-only impl (no commonMain Whisper).** The Whisper API needs raw audio
bytes uploaded as multipart form data. `commonMain` has no portable way to read
arbitrary file bytes — same constraint that put `FileBlobWriter` in jvmMain.
The interface lives in commonMain so iOS/Android can plug in native ASR
(SFSpeech, MediaRecorder + Vosk, on-device CoreML Whisper, etc.) without
breaking the agent contract.

**Why no lockfile cache in v1.** `LockfileEntry` is keyed by `assetId` and
references an output asset id. ASR output is *text + timestamps*, not an
asset. Bolting it into the lockfile would either (a) write the transcript to a
text-blob asset (introduces a new asset kind for limited benefit) or
(b) generalize the lockfile to "text outputs too" (premature — wait until at
least one more enhancement tool wants caching). The Whisper API is also cheap
enough per call that re-running on the same audio is fine for v1. Revisit when
we see real users transcribing the same long audio repeatedly.

**Why iOS/Android containers don't get the tool.** Same reason they don't get
`GenerateImageTool` today: those containers don't yet wire any generative
provider. When iOS gets its first OpenAI engine wired, both AIGC tools and the
ASR tool come along together — same provider key, same conditional pattern.

**Why ASR before text-to-video / TTS.** Three reasons: (1) clean provider
story — Whisper is one well-known endpoint; video gen is a moving target across
providers (Sora, Runway, Veo, Kling) with very different shapes. (2) Lower
friction — text + timestamps round-trip into existing `AddSubtitleTool`; video
gen needs blob storage + clip-creation plumbing. (3) Fills the *empty* ML
enhancement lane in §5.2; image gen already exists for the generation lane.

**System prompt.** Added a short "ML enhancement" paragraph naming
`transcribe_asset` so the agent knows the tool exists. `TaleviaSystemPromptTest`
asserts the phrase still appears so a refactor can't silently break it.

**When to revise.** When a second ASR provider lands (Deepgram, AssemblyAI,
on-device whisper.cpp), keep the interface stable and add a sibling engine.
When transcription becomes a hot path on the same audio, add a lockfile-style
cache keyed by `(audioPath fingerprint, modelId, languageHint)` →
`AsrResult` blob — the inputHash discipline from the AIGC lockfile carries
over directly.

## 2026-04-19 — Project lifecycle tools (VISION §5.2 — agent as project manager)

**Context.** `ProjectStore` already had `get / upsert / list / delete / mutate`,
but no agent tool exposed any of them. A new conversation could not bootstrap a
project; the desktop app hard-coded one at startup; the server assumed projects
pre-existed. The agent literally could not answer "make a graduation vlog from
scratch" — it had no `projectId` to thread into `add_clip`.

**Decision.** Add four tools under `core/tool/builtin/project/`:

- `create_project` — title-required, optional explicit `projectId` (default
  slug = `proj-{slug}`), optional `resolutionPreset` (720p/1080p/4k) + `fps`
  (24/30/60). Defaults to 1080p/30. Fails loud on duplicate id.
- `list_projects` — catalog metadata only, no Project JSON decode.
- `get_project_state` — single-project snapshot: title, output profile, asset /
  source-node / lockfile / render-cache / track counts, source revision,
  timeline duration, timestamps. The agent uses this to *plan* before edits
  rather than guess what already exists.
- `delete_project` — destructive; permission `project.destructive` defaults to
  ASK. Does not auto-prune sessions that referenced the project (the prompt
  tells the agent to warn the user instead).

**Why a `ProjectSummary` extension to `ProjectStore`.** `Project.title` lives in
the SQL row, not in the canonical `Project` model — bloating the model just for
listings would invert the storage layout. Added `ProjectSummary` data class +
`summary(id)` / `listSummaries()` methods so list_projects can return titles
without decoding every Project's full JSON. `list_projects` reads metadata only;
`get_project_state` is the heavy single-project read.

**Why the tools live in `core`, not per-app.** Same reasoning as the source
tools — pure local state mutation through `ProjectStore`, no I/O, no platform
deps. Composition root in each container (server, desktop, Android,
iOS-Swift) registers them.

**Why `project.read` / `project.write` default ALLOW; `project.destructive`
defaults ASK.** Reading the catalog and creating an empty project are no-cost
local mutations (parallel to `source.read` / `source.write`). Deletion drops the
Source DAG, Timeline, Lockfile, and RenderCache — irreversible from the user's
perspective and there's no undo lane below the store. ASK-by-default protects
the user without hand-holding the read path.

**Why duplicate-id is a hard failure, not silent overwrite.** `create_project`
on an existing id would silently obliterate the source DAG — exactly the data
loss `delete_project` asks the user about. Failing loud forces the agent to
call `list_projects` first and either pick a fresh id or operate on the
existing project explicitly.

**OutputProfile shorthand, not full struct.** The LLM picks one of three
resolution presets and one of three frame rates rather than constructing an
`OutputProfile` JSON blob. Keeps the input schema compact and the failure modes
obvious (`unknown resolutionPreset` is a clearer error than a partially-filled
profile). Custom profiles can be added later via a `mutate_output_profile` tool
if real users need them — not now.

**System prompt + regression guard.** Added a "Project lifecycle" paragraph to
`TaleviaSystemPrompt.kt` and extended the rule about timeline tools to direct
the agent to bootstrap a project when the catalog is empty.
`TaleviaSystemPromptTest` now asserts `create_project` / `list_projects` /
`get_project_state` still appear in the prompt.

**When to revise.** When a session needs to be associated with a newly-created
project automatically (today the user/caller provides `projectId` separately to
`POST /sessions`), the natural next step is either a server endpoint surface
for project CRUD or a `bootstrap_session` tool that returns both `projectId`
and `sessionId`. Defer until session-level UX feedback motivates it.

## 2026-04-19 — Source-mutation tools close the consistency-binding lane

**Context.** VISION §3.3 + §5.5: the system prompt told the agent to pass
`character_ref` ids in `consistencyBindingIds`, but no tool existed to *create*
those ids. The agent could read the system prompt, plan to apply consistency,
and then have no way to actually write a node — a dormant lane.

**Decision.** Add five tools under `core/tool/builtin/source/`:

- `define_character_ref` — creates/replaces a `core.consistency.character_ref` node.
- `define_style_bible` — creates/replaces `core.consistency.style_bible`.
- `define_brand_palette` — creates/replaces `core.consistency.brand_palette`,
  validates `#RRGGBB` and normalises to uppercase.
- `list_source_nodes` — read-only query, filters by `kind` / `kindPrefix`,
  returns id/kind/revision/contentHash/parents + a kind-aware human summary.
  Optional `includeBody` for full JSON.
- `remove_source_node` — deletes a node by id; doesn't cascade because
  `staleClips()` already treats vanished bindings as always-stale.

All three definers are **idempotent on `nodeId`**: same id + same kind →
`replaceNode` (preserves id, bumps revision, recomputes contentHash). Same id
but *different* kind → `IllegalArgumentException` (loud failure beats silent
shape mismatch). Default `nodeId` is a slugged variant of `name`
(`character-mei`, `style-cinematic-warm`, `brand-acme`) so the LLM rarely needs
to invent ids.

**Why in `core`, not per-app.** The tools mutate `Project.source` via
`ProjectStore.mutateSource(...)` — pure local state, zero I/O, no platform
dependencies. They belong next to the source schema they manipulate. Each
container (`ServerContainer`, `AppContainer`, `AndroidAppContainer`,
iOS `AppContainer.swift`) registers them at composition.

**Why `source.read` / `source.write` default to ALLOW.** Unlike AIGC (external
cost) or media export (filesystem write), source mutations are local-only state
on `Project.source`. Asking the user to confirm every character_ref creation
would be hostile. Apps that want stricter policy can override the rule.

**Why a separate slug helper, not delegate to `nodeId` strings.** The slug
shape (`{prefix}-{a-z0-9-only}`) needs to be consistent across all three
definers, so `SourceIdSlug.kt` owns the rule. Co-locating it with the tools
keeps the surface tight.

**ServerSmokeTest flake.** `submitMessageUsesRequestedProviderInsteadOfDefault`
asserted `openai.requests.size == 1`, but `SessionTitler` runs through the
*same* provider on first turn — adding new tool registrations slowed
`ServerContainer` construction enough to amplify the race. Relaxed to
`>=1` plus a check that the *first* request's `providerId` matches. The point
of the test is "default provider didn't see this," which still holds.

**When to revise.** When a fourth consistency kind lands (e.g. location-bible,
prop-ref), add a sibling definer + extend `ListSourceNodesTool.humanSummary`.
When source nodes need cross-references (one node parents another via
`parentIds`), the definers will need a `parentIds` input — not added now
because no current consistency kind requires it.

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
