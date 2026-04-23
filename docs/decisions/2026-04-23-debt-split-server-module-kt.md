## 2026-04-23 — Split apps/server/ServerModule.kt (667 lines) into route-group siblings (VISION §5.6)

**Context.** `apps/server/src/main/kotlin/io/talevia/server/ServerModule.kt`
was 667 lines — R.5 #4 long-file threshold (500-800 → default P1). One
file held:

1. The `Application.serverModule()` Ktor entry, including content-
   negotiation + status-pages + optional bearer-token install (~45 lines).
2. The full `routing { … }` block: /health, /sessions + /messages +
   /cancel + SSE events, /projects CRUD, /media multipart upload,
   /metrics prometheus dump (~300 lines).
3. Request/response DTOs + `SessionSummary` companion + `defaultModelFor`
   (~65 lines).
4. `BusEventDto` + its `from(BusEvent)` companion (~145 lines — the
   biggest single chunk, mechanical mapping from the Core sealed
   hierarchy to a plain-string wire shape).
5. `requireLength` / `requireReasonableId` validators + `MAX_*` constants
   (~20 lines).
6. `eventName(BusEvent)` SSE label mapper (~25 lines).

Reader tax: finding which route handled `POST /sessions/{id}/cancel` meant
scrolling past `BusEventDto` and ~15 other endpoints. New-route additions
append to whichever section feels right and the file drifts further.

Rubric delta §5.6: long-file 667 → 106 for ServerModule.kt; every sibling
under 300 lines.

**Decision.** Split into 6 sibling files in the same `io.talevia.server`
package. Every cross-file reference flipped `private` → `internal`; the
top-level `Routing` extension functions are the new composition seam.

| File | Lines | Contents |
|---|---|---|
| `ServerModule.kt` | 106 | `fun Application.serverModule()` — installs (ContentNegotiation / StatusPages / optional BearerAuth), lifecycle teardown, `routing { … sessionRoutes(…); projectRoutes(…); mediaRoutes(…); metricsRoute(…) … }`. Nothing else. |
| `SessionRoutes.kt` | 193 | `internal fun Routing.sessionRoutes(container, agentScope)` — /sessions CRUD, /parts, /messages (runs `agent.run(…)` on `agentScope`), /cancel, /events SSE. |
| `ProjectRoutes.kt` | 74 | `internal fun Routing.projectRoutes(container)` — /projects list, create, state, delete. |
| `MediaRoutes.kt` | 95 | `internal fun Routing.mediaRoutes(container)` — POST /media?projectId=… multipart upload. |
| `MetricsRoute.kt` | 40 | `internal fun Routing.metricsRoute(container)` — GET /metrics prometheus scrape. |
| `ServerDtos.kt` | 247 | Request/response DTOs + `SessionSummary` + `BusEventDto` (+ `from` companion) + `eventName` + `defaultModelFor`. The DTO bag — biggest sibling file, but it's cohesive and mostly a dumb data class union. |
| `ServerValidation.kt` | 29 | `MAX_TEXT_FIELD_LENGTH` / `MAX_TITLE_LENGTH` + `requireLength` / `requireReasonableId`. |

Behaviour-preserving: the `routing { get("/health") … }` block in
`ServerModule.kt` directly calls the extension functions in exactly the
same order the old inline block ran, so Ktor's plugin / routing state
machine sees the same install sequence.

**Axis.** "New HTTP route group." A future /admin or /webhooks endpoint
set lands in a new sibling file (`AdminRoutes.kt` / `WebhookRoutes.kt`)
with its own `Routing.adminRoutes(container)` function invoked from
`ServerModule.kt`. ServerModule.kt never regrows past its current 106
lines because route body lives in the sibling — only the one-line
invocation is added here. Similar "new route group" in a monolithic
module is what blew the file past 500 lines in the first place.

**Alternatives considered.**

- **Keep one big `routing { … }` block; split only the DTO layer.**
  Would have dropped the file size ~150 lines (still ~500 — edge of
  the long-file threshold). Rejected: the file shape is
  "several unrelated handlers glued together", and pulling only the
  DTOs keeps the worst concern (finding a handler among N peers) in
  place.

- **Extract one handler per file** (`HealthRoute.kt`,
  `SessionsListRoute.kt`, …). Rejected: too fine-grained — /health is
  one line, doesn't warrant a file. Route **groups** (sessions /
  projects / media / metrics) are the natural seam because they share
  testable context (`sessionRoutes` wants `agentScope`; `projectRoutes`
  doesn't).

- **Move `BusEventDto` + `eventName` + `from` into
  `core.bus.BusEvent.kt`.** Would centralise BusEvent ↔ wire mapping
  in the Core module where the events live. Rejected for this cycle:
  `BusEventDto` is a server-specific wire format (matches the exact
  SSE payload the web UI consumes) and the Core layer has no HTTP /
  SSE business. If another platform (Desktop debug panel, a future
  iOS bridge) needs the same string-keyed DTO, **then** promote —
  speculative centralisation.

- **Replace `val json = JsonConfig.default` inside `sessionRoutes`
  with a top-level constant in `ServerDtos.kt`.** The SSE writer needs
  it to serialise `BusEventDto`. Kept function-local for now:
  `JsonConfig.default` is a project-wide singleton and the local `val`
  is 10 chars of ceremony. A future /admin route that also needs JSON
  can factor this without touching sessionRoutes.

**Coverage.** `:apps:server:test` green — includes the pre-existing
`InputValidationTest` (exercises `requireLength` / `requireReasonableId`
behaviours via the real routes), `MetricsEndpointTest` (exercises the
`/metrics` handler, now in `MetricsRoute.kt`), `ToolSpecBudgetGateTest`
(unaffected, registry-only). `:core:jvmTest` + `:apps:cli:test` +
`:apps:desktop:test` + `ktlintCheck` also green. The ktor `routing {}`
plugin machinery verifies every extension-function call site compiles
against the real `Routing` receiver.

**Registration.** No tool / AppContainer change. Package unchanged;
Kotlin source set globs pick up the new siblings automatically. No iOS
/ Android / Desktop / CLI touch — server-only refactor.
