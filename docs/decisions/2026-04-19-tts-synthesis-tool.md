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
