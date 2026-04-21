## 2026-04-19 — `generate_music` tool + `MusicGenEngine` contract, no concrete provider

**Context.** VISION §2 lists "音乐生成" as an AIGC compiler pillar alongside
image / video / TTS. The first three have contracts + concrete OpenAI-backed
engines + tools; music did not. Gap-analysis flagged it as the clearest §2
pillar absence.

**Decision.**
- Add `core/platform/MusicGenEngine.kt`: `{providerId, suspend fun generate(MusicGenRequest)}`
  mirroring `VideoGenEngine` shape. Request: `{prompt, modelId, seed, durationSeconds,
  format, parameters}`. Result: `{GeneratedMusic(audioBytes, format, durationSeconds),
  provenance}`.
- Add `core/tool/builtin/aigc/GenerateMusicTool.kt`: same seed-mint / lockfile-hash
  / consistency-fold / record pattern as `GenerateImageTool` and `GenerateVideoTool`.
  Cache key over `(tool, model, seed, duration, format, effective prompt, bindings,
  negative)`.
- Register in desktop + server containers with the same `musicGen?.let { register(…) }`
  gating pattern used for image / video / TTS. `musicGen` defaults to `null`
  in both containers — no concrete provider wired.
- `character_ref.voiceId` bindings are silently ignored by music gen: music has
  no speaking voice; only `style_bible` / `brand_palette` meaningfully fold.
- Unit coverage in `GenerateMusicToolTest` via a fake engine (persistence,
  seed auto-mint, style-bible folding, lockfile cache hit + duration bust).

**Alternatives considered.**
- **Ship a concrete Replicate-backed MusicGen engine.** Replicate does host
  facebook/musicgen behind a public token-gated API. Rejected *for this round*
  because the architectural lift (proving "add a new AIGC lane = add a contract
  + a tool + wire it in, same shape as the other three") is the first-class
  VISION §2 claim ("加一个新 Tool 够不够像注册一样低?"). A Replicate engine
  is useful but orthogonal and can land in a follow-up without touching the
  contract or tool. Keeping the contract stable across future providers is
  more valuable than one specific provider.
- **Bundle music into a generic `GenerativeProviderRegistry`.** Rejected for
  the same reason `ImageGenEngine` / `VideoGenEngine` / `TtsEngine` are
  separate interfaces: modality-specific fields (duration, format, voice,
  dimensions) don't usefully share a common shape, and a premature umbrella
  would either be too vague to type-check or leak modality concepts between
  lanes.
- **Stub engine that throws "not configured."** Rejected — adds a class that
  only exists to fail, and the `musicGen?.let` gating already handles the
  unregistered case cleanly (same pattern as `imageGen` / `videoGen` / `tts`
  when `OPENAI_API_KEY` is unset).
- **Register `generate_music` unconditionally with a placeholder engine.**
  Same objection as stub: surfacing a tool to the LLM that will always fail
  is worse UX than omitting it.

**Why.** VISION §2 requires that new compiler lanes plug in cheaply. This
change makes "add music gen" a 2-file affair (platform contract + tool)
plus one line per container — exactly the low-friction extensibility the
VISION claims. Once a concrete provider engine lands it's a 1-line flip in
the container (`val musicGen = RealEngine(…)`).

**How to apply.** Follow the same shape for future AIGC lanes (sound FX, 3D,
lip-sync, etc.): one platform interface per modality, one tool, `?.let`
register in each container. Do not expand `MusicGenEngine` with modality-
alien fields — route provider extras through `MusicGenRequest.parameters` and
echo them into provenance.

---
