## 2026-04-19 — `describe_asset` (VISION §5.2 ML lane — image counterpart to ASR)

**Context.** The ML enhancement lane had one modality wired: `transcribe_asset`
for audio → text. Image → text (describe / caption / extract visible text /
brand-check) was a blank spot despite being a high-traffic use case —
"what's in this photo?", "pick the best import for the intro", "read this
image and lift the caption into a character_ref's visualDescription". With
Vision-class multimodal LLMs now commodity at provider-level (gpt-4o-mini
is ~$0.15 per 1M input tokens), shipping the image side of the pair
completes VISION §5.2's "vision/multimodal" bullet.

**Decision.** New `core.platform.VisionEngine` interface in `commonMain` +
`OpenAiVisionEngine` in `jvmMain` + `core.tool.builtin.ml.DescribeAssetTool`.
Tool id `describe_asset`, permission `ml.describe` (ASK — bytes exfiltrated).
Input: `(assetId, prompt?, model="gpt-4o-mini")`. Output: text description
plus the standard provider/model provenance. Engine wired conditionally on
`OPENAI_API_KEY` in desktop + server containers, same pattern as
`imageGen` / `asr` / `tts` / `videoGen`.

**Why a separate engine instead of extending `AsrEngine`.** The obvious
parallel ("transcribe_asset handles both audio → text and image → text") is
false: ASR and vision are different providers, different endpoints, different
parameter vocabularies (language hints / timestamps for audio; focus prompts
/ image-urls for vision), and `AsrResult.segments` is meaningless for a
still image. Forcing them under one interface would leak modality-specific
fields across both implementations. Keeping them as sibling engines mirrors
the AIGC side, where `ImageGenEngine` / `VideoGenEngine` / `TtsEngine` are
distinct interfaces.

**Why `jvmMain` and not `commonMain`.** Vision needs raw image bytes (base64
into `data:image/...;base64,...` for the OpenAI API). `java.io.File.readBytes()`
is the simplest way to get those on the JVM; doing it in `commonMain` would
require a KMP file-IO abstraction we don't have yet. Same call as the
Whisper engine — architecture rule #1 still holds because the interface
lives in `commonMain`; only the translation lives beside the other
`OpenAi*Engine`s.

**Why images only, not video frame-grab.** The Vision API doesn't accept
video; to "describe a moment in a video" the caller would have to grab a
frame first via `VideoEngine.extractFrame` or an equivalent. We could bury
that dependency inside the tool, but that would couple `DescribeAssetTool`
to `VideoEngine` and complicate the permission model ("ml.describe" implies
"timeline read or render access"). Failing loudly on non-image files keeps
this tool single-responsibility; when frame-grab describe becomes a real
workflow, it becomes its own tool that composes extract-then-describe.

**Why no project lockfile cache (v1).** `LockfileEntry` keys generated
**assets**, not derived text. Describe outputs text directly back to the
agent — there is no asset id to cache against. Same open we left on
`transcribe_asset`: if repeat-describe becomes common, materialize the
description as a JSON asset and key a lockfile entry off it. No speculative
scaffolding for today.

**Why default model `gpt-4o-mini`, not `gpt-4o`.** Describe is an
enhancement not a generation — mini is ~15× cheaper, fast, and has been
shown to match 4o on describe-quality benchmarks within noise. The agent
can opt into `gpt-4o` via the `model` parameter when the user needs
fine-grained detail ("read the small text on this label"). Pattern matches
OpenCode's default-to-mini-for-tool-facing-vision convention.

**Why `prompt` optional (default: generic describe).** Two use patterns:
"what's in this image?" (no focus) and "what brand is on the mug?"
(focused). Requiring a prompt would force the agent to invent one for the
generic case; allowing null lets the engine substitute a well-tuned default
("Describe this image. Note the subject, setting, notable colors / lighting,
and any text visible."). Blank-string is treated as null — defensive
because LLM tool schemas sometimes emit `""` when they mean omit.

**Tests.** 5 cases in `DescribeAssetToolTest` using a `RecordingVisionEngine`
fake: default-path-and-model resolution, custom prompt + model forwarding,
blank-prompt-is-omit, long-text preview ellipsis, short-text no ellipsis.
No real OpenAI call — engine translation is exercised by the integration
matrix, not unit tests.

**Registration.** desktop + server containers, conditional on
`OPENAI_API_KEY`. Android + iOS composition roots do NOT register it —
the `OpenAiVisionEngine` is `jvmMain`-only by design. When native vision
providers land (Vision framework / ML Kit) they'll fill the iOS /
Android gaps via the same `VisionEngine` interface.

**System prompt.** New "ML enhancement" paragraph teaches the pair:
`transcribe_asset` for audio, `describe_asset` for images, with explicit
callouts for (a) the character_ref scaffolding pattern, (b) images-only
scope, (c) user-confirmation on bytes upload. `describe_asset` added to
the Compiler mental model alongside `transcribe_asset`.

---
