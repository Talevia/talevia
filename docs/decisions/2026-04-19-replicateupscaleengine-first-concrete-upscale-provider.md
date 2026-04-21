## 2026-04-19 — `ReplicateUpscaleEngine` — first concrete upscale provider

**Context.** `upscale_asset` tool + `UpscaleEngine` contract shipped but
had no bundled provider. CLAUDE.md known-incomplete flagged it as the
next Replicate engine to add; the music engine had established the
polling pattern.

**Decision.**
- New `ReplicateUpscaleEngine` under `core/src/jvmMain/kotlin/...` (NOT
  commonMain). Same async-poll shape as `ReplicateMusicGenEngine`:
  `POST /v1/models/{slug}/predictions` → poll `urls.get` every 2 s →
  download the output URL. Default model `nightmareai/real-esrgan`.
- **Placement: `jvmMain`, not `commonMain`.** The engine has to read the
  source image file to attach it to the request, and `commonMain`
  cannot touch `java.io.File`. `OpenAiWhisperEngine` /
  `OpenAiVisionEngine` are the precedent — both need filesystem access
  and both live under `jvmMain`. Desktop + server pull the same artifact
  via the shared `ktor-client-cio` configuration.
- **Upload: base64 `data:` URI.** Replicate model inputs accept either a
  public URL or a `data:` URI for binary media. Using a data URI keeps
  the engine stateless (no bucket config), works for any format the
  downstream model accepts, and matches the existing pattern described
  in Replicate's docs. Payload balloons with image size — acceptable for
  stills up to a few MB; a pre-signed upload path is the escape hatch
  for 4K+ inputs (docs note, deferred).
- **Provenance omits the image data URI.** Logging the entire base64
  payload into `LockfileEntry.provenance.parameters` would double the
  per-entry storage cost and add zero replay value (the image is
  identifiable via the `sourceAssetId` dependency graph). Records only
  scale / seed / format / slug + passthrough parameters.
- **Width / height returned as (0, 0).** SR providers don't reliably
  echo output dimensions back; `UpscaleAssetTool` already relies on
  `storage.import` probing the persisted bytes — the (0, 0) on
  `UpscaledImage` is a deliberate "the engine doesn't know; ask the
  downstream probe" signal.
- Wired into desktop + server containers behind `REPLICATE_API_TOKEN`.
  `REPLICATE_UPSCALE_MODEL` overrides the default slug (SUPIR,
  CodeFormer, etc.). CLAUDE.md + system prompt updated.
- MockEngine-backed tests cover happy-path submit/poll/download, array
  output shape, failed status, and empty-source fail-loud.

**Alternatives considered.**
- **Make the engine commonMain-friendly with a pluggable `readImage`
  lambda.** Rejected — adds complexity (every caller has to pass a
  working reader) for a capability iOS/Android don't need right now.
  Following the Whisper/Vision precedent keeps the codebase consistent.
- **Use pre-signed upload URLs.** Considered for 4K+ support; rejected
  for v1 because it requires bucket config (S3 / GCS / similar) and a
  separate multipart upload step. Data-URI works for the common case;
  we can layer a `UploadStrategy` abstraction later if the file-size
  ceiling becomes a real workflow block.
- **Use the `/v1/predictions` endpoint with an explicit version hash.**
  Rejected for the same reason as `ReplicateMusicGenEngine` — forces a
  version pin that ages poorly; the model-scoped endpoint always uses
  Replicate's published latest.

**Why.** Closes the second Replicate lane. VISION §2 ML-enhancement
pillar ("超分") is now demonstrably usable on Mac desktop + server with
a single env var. Establishes the Replicate-engine pattern for future
enhancements (denoise, inpaint, style-transfer): sibling file under
`jvmMain/.../provider/replicate/`, same constructor shape, same
container gate.

**How to apply.** For the next Replicate-backed enhancement, clone
this file, swap the default `modelSlug`, and confirm the `output`
shape — most Replicate models return either a string URL or a
one-element list, which the shared `extractImageUrl` / `extractAudioUrl`
shape already handles. Do not echo bulk input bytes into provenance.

---
