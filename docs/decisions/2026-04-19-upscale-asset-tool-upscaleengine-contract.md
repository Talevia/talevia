## 2026-04-19 — `upscale_asset` tool + `UpscaleEngine` contract

**Context.** VISION §2 lists "ML 加工: 字幕识别、抠像、超分、自动调色、
去噪" as compiler pillars. Captioning landed via `transcribe_asset` +
`auto_subtitle_clip`; super-resolution was the next clearest gap in the ML
"enhance existing asset" lane. Missing here blocks any workflow that wants
to push AIGC imagery to a higher-resolution master, clean up noisy imports,
or ship a 4K cut from 1080p sources.

**Decision.**
- Add `core/platform/UpscaleEngine.kt`. Shape mirrors `ImageGenEngine`
  rather than `AsrEngine` / `VisionEngine` because SR emits bytes (new
  artifact), not derived text: `UpscaleRequest(imagePath, modelId, scale,
  seed, format, parameters)` → `UpscaleResult(UpscaledImage, provenance)`.
- Add `core/tool/builtin/aigc/UpscaleAssetTool.kt`. Same seed-mint /
  lockfile-hash / provenance-record pattern via `AigcPipeline`. Hash is
  `(tool, sourceAssetId, model, scale, seed, format)` — no consistency
  folding because upscaling is a pixel-fidelity op, not a creative one.
  Permission `"aigc.generate"` (same bucket as other byte-producing AIGC
  lanes).
- **Folder placement:** under `tool/builtin/aigc/`, not `tool/builtin/ml/`.
  The split is operational: `aigc/` is "emits bytes, uses AigcPipeline";
  `ml/` is "emits derived text" (`describe_asset`, `transcribe_asset`).
  Super-res emits bytes and wants the same seed + lockfile disciplines as
  image gen. Future denoise / inpaint / style-transfer tools belong here
  too; pure-analysis tools belong under `ml/`.
- Range guard: `scale in 2..8` — narrower rejection (1x is a no-op,
  anything past 8x is a provider-specific exotic). Engines clamp further
  based on the chosen model (2x-only model + `scale=4` input → engine
  clamps and records actual scale in provenance).
- `v1: images only`. Video super-res is a different beast (temporal
  coherence, frame batching) — the cleanest path when we add it is a
  sibling `VideoUpscaleEngine`, not overloading this one.
- Wired into desktop + server containers with the same `upscale?.let { … }`
  gating pattern. No bundled concrete engine — Real-ESRGAN / SUPIR are
  usually on Replicate or run locally, both environment-specific.
- Unit coverage in `UpscaleAssetToolTest`: persistence, seed auto-mint,
  lockfile cache hit + scale-bust, scale-range guard.

**Alternatives considered.**
- **Separate `image_upscale` + `video_upscale` tools.** Rejected for v1 —
  video SR is genuinely different enough to deserve its own contract, not a
  flag on this one, but shipping only images first is correct sequencing.
- **Fold into `GenerateImageTool` with an `assetId` input.** Rejected —
  image gen takes a prompt and produces novel imagery; upscaling takes an
  asset and refines it. Same engine pattern, different intent; fusing them
  would make the `GenerateImageTool` schema harder for the LLM to reason
  about.
- **Place under `tool/builtin/ml/`.** Rejected — `ml/` is
  "analyse → text", and `upscale_asset` needs `AigcPipeline` (seed,
  lockfile). Either the tool sits with its pipeline under `aigc/` or
  `AigcPipeline` gets promoted; the former is cheaper.
- **Default scale 4 instead of 2.** Rejected — most users want 2x (safer,
  faster); 4x is the "really push it" case. 2x minimises surprise on
  first call.

**Why.** VISION §2's "ML 加工" lane now has two concrete exemplars
(transcribe_asset + captioning, and super-res), proving the ML lane can grow
orthogonal to AIGC generation. The architectural precedent also documents
what future enhancement-that-emits-bytes tools (denoise, inpaint, style-
transfer) should look like — one interface under `core/platform/`, one tool
under `tool/builtin/aigc/` that uses `AigcPipeline`.

**How to apply.** For the next byte-emitting ML enhancement (denoise, etc.),
follow the same three-piece shape: engine interface in `core/platform/`,
tool under `tool/builtin/aigc/`, `?.let`-gated registration in each
container. Keep permission at `"aigc.generate"` (the external cost / byte
production / cache concerns match that bucket).

---
