## 2026-04-21 — template='auto' + intent classifier for one-call novice bootstrap (VISION §5.4 rubric)

Commit: `0975461`

**Context.** VISION §5.4's novice path: "a sentence of intent → a
viewable first cut." Today the novice still has to `create_project` +
manually `set_character_ref` / `add_source_node` / pick a genre before
the agent has anything to chew on. `create_project_from_template`
already exists (covers all five genres per
`docs/decisions/2026-04-21-create-project-template-covers-all-five-genres.md`)
but demands the caller pick the template up-front — the LLM has to
translate a free-form user request into a genre keyword before the
bootstrap tool is callable. The backlog bullet
(`auto-author-first-project-from-intent`, P0) called for
`start_project_from_intent(intent)` — a single call that classifies,
creates, and seeds.

**Decision.** Extend `CreateProjectFromTemplateTool` with
`template="auto"` + a new optional `intent: String` field. When
`template="auto"`:

1. `intent` is required (blank → loud failure; silent fallback to
   narrative would hide novice misuse and a tool-use error is cheaper
   than a subtly-wrong skeleton).
2. `IntentClassifier.classify(intent)` (new file
   `core/tool/builtin/project/template/IntentClassifier.kt`) runs a
   deterministic keyword-bag match, lowercase substring-level, across
   five bags — one per genre. Highest-scoring bag wins; ties resolved by
   the deterministic `[narrative, vlog, ad, musicmv, tutorial]` order
   (narrative first because its scaffold is the closest to a neutral
   creative skeleton); zero matches → `narrative` + a "no signal" reason.
3. `Output` gains `inferredFromIntent: Boolean = false` +
   `inferredReason: String? = null`, echoed into `outputForLlm` so the
   agent can redo with an explicit template if inference was wrong.
4. `helpText` + `inputSchema` document `"auto"` and the new `intent`
   field.

No new tool; §3a #1 net-zero tool growth. The classifier lives
on-device (no LLM round-trip, no provider dependency) — matches how
`opencode` tool dispatch stays provider-agnostic at the outer tool
boundary. The five AppContainers already register
`CreateProjectFromTemplateTool`, so the auto mode inherits
registration.

**Alternatives considered.**

- *New `StartProjectFromIntentTool` per the bullet's literal wording*:
  +1 `Tool.kt`. Rejected — §3a #1 pushes toward "add a parameter,
  don't add a tool". Template-based bootstrap already has one tool
  as its natural home; polymorphic "template OR intent" fits cleanly
  behind one discriminator (`template="auto"`). Two tools means the
  LLM has to pick one per turn, paying the spec cost on both forever.
- *Ship the classifier as a sub-tool the LLM calls explicitly before
  `create_project_from_template`*: rejected — defeats the whole point
  (novice still has to issue two tool calls, and the classifier is
  cheap enough to inline).
- *Use an LLM sub-call for genre classification*: rejected — cost +
  latency + provider-specific ambiguity for a problem that keyword
  matching solves in <1ms deterministically. If classification
  accuracy ever becomes a real bottleneck we can swap the
  implementation behind the `IntentClassifier` facade without changing
  the tool surface.
- *Emit a warning when classification score is low instead of failing
  on blank intent*: rejected — the auto mode is novice-facing; "silent
  misinterpretation" is a worse UX than "tell me what you meant". The
  existing explicit-template path stays available for advanced callers
  who don't want heuristic interpretation.

Industry consensus referenced: this is the same shape as `git`'s
subcommand aliasing (`git co` → `git checkout`) — the surface stays
monolithic, auto-resolution lives inside. `kotlinx.serialization`
sealed-class pattern inspired the discriminator-via-string approach
(one field picks the variant, rest of the input applies per-variant).

**Coverage.**

- `CreateProjectFromTemplateToolTest.autoTemplateClassifiesNarrativeFromStoryKeywords`
  — narrative signal wins with multiple keywords.
- `CreateProjectFromTemplateToolTest.autoTemplateClassifiesMusicMvFromMusicKeyword`
  — "music video" phrase survives tokenisation.
- `CreateProjectFromTemplateToolTest.autoTemplateClassifiesTutorial`
  — "step-by-step" + "how-to" double hit.
- `CreateProjectFromTemplateToolTest.autoTemplateFallsBackToNarrativeOnEmptySignal`
  — zero-match intent still produces a valid project and flags the
  fallback in `inferredReason`.
- `CreateProjectFromTemplateToolTest.autoTemplateRejectsBlankIntent`
  — whitespace intent = loud failure.
- `CreateProjectFromTemplateToolTest.autoTemplateRejectsMissingIntent`
  — null intent = loud failure.
- `CreateProjectFromTemplateToolTest.explicitTemplateIgnoresIntent` —
  explicit template wins even when intent has contradictory keywords;
  prevents auto-mode leakage into explicit calls.

**Registration.** No new tools. `CreateProjectFromTemplateTool` was
already registered in CLI (`CliContainer.kt`), Desktop
(`apps/desktop/...AppContainer.kt`), and Server
(`ServerContainer.kt`). Pre-existing gap: Android
(`AndroidAppContainer.kt`) and iOS (`AppContainer.swift`) register
`CreateProjectTool` but NOT the template variant — that predates this
cycle and will be tracked as a separate debt item (see
`docs/BACKLOG.md` P2 follow-up). This cycle doesn't regress either
platform; the auto mode follows the existing registration topology.

§3a checklist pass:
- #1 zero new tools, one new helper file (classifier) — net tool
  count unchanged. ✓
- #2 not a Define/Update pair. ✓
- #3 `Project` unchanged; new fields live on the tool's Input/Output. ✓
- #4 `inferredFromIntent` is `Boolean` but its default (`false`) maps
  to "user knew the template" — not a state machine flag, just an
  "I did inference" echo. ✓
- #5 no genre-specific type in Core; the classifier produces genre
  label strings that the existing template switch already accepts.
  Keyword bags are implementation detail, not types. ✓
- #6 no session-binding surface added. ✓
- #7 new `Input.intent` and new `Output` fields all default to
  null/false, preserving backward compat with existing JSON payloads. ✓
- #8 no new AppContainer wiring needed — inherits registration of
  the existing tool. The pre-existing Android/iOS gap is tracked as a
  separate debt. ✓
- #9 tests cover: happy path (3 genres), zero-match fallback, blank
  intent, missing intent, explicit template beating contradictory
  intent. Six edges, not just the narrative happy path. ✓
- #10 helpText / schema diff adds ~120 tokens across the tool spec.
  Well under the 500-token flag threshold; paid once per turn. ✓
