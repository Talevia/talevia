# DECISIONS

Running log of design decisions made while implementing the VISION.md north star.

Each entry records the **decision**, the **alternatives considered**, and the **reasoning**
— the reasoning is what matters when someone comes back in six months and wants to
revisit. "We did X" without "because Y" rots.

Ordered reverse-chronological (newest on top).

---

## 2026-04-19 — `import_source_node` (VISION §3.4 — closes "可组合")

**Context.** §3.4 names four codebase properties for Project / Timeline:
可读, 可 diff, 可版本化, 可组合. After snapshot / fork / diff landed, the
first three were covered. "可组合 (片段 / 模板 / 特效 / 角色可跨 project
复用)" was the only unfilled leg — the agent had no way to lift a `character_ref`
defined in a narrative project into a vlog project without retyping the body.
`fork_project` copies a whole project, which is the wrong tool for "share
one character".

**Decision.** New `core.tool.builtin.source.ImportSourceNodeTool` (id
`import_source_node`, permission `source.write`). Inputs: `(fromProjectId,
fromNodeId, toProjectId, newNodeId?)`. Walks the source node + its parent
chain in topological order, inserts each one into the target project, returns
`(originalId, importedId, kind, skippedDuplicate)` per node. Wired into all
four composition roots (server / desktop / Android / iOS).

**Why content-addressed dedup, not id-addressed.** `SourceNode.contentHash`
is a deterministic fingerprint over `(kind, body, parents)`. The AIGC
lockfile keys cache entries on bound nodes' content hashes (not their ids).
So when an imported node lands with the *same* contentHash as the source,
every previous AIGC generation that referenced that node is automatically a
cache hit on the target side too — without the agent doing anything special.
The alternative — keying on id — would force the user to use the same id on
both sides, and would still miss when ids legitimately differ ("Mei" vs
"character-mei-v2") even though the bodies are identical.

**Why reuse + remap parent refs when a parent is deduped to an existing
target node under a *different* id.** Real example: the source's `style-warm`
parent matches the target's pre-existing `style-vibe-1` by contentHash. We
reuse the existing node (no insertion) and remap the leaf's `SourceRef` to
point at `style-vibe-1`. The alternative — refusing to remap and inserting a
duplicate `style-warm` — would create two source nodes with identical content
but different ids in the same project, defeating the dedup discipline that
makes lockfile cache transfer work.

**Why fail loudly on same-id-different-content collision instead of auto-rename.**
If the target already has `character-mei` with different content, we throw
with a hint to pass `newNodeId` or `remove_source_node` first. The
alternative (silent suffix-rename to `character-mei-2`) would create
unobvious id divergence — a future binding referencing `character-mei` would
quietly resolve to the *original* version, not the just-imported one. Forcing
the agent to make the conflict explicit is worth one extra round-trip.

**Why `newNodeId` only renames the leaf, not parents.** The common case is
"I want to import the Mei character into the vlog project but the vlog
already has a `character-mei` for someone else". The leaf is what the user
named; parents are derived. Per-parent renaming would multiply the input
surface for a case the agent will rarely hit (today's consistency nodes are
leaves). When richer source schemas land and parent collisions become real,
the caller can break the import into two: `import_source_node(parent, ...,
newNodeId=...)` then `import_source_node(leaf, ...)` lets the parent-dedup
path remap the leaf's refs.

**Why reject self-import (`from == to`).** Nearly always a mistake. The
agent that wants a within-project copy already has `define_character_ref` /
`define_style_bible` / `define_brand_palette` with a fresh id. Failing
loudly costs one corrected round-trip and prevents the silent no-op that
content-addressed dedup would otherwise produce.

**Why permission `source.write`, not `project.write`.** This tool only
mutates `Project.source`; it does not touch the timeline, lockfile, render
cache, or asset catalog. Aligning with `define_*` / `remove_source_node`
keeps the permission ruleset clean — the user can grant blanket source
edits without authorising broader project mutations.

**Tests.** 9 cases in `ImportSourceNodeToolTest`: leaf import, idempotent
re-import, topological parent walk, parent-dedup remapping, same-id-
different-content failure, `newNodeId` rename, self-import rejection, missing
source/target project, missing source node.

---

## 2026-04-19 — Timeline tool parity on Android + iOS

**Context.** Desktop and server containers register `ApplyFilterTool`,
`AddSubtitleTool`, and `AddTransitionTool`; Android and iOS did not. The
result: the agent on mobile couldn't express "apply a vignette", "add a
line of subtitle text", or "cross-dissolve between clips A and B", even
though all three tools are pure commonMain state mutators on
`Project.timeline` with no platform-specific plumbing whatsoever.

**Decision.** Register all three on `AndroidAppContainer` and iOS
`AppContainer.swift`. Zero new code, just composition.

**Why this isn't blocked by the known Android/iOS engine gap.**
CLAUDE.md calls out that `Media3VideoEngine` and `AVFoundationVideoEngine`
currently fall back to no-op for the filter / transition render passes.
That's an **export-time** engine gap, not an **authoring-time** tool gap.
Tool dispatch mutates `Project.timeline` inside core; it has no engine
call. The tools can be authored today; when the engines catch up, existing
project state will render without any project migration. The alternative
(withholding the tools until the engines catch up) would create a lopsided
agent where mobile can't even express intent that desktop can realize.

**Why not also wire AIGC tools (`generate_image`, `synthesize_speech`,
`transcribe_asset`) on mobile.** Those have real platform wiring — an
`HttpClient`, a platform-appropriate `MediaBlobWriter`, secret storage for
the API key. Android has an in-memory `SecretStore` stub; iOS has none.
Wiring AIGC on mobile needs those prerequisites first. Out of scope for
this commit; tracked for a follow-up when mobile secret stores land.

**Surface area.** Two files touched, three `register(…)` calls each.
Compiles against `:apps:android:compileDebugKotlin` and
`:core:compileKotlinIosSimulatorArm64`.

---

## 2026-04-19 — `list_lockfile_entries` tool (VISION §3.1 — agent project orientation)

**Context.** The lockfile has been load-bearing since the AIGC lane landed:
`find_stale_clips` reads it to answer "what needs regenerating?",
`generate_image` / `synthesize_speech` write to it for cache hits. But the
agent had no way to introspect it — no answer to "what have we generated so
far?", "do we already have a Mei portrait we can crop instead of
re-generating?", or "show me the last 5 TTS calls so I can reuse a voice
line". Without that orientation step, planning tools get proposed that
duplicate existing artifacts.

**Decision.** New read-only tool `core.tool.builtin.project.ListLockfileEntriesTool`.
Input `(projectId, toolId?, limit=20, max=200)`. Returns entries most-recent
first with `(inputHash, toolId, assetId, providerId, modelId, seed,
createdAtEpochMs, sourceBindingIds)`. Permission `project.read`.

**Why most-recent-first in the response but append-only on disk.** The
lockfile's natural ordering is insertion-order (an audit trail — append-only).
But the agent's dominant query shape is "what did I generate recently?", so
reversing client-side saves the model a re-sort. The on-disk ordering stays
canonical; only the tool response is flipped.

**Why `toolId` filter instead of a kind/modality enum.** The lockfile records
tool ids verbatim (`"generate_image"`, `"synthesize_speech"`). A higher-level
enum (`image | audio | video`) would drift out of sync with the tool
registry every time a new AIGC tool lands. Filtering by the raw tool id
means the schema stays stable as the compiler surface grows.

