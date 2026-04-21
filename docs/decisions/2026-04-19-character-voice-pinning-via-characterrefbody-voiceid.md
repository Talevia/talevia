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
