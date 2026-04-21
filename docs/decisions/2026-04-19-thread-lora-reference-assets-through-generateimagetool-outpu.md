## 2026-04-19 — Thread LoRA + reference assets through `GenerateImageTool` output and lockfile hash

**Context.** `GenerateImageTool` already folded `CharacterRefBody.loraPin` and
`CharacterRefBody.referenceAssetIds` into a `FoldedPrompt` via
`AigcPipeline.foldPrompt`, but the returned folded object was *dropped on the
floor* at two critical boundaries:

1. The fields never reached the `ImageGenEngine` — `ImageGenRequest` only
   carried `prompt / modelId / width / height / seed / n / parameters`. Engines
   that could translate LoRA or reference images into their native wire shape
   had no surface to receive them through.
2. The AIGC lockfile hash did not include LoRA or reference-asset axes. Two
   identical prompts with different LoRA weights collided on the same cache
   key; the second call would return the first asset despite being a
   semantically distinct generation. That is an end-to-end correctness bug
   for VISION §3.1 "产物可 pin".

`GenerateImageTool.Output` also lacked the visibility fields the LLM needs to
reason about *what got bound* — it saw `appliedConsistencyBindingIds` but not
which LoRA adapters or reference images those bindings resolved to.

**Decision.**

1. **Extend `ImageGenRequest` with the three provider-specific hooks.** Added
   `negativePrompt: String?`, `referenceAssetPaths: List<String>`, and
   `loraPins: List<LoraPin>`. Engines that cannot natively consume a given
   hook (OpenAI DALL-E / GPT-Image-1 has no LoRA; text-only endpoints take no
   references) are *still required* to record the incoming value in
   `GenerationProvenance.parameters`. Silently dropping them would make the
   audit log lie about what the caller asked for and — worse — make the
   provenance superset look indistinguishable between two runs that had
   different LoRA intent, which then corrupts downstream replay.

2. **OpenAI engine: wire body vs provenance parameters split.** OpenAI's
   `/v1/images/generations` endpoint rejects unknown fields with HTTP 400, so
   we cannot attach `negativePrompt` / `referenceAssetPaths` / `loraPins` to
   the request JSON. The engine now maintains two JSON objects:
   - **Wire body** — strictly the fields the OpenAI API accepts.
   - **Provenance parameters** — a *superset* of the wire body plus
     `_talevia_negative_prompt`, `_talevia_reference_asset_paths`,
     `_talevia_lora_pins`. The `_talevia_` prefix namespaces extensions that
     are our concern only.

   Separating the two surfaces is the correct shape for the platform contract:
   "engine impls translate what they can; the rest still shows up in the
   audit log." Providers that *do* support LoRA (Stable Diffusion backends,
   custom image-gen endpoints) will translate the common-typed input into
   their native shape and add zero `_talevia_` keys.

3. **Hash all three axes into the lockfile input.** `GenerateImageTool.inputHash`
   now includes `neg`, `refs`, `lora` alongside the existing axes. A change to
   any of them busts the cache. `contentHash` on the bound source node already
   changes when `CharacterRefBody.loraPin.weight` shifts — so the existing
   stale-clip detection path *also* flags it — but we want the hash itself to
   be unambiguous as a standalone key, because `list_lockfile_entries` and
   `find_stale_clips` reason about the hash directly, not the node graph.

4. **Expose the resolved pins on `Output`.** Added `negativePrompt`,
   `referenceAssetIds`, `loraAdapterIds` to `GenerateImageTool.Output`. The
   agent can read back that a bound character injected `hf://mei-lora` without
   re-querying the source graph. Keeps the tool's output self-describing.

5. **Resolve asset ids → paths at the tool boundary.** `MediaPathResolver`
   takes `AssetId → String` asynchronously; `GenerateImageTool` resolves
   `folded.referenceAssetIds` via the injected `MediaStorage` (which is a
   `MediaPathResolver`) before calling the engine. Engines must never see
   `AssetId.value` as a path — that violates the M2 architectural rule.

**Why not make the engine fetch paths itself.** Rejected. The engine layer is
already "translate common → native"; giving it a second responsibility
("resolve project-scoped asset ids") would couple it to the
`MediaPathResolver` contract and make stateless engine impls harder to write.
The tool owns the project context, so path resolution happens there.

**Why `_talevia_` prefixed provenance keys.** Provenance parameters are a
`JsonObject` shared with "what was on the wire." Mixing user-visible fields
(`prompt`, `size`) with implementation-only extensions in the same namespace
would later confuse a replay tool or a human reading the audit log. A
namespace prefix keeps the two concerns visibly distinct.

**Why require engines without a given hook to still record it.** Provenance
is load-bearing for two jobs: audit ("what did you ask the provider?") and
cache-key reconstruction ("would this run hit the same entry?"). If
`OpenAiImageGenEngine` silently dropped the negative prompt, two lockfile
entries with distinct caller intent would have identical provenance — the
same hash collision concern as before, pushed one layer down. Making the
contract explicit ("MUST record") at the `ImageGenEngine` KDoc forces future
providers to inherit the discipline.

**Coverage.** Added two tests to `GenerateImageToolTest`:
- `loraAndReferenceAssetsFlowToEngineAndOutput` — defines a character_ref
  with a `LoraPin` and a reference image, binds it, asserts the engine saw
  both as resolved paths + pins AND that `Output.referenceAssetIds` /
  `Output.loraAdapterIds` surfaced them.
- `loraWeightChangeBustsTheLockfileCache` — generates once with weight 1.0,
  flips to 0.4, asserts second call is a miss.