**Why no cursor/pagination.** v0 target is projects with <1000 entries;
the 200-entry cap is enough for interactive orientation. When a user's
lockfile outgrows that, `limit` already supports exact slicing by the caller,
and real pagination can be added as a `offset` input without schema break.

**Placement under `project/`, not `aigc/`.** The lockfile is per-project
state — the same organization as `find_stale_clips` (also lockfile-driven).
AIGC tools *produce* entries; project tools *query* them. Co-locating the
query with the snapshot/fork/state tools keeps the agent's "planning" lane
in one namespace.

**System prompt + regression guard.** System prompt gains a short paragraph
naming the tool + the two canonical use cases (orientation, reuse).
`TaleviaSystemPromptTest` asserts `list_lockfile_entries` still appears so a
prompt-refactor can't silently drop it.

**Surface area.** Wired into all 4 composition roots (server, desktop,
Android, iOS).

---

## 2026-04-19 — `replace_clip` tool (VISION §3.2 — regenerate-after-stale)

**Context.** With `find_stale_clips` (this morning's commit) the agent can answer
"what needs regenerating?", and `generate_image` produces the new asset. But
there was no tool to splice the new asset back into the timeline — every clip
mutation tool (`split`, `apply_filter`, `add_subtitle`, …) leaves the asset id
fixed. The agent's only options were to `add_clip` again (creates a duplicate)
or do nothing. The DAG → query → re-render workflow stopped one step short of
a complete loop.

**Decision.** New tool `core.tool.builtin.video.ReplaceClipTool` — input
`(projectId, clipId, newAssetId)`, swaps `Clip.Video.assetId` /
`Clip.Audio.assetId` in place. Position (`timeRange`), trim (`sourceRange`),
transforms, filters, audio volume — all preserved. Permission `timeline.write`
(same bucket as the other clip mutators).

**Side effect on `Clip.sourceBinding`.** When the new asset has a lockfile
entry with non-empty `sourceBinding`, copy that binding onto the replaced
clip. Three reasons:

1. The agent regenerated this asset *because* a source changed — it would be
   nonsense to leave the clip's binding pointing at the *old* set (or worse,
   `emptySet()`).
2. Future `find_stale_clips` queries route through `Lockfile.findByAssetId`
   anyway, so this side effect is mostly informational. But `Clip.sourceBinding`
   *is* what `Project.staleClips(changed: Set<SourceNodeId>)` (the export-time
   incremental render path, acce14c) uses — keeping it correct means the two
   stale-detection lanes (lockfile-driven + binding-driven) agree.
3. It quietly closes a sub-gap: `add_clip` doesn't thread sourceBinding from
   the AIGC tool's output into the new clip. We deliberately *didn't* fix that
   in the lockfile-driven detector commit (would conflate add_clip with AIGC
   bookkeeping). But on `replace_clip` the relationship is unambiguous —
   you're swapping in *this specific* asset whose binding we already know.

**Why not refactor `add_clip` instead.** Considered: have `add_clip` look up
the asset's lockfile entry and copy its `sourceBinding`. Rejected because:

- `add_clip` is also the tool for hand-authored / imported clips that have no
  lockfile entry — every call would do a wasted lookup.
- The semantic of `add_clip` is "place this asset on the timeline at this
  spot." Adding "and also, by the way, copy its DAG bindings" muddies the
  purpose. `replace_clip` is *explicitly* about a regenerate flow, so the
  binding copy is on-theme.

If a future "always thread bindings" decision lands (e.g. the binding becomes
load-bearing for incremental render even on first-add), `add_clip` can adopt
the same `findByAssetId` lookup; the helper is on `Lockfile` already.

**Why text clips are rejected loudly.** `Clip.Text` has no `assetId` — it
carries the text inline in the model. Asking the tool to "replace its asset"
is meaningless. Erroring beats silently no-op'ing because the agent's plan
("replace clip X") would otherwise look like it succeeded with no observable
effect.

**Why preserve `sourceRange` instead of resizing.** A regenerate produces a
new asset whose duration may differ from the old one. Two options:

- **Resize the clip** to the new asset's full duration. Friendly but
  destructive — a previous `split_clip` chose those exact endpoints, and
  silently overwriting them re-asks the agent to re-split.
- **Preserve `sourceRange`** and clamp at render time if the new asset is
  shorter. Conservative but predictable.

Picked the second. If the regenerated asset *is* a different length and the
agent wants to honour that, it can call `split_clip` / a future `resize_clip`
explicitly. The principle: tool inputs should change exactly what they
declare, no more.

**Surface area.** Wired into all 4 composition roots (server, desktop,
Android, iOS). System prompt updated to teach the full workflow:
edit character → `find_stale_clips` → `generate_image` → `replace_clip`.

**Tests.** `ReplaceClipToolTest` covers six paths: video preserve-everything,
audio preserve-volume, text-clip rejection, missing clip, missing asset, and
the source-binding copy-from-lockfile case (the one that proves the
regenerate-flow side effect).

---

## 2026-04-19 — Lockfile-driven `find_stale_clips` (VISION §3.2 close-the-loop)

**Context.** The Source DAG (068a350), incremental render path (acce14c), and
AIGC lockfile (b125574) all landed, but they were three pieces of theory: the
agent had no way to *use* them. After the user edited a `character_ref`, the
agent could not answer "which clips on the timeline are now stale?" without
reading the entire project JSON and reasoning manually. The DAG paid for
itself only at export time, never at planning time.

**Decision.** Detect staleness from the **lockfile**, not from
`Clip.sourceBinding`, and surface it as a read-only `find_stale_clips` tool.

- Snapshot `SourceNode.contentHash` for every bound id at lockfile-write time
  (new field `LockfileEntry.sourceContentHashes: Map<SourceNodeId, String>`).
- New domain extension `Project.staleClipsFromLockfile(): List<StaleClipReport>`
  walks each clip on the timeline, looks up the lockfile entry that produced
  its asset (`Lockfile.findByAssetId`), and compares snapshotted hashes against
  current source hashes. Mismatch on any bound node → flag the clip and report
  *which* source ids drifted.
- New tool `core.tool.builtin.project.FindStaleClipsTool` returns
  `(staleClipCount, totalClipCount, reports[clipId, assetId, changedSourceIds])`.
- System prompt teaches the workflow: edit character → `find_stale_clips` →
  regenerate each reported clip with the same bindings.

**Why lockfile-driven, not `Clip.sourceBinding`-driven.** `Clip.sourceBinding`
is the field VISION §3.2 says clips *should* carry. But today `AddClipTool`
does not thread the AIGC tool's `sourceBinding` output into the new clip — it
takes `(assetId, trackId, …)` and constructs `Clip.Video(sourceBinding =
emptySet())`. Two ways to close the loop:

1. Refactor `AddClipTool` to look up the asset's lockfile entry and copy its
   `sourceBinding` into the clip. Conflates "place this asset on the timeline"
   with "AIGC bookkeeping" — every future clip-creating path would need the
   same special-casing.
2. Drive detection from `Clip → AssetId → LockfileEntry → sourceBinding`,
   leaving the Clip layer untouched.

Picked (2). The lockfile is already the audit record of "what produced what";
piggy-backing the staleness query on it keeps the Clip layer decoupled from
AIGC and makes legacy hand-authored clips work the same as AIGC clips
(neither has a `Clip.sourceBinding`, both are queried by asset id). Future
work to add a `replace_clip` tool can adopt the same lookup without a schema
migration.

**Why "empty `sourceContentHashes` = unknown, not stale".** Legacy lockfile
entries written before the snapshot field existed have `emptyMap()`. Two
choices:

