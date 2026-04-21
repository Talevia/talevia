## 2026-04-19 — `update_character_ref` / `update_style_bible` / `update_brand_palette` (surgical source edits)

**Context.** VISION §5.4 asks for a professional-user path where the
agent (or user) can make precise, field-level edits on consistency
nodes — "change Mei's hair to red" or "pin Mei to the alloy voice" —
without re-asserting the whole node. The `define_*` tools already
shipped in a "create or replace" shape: `define_character_ref`
requires `name + visualDescription` every call, so patching only
`voiceId` meant the agent had to read the node, copy the fields it
wanted to keep, and re-send them. That's brittle (silent drift on
fields the agent forgets to copy) and noisy in the agent transcript.

**Decision.** Three new tools, one per consistency kind:
`UpdateCharacterRefTool` / `UpdateStyleBibleTool` /
`UpdateBrandPaletteTool`. Each takes `projectId + nodeId` plus the
body fields it wants to patch, all optional, with "at least one must
be set" as the guard. Unspecified fields inherit from the current
node; the merged body is written back via `replaceNode` so
`contentHash` bumps and downstream clips go stale the same way a
redefinition would. Registered in all four composition roots and
documented in the system prompt alongside the `define_*` block.

**Why three typed tools, not one polymorphic `update_source_node`.**
Considered a single tool that takes `nodeId + bodyOverrides:
JsonObject` and merges a JSON patch onto the stored body. Rejected:

1. The LLM would need to know the body schema for each kind to
   construct the override. That duplicates schema knowledge into the
   prompt, defeating the benefit of typed tools.
2. The `JsonObject` shape on the LLM side is fuzzy — no
   per-field descriptions, no per-kind validation (e.g. hex color
   format, list non-emptiness). Three typed tools keep per-field
   validation in the tool input schema where the agent can actually
   see it.
3. Three tools lets each carry the kind-specific knobs cleanly:
   `clearLoraPin`, `voiceId=""`-as-clear, hex-color validation,
   "hexColors cannot be cleared" — these each belong to exactly one
   kind.

The cost is ~400 LOC of parallel structure across three files
instead of one generic patcher. Worth it for the LLM-UX gain.

**Semantics of optional fields.** Shared pattern across all three
tools:
- Scalar strings (`name`, `visualDescription`, `description`): `null` →
  keep, non-blank → replace, blank string rejected at input time.
  Blank would roundtrip to nonsense; "clear" isn't a valid state for
  these anchor fields.
- Optional strings (`voiceId`, `lutReferenceAssetId`,
  `negativePrompt`): `null` → keep, `""` → clear, non-blank → set.
  Matches the `define_*` tools' already-established "blank = unset"
  idiom.
- Lists (`referenceAssetIds`, `moodKeywords`, `typographyHints`,
  `parentIds`): `null` → keep, `[]` → clear, non-empty → replace.
  Full-list replacement (not per-item patch) because lists here are
  meaningful wholes — a reference-image set or a mood-keyword stack.
- `hexColors`: special — non-empty replace only. A palette with zero
  colors is a data-model error, so the tool rejects `[]` with a
  pointer to `remove_source_node` for the actual "delete the
  palette" intent.
- `loraPin`: `null` → keep, object → replace the full pin (adapterId
  required), `clearLoraPin=true` → drop the pin. `clearLoraPin` +
  `loraPin` in the same call is rejected at input time so the
  intent is unambiguous.
- `parentIds`: reuses `resolveParentRefs` for validation — same
  no-self-ref / must-resolve rules as the `define_*` tools.

**Why not extend `define_*` with "if exists, merge instead of
replace"?** Considered making `visualDescription` optional on
`define_character_ref` when the nodeId already exists, so the same
tool could create or patch. Rejected:

1. Overloads the semantic of "define" — "define X" should read as
   "assert X's full identity", not "patch X if you can figure out
   whether it exists". Separate verbs match the mental model.
2. The JSON-schema for the tool would have to mark required fields
   as conditionally required (`name` required only if node doesn't
   exist), which most LLMs struggle to honour reliably.
3. Creation-vs-update is a decision the agent should make
   consciously. Forcing separate tools makes the intent legible in
   the transcript ("the agent chose to update, not redefine") and
   avoids a class of accidental overwrites.

**Why no `replaced`-style output.** The `define_*` tools return
`replaced: Boolean`; the update tools return `updatedFields:
List<String>` instead — the list of body fields the caller touched.
Gives the agent exact feedback about what propagated. More useful
than a boolean here because "update" has no create branch.

**Alternatives considered.**
1. *Field-level tools per kind (e.g.
   `UpdateCharacterRefVoiceIdTool`, `UpdateCharacterRefLoraTool`).*
   Rejected — one tool per field would explode the registry
   (character_ref alone has 6 body fields → 6 tools × 3 kinds = 18
   tools). Doesn't scale when body schemas grow.
2. *JSON-Patch (`op=replace`, `path=/voiceId`) syntax.* Rejected —
   extra cognitive load on the LLM, and the validation story (is the
   path valid? is the value type-compatible?) is worse than a typed
   schema per kind.
3. *Auto-derive update tools from body serializers via reflection.*
   Too clever; KMP's common-main reflection support is limited and
   the generated schema wouldn't carry field-level prose. The
   parallel structure across three files is fine to maintain by
   hand today.

---
