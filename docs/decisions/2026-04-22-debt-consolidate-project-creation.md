## 2026-04-22 — Keep CreateProject / CreateProjectFromTemplate as two tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-project-creation`
asked whether `CreateProjectTool` + `CreateProjectFromTemplateTool`
could merge into `create_project(template: String? = null)`, with
null template meaning "empty source" (current `create_project`
behavior) and non-null template driving the seeding path (current
`create_project_from_template`).

This is the **first evaluation in the variant-consolidation series
that lacks a structural blocker.** The seven prior evaluations
(add / remove / apply / snapshot / duplicate / maintain-lockfile /
pinning) all rejected consolidation on concrete antipatterns —
different identifier types, divergent failure modes, applicability
downgrades, union-schema Output nullability. Here:

- Both tools share `(title, projectId?, resolutionPreset?, fps?)`
  as the base Input.
- `create_project_from_template` adds **additive** optional fields
  (`template`, `intent`) — no identifier divergence.
- Output divergence is **naturally zero-defaulted**: empty-source
  creation produces `seededNodeIds = emptyList()`, `inferredFromIntent = false`,
  etc. Not the "union schema" antipattern where a field populates
  only per branch.
- Same permission (`project.write`), same failure mode (duplicate
  project id → loud error), same applicability.

Token savings from merging: ~90 tokens (2 tools × ~190 tokens each
→ 1 tool × ~290 tokens). Larger than the ~60-token savings the
structural evaluations rejected at.

Decision: **still keep the two tools unchanged.** Three
non-structural reasons — which is important to flag explicitly
because this is **not** the "structural blocker" reasoning the
prior evaluations leaned on.

**Decision reasoning.**

1. **Tool name as intent signal.** `create_project` vs
   `create_project_from_template` encodes a binary planning
   decision in the tool choice itself. The LLM plans "I'll start
   empty" (tabula rasa, add nodes manually) or "I'll seed from a
   genre template" (novice path, scaffold in one call) — and the
   tool name commits to that branch. A merged
   `create_project(template?)` forces the LLM to re-encode the
   same decision as a field value at call time. Tool names are
   sticky in the LLM's planning context; parameter values are not.

2. **Domain-concept separation.** `create_project_from_template`
   carries two concepts that don't apply to empty-project creation:
   - `template` ∈ {narrative / vlog / ad / musicmv / tutorial / auto}
     — genre-specific vocabulary.
   - `intent` — keyword-classifier input, required iff
     template="auto".
   Merging pulls these into the signature of the simple-creation
   path, where they're dead weight for every call that isn't
   seeding from a template. The `intent` requirement coupled to
   `template="auto"` is particularly awkward in a merged schema
   — JSON Schema can't express "required iff another field equals
   a specific value" cleanly; the runtime check would fire loud
   but only after the LLM already composed a bad call.

3. **Complexity gradient preservation.** `CreateProjectTool` is
   ~80 lines; `CreateProjectFromTemplateTool` is ~200+ lines
   (intent classifier, genre-specific seeding). Merging puts
   that complexity behind a single entry point, so a bug in the
   template path can surface on "simple create" calls that
   semantically shouldn't touch template code at all. Split
   tools make the complexity gradient visible to anyone reading
   the registry: "one of these is simple, the other is not".

**Alternatives considered.**

1. **Collapse into `create_project(template: String? = null,
   intent: String? = null)` + keep both old tool ids as aliases.**
   Rejected — tool-id aliasing adds LLM context cost (two specs
   for same underlying tool) without saving the tokens the bullet
   was targeting. Also makes the "intent signal in tool name"
   benefit ambiguous (does the LLM see two tools or one?).

2. **Consolidate now, revisit if the domain-concept separation
   proves load-bearing.** Rejected on the skill's hard rule 12
   ("一次例外" — no exceptions without a concrete driver). The
   prior seven variant-consolidation evaluations all kept the
   split; breaking that streak needs a strong affirmative reason,
   not "the structural argument didn't fire this time". The
   weaker ergonomic argument is enough to keep.

3. **Alternative: merge the tools but add a separate
   `create_project_from_intent(intent)` to isolate the auto-
   classifier.** Would split the tool tree differently (by "does
   the user supply a template" vs "does the user supply an
   intent") instead of by "does any seeding happen". Rejected as
   scope creep — the bullet asked about the existing two-tool
   split, not a three-tool rearrangement.

**§3a session-project-binding note (rule 6).** Neither tool takes
`projectId` in its Input (they both CREATE the project and return
its id), so no `ToolContext.currentProjectId` routing applies. No
deferred binding sweep flag needed.

**§3a genre-hardcoding note (rule 5).** `CreateProjectFromTemplateTool`
hardcodes genre template ids (narrative / vlog / ad / musicmv /
tutorial). This is an existing antipattern inside a single tool —
not something this evaluation introduces or expands. The future
"genre concepts move to `SourceNode.body` entirely" refactor would
dissolve that hardcoding along with others; until then the tool
lives with it. Keeping the split at least confines the
genre-specific vocabulary to one tool spec instead of leaking it
into `create_project`.

**Coverage.** Docs-only — no code touched, no test added. Existing
tests (`CreateProjectToolTest`, `CreateProjectFromTemplateToolTest`)
pass unchanged.

**Registration.** No registration churn. Both tools remain
registered in the 5 AppContainers (CLI / Desktop / Server / Android
/ iOS) via their existing constructor signatures.

---
