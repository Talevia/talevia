## 2026-04-23 — Centralise desktop pretty-print Json (VISION §5.6 rubric axis)

**Context.** `apps/desktop/` carried three near-identical pretty-
printing `Json` instances:

- `LockfilePanel.kt` — `private val PrettyJson = Json(JsonConfig.default) { prettyPrint = true; prettyPrintIndent = "  " }`
- `TimelineClipRow.kt` — `internal val TimelinePrettyJson = Json(JsonConfig.default) { prettyPrint = true; prettyPrintIndent = "  " }`
- `SourceNodeHelpers.kt` — `internal val SourcePrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }`

Two were based on `JsonConfig.default` (the canonical project
`Json` with `classDiscriminator = "type"` +
`ignoreUnknownKeys = true`, per CLAUDE.md Serialisation
Conventions); one — `SourcePrettyJson` — was built from zero. The
drift was harmless *today* because the divergent instance only
encoded `JsonObject` (which ignores `classDiscriminator`), but
"two-base configuration for the same intent" invites a future
reader to reach for the wrong one, or to silently encode a
polymorphic sealed-class value through the non-default base and
lose the discriminator field downstream.

Rubric delta §5.6: Desktop-side `Json` configuration drift "部分" →
"有" (one canonical instance; zero divergent copies).

**Decision.** New file
`apps/desktop/src/main/kotlin/io/talevia/desktop/DesktopPrettyJson.kt`
holds the single shared instance:

```kotlin
@OptIn(ExperimentalSerializationApi::class)
internal val DesktopPrettyJson: Json = Json(JsonConfig.default) {
    prettyPrint = true
    prettyPrintIndent = "  "
}
```

Three call sites switch to it:

- `LockfilePanel.kt` — `PrettyJson.encodeToString(JsonObject.serializer(), …)` → `DesktopPrettyJson.…` (local `PrettyJson` val deleted).
- `TimelineClipRow.kt` — `TimelinePrettyJson.encodeToString(Clip.serializer(), …)` → `DesktopPrettyJson.…` (local val deleted). This is the one site where `classDiscriminator = "type"` is load-bearing: `Clip` is polymorphic, so the `JsonConfig.default` base is required for correct polymorphic encoding.
- `SourceNodeRow.kt` — `SourcePrettyJson.encodeToString(JsonObject.serializer(), …)` → `DesktopPrettyJson.…` (`SourcePrettyJson` val deleted from `SourceNodeHelpers.kt`).

Risk of unifying base: zero in today's output. `JsonConfig.default`'s
`classDiscriminator` + `ignoreUnknownKeys` flags affect polymorphic
`@Serializable` encoding / decoding; they do not alter pretty-printing
of raw `JsonElement` / `JsonObject` trees, which is what the two
`JsonObject.serializer()` call sites actually do. The `Clip`
call site always wanted the `JsonConfig.default` base.

**Axis.** "Future Desktop panel that needs pretty-printed JSON."
Before: first instinct is to copy-paste a local `val PrettyJson = Json(…)
{ prettyPrint = true }`, creating a fourth divergent copy. After:
there is exactly one `DesktopPrettyJson` in the package; a fourth
panel just imports it. The pressure this cycle addresses — "three
near-copies, one subtly different" — re-emerges only if someone
re-introduces a panel-local instance; the centralised file is the
obvious import, so the next panel joins it by default.

**Alternatives considered.**

- **Promote the helper to `core/serialization/PrettyJson.kt` shared
  across apps.** Would also cover server / CLI / Android / iOS if
  any of them grow a pretty-print need. Rejected: none of the other
  containers currently pretty-print JSON (they either stream raw
  bytes or rely on `JsonConfig.prettyPrint` in `FileProjectStore`
  for the `talevia.json` write path). Abstracting on N=1 consumer
  package violates CLAUDE.md "Don't design for hypothetical future
  needs". When the second container needs it, that cycle spawns
  the core-level promotion; today's scope is "collapse the three
  divergent desktop copies", nothing more.

- **Keep the three local instances but rewrite `SourcePrettyJson`'s
  base to match the other two.** Would fix the correctness drift
  without moving code around. Rejected: would leave three identical
  copies of a 4-line helper, which is the exact anti-pattern that
  the P2 bullet asked to fix. The duplication is what makes the
  next copy-paste feel natural; removing the duplication is what
  closes the loop.

- **Put `DesktopPrettyJson` in `SourceNodeHelpers.kt` (or
  `LockfilePanel.kt`) rather than a new sibling file.** Would save
  one file. Rejected: a shared helper living in one specific
  panel's file implies "that panel owns the constant", which makes
  it marginally less discoverable from the *other* panels'
  perspective. A dedicated `DesktopPrettyJson.kt` is 25 lines (incl.
  KDoc + package + imports) and declares the intent at the
  filename level — cheap insurance against the next drift.

**Coverage.** `:apps:desktop:ktlintFormat` + `:apps:desktop:ktlintCheck`
green. `:apps:desktop:assemble` + `:apps:desktop:test` green (the
pre-existing `MacOsInfoPlistExtraXmlTest` still passes — Desktop has
no automated Compose UI harness to exercise the panel rendering, so
the visual-output invariant for the three callers rests on the base-
switch analysis above, not on a test). No behavioural change for
Lockfile or SourceNode callers (both print `JsonObject`, unaffected
by the `classDiscriminator` flag); Timeline clip inspector gains
nothing and loses nothing (already was on `JsonConfig.default`).

**Registration.** No tool / container change — pure internal
refactor inside `apps/desktop/`. Package unchanged; all three
renamed symbols were package-`internal`, so no external caller
could have reached them.
