## 2026-04-19 — Consistency nodes live in Core, not in a genre extension

**Context.** VISION §3.3 demands first-class "character reference / style bible / brand
palette" source abstractions so AIGC tools have something to condition on for cross-shot
consistency. The question is: do these live under `core/domain/source/consistency/` or
inside every genre that needs them (`genre/vlog/`, `genre/narrative/`, …)?

**Decision.** Consistency nodes live in Core under
`core/domain/source/consistency/` with kinds in the `core.consistency.*` namespace.

- `CharacterRefBody(name, visualDescription, referenceAssetIds, loraPin)`
- `StyleBibleBody(name, description, lutReference, negativePrompt, moodKeywords)`
- `BrandPaletteBody(name, hexColors, typographyHints)`

**Why this does *not* violate "no hardcoded genre schemas in Core".** The anti-requirement
forbids *genre* schemas in Core — narrative, vlog, MV, etc. Consistency nodes are
*cross-cutting mechanisms* that every genre reuses to solve the same problem (identity
lock across shots). Defining them per-genre would either duplicate the schema or force
each genre to reinvent the wheel, neither of which serves VISION §3.3's goal of "a
single `character_ref` that transits all the way to every AIGC call in the project."

**Alternatives considered.**
- **One copy per genre.** Rejected: encourages drift (vlog's character looks slightly
  different from narrative's character), breaks the "change one character → all its
  references refactor" promise.
- **A generic `ConstraintNode` with a free-form JSON body.** Rejected: loses the
  guardrails on field names (every downstream fold function would string-match on
  keys). Typed bodies make the prompt folder's behavior auditable.
