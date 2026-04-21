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