- **Always-stale**: every legacy entry's clips show up in every report. Loud,
  but mostly false positives — the agent would propose regenerating clips
  that haven't actually drifted.
- **Always-fresh / skip**: silently exclude legacy entries from the report.

Picked the second. Lying-about-stale erodes trust in the report; lying-about-
fresh just means a one-time "you have N legacy clips that we can't reason
about — regenerate if you've edited their sources" message at most. The
common case (entries written after this commit) is unaffected, and projects
created today have zero legacy entries.

**Why under `tool/builtin/project/` (not `source/`).** The query crosses
*both* layers — it walks the timeline (project) and reads the source DAG. Two
sibling tools — `get_project_state` (counts across both) and
`find_stale_clips` (joins across both) — both belong with the cross-cutting
project tools. `source/` is reserved for tools that only mutate / read the
source DAG itself (`define_character_ref`, `list_source_nodes`, …).

**Why read-only (`project.read`), not gated.** The tool produces no side
effects, makes no provider calls, costs nothing. Gating it would force the
agent to ASK before every diagnostic — strictly worse than letting it poll
between edits.

**Why report only direct-changed source ids, not the transitive closure.**
The user-facing fix is "regenerate clip X". The agent doesn't need the full
DAG of derived nodes to do that — it needs to know *what changed* so it can
explain in the chat ("Mei's hair changed from teal → red, so I'll regenerate
…"). Transitive descendants would bloat the report on chatty graphs without
helping the action.

**Surface area.** Added to all 4 composition roots (server, desktop, Android,
iOS) so every platform with a chat surface can dispatch it. No engine /
permission rule changes — `project.read` already exists.

**Tests.** `FindStaleClipsToolTest` covers six scenarios: fresh project,
character edit flags clip, multi-binding only one changes (proves the
"changedSourceIds" precision), legacy entry skipped, imported clip without
lockfile entry skipped, empty lockfile short-circuit. `GenerateImageToolTest`
gained `lockfileEntrySnapshotsBoundSourceContentHashes` to lock in the
snapshot-on-write contract — the detector is dead without it.

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

---

## 2026-04-19 — Project-level named snapshots (VISION §3.4 — "可版本化")

**Context.** `revert_timeline` already lets the user undo the last few mutations
*within a chat session* — every mutating tool emits a `Part.TimelineSnapshot`, the
revert tool restores it. That covers "oops, undo that". It does **not** cover
"keep a copy of the project as-of today's review so I can return to it next week"
— those snapshots die when the session ends. VISION §3.4 explicitly asks for
"可版本化：历史可追溯、可回滚、可分支", and a session-scoped lane is not it.

**Decision.** Add a third snapshot lane: project-level, named, persistent.
- New domain type `ProjectSnapshot(id, label, capturedAtEpochMs, project)` stored
  inline as `Project.snapshots: List<ProjectSnapshot> = emptyList()`.
- Three new tools under `core/tool/builtin/project/`:
  - `save_project_snapshot` (permission `project.write`)
  - `list_project_snapshots` (permission `project.read`)
  - `restore_project_snapshot` (permission `project.destructive` → user is asked)

**Why inline storage instead of a separate `project_snapshots` SQL table?**
- `ProjectStore.mutate(...)` already gives us atomicity under a mutex — putting
  snapshots in a sibling table would mean a second store + a second lock + a
  cross-store consistency story. Inline keeps save-and-restore mechanically
  identical to every other Project mutation.
- The Project JSON blob is already what we serialize; inline adds zero schema
  migration on JVM, iOS, and Android.
- The cost — JSON blob grows linearly with snapshot count — is fine for v0
  (target: <100 snapshots per project). When a real user blows that envelope
  we'll add eviction or migrate to a sub-table. We are not going to design for
  hypothetical thousand-snapshot projects today.

**Why restore preserves the snapshots list itself.** Without this rule, restoring
to v3 would delete v1, v2, and any post-v3 snapshots — restore becomes a one-way
trapdoor and "可版本化" stops meaning anything. With it, restore behaves like
`git checkout <snapshot>`: state changes, history stays. The snapshots list +
project id are the two preserved fields; everything else (timeline, source,
lockfile, render cache, assets, output profile) is replaced wholesale from the
captured payload.

**Why save clears nested snapshots before storing.** A project with N snapshots,
captured M times, would otherwise carry O(M·N) snapshot copies inside snapshots
inside snapshots. The captured payload's own `snapshots` field is set to empty
at save time; restore re-attaches the live list. Quadratic blow-up avoided
without giving up the "restore preserves history" rule.

**Why restore is `project.destructive`, not `project.write`.** Restore overwrites
the live timeline + source + lockfile + render cache wholesale. Users can re-enter
the prior state via another snapshot, but if they hadn't saved one first, there's
nothing to roll back to. That matches the bar we already set for `delete_project`
— irreversible-from-the-user's-perspective gets ASK by default. The system prompt
tells the agent to suggest `save_project_snapshot` first when the live state
hasn't been captured.

**Why asset bytes are not snapshotted.** Snapshots reference `AssetId`s in the
shared `MediaStorage`; we do not deep-copy the underlying mp4/png blobs. This is
the same trade-off git makes vs. LFS — saving the manifest is cheap, copying
every blob would balloon storage and make snapshots a first-class media-management
concern. If a user deletes the underlying file, restore will succeed but
downstream renders may miss the asset; that's a future "snapshot integrity" tool's
problem, not a load-bearing invariant we promise here.

**Alternatives considered.**
- **Sibling `project_snapshots` SQL table.** Rejected for the reasons above —
  second store, second lock, second migration, no benefit at v0 scale.
- **Make restore non-destructive (always require explicit confirm flag).** Rejected:
  permission-system-as-confirmation is the established pattern (see
  `delete_project`); reinventing per-tool confirmation flags fragments the UX.
- **Auto-snapshot on every mutation (silent versioning).** Rejected: that's what
  `revert_timeline` already does within a session. Project-level snapshots are
  *named, intentional* checkpoints — silent auto-snapshots would dilute the lane
  into noise within weeks.
- **Reuse `revert_timeline`'s session-scoped snapshots and just persist them to
  the Project on session end.** Rejected: timeline-only snapshots miss the
  source DAG, lockfile, and render cache, all of which need to round-trip for
  "go back to the state I had on Tuesday" to mean what the user thinks it means.

---

## 2026-04-19 — `fork_project` closes VISION §3.4 "可分支"

**Context.** Snapshots gave us "可追溯" (history) and "可回滚" (rollback). The
third leg of §3.4 — "可分支" (branching) — was still missing. Users who want to
explore "what if I cut this differently" without losing the original have to
either (a) save a snapshot and overwrite, or (b) export and re-import via JSON
gymnastics. Neither is the right primitive.

**Decision.** Add a `fork_project` tool (permission `project.write`) that creates
a new project from either:
- the source project's *current* state (no `snapshotId` argument), or
- a captured snapshot (`snapshotId` argument).

The new project gets a fresh id and an empty `snapshots` list. Everything else
(timeline, source DAG, lockfile, render cache, asset catalog ids, output
profile) is inherited from the chosen payload via a single `copy(id=…, snapshots=…)`.

**Why share asset bytes between source and fork instead of duplicating.** Same
trade-off as snapshots themselves — `MediaStorage` is content-addressed enough
that duplicating blobs would just consume disk for no benefit. The canonical
mutation pattern is "produce a *new* asset and `replace_clip`", so concurrent
forks won't step on each other's bytes. If we ever introduce in-place asset
mutation we'll need refcounting, but we are not going to design for that today.

**Why fork starts with an empty snapshots list.** A fork is a fresh trunk — its
own history starts at the moment of the fork. Carrying the source project's
snapshots into the fork would muddle the user's mental model ("if I restore
'final cut v1' on the fork, do I get the source-project state or the fork
state?"). Cleaner to make forks distinct timelines from the start; if the user
wants to cherry-pick a snapshot from the source project they can fork *from*
that snapshot.

**Why fork doesn't ASK like restore does.** Forks are non-destructive — the
source project is untouched. Permission `project.write` matches `create_project`
because the operation is a create. The user only sees a permission prompt if
they've tightened the rules.

**Alternatives considered.**
- **Auto-fork on every save_project_snapshot (CoW-style).** Rejected — silent
  proliferation of projects would clutter `list_projects` and break the user's
  intuition that a snapshot is a *checkpoint within a project*, not a sibling
  project. Forks should be intentional.
- **Cross-project snapshot sharing (one snapshot belongs to many projects).**
  Rejected — the inline-snapshot decision (see prior entry) already chose
  per-project storage. Cross-project sharing would require a sibling table and
  a refcount story for snapshot lifetime; unnecessary at v0.
- **`copy_project` instead of `fork_project`.** Rejected — "fork" maps cleanly
  onto the git mental model and pairs with snapshot vocabulary; "copy" suggests
  a one-shot shallow duplication with no semantic relationship to the source.

## 2026-04-19 — Character voice pinning via `CharacterRefBody.voiceId` (VISION §5.5 audio lane)

**Context.** `CharacterRefBody` gives visual-identity tools (image-gen, future
image-to-video) everything they need to keep a character consistent across
shots — a name, a natural-language description, reference asset ids, an optional
LoRA pin. But the audio lane (`synthesize_speech`) had no parallel: every call
the agent made had to carry a raw `voice` string, even when the "speaker" was a
character the agent already knew. That meant two failure modes. **(a)** agent
drift — after edit #5 the agent forgets which voice it chose for Mei and swaps
to `nova` mid-scene. **(b)** rebinding asymmetry — a user edit to "make Mei
sound deeper" has no anchor on the character; the agent has to re-find every
speech clip by grepping the timeline. Voice belongs on the character.

**Decision.** Add optional `voiceId: String? = null` to [`CharacterRefBody`](../core/src/commonMain/kotlin/io/talevia/core/domain/source/consistency/ConsistencyBodies.kt).
`synthesize_speech` gains the same `consistencyBindingIds: List<String>` input
every AIGC tool already has, plumbed through a new `FoldedVoice` helper in
[`VoiceFolding.kt`](../core/src/commonMain/kotlin/io/talevia/core/domain/source/consistency/VoiceFolding.kt)
and a `AigcPipeline.foldVoice(...)` wrapper. When a bound character_ref has a
non-null voiceId, that voice **overrides** the caller's explicit `voice` input.
The resolved voice is what gets hashed into the lockfile key and what the engine
receives. `define_character_ref` accepts an optional `voiceId` so the agent can
set it at creation time.

**Why `FoldedVoice` is separate from `FoldedPrompt`.** The prompt fold pulls
style bibles, brand palettes, *and* character visual descriptions into text.
None of those apply to TTS — there's no style axis on a voice. Bolting voice
onto `FoldedPrompt` would either (a) make half the `FoldedPrompt` fields
meaningless for audio or (b) hide the voice inside a structure named for
prompts. Two folds, one per modality, is the cleaner cut: visual-fold consumes
`name + visualDescription + refs + lora`, audio-fold consumes `voiceId`. A
future image-to-video tool would use both.

**Why voiceId overrides the explicit voice input (not the other way around).**
The agent's intent "this character speaks" is the stronger signal than a voice
string the agent may have copy-pasted from an earlier call. If the caller
bothered to bind a character_ref, they want *that character's voice*, not a
parallel voice string they also have to remember to update. The loud failure
comes the other direction: binding two character_refs with voiceIds is an
error — "which speaker?" is ambiguous and "first wins" silently regresses when
a caller later adds a second character. Callers disambiguate by binding only
the speaker (the other characters can still be bound via visual tools if they
also appear on-screen, just not via the TTS call).

**Why voiceId is nullable on the character (vs required).** Not every character
has a pinned voice — a minor character may speak once with a hand-picked
placeholder voice, a voice-casting decision may happen late in production.
Making it optional keeps `define_character_ref` usable before the voice is
chosen and lets the fold silently skip characters that lack one (no fallback
ambiguity — the raw `input.voice` wins).

**Cache / lockfile semantics.** The hash key is built from the *resolved*
voice, not `input.voice`. So two callers that arrive at the same voice — one
via `consistencyBindingIds=["mei"]`, one via explicit `voice="nova"` — hit the
same cache entry and share the same asset. The lockfile's `sourceBinding`
records only the character_refs whose voiceId was actually applied, so the
stale-clip detector repaints the speech clip when the character's voice
changes but not when the visual description changes (the visual description
doesn't affect the audio output). Characters without voiceIds are silently
dropped from `sourceBinding` — the *binding itself* is still the agent's
intent, but it has no audio-side stale trigger until the character gains a voice.

**Alternatives considered.**
- **Put voiceId on ToolContext (like auth).** Rejected — voiceId is a
  per-character attribute, not a per-session attribute. The agent rarely
  reasons about a global "current voice"; it reasons about "Mei's voice".
- **Add a parallel `voice_ref` source-node kind distinct from character_ref.**
  Rejected — forces the agent to maintain two bindings for the same character
  (one for image-gen, one for TTS) and creates the possibility of a character
  with a voice and no visual description, or vice versa. One node, all the
  signals, is the simpler mental model.
- **Require voiceId rather than making it optional.** Rejected — breaks the
  case where the agent defines Mei visually before the user has approved a
  specific voice cast.

## 2026-04-19 — `diff_projects` closes VISION §3.4 "可 diff"

**Context.** VISION §3.4 lists four properties for project-level edits: 可观察
(list_projects + get_project_state), 可版本化 (save/restore/list snapshots),
可分支 (fork_project), and 可 diff — "the agent can tell a user what actually
changed between v1 and v2 without dumping both Projects and asking the model to
spot the delta itself." The first three shipped; the diff leg was missing, so
the only way to answer "what did this fork add?" or "what did I break between
snapshots?" was to read-project-state twice and compare in-context. That
wastes tokens and is error-prone on large timelines.

**Decision.** Ship a read-only [`DiffProjectsTool`](../core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/DiffProjectsTool.kt)
(permission `project.read`). Input is `(fromProjectId, fromSnapshotId?,
toProjectId?, toSnapshotId?)` — a null snapshotId means "current state of that
project", toProjectId defaults to fromProjectId. Output has three diff
sections: timeline (tracks + clips by id), source (nodes by id, with
contentHash-change detection), and lockfile (entry-hash set-diff plus a
tool-id bucket count). Detail lists are capped; totals are always exact.

**Why clip matching by ClipId rather than by asset or position.** A moved clip
(timeRange changed) should show up as `changed`, not `remove + add`. Matching
by ClipId makes "the user moved clip c1 from 0-2s to 1-3s" one entry in
`clipsChanged` with a specific `changedFields=["timeRange"]` list — the agent
can read that back as "you shifted clip c1 by one second" rather than having
to reconstruct the match itself.

**Why source-node change detection uses contentHash, not body-equality.** We
already invalidate on contentHash elsewhere (stale-clip detection) and the
hash is always up to date on every node. Body-equality would need to pull the
JsonElement for each node on each side and compare; contentHash is one string
equality. Same notion of "change," faster.

**Why cap detail lists but keep totals exact.** A wholesale timeline rewrite
could blow the response into thousands of tokens if every clip change
serialises its full field list. Capping at `MAX_DETAIL` keeps the response
bounded; exact totals let the agent still say "47 clips changed (showing the
first 20)". If the agent needs the full list it can refine with
`get_project_state` or re-run with narrower bounds.

**Alternatives considered.**
- **Single "project identical?" boolean tool.** Rejected — the user asks
  "what's different?" as often as "anything different?". A bool-only tool
  forces a follow-up, doubling LLM turns.
- **Server-side unified diff over JSON dumps.** Rejected — meaningless for
  humans (and the agent) at the JSON-key level; domain diff (tracks/clips/
  nodes/lockfile) matches how the model reasons about the project.
- **Extend `get_project_state` with a snapshotId field so the agent can diff
  in-context.** Rejected — doubles the per-call cost (two state pulls), and
  the model isn't especially good at diffing large JSON blobs by eye. A
  typed diff tool is strictly cheaper and more reliable.

## 2026-04-19 — Thread LoRA + reference assets through `GenerateImageTool` output and lockfile hash

**Context.** `GenerateImageTool` already folded `CharacterRefBody.loraPin` and
`CharacterRefBody.referenceAssetIds` into a `FoldedPrompt` via
`AigcPipeline.foldPrompt`, but the returned folded object was *dropped on the
floor* at two critical boundaries:

1. The fields never reached the `ImageGenEngine` — `ImageGenRequest` only
   carried `prompt / modelId / width / height / seed / n / parameters`. Engines
   that could translate LoRA or reference images into their native wire shape
   had no surface to receive them through.
2. The AIGC lockfile hash did not include LoRA or reference-asset axes. Two
   identical prompts with different LoRA weights collided on the same cache
   key; the second call would return the first asset despite being a
   semantically distinct generation. That is an end-to-end correctness bug
   for VISION §3.1 "产物可 pin".

`GenerateImageTool.Output` also lacked the visibility fields the LLM needs to
reason about *what got bound* — it saw `appliedConsistencyBindingIds` but not
which LoRA adapters or reference images those bindings resolved to.

**Decision.**

1. **Extend `ImageGenRequest` with the three provider-specific hooks.** Added
   `negativePrompt: String?`, `referenceAssetPaths: List<String>`, and
   `loraPins: List<LoraPin>`. Engines that cannot natively consume a given
   hook (OpenAI DALL-E / GPT-Image-1 has no LoRA; text-only endpoints take no
   references) are *still required* to record the incoming value in
   `GenerationProvenance.parameters`. Silently dropping them would make the
   audit log lie about what the caller asked for and — worse — make the
   provenance superset look indistinguishable between two runs that had
   different LoRA intent, which then corrupts downstream replay.

2. **OpenAI engine: wire body vs provenance parameters split.** OpenAI's
   `/v1/images/generations` endpoint rejects unknown fields with HTTP 400, so
   we cannot attach `negativePrompt` / `referenceAssetPaths` / `loraPins` to
   the request JSON. The engine now maintains two JSON objects:
   - **Wire body** — strictly the fields the OpenAI API accepts.
   - **Provenance parameters** — a *superset* of the wire body plus
     `_talevia_negative_prompt`, `_talevia_reference_asset_paths`,
     `_talevia_lora_pins`. The `_talevia_` prefix namespaces extensions that
     are our concern only.

   Separating the two surfaces is the correct shape for the platform contract:
   "engine impls translate what they can; the rest still shows up in the
   audit log." Providers that *do* support LoRA (Stable Diffusion backends,
   custom image-gen endpoints) will translate the common-typed input into
   their native shape and add zero `_talevia_` keys.

