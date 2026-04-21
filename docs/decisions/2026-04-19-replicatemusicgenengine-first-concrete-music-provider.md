## 2026-04-19 — `ReplicateMusicGenEngine` — first concrete music provider

**Context.** `MusicGenEngine` + `generate_music` shipped as a contract-only
lane; no bundled provider meant the tool stayed unregistered on every
deployed container and "generate music" was a documentation feature. No
mainstream direct public API (Suno / Udio don't expose one), so Replicate-
hosted MusicGen was the natural first target.

**Decision.**
- New `core/provider/replicate/ReplicateMusicGenEngine.kt` implementing
  `MusicGenEngine`. Async-poll shape parallel to `OpenAiSoraVideoGenEngine`:
  `POST /v1/models/{slug}/predictions` with `{input: {prompt, duration, seed,
  output_format}}`, poll the returned `urls.get` every 3 s until status ∈
  `{succeeded, failed, canceled}`, download the first URL in `output`.
- **One model slug per engine instance** (default `meta/musicgen`,
  overridable via constructor). Arbitrary-slug routing was rejected because
  the engine owns the translation to `MusicGenResult` + provenance and a
  slug-unaware engine couldn't verify the response shape. Callers wanting a
  different Replicate model instantiate a second engine.
- **Duration: `ceil(durationSeconds).toInt()`.** MusicGen accepts integer
  seconds; rounding up means a 15.5 s request doesn't silently truncate.
- **`output` shape normaliser.** Replicate returns either a string URL, a
  one-element list, or `{audio: "..."}` depending on model version. The
  engine handles all three defensively; anything else → loud fail.
- **Seed passed on the wire unconditionally.** Some MusicGen versions
  honour it, others ignore it; passing it unconditionally means a
  seed-aware version upgrade needs no engine change, and the lockfile hash
  is meaningful either way.
- Wired into desktop + server containers behind `REPLICATE_API_TOKEN`.
  `REPLICATE_MUSICGEN_MODEL` env var overrides the default slug for teams
  with fine-tuned variants.
- MockEngine-backed unit tests cover: happy-path submit → poll (starting →
  processing → succeeded) → download, array-shaped `output`,
  `status=failed` error path, and duration-ceil behaviour.

**Alternatives considered.**
- **Hardcode a specific model version hash.** Rejected — Replicate
  versions rotate, pinning would age badly. The model-scoped endpoint
  always uses the published latest version; provenance records the
  version Replicate echoes on the final payload.
- **Auto-detect `modelSlug` from the tool's `modelId` input.** Rejected
  — tool-layer `modelId` is a free-form string the tool hashes for cache
  coherence; using it as a Replicate slug would turn typos into silent
  "404 model not found" and inflate the cache namespace.
- **Replace seed with `classifier_free_guidance` / `temperature`.**
  Rejected — those are sampler knobs, not determinism controls. The
  VISION §3.1 seed discipline is about reproducibility intent; passing
  a seed regardless of whether the model reads it preserves that
  intent. Sampler-knob tuning belongs in `request.parameters`.
- **Use `/v1/predictions` with an explicit version hash.** Considered;
  rejected because the version-scoped endpoint needs a 64-char hash the
  caller must keep in sync with Replicate's release cadence. The
  model-scoped endpoint trades one env override for one deploy-time
  concern.

**Why.** Closes the "tool ships, nothing to call" gap for music. `generate_music`
now turns into a functional lane any deployment with a Replicate token
can use, and the shape established here is the template for the next
Replicate-backed engine (`ReplicateUpscaleEngine` for Real-ESRGAN, same
poll shape, different model slug + output field).

**How to apply.** Future Replicate engines should live under
`core/provider/replicate/`, take the same constructor shape
`(httpClient, apiKey, modelSlug, …, pollIntervalMs, maxWaitMs)`,
normalise the `output` field defensively, and wire into containers
with the same `REPLICATE_API_TOKEN` gate pattern. Do not couple the
Replicate engines to OpenAI's — they share no code path today beyond
the abstract `*Engine` interface.

---
