## 2026-04-20 — `WebFetchTool` — fetch an HTTP(S) URL as text (OpenCode parity)

**Context.** The agent's knowledge is frozen — when a user says "read
this blog post / gist / README" and paste a URL, our only option today
was "sorry, can't reach the web". Claude Code's `WebFetch` and
OpenCode's `tool/webfetch.ts` both solve this with one GET + best-effort
text extraction + ASK permission keyed on host.

**Decision.**
- New `WebFetchTool` (`id="web_fetch"`) in `core/tool/builtin/web/`,
  commonMain. Takes an injected `io.ktor.client.HttpClient` — reuses
  the one every container already wires for providers, so there's no
  new dependency surface. Input `{url, maxBytes?}`; output
  `{url, status, contentType, content, bytes, truncated}`.
- **HTML → plain-text stripper, not a DOM parser.** Regex-based: drop
  `<script>` / `<style>` / `<noscript>` blocks, strip comments, convert
  block-ending tags (`<br>`, `</p>`, `</div>`, `</li>`, `</h1..6>`,
  `</tr>`) to newlines, remove remaining tags, decode the six entities
  users actually write (`&amp; &lt; &gt; &quot; &nbsp; &#39;`),
  collapse whitespace runs. We don't run JS and don't render the page
  — the goal is "give the LLM readable signal", not fidelity.
- **Text-ish content-types only.** `text/*`, `application/json`,
  `application/xml`, `+json` / `+xml` subtypes. Binary responses fail
  with a clear message pointing at `import_media`. This is deliberate:
  the LLM has no business slurping a 10 MB PNG as a tool result.
- **Permission pattern = URL host.** `https://github.com/anthropic/foo`
  buckets under `github.com`, so one "Always" rule covers every path
  on that host. Full-URL patterns would never match twice; path-based
  patterns would combinatorially explode. Host-level is the right
  granularity for "I trust this domain".
- **Default 1 MB response cap, 5 MB hard cap.** 1 MB covers blog
  posts / READMEs / gists comfortably; refusing to slurp 10 MB SPAs
  is a feature. The agent can pass `maxBytes` explicitly up to the
  hard cap when it knows the target is big prose.
- **Non-2xx = error.** The agent should retry / reconsider on 404 /
  500, not silently feed the LLM an error body as if it were content.
  The error message includes status + the first 500 chars of the body
  so the agent can see what happened.
- Wired into CLI / desktop / server; iOS / Android deliberately skipped
  (same posture as `FileSystem` — mobile platforms stay at "no
  regression" per the platform-priority rules; web fetch on mobile
  can be added when mobile moves off the freeze).

**Alternatives considered.**
- **Full DOM parser (jsoup).** Rejected — jsoup is JVM-only, we'd have
  to either pin the tool to JVM (inconsistent with the rest of the fs
  / bash family's commonMain layout) or add a KMP HTML parser dep.
  The regex stripper is 30 lines and covers the "readable signal"
  requirement. Revisit when we see the LLM choking on malformed HTML.
- **Follow redirects? Respect robots.txt? Handle cookies?** Ktor's
  default `HttpClient` follows 3xx redirects out of the box — good.
  No cookies (no session semantics in a tool call). No robots.txt —
  the user explicitly asked us to fetch this URL; robots is a
  crawler-etiquette concern, not a single-shot-at-user-request one.
- **Cache responses.** Rejected for v1 — the agent decides when to
  re-fetch, and caching raises freshness questions (how long? keyed
  on what? expiry semantics?). Revisit if we see the same URL
  fetched many times in one session.
- **Expose a `prompt` parameter (Claude Code style).** Claude Code's
  `WebFetch` accepts a focusing prompt and summarizes the page with
  a second LLM call before handing text back. Rejected for v1 —
  that's a separate concern (per-tool summarization) that would
  apply to `bash`, `grep`, large `read_file` too. Do it once as a
  general post-processor, not bolted onto this one tool.

**Follow-ups.**
- Darwin / Android engine support is already in ktor; enabling
  WebFetch on mobile is a matter of lifting the freeze. Out of scope
  for v1.
- If the agent starts frequently fetching the same URLs in one
  session, add a per-session response cache keyed on
  `(url, maxBytes)`.

---