3. **Hash all three axes into the lockfile input.** `GenerateImageTool.inputHash`
   now includes `neg`, `refs`, `lora` alongside the existing axes. A change to
   any of them busts the cache. `contentHash` on the bound source node already
   changes when `CharacterRefBody.loraPin.weight` shifts — so the existing
   stale-clip detection path *also* flags it — but we want the hash itself to
   be unambiguous as a standalone key, because `list_lockfile_entries` and
   `find_stale_clips` reason about the hash directly, not the node graph.

4. **Expose the resolved pins on `Output`.** Added `negativePrompt`,
   `referenceAssetIds`, `loraAdapterIds` to `GenerateImageTool.Output`. The
   agent can read back that a bound character injected `hf://mei-lora` without
   re-querying the source graph. Keeps the tool's output self-describing.

5. **Resolve asset ids → paths at the tool boundary.** `MediaPathResolver`
   takes `AssetId → String` asynchronously; `GenerateImageTool` resolves
   `folded.referenceAssetIds` via the injected `MediaStorage` (which is a
   `MediaPathResolver`) before calling the engine. Engines must never see
   `AssetId.value` as a path — that violates the M2 architectural rule.

**Why not make the engine fetch paths itself.** Rejected. The engine layer is
already "translate common → native"; giving it a second responsibility
("resolve project-scoped asset ids") would couple it to the
`MediaPathResolver` contract and make stateless engine impls harder to write.
The tool owns the project context, so path resolution happens there.

