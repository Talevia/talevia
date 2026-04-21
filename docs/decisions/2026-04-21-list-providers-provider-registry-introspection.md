## 2026-04-21 — list_providers ProviderRegistry introspection (VISION §5.2 Compiler 层)

Commit: `8c86228`

**Context.** `ProviderRegistry` has been the Agent's provider lookup
since M4 — `Agent.streamTurn` resolves a provider by
`ModelRef.providerId` for every turn. But the agent itself had no
tool to answer "which providers are available in this container?" —
a first-class introspection question for several concrete flows:

- The agent wants to suggest a cheaper provider for a bulk task
  ("use Gemini for batch summarisation, Claude for reasoning")
  but doesn't know which providers are actually configured.
- A deploy has only Anthropic wired; the agent should stop asking
  "which provider?" questions and just proceed.
- The user says "switch to OpenAI" and the agent wants to verify
  that provider is registered before committing to the switch.

**Decision.** `ListProvidersTool(providers: ProviderRegistry)` —
returns one `Summary(providerId, isDefault)` per registered provider
plus the aggregate `defaultProviderId`. No HTTP call — pure local
container introspection.

Model-catalog discovery (`LlmProvider.listModels()` is a suspend
function that hits an external API to enumerate the provider's
current models) is **intentionally out of scope** here. That's a
separate tool when the concrete flow needs it — the registry list is
cheap enough to be silent-default while a model list is an
external-api call that deserves its own permission grant.

New permission `provider.read` (default ALLOW in
`DefaultPermissionRuleset`) — matches the other `*.read` silent-default
pattern.

**Registration required an unusual init-block pattern.** `tools` is
declared as a property initializer in every AppContainer; most tools'
dependencies (sessions, projects, media) are declared before `tools`
in each class, so they're available by the time `tools.apply { ... }`
runs. `ProviderRegistry` is declared *after* `tools` because its
construction depends on `httpClient` + `secrets` that end up mid-file.
Reshuffling five containers to move providers before tools is
disruptive; using an `init { tools.register(ListProvidersTool(providers)) }`
block placed right after the providers property is minimally invasive
and works because Kotlin processes property initializers + init
blocks top-to-bottom — the init block runs after `providers` is set.

**Alternatives considered.**

1. *Reshuffle all five AppContainers to declare `providers` before
   `tools`.* Rejected — disruptive, touches every provider-dependent
   comment and the dependency-resolution ordering is historical.
   The init-block pattern is the small, surgical fix.
2. *Include model catalog inline (call `listModels()` per provider
   during the tool execution).* Rejected — that's an N × external-
   HTTP-call fan-out for what should be cheap local introspection.
   Also moves the tool into `provider.write`-equivalent risk class
   (external calls, metered by some providers). Keep reads cheap;
   model discovery can be its own tool.
3. *Reuse `session.read` permission.* Rejected — ProviderRegistry
   is not session state. The `*.read` naming pattern carves by
   subsystem (project / source / session / provider), and operators
   scoping by subsystem is the industry-standard permission
   granularity (AWS IAM `ec2:Describe*`, Postgres role privileges,
   Kubernetes RBAC resource kinds).
4. *Expose `LlmProvider` instances directly via the Output.*
   Rejected — they're interfaces with method references, not
   serializable. A `Summary` DTO keeps the wire shape tight and
   avoids leaking internal implementation classes.

**Coverage.** `ListProvidersToolTest` — five tests: lists all
registered providers; first registered is marked default; empty
registry returns zero + null default; single-provider case flags as
default; `Builder.add` deduplication propagates to the tool output.

**Registration.** `ListProvidersTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. Each uses an init
block placed after the container's `providers` field so the tool
construction sees a non-null `ProviderRegistry`. New permission
`provider.read=ALLOW` added to `DefaultPermissionRuleset`.
