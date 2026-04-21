## 2026-04-20 — `WebSearchTool` + `SearchEngine` abstraction (OpenCode/Claude Code parity)

**Context.** `WebFetchTool` (shipped earlier today) lets the agent
read a URL it already knows about. The "find references on X" / "what
are the latest posts about Y" / "give me a few inspirations for the
travel-vlog opener" class of intents is upstream of that — the agent
needs to *discover* the URLs first. Both Claude Code (`WebSearch`) and
OpenCode (`tool/websearch.ts`) ship a dedicated search tool exactly
for this. Without it the agent invents URLs and `web_fetch` 404s, or
asks the user to paste links.

**Decision.**
- New `WebSearchTool` (`id="web_search"`) in
  `core/tool/builtin/web/`, commonMain. Input
  `{query: String, maxResults: Int = 5}`; output
  `{query, provider, results: List<{title,url,snippet}>, answer?}`.
- The tool is **provider-agnostic**: it depends on a new
  `core.platform.SearchEngine` interface (mirroring the
  `ImageGenEngine` / `MusicGenEngine` / `VideoGenEngine` pattern).
  Concrete vendors live under `core.provider.<vendor>` and translate
  their native JSON into `SearchResults`. None of the SDK-native
  shapes leak past the boundary (CLAUDE.md §5).
- First concrete provider: **Tavily**
  (`core/provider/tavily/TavilySearchEngine.kt`). Single
  `POST /search` endpoint, free tier covers interactive use, returns
  a small LLM-friendly JSON shape with an optional one-paragraph
  synthesised answer. Wired only when `TAVILY_API_KEY` is set in env;
  otherwise the search slot stays null and `web_search` stays
  unregistered (same gating as the AIGC engines).
- **Permission**: new `web.search` permission, defaults to ASK with
  the **lower-cased trimmed query** as the pattern. So an "Always"
  rule scopes to that exact phrase rather than blanket-granting
  search; users that want frictionless search can flip to ALLOW
  with pattern `*` once. `ServerPermissionService` auto-rejects
  ASK so headless deployments stay deny-by-default.
- **Result cap**: `maxResults` defaults to 5, hard-capped at 20. We
  truncate at the tool layer so a runaway provider response can't
  blow the context budget.
- Wired in CLI / desktop / server containers (the three JVM apps
  with HTTP access). Mac CLI > desktop > server priority per
  CLAUDE.md.

**Alternatives considered.**
- **Brave Search API**. Solid free tier, but the response shape is
  closer to a raw SERP than to LLM-synthesised hits — no `answer`
  field, snippets are HTML-heavy. Tavily's design is explicitly
  "search for agents", which matches the intent better. Brave is a
  natural second provider to add behind the same `SearchEngine`
  interface.
- **Exa** (formerly Metaphor). Excellent semantic search, but the
  pricing model is per-query with a smaller free tier — less
  friendly for an interactive agent doing many small lookups.
- **DuckDuckGo zero-click API**. No API key required, but the
  response is a thin abstract that often returns nothing useful for
  long-tail queries. Wrong tool for "find me references" use cases.
- **Bing Web Search**. Azure setup overhead is heavy for a
  first-party provider; revisit when we have multi-provider routing.
- **Skip search entirely; let the LLM hallucinate URLs and fall
  back to `web_fetch`.** Tried in early M4 demos. Hallucinated URLs
  4xx ~70% of the time, frustrating UX.

**Why this matches VISION.**
- §3 "Compiler is pluggable Tools": `SearchEngine` is one more
  pluggable interface, identical in shape to the AIGC engines.
- §5 rubric "Source layer + tool coverage": closes the
  reference-discovery gap that `web_fetch` alone left open. The
  agent can now ground its choices in real, current information
  instead of training-cutoff guesses — directly relevant for vlogs
  that reference current events, music recs, recent VFX trends.

**Why we copied the host-pattern idea (per-query) for permission.**
`web_fetch` keys on URL host so users grant a domain at a time. For
search, the equivalent narrowing knob is the *query phrase* — letting
"Always" allow `kotlin coroutines` doesn't grant blanket search
access. Users who want one-click frictionless search can still go to
the rule editor and switch the pattern to `*`.

**Files touched.**
- `core/src/commonMain/.../platform/SearchEngine.kt` (new)
- `core/src/commonMain/.../provider/tavily/TavilySearchEngine.kt` (new)
- `core/src/commonMain/.../tool/builtin/web/WebSearchTool.kt` (new)
- `core/src/commonMain/.../permission/DefaultRules.kt` (added rule)
- `core/src/commonMain/.../agent/TaleviaSystemPrompt.kt`
  (new "# Web search" section)
- `apps/cli/.../CliContainer.kt`,
  `apps/desktop/.../AppContainer.kt`,
  `apps/server/.../ServerContainer.kt` (gated wiring on
  `TAVILY_API_KEY`)
- `core/src/commonTest/.../tool/builtin/web/WebSearchToolTest.kt`
  (tool-layer tests against a fake SearchEngine)
- `core/src/commonTest/.../provider/tavily/TavilySearchEngineTest.kt`
  (wire-format tests against ktor MockEngine)

---
