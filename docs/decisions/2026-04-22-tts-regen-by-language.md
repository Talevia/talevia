## 2026-04-22 — `fork_project(variantSpec.language=…)` regenerates TTS per text clip (VISION §5.2 / §5.5 rubric)

Commit: `4f1a819`

**Context.** The 2026-04-21 `generate-project-variant` decision shipped
`fork_project(variantSpec={aspectRatio?, durationSecondsMax?})` as a pure
in-memory reshape and explicitly deferred a `language` dimension: "TTS
regeneration needs provider calls, input parameter mutation on text clips,
and source-binding propagation; all out of scope for a pure in-memory
reshape. A follow-up cycle can add `language` to VariantSpec once the speech
regen path has a 're-emit TTS for all text clips bound to source node X'
primitive." This cycle closes that extension point — users asking "give me
the Spanish version of my vlog" no longer have to hand-dispatch
`synthesize_speech` per subtitle clip.

Backlog bullet (`docs/BACKLOG.md` P1 top, after P0 emptied):
`tts-regen-by-language`.

**Decision.**

1. **`VariantSpec` grows `language: String?`** (ISO-639-1 hint). When set,
   `ForkProjectTool` walks the fork's timeline, picks out every `Clip.Text`
   with a non-blank `text`, and dispatches the registered `synthesize_speech`
   tool per clip with `(text, projectId=fork, language, consistencyBindingIds)`.
   Blank / empty-text clips are skipped — pure-timing placeholders shouldn't
   produce silent audio.

2. **TTS results surface as `Output.languageRegeneratedClips: List<LanguageRegenResult>`**
   — `(clipId, assetId, cacheHit)` per regeneration. The fork's **timeline
   is not rewired**: the caller chains `replace_clip` (or `add_clip` on a
   voiceover track) per entry to swap the actual audio reference. Rationale:
   fork's identity is "reshape primitive", not "timeline editor"; we keep
   the mutations the user sees in the transcript explicit.

3. **`SynthesizeSpeechTool.Input` grows `language: String?`**; participates
   in the lockfile `inputHash` so `(same text, different language)` is a
   distinct cache entry rather than a stale hit. Echoed back in Output.
   `TtsRequest.language` carries through to the engine — OpenAI TTS
   auto-detects language and doesn't route the hint to the wire, but the
   field is still recorded in provenance and used for cache-key discipline.
   Providers that do accept a language param can adopt it without any
   further interface churn.

4. **Registry injection instead of direct SynthesizeSpeechTool coupling.**
   `ForkProjectTool` constructor grew `registry: ToolRegistry? = null` —
   when `variantSpec.language` is set we look up `synthesize_speech` through
   the registry and dispatch via its typed `RegisteredTool.dispatch(rawInput, ctx)`
   entry point. Matches the existing `CompareAigcCandidatesTool` pattern
   (one tool orchestrating another through the registry). The four JVM
   AppContainers (CLI / Desktop / Server / Android) now call
   `register(ForkProjectTool(projects, this))`; iOS `AppContainer.swift`
   passes `registry: registry`. Rig defaulting `null` keeps the wide net of
   existing tests compiling without change.

5. **Loud failure when language is asked for without a registry**: explicit
   `error()` with a hint naming the fix ("wire TtsEngine/SynthesizeSpeechTool
   in the container or drop variantSpec.language"). §3a rule 4 — there is no
   sensible silent fallback: swallowing the request would invisibly ship the
   wrong content.

**Alternatives considered.**

- **Inject `SynthesizeSpeechTool` directly into `ForkProjectTool`** — cleaner
  types at the call site but couples two tools at the constructor level.
  `CompareAigcCandidatesTool` already uses the `ToolRegistry`-through-
  indirection pattern; adopting the same shape keeps the coupling uniform and
  avoids blowing up the constructor's signature across five AppContainers
  with tool-specific dependencies (engine, storage, blob writer) that
  `ForkProjectTool` doesn't otherwise care about.
- **Don't dispatch inside fork, just surface `pendingTtsRegenerations`** — would
  preserve the "fork is a pure transform" invariant perfectly but forces the
  LLM into N additional tool-call turns per fork. Defeats the backlog's
  "same vlog in Spanish" one-intent goal. The Output does stay side-effect
  explicit: the caller still chains `replace_clip` to actually swap audio.
- **Auto-rewire audio clips too** — would need a text-clip → audio-clip
  mapping (which does not exist in the domain model) plus a heuristic for
  matching by sourceBinding or time-range. Too speculative; leaves the user
  no way to intervene if the auto-match is wrong.
- **New dedicated `regenerate_tts_in_language` tool** — §3a rule 1 violates
  it (net +1 tool) and the backlog bullet explicitly scopes the feature to
  `fork_project.variantSpec` — a fold-in, not a split-out. The existing
  `synthesize_speech` tool is already the primitive for "re-emit TTS for
  one clip"; we just compose it.
- **Wire `language` into OpenAI TTS body** — OpenAI's Speech API doesn't
  accept a language parameter; auto-detects from text. The hint travels
  through `TtsRequest.language` anyway so future providers (Azure, Eleven
  Labs) can forward it to their own wire protocols when they ship.

**Coverage.**

- `tool.builtin.project.ForkProjectLanguageVariantTest` — 7 tests covering:
  per-text-clip dispatch with correct language propagation to the engine +
  lockfile; blank/empty text clips skipped; cross-fork cache behaviour
  (language participates in the hash); distinct lockfile entries for
  distinct languages on the same text; loud failure without a registry; no
  regen when language is unset; Output JSON round-trip through
  `JsonConfig.default`.
- Existing `SynthesizeSpeechToolTest` continues to pass — the new Input /
  Output fields default to null and don't perturb legacy test fixtures.
- All five AppContainer paths compile through `./gradlew :core:jvmTest
  :apps:server:test :apps:cli:compileKotlin :apps:desktop:assemble
  :apps:android:assembleDebug` plus
  `:core:compileKotlinIosSimulatorArm64`; `ktlintCheck` clean.

**Registration.** `ForkProjectTool(projects, this)` in CLI / desktop /
server / Android Kotlin containers; iOS Swift container passes
`registry: registry`. No new tools registered — the net tool count is flat
per §3a rule 1 (new fields on an existing tool + a new select-like dimension
on an existing variantSpec).

---