**Why `_talevia_` prefixed provenance keys.** Provenance parameters are a
`JsonObject` shared with "what was on the wire." Mixing user-visible fields
(`prompt`, `size`) with implementation-only extensions in the same namespace
would later confuse a replay tool or a human reading the audit log. A
namespace prefix keeps the two concerns visibly distinct.

**Why require engines without a given hook to still record it.** Provenance
is load-bearing for two jobs: audit ("what did you ask the provider?") and
cache-key reconstruction ("would this run hit the same entry?"). If
`OpenAiImageGenEngine` silently dropped the negative prompt, two lockfile
entries with distinct caller intent would have identical provenance — the
same hash collision concern as before, pushed one layer down. Making the
contract explicit ("MUST record") at the `ImageGenEngine` KDoc forces future
providers to inherit the discipline.

**Coverage.** Added two tests to `GenerateImageToolTest`:
- `loraAndReferenceAssetsFlowToEngineAndOutput` — defines a character_ref
  with a `LoraPin` and a reference image, binds it, asserts the engine saw
  both as resolved paths + pins AND that `Output.referenceAssetIds` /
  `Output.loraAdapterIds` surfaced them.
- `loraWeightChangeBustsTheLockfileCache` — generates once with weight 1.0,
  flips to 0.4, asserts second call is a miss.

## 2026-04-19 — `parentIds` on `define_character_ref` / `define_style_bible` / `define_brand_palette`

**Context.** The Source DAG already supported cross-references via
`SourceNode.parents: List<SourceRef>`, and `Source.stale(ancestorId)` walked
the ancestry to report every descendant that needs recomputation. But the
**definer tools the agent actually calls** (`define_character_ref`,
`define_style_bible`, `define_brand_palette`) didn't expose `parentIds` on
their inputs. In practice that meant every consistency node the agent created
sat as a disconnected root — e.g. a `character_ref` whose wardrobe palette
derives from a `brand_palette` had no way to record that derivation, so an
edit to the brand palette *wouldn't* cascade staleness onto the character.

This is the §5.1 Source layer question #2 gap: "改一个 source 节点（比如角色
设定），下游哪些 clip / scene / artifact 会被标为 stale？这个关系是显式的吗？"
The DAG machinery existed; the tool-surface bridge didn't.

**Decision.**

