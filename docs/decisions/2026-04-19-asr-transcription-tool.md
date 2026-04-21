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
