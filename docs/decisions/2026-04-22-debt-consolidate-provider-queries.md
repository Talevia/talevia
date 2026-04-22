## 2026-04-22 — provider_query consolidates list_providers + list_provider_models (debt)

Commit: `dbbe50c`

**Context.** `core/tool/builtin/provider/` held two tools — `ListProvidersTool`
(enumerate registered providers) and `ListProviderModelsTool` (fetch one
provider's /models catalog). Both were thin slices over the same
`ProviderRegistry`; the only substantive differences were that `models`
took a `providerId` filter and made an HTTP call. Identical shape to the
`list_*` pairs we've already consolidated into `session_query` and
`project_query`.

The backlog flagged this as a direct `debt-consolidate-provider-queries`
task. Third cycle-level skip on `per-clip-incremental-render` (explicitly
deferred multi-day refactor per the 2026-04-19 decision); this debt bullet
is a self-contained 1-cycle job that nudges the consolidation curve.

**Decision.** Single unified `ProviderQueryTool` with
`select: providers | models` discriminator; delete both original tools and
their tests.

- `select=providers` — enumerate all registered providers; echoes each
  `(providerId, isDefault)`. Rejects `providerId` filter loud — providers
  IS the enumerate-all verb.
- `select=models, providerId=X` — fetch X's model catalog. Unknown
  provider fails loud (caller typo). Provider-side HTTP failures
  (network / auth / rate-limit) surface via `Output.error` with empty
  `rows` — same pattern `web_fetch` / `web_search` use.

Uniform output shape `{select, total, returned, rows, error?}`, mirroring
the other query primitives. Row data classes (`ProviderRow`, `ModelRow`)
are public so consumers decode via the paired serializers.

Net tool count: **−1** (2 removed, 1 added). LLM context shrinks by one
tool spec plus the associated help-text.

**Alternatives considered.**

- **Keep the two original tools + add a `provider_query` super-set** —
  rejected. Would net +1 tool and double-teach the LLM, worst of both
  worlds.
- **Consolidate into an existing `tool_query` or similar meta-query** —
  rejected. Provider registry is a distinct domain from tool registry;
  cross-cutting query tools become haystacks. Matches the
  `session_query` / `project_query` / `provider_query` per-domain split
  we already have.
- **Merge the HTTP-hitting `models` call into a new class of
  "outbound-query" tools** — rejected as premature abstraction. One
  `select` calls out, one doesn't; the distinction is visible on the
  single select discriminator without spinning up a new tool category.

**Coverage.** `ProviderQueryToolTest` — 8 tests: providers enumerates with
default marker; empty registry returns empty rows; models returns catalog
for known provider; HTTP failure surfaces via error field (empty rows);
unknown providerId fails loud with discover-hint; models without
providerId fails with "requires providerId" hint; providers-select rejects
stray providerId filter; unknown select fails loud;
case-insensitive select. Old `ListProvidersToolTest` +
`ListProviderModelsToolTest` deleted. Full JVM + iOS compile + ktlint clean.

**Registration.** `ProviderQueryTool(providers)` wired in CLI / desktop /
server / android Kotlin containers plus iOS Swift `AppContainer.swift`.
Removed two `register(ListProviders*Tool(...))` lines from each of the
five containers. Permission stays `provider.read` (unchanged). One
broken kdoc reference (`EstimateTokensTool` → `ListProvidersTool`) fixed
up to point at `ProviderQueryTool`.

---