1. **Extend all three definer tool Inputs with `parentIds: List<String>`.**
   Optional, defaults to empty, so every existing caller is untouched. JSON
   schema documents the use-case: "Optional source-node ids this {kind}
   depends on … editing any parent cascades contentHash changes." The agent
   reads that directly in its tool catalog.

2. **Validate ids at the tool boundary** (`ResolveParentRefs.kt`):
   - Blank ids are dropped (LLMs sometimes emit `""`).
   - Self-references (`parentIds = [self]`) fail loudly — cycle prevention at
     the entry point, so lower layers don't need to guard.
   - Unknown ids fail loudly with the hint "define the parent first, or use
     `import_source_node` to bring it in." Dangling `SourceRef`s would show up
     as ghost edges in `list_source_nodes` and corrupt stale-propagation.
   - Duplicates are deduped while preserving caller order.

3. **Extend `addCharacterRef` / `addStyleBible` / `addBrandPalette` helpers
   with a `parents: List<SourceRef> = emptyList()` parameter.** Keeps helpers
   symmetric with the tool surface and avoids forcing tools to open-code
   `addNode(SourceNode(id, kind, body, parents))`. Default is empty, so
   existing tests and callers are unaffected.

4. **On `replace` path, update the node's `parents` field too, not just the
   body.** A user re-defining "Mei" with `parentIds = [style-warm]` *expects*
   the stored node to have that parent afterward — dropping the new parent
   list silently would make re-define semantically asymmetric with first
   define. `replaceNode`'s `bumpedForWrite` re-computes contentHash from the
   updated parents list, so ancestry-driven staleness lands correctly.

**Why validate at the tool boundary and not in `addNode`.** `addNode` is
genre-agnostic and lives in the Source lane alongside the raw data model.
Adding "all `SourceRef`s must resolve" as an invariant there would force
every low-level mutation (migration, import, test fixture) to carry the full
node index — a foot-gun in places where dangling refs are a transient state
that gets fixed up before commit. The definer tools are the one authoritative
user-facing entry where "the graph must be well-formed right now" is a valid
contract to enforce.

**Why fail loudly on unknown parents instead of silently dropping them.** The
LLM typing `parentIds = ["style-warm"]` when `style-warm` doesn't exist is
nearly always a sequencing mistake (it forgot to call `define_style_bible`
first, or misremembered the id). A silent drop would create a character_ref
with no parents but an intent the caller thought it had expressed —
failure-to-propagate-later is a much worse symptom than failure-at-define-now.

**Why keep `parentIds` separate from `consistencyBindingIds`.** The two serve
distinct roles:
- `consistencyBindingIds` on AIGC tools = "fold these nodes into **this one**
  prompt." Ephemeral, per-call.
- `parentIds` on define_* tools = "this node derives from those nodes
  structurally." Persistent in the Source DAG; drives staleness propagation.
A character_ref that *derives from* a brand_palette usually wants both: the
parent ref keeps the derivation visible + cascades staleness, and AIGC tools
still bind just the character (the brand palette folds implicitly through
the DAG-flattening pass when that lands, or explicitly via a second binding
today).

**Coverage.** Added 5 tests to `SourceToolsTest`:
- `defineCharacterRefThreadsParentIdsIntoNode` — parent makes it onto the
  stored node.
- `parentEditCascadesContentHashDownstream` — editing the brand palette
  makes `Source.stale(brand-acme)` include the style_bible that parents it.
- `parentIdsThatDontExistFailLoudly` — unknown id errors out.
- `selfParentIsRejectedAtTheToolBoundary` — cycle protection.
- `replacingCharacterRefUpdatesParentsToo` — re-define rewrites parents, not
  just body.

System prompt gained a paragraph teaching the agent when to use `parentIds`
(derivation, not documentation) and when to rely on flat
`consistencyBindingIds` instead.

---

## 2026-04-19 — `ApplyLutTool` and `style_bible.lutReference` enforcement

**Context.** `StyleBibleBody.lutReference: AssetId?` has existed since the
consistency-node work landed — VISION §3.3 names it as the traditional-lane
anchor for a project-global color grade. But no tool ever *read* the field:
`define_style_bible` wrote it, and the FFmpeg engine's filter pass implemented
brightness / saturation / blur / vignette only. The LUT reference was data
without a consumer, so a user asking "apply the project's LUT to every clip"
had no path that worked end-to-end.

**Decision.** Add a new `apply_lut` tool and teach the FFmpeg engine to bake
LUT references via `lut3d`.

**Tool shape.** `ApplyLutTool.Input` takes `projectId + clipId` plus *exactly
one* of:
- `lutAssetId` — a LUT already imported into the asset catalog. Direct path,
  no Source DAG involvement.
- `styleBibleId` — a `core.consistency.style_bible` node. The tool looks up
  the node, reads its `lutReference` *at apply time*, and also attaches the
  style_bible's nodeId to the clip's `sourceBinding`.

Neither-or-both fails loudly (`IllegalArgumentException`) so the LLM can't
pick ambiguously.

**Why read `lutReference` at apply time, not at render time.** The alternative
is to store only the `styleBibleId` on the filter and re-resolve the LUT
every render. That would give automatic propagation — edit the style_bible's
LUT, re-render, new color — but it would also mean render-time failures when
the style_bible is later removed or has its LUT unset. Matching the existing
staleness paradigm is more consistent: `replace_clip` works the same way (it
snapshots the new asset's sourceBinding at the moment of replacement), and
`find_stale_clips` is the detection half. The workflow is symmetric across
AIGC and traditional clips: edit the upstream node → `find_stale_clips` → for
traditional-LUT clips, re-run `apply_lut`; for AIGC clips, regenerate and
`replace_clip`. Keeping apply-time resolution also avoids needing a
"style_bible-aware" FFmpeg render loop, which would leak source-DAG types
into the engine abstraction (VISION anti-requirement).

**Why attach `styleBibleId` to `Clip.sourceBinding` on the style_bible path.**
The binding is what future staleness machinery (beyond today's
lockfile-only `find_stale_clips`) needs to see this clip as downstream of
the style_bible. Today the field sits unused for traditional clips; writing
it now means when find_stale_clips is extended to walk `Clip.sourceBinding`
directly (a planned follow-up), LUT clips automatically participate. Not
writing it would create a silent gap the user would have to manually repair.

**Why not store `styleBibleId` *on* the filter.** Considered a `Filter`
variant tagged with a source-node id for "smart" rerendering. Dropped:
`Filter` is a render-engine-facing type, not a DAG-aware one, and keeping it
numeric-param + optional asset keeps the engine layer unaware of the Source
DAG. The binding belongs on the clip — which already has a first-class
`sourceBinding` set — not on the filter.

**Domain change.** Extended `Filter` with `val assetId: AssetId? = null`.
Backwards-compatible (default null; ignoreUnknownKeys on the JSON config
absorbs older rows during read). The existing numeric `params` map is
preserved as-is for filters like `brightness`, which don't need an asset.
Path-bearing filters (LUT today, image-overlays tomorrow) set `assetId`
instead of trying to squeeze a path into a `Map<String, Float>`.

**FFmpeg rendering.** The render loop pre-resolves every distinct
`Filter.assetId` to an absolute path via `MediaPathResolver` *before*
invoking `filterChainFor`. The resolver call is the only suspend point;
`filterChainFor` stays non-suspend so the existing `FilterChainTest` unit
tests (which use a `NullResolver`) keep working. The `lut` filter renders
as `lut3d=file=<escaped path>`. Escaping follows ffmpeg's filtergraph
metacharacter rules: `:`, `\\`, `'`, `,`, `;`, `[`, `]` all get
backslash-prefixed, because paths with colons or brackets otherwise get
re-parsed as filter syntax.

**Android / iOS gap.** `Media3VideoEngine` and `AVFoundationVideoEngine`
continue to carry filters on the Timeline without baking them — the same
gap already documented in `CLAUDE.md` "Known incomplete" for
brightness/saturation/blur. `apply_lut` inherits that gap rather than
re-opening it; the filter is attached to the clip regardless of engine so
a later render on FFmpeg (or once Media3/AVFoundation catch up) will
honor it. The system prompt explicitly teaches this so the LLM doesn't
promise a Media3/iOS user that the LUT will render today.

**Missing-asset behavior.** If the LUT assetId can't be resolved at render
time, the filter is dropped silently rather than aborting the render.
Matches the existing "unknown filter name is dropped" behavior — one
misconfigured filter shouldn't blow up a whole export. Validation at
apply time (`media.get(lutId) ?: error(...)`) catches the common case of
typos or un-imported LUTs early, so render-time drop should be rare.

**Coverage.**
- `ApplyLutToolTest`: direct-asset path, style_bible path (with sourceBinding
  attach), missing style_bible, style_bible without lutReference, both-ids
  rejected, neither-id rejected, missing-asset, non-video clip rejected.
- `FilterChainTest`: new cases for lut-with-resolved-path, lut-without-path
  (dropped), lut-without-assetId (dropped, defensive), and special-char
  escaping in paths.

**Registration.** All four composition roots (Android, Desktop, Server,
iOS Swift) register `ApplyLutTool`. Mirrors the pattern for every other
tool — keeps the tool surface uniform across platforms.

**System prompt.** New "Traditional color grading (LUT)" section teaches
the two call shapes and names style_bible as the preferred path when a
project has one. Key phrase `apply_lut` added to `TaleviaSystemPromptTest`
so the tool's invocation phrase can't silently drift.

---

## 2026-04-19 — `RemoveClipTool` — the missing scalpel for the editing lineup

**Context.** The cut/stitch/filter/transition lineup the agent uses to *edit*
(VISION §1) had a gaping hole: the agent could `add_clip`, `replace_clip`,
`split_clip`, `apply_filter`, `apply_lut`, `add_transition`, `add_subtitle` —
but had **no way to delete a clip**. The only workaround was
`revert_timeline` to a prior snapshot, which is a bulldozer (it discards
every later edit too) where a scalpel was needed. A user saying "drop the
second take" had no clean execution path.

**Decision.** Add a new `remove_clip(projectId, clipId)` tool that finds a
clip by id across all tracks and removes it. Output reports the trackId it
came from and remaining clip count on that track.

**Why no ripple-delete.** Considered shifting downstream clips' `timeRange`s
left by the gap size. Rejected: timestamps in the timeline are addressed by
absolute time across tracks. Subtitles at 00:14, transitions between v1[02]
and v1[03], audio fades at 00:08 — all of those are positioned by absolute
timeline time. Ripple-deleting one video clip would silently invalidate
transitions / subtitles / audio cues whose authors were targeting wall-clock
positions on the timeline, not relative offsets within a track. The
NLE-style "ripple delete" trade-off is real (a casual editor *expects* the
gap to close), but the cost of silent corruption to other tracks is higher.
If the user wants ripple behavior, the agent can chain `move_clip` for each
downstream clip — explicit two-step instead of magic side effect.

**Why preserve the empty track.** When a track loses its last clip, the
track itself is left in place rather than auto-removed. Reason: subsequent
`add_clip(trackId=…)` calls need a target. The agent often deletes the
single placeholder clip on a fresh track and immediately adds a real one;
forcing it to recreate the track would add a round-trip and a chance to
pick the wrong track id.

**Why timeline.write permission.** Same scope as add_clip / split_clip /
replace_clip. Removing a clip is symmetric with adding one — a routine
mutation, not a project-level destructive op like `delete_project`.

**Snapshot for revert_timeline.** Emits `Part.TimelineSnapshot` post-mutation
via the shared `emitTimelineSnapshot` helper. So `revert_timeline` can roll
the deletion back — no data is permanently lost from the agent's POV. This
is the same pattern every other timeline-mutating tool uses; consistency
matters more than micro-optimizing snapshot frequency.

**Failure mode.** Missing clipId fails loudly with `IllegalStateException`
naming the offending id, and the project is left untouched. We don't
silently no-op because the LLM should learn from the error and find the
right id rather than silently moving on assuming the delete succeeded.

**Coverage.** `RemoveClipToolTest` (6 tests): named-clip removal with
sibling preservation and no-shift assertion, cross-track scoping
(audio track untouched when video clip removed), empty-track
preservation, audio-clip removal from audio track, missing-clip
fail-loud, post-mutation snapshot emission for revert_timeline.

**Registration.** All four composition roots (server, desktop, Android,
iOS Swift) register `RemoveClipTool` next to `SplitClipTool`. System
prompt gained a "Removing clips" section teaching when to use it (drop a
clip vs revert) and the no-ripple semantics. Key phrase `remove_clip`
added to `TaleviaSystemPromptTest`.

---

## 2026-04-19 — `MoveClipTool` — closes the ripple-delete chain

**Context.** `RemoveClipTool` shipped with system-prompt guidance saying
"if you want ripple-delete behavior, follow up with `move_clip` on each
downstream clip" — but `move_clip` did not exist. Same for the help text
on `RemoveClipTool` itself. The agent was being told to call a tool that
wasn't registered, a credibility gap that would manifest as the LLM either
hallucinating a `move_clip` call that fails at dispatch or silently dropping
the ripple-delete workflow entirely.

**Decision.** Add `move_clip(projectId, clipId, newStartSeconds)` that
repositions a clip on the timeline by id. Output reports trackId, oldStart,
newStart so the LLM can chain moves without re-reading state.

**Why same-track only.** Considered allowing a `newTrackId` parameter so
the agent could move a clip across tracks (e.g. v1 → v2). Rejected for v1:
cross-track moves change the rendering pipeline (different stack ordering
for video, different filter chains, different audio routing). The
move_clip tool would either need to validate "destination track is the
same track *type*" (refuse video → audio) or silently allow nonsense, both
of which are worse than "this tool is for shifting in time, period." When
a real cross-track driver appears, a separate `move_clip_to_track` tool
keeps the semantics distinct.

**Why preserve `sourceRange`.** A move shifts when the clip plays on the
timeline, not what material it plays. `sourceRange` (start/duration into
the source asset) is untouched — the same frames render, just at a
different timeline time. Conflating "move on timeline" with "trim source
window" would make this tool double as `trim_clip`, which deserves its
own primitive when it lands.

**Why allow overlap.** Considered refusing a move that would create
overlap with siblings. Rejected: overlapping clips on a single track are
the foundation of picture-in-picture, layered effects, and transitions
(which by definition span the boundary between two adjacent clips). The
existing `add_clip` doesn't refuse overlap; `move_clip` matches that
discipline. The agent (or user inspecting the result) is expected to
catch unintended overlaps and iterate.

**Why `newStartSeconds: Double` and not `deltaSeconds`.** Absolute time
matches how every other timeline tool addresses positions
(`atTimelineSeconds` in `split_clip`, `start` in `add_clip`). A delta API
would force the agent to read the current position before computing the
target, adding a round-trip. The agent can compute `newStart = oldStart +
delta` itself when it has the clip in hand from a prior tool result.

**Validation.**
- `newStartSeconds < 0` → IllegalStateException ("must be >= 0").
  Negative start times are nonsense on a timeline anchored at zero.
- Missing clipId → IllegalStateException naming the offending id, project
  untouched. Same fail-loud discipline as `remove_clip` / `split_clip`.

**Snapshot for revert_timeline.** Emits `Part.TimelineSnapshot`
post-mutation — same pattern as every other timeline-mutating tool. The
move can be rolled back via `revert_timeline` like any other edit.

**Sibling reordering.** After updating `timeRange.start` the track's
clips are re-sorted by start time so downstream consumers don't have to
assume sorted-or-not. Matches what `SplitClipTool` does on its rebuild.

**Coverage.** `MoveClipToolTest` (7 tests): same-track move with sibling
reordering, duration + sourceRange preservation, cross-track scoping,
overlap-allowed (no refusal), missing-clip fail-loud, negative-start
rejection, post-mutation snapshot emission for revert_timeline.

**Registration.** All four composition roots register `MoveClipTool`
next to `RemoveClipTool`. System prompt gained a "Moving clips" section
teaching duration preservation, the same-track-only constraint, and the
ripple-delete chain pattern (`oldStart - removedDuration` arithmetic).
Key phrase `move_clip` added to `TaleviaSystemPromptTest`.


## 2026-04-19 — `VideoGenEngine` + `GenerateVideoTool` — AIGC video lane

**Context.** VISION §2 lists "文生视频 (text-to-video)" alongside text-to-image
and TTS as one of the AIGC compiler lanes. The implementation had the other two
(`GenerateImageTool` + `SynthesizeSpeechTool`) but no video-generation path —
even the system prompt's fallback example of an unsupported capability was
"text-to-video", proving the gap was visible to the model. Sora 2 went GA on
the OpenAI API in late 2025; closing this lane now unblocks storyboard → motion
previews without wiring up a second provider.

**Decision.** Add three pieces, mirroring the image-gen shape so AIGC tooling
stays uniform:
1. `VideoGenEngine` interface in `core.platform` with `VideoGenRequest` /
   `VideoGenResult` / `GeneratedVideo(mp4Bytes, width, height,
   durationSeconds)`.
2. `GenerateVideoTool` in `core.tool.builtin.aigc` using the existing
   `AigcPipeline` (seed mint, prompt fold, lockfile lookup / record).
3. `OpenAiSoraVideoGenEngine` targeting `/v1/videos` + `/v1/videos/{id}/content`.

**Per-modality interface vs umbrella.** Followed the precedent set by
`ImageGenEngine` / `TtsEngine`: one interface per modality rather than a
generic `GenerativeEngine` umbrella. The signature differences (video has
`durationSeconds`, image has `n`, TTS has `voice` + `format`) would either be
lost under a vague common shape or require every caller to carry
modality-specific extras in `parameters: Map<String, String>`. Per-modality
types type-check inputs and keep translation local.

**Duration in the cache key.** `durationSeconds` is hashed into the lockfile
input, so a 4s render and an 8s render of otherwise-identical inputs produce
two distinct cache entries. Dropping duration from the hash would conflate
semantically distinct outputs — the user would ask for 8s and get the cached
4s asset back.

**Duration echoed on `GeneratedVideo`.** The provider may clamp the requested
duration to a supported step (Sora 2 supports 4 / 8 / 12). The engine prefers
the provider's echoed duration (if any) and falls back to the request value.
This means `Output.durationSeconds` reflects what was actually rendered, not
what was asked for — so `MediaMetadata.duration` on the imported asset is
honest and downstream code (export timeline length, stale-clip tracking) sees
the real number.

**Async polling inside the engine.** Text-to-video is asynchronous: POST
creates a job, GET polls status, `GET /v1/videos/{id}/content` downloads the
finished bytes. The engine hides this behind a single suspend call — callers
see one blocking operation that returns the mp4. Poll interval (`5s`) and max
wait (`10 min`) are constructor-tunable so tests can inject faster values and
production can bump the deadline if Sora queues get congested. Failure /
cancellation / timeout all surface as `error(...)` with the last seen status
so the agent can explain the failure to the user rather than silently fall
through.

**Why the engine polls, not the tool.** Considered having `GenerateVideoTool`
return immediately with a job id and a second tool to download when ready.
Rejected: that shape forces the agent to orchestrate two calls for every
render, bloats the tool registry (every async provider would add its own
poll tool), and the tool / lockfile layer works in terms of finished assets.
Keeping the async contract inside the engine keeps the tool layer uniform
with image / TTS.

**Provenance for hooks the provider doesn't yet support.** Sora's create
endpoint doesn't yet accept negative prompts, reference clips, or LoRA pins;
the engine still records whatever the caller passed in
`GenerationProvenance.parameters` via `_talevia_*` sidecar keys. Matches the
precedent set in `OpenAiImageGenEngine` — the lockfile hash would otherwise
collide when the agent *did* change one of those fields but Sora ignored it,
hiding the semantic input change from the cache layer.

**Modality gating at the composition root.** Registered in
`apps/{desktop,server}` under the same `OPENAI_API_KEY` gate as the other
AIGC engines. Not registered on Android or iOS — those platforms don't
register any AIGC tool today because neither container wires an HTTP client
/ blob writer pair. When we add those, `generate_video` comes along for the
ride with the other AIGC tools.

**Coverage.** `GenerateVideoToolTest` (6 tests with a `FakeVideoGenEngine`):
asset persistence + provenance readback, client-side seed minting when
`seed=null`, consistency-binding prompt folding, lockfile cache hit on repeat
+ miss when duration changes, sourceContentHashes snapshot for stale-clip
detection, and LoRA / reference-asset flow-through.

**System prompt.** New "# AIGC video (text-to-video)" section teaches the
model the defaults (Sora 2, 1280x720, 5s), duration-cache-key semantics,
binding parity with image gen, and the "this takes a while" expectation
that callers should surface to the user. `generate_video` added to the
`Compiler = your Tool calls` list and to the prompt-test key phrases.


## 2026-04-19 — `MoveClipTool` — closing a credibility gap in the system prompt

**Context.** The system prompt's "Removing clips" section promised the model
it could chain `move_clip` on every downstream clip to simulate ripple-delete
after `remove_clip` — but the tool didn't exist. The LLM was being told to
call a nonexistent primitive, which risks hallucinated calls and broken
recovery paths. The gap was pre-existing (earlier session had authored the
tool file + tests but not wired registration).

**Decision.** Register the existing `MoveClipTool` in all four composition
roots (desktop, server, Android, iOS Swift) and teach the model explicitly
via a new "# Moving clips" system-prompt section. Add `move_clip` to the
prompt-test key phrases so removal regresses loudly next time.

**Semantics captured in the prompt.**
- Changes `timeRange.start`; duration and `sourceRange` preserved (same
  material, different timeline position).
- Same-track only — cross-track moves change rendering semantics (stack
  order, filter pipeline) and deserve a separate tool when a driver appears.
- No overlap validation — PiP, transitions, and layered effects legitimately
  need overlapping clips; refusing to move into an overlap would block
  real workflows.
- Emits a timeline snapshot, so `revert_timeline` can undo the move —
  consistency with every other timeline-mutating tool.

**Bundled with `generate_video` commit.** Linter auto-added the MoveClipTool
import to the server container when it saw the untracked file, so splitting
the commits would have required fighting the linter. Bundled into the same
commit as T6 (VideoGen); commit message calls out both pieces explicitly.
Same pattern as the earlier `remove_clip` + `apply_lut` bundle.
