# Engineering notes

Knowledge base of Kotlin / Compose Desktop / coroutines / test-harness
gotchas and positive recipes surfaced during `/iterate-gap` cycles — the
kind of "踩过一次就不想再踩" material that doesn't depend on Talevia's
current rules and would survive a project-wide convention rewrite.

- For feedback on hard rules / skill rules / platform-priority decisions,
  see `docs/PAIN_POINTS.md`.
- For concrete actionable TODOs, see `docs/BACKLOG.md`.

Append-only. Each entry cites the cycle that surfaced it so a reader can
`git log -S <slug>` back to the original commit.

---

## Kotlin language gotchas

### Primary-ctor parameter silently shadows a same-named property inside later initialisers
`2026-04-23 — debt-server-container-env-defaults`

Keep the ctor param named `env`, declare
`private val env: Map<String, String> = withServerDefaults(env)` to
shadow it, and expect downstream `env["..."]!!` lookups to hit the
normalised property. In most languages with this pattern, it works. In
Kotlin it doesn't: primary-constructor parameters stay in scope throughout
the class body, and when a subsequent property initialiser references
`env`, the compiler resolves to the ctor parameter, not the shadowing
property. You get a NPE with the defaults-filled property sitting
unused one line above.

**Fix:** rename the ctor param (`rawEnv` / `initialEnv`) so resolution
is unambiguous. The compiler won't warn about the shadow.

### Two `private fun JsonObjectBuilder.X` extensions in one package break Kotlin/Native's `$default` synthesizer
`2026-04-25 — debt-ios-swift-catchup-deep-drift`

Two file-private top-level extension functions with the **same exact
signature** in the same package compile fine on JVM (each gets a
unique mangled name keyed on file) but break Kotlin/Native at the
LINK step (not the compile step) with:

```
e: Compilation failed: kfun:io.talevia.core.tool.builtin.video#stringProp$default__at__kotlinx.serialization.json.JsonObjectBuilder(kotlin.String;kotlin.String?;kotlin.Int){}kotlinx.serialization.json.JsonElement?
e: java.lang.AssertionError: ...DeclarationsGeneratorVisitor.visitSimpleFunction(LlvmDeclarations.kt:449)
```

K/N synthesises `<fun>$default` companions for default-args and the
file-private mangling collides for the synthetic.

**Symptom that hides this from "every-target":** `:core:compileKotlinIosSimulatorArm64`
type-checks Kotlin source and PASSES. The bug only fires at
`:core:linkDebugFrameworkIosSimulatorArm64` / `:core:linkDebugFrameworkIosX64`.
CLAUDE.md's "Every target" command runs `compileKotlinIosSimulatorArm64`
but NOT the link step → broken framework can sit on main for cycles
without anyone noticing.

**Fix:** consolidate the duplicate definitions into one location with
`internal` visibility (e.g. one canonical `JsonObjectBuilder.stringProp`
in a `*Schema.kt` sibling file, callers in the same package use it
directly). Or rename one set so the signatures differ.

### Type-inference cascade: "Cannot infer type for this parameter" often masks a missing import
`2026-04-23 — tts-provider-fallback-chain`

When a generic-lambda call like `withProgress { synthesizeWithFallback(...) }`
triggers `"Cannot infer type for this parameter"` followed by cascade
unresolved-reference errors on the returned value's fields (e.g.
`Unresolved reference 'audio'` on a `.audio` access), the fix is almost
always:

1. Add an import you forgot (for the helper's return type).
2. Add an explicit type argument on the outer generic call
   (`.withProgress<TtsResult>(...)`).

The errors point downstream of the actual problem — don't chase them at
the `.audio` site.

---

## Coroutines / Flow testing

### `runTest` + `MutableSharedFlow`: subscribe-before-publish or the test hangs
`2026-04-23 — bundle-asset-relink-ux`

`CoroutineScope(SupervisorJob() + Dispatchers.Default) + flow.take(1).toList()`
under `runTest` blows up with `UncompletedCoroutinesError` — the test
scheduler waits for all child coroutines, and `toList` on a
never-terminating SharedFlow never returns.

Reliable recipe:

```kotlin
val captured = CompletableDeferred<BusEvent.AssetsMissing>()
backgroundScope.launch { captured.complete(flow.first()) }
yield()  // let the collector register its subscription
// ... code that publishes the event ...
assertEquals(expected, captured.await())
```

`backgroundScope` is auto-cancelled by `runTest`. `first()` terminates on
first match. `MutableSharedFlow` (what `EventBus` uses) has no replay by
default — publishing before the subscriber launches drops to zero
listeners.

---

## Tool interface evolution

### Widening a Tool ctor from single-value to `List<T>` via secondary-ctor delegation
`2026-04-23 — tts-provider-fallback-chain`

Moving `SynthesizeSpeechTool`'s primary ctor from `TtsEngine` to
`List<TtsEngine>` kept all 5 `AppContainer`s' call sites unchanged:

```kotlin
class SynthesizeSpeechTool(
    private val engines: List<TtsEngine>,
    ...
) {
    init { require(engines.isNotEmpty()) }
    constructor(engine: TtsEngine, bundleBlobWriter: ..., projectStore: ...)
        : this(listOf(engine), bundleBlobWriter, projectStore)
}
```

No ripple to `:core:compileKotlinIosSimulatorArm64` / android / desktop /
cli / server. Future ctor widenings (multi-VideoEngine routing,
multi-SecretStore chains) can copy this exact shape — the LLM-facing Tool
contract stays stable, the DI surface grows without a container sweep.

### Flipping an `Input` default is a breaking change for every implicit caller
`2026-04-23 — debt-import-media-auto-in-bundle-default`

`ImportMediaTool.Input.copy_into_bundle: Boolean = false` → `Boolean? = null`
(auto) looked local, but every test that called
`Input(path=..., projectId=...)` without naming the argument was relying
on the old `false` default. Auto mode changes behaviour for those callers:
6 tests went red across 2 files that had nothing to do with the storage
mode being changed.

The dependency isn't on the value "semantically" — it's on the *concrete
value* the caller runs against.

**Discipline:** before changing a default on a public Input/ctor param,
grep for all call sites with and without named-arg form; budget time to
update implicit callers to be explicit. Free if done up-front; expensive
if discovered in red CI.

### `@Serializable` row types in a tool's `Output` are output contracts, not implementation details
`2026-04-23 — debt-unify-project-diff-math`

When two tools look like they could share an `@Serializable` row type,
sharing couples their public output surfaces. Lifting
`DiffProjectsTool.TimelineDiff` / `ClipRef` to a shared module and having
both tools re-export would have either (a) forced one side to rename its
public type (caller churn for no behavioural benefit) or (b) added
query-only fields to the *other* side's public output.

**Rule of thumb:** when two tools' `Output` shapes look almost-but-not-quite
the same, share the *math* (via a module-internal neutral raw type), keep
the *row types* per-site. See also the "neutral raw type + `.map`" entry
under Refactor judgement.

### Exhaustive `when` on `BusEvent` forces metrics routing at compile time
`2026-04-23 — bundle-asset-relink-ux`

Adding a new `BusEvent` variant triggers a compile error in
`core.metrics.Metrics`'s `when (event)` on the sealed interface
(`Metrics.kt:128`). Best-case static enforcement: every new event type
has an explicit metrics routing (even if "ignore"), and the Prometheus
scrape stays complete by construction.

**Mental note:** when adding a `BusEvent` variant, plan on touching
`Metrics.kt` in the same change. The compiler will remind you, but
budgeting for it up-front avoids a surprise at validation time.

---

## Test design

### Static-invariant tests should expect to find a real bug on the first run
`2026-04-23 — debt-registered-tools-contract-test`

Dry-run-scanning all 104 `Tool.kt` files against all 5 `AppContainer`s
found exactly one tool missing from every container: `OpenProjectTool`,
with a `TODO(file-bundle-migration): register OpenProjectTool` comment.
Exactly the shape the new contract test is designed to catch.

**General pattern:** when you build a static invariant check, expect the
first run to find something. If it doesn't, the invariant is either
trivially satisfied (not worth enforcing) or too lax (missing the real
failure cases). Scan-then-fix is the honest first cycle of any
static-check work; skipping the scan and shipping with a broken-on-day-one
test devalues the guard.

### `assertFailsWith<T>` is brittle across library boundaries
`2026-04-23 — bundle-mac-launch-services`

`assertFailsWith<IllegalStateException>` for "bundle-with-no-`.talevia`-
sibling-and-no-`talevia.json`-inside must fail" broke because okio's
`FileSystem.source(path)` throws `java.io.FileNotFoundException`, not
wrapped.

**Heuristic:**

- Prefer `assertFailsWith<Throwable>` or plain `try / catch (_: Throwable)`
  when the failure crosses a library boundary (okio, sqldelight,
  kotlinx-serialization).
- Reserve typed asserts for exceptions the code-under-test throws itself
  (`IllegalStateException("bundle has no talevia.json")` — fine to assert
  on).

"Code I wrote threw this" is assertable; "library layer threw something
when I asked it to read nothing" is not.

### Pre-create antecedent tables beats full snapshot replay for additive migrations
`2026-04-23 — debt-add-sqldelight-migration-verification`

First attempt wrote a `v1Schema.sql` test fixture replaying a v1 snapshot
(tables + indexes + sample rows) before `Schema.migrate(driver, 1, ...)`.
Three tests in, noticed that 1.sqm / 2.sqm only CREATE new tables and
3.sqm uses `DROP TABLE IF EXISTS` — the migrations don't need the v1
schema to exist to exercise their SQL.

**Lightweight pattern that worked:** for each migration start-version,
pre-create *just the tables the version's migrations reference* (usually
zero or one), and let the migration run. Covers "migration SQL doesn't
throw" and "final schema matches expectation" without the maintenance drag
of full snapshots.

**When to prefer SqlDelight's `verifyMigrations` / full snapshots:**
migrations that rewrite existing rows in place or depend on specific
column types / defaults. Additive or `IF EXISTS`-guarded migrations
don't need them.

---

## Refactor judgement

### "Neutral raw type + per-site `.map` adapter" beats generic dispatch with N type params
`2026-04-23 — debt-unify-project-diff-math`

Reflex when DRYing duplicate math between two tools: parameterise over
the row type and share everything
(`computeTimelineDiff<T, C, Chg, R>(…, trackRefCtor, clipRefCtor,
clipChangeCtor, buildResult): R`). Five type parameters + four lambda
constructors. Every call site pays the cost of understanding a generic
signature for zero generality gain when N=2.

**Better default shape:** extract the math to a module-internal neutral
raw type (`TimelineDiffRaw` with `RawTrackRef` etc. — plain data classes,
not `@Serializable`). Each site calls it then `.map { }` into its own
per-site `@Serializable` row types.

- Zero type parameters in the shared helper.
- Both tools' public surfaces are byte-identical to before.
- Each call site picks up ~5 lines of trivial mapping.

Reach for generics only when 3+ sites need the same pattern **and** the
constructors meaningfully differ (not just "two @Serializable types are
named differently"). Generics-first is how most abstractions become
harder to use than the duplication they replace.

### Grep the full file for private helpers before removing them
`2026-04-23 — debt-unify-project-diff-math`

Deleting `DiffProjectsTool`'s `List<T>.cap()` extension + `MAX_DETAIL`
const as part of sweeping out timeline math compiled-broke because the
same helper was also used by `.diffSource` and `.diffLockfile` inside
the same file — three trailing call sites the refactor net missed.

**30-second fix:** before removing a private helper, run
`grep -c '\.cap()' <file>` to count *all* call sites, not just the ones
matching the refactor's primary concern. If the count is higher than
you expect, the helper is load-bearing beyond the local area.

### Conservative auto-mode: fs uncertainty falls back to the pre-heuristic behaviour
`2026-04-23 — debt-import-media-auto-in-bundle-default`

First draft of auto-mode called `fs.metadata(path).size` without handling
stat failure — if metadata wasn't available, the code path fell through
in undefined ways.

Second draft:

```kotlin
val size = runCatching { fs.metadata(path).size }.getOrNull() ?: return false
```

i.e. "unknown size → don't copy, reference instead". Auto mode is
specifically for the case where the agent didn't decide. If the heuristic
can't measure, err toward the conservative side (reference-by-path, bytes
stay in place) rather than the potentially-dangerous side (copy gigabytes
we can't verify).

**Pattern:** when a heuristic has a sane-default fallback, the
"information missing" branch should always land on the side that would
have been chosen *pre-heuristic*. That preserves backwards semantics for
edge cases while the heuristic helps the common path.

---

## Build / lint / packaging

### ktlint `no-blank-line-before-rbrace` fires after mass-delete of a trailing block
`2026-04-23 — debt-split-fork-project-tool`

After deleting the final block of private helpers in `ForkProjectTool`,
the remaining blank line before the class's closing `}` tripped
`standard:no-blank-line-before-rbrace`. Clean after `ktlintFormat`.

**Pattern to remember:** `ktlintCheck` fails after a delete-the-tail
refactor slightly more often than other refactors, because the natural
pattern ("blank line between the last method and the class brace") is
ktlint-clean only when there's a method in front of it — delete the
method and the blank becomes a violation. Cheap, surprising. Run
`ktlintFormat` preemptively after any edit that removes the last item
before a closing brace.

### Compose Desktop `extraKeysRawXml` is raw-XML injection with no schema validation
`2026-04-23 — bundle-mac-launch-services`

`nativeDistributions.macOS.infoPlist.extraKeysRawXml` takes a raw XML
string that the packager splices into the generated `Info.plist` before
`jpackage` runs. There is no schema check, no lint, no compile-time
structure — mistype `<array>` as `<array/>` or forget to close an outer
`<dict>` and the assemble step still succeeds (it only runs at
`packageDmg` time, not `assemble`). `Info.plist` only fails to parse on
a user's machine at first Launch Services registration.

**Cheap mitigation worth considering:** a unit test that loads the
`build.gradle.kts` raw-XML string through `NSPropertyListSerialization`
(or Apple's `plutil -lint`) on CI. Without it, the only verification is
"a mac user double-clicks a `.talevia` bundle and nothing happens" — an
unacceptable coverage gap for dynamically-verified-only features.

See also `debt-plist-extra-keys-lint-test` in `docs/BACKLOG.md` — concrete
follow-up.

### Compose Desktop dev-loop and packaged-app bundle identity diverge
`2026-04-23 — bundle-mac-launch-services`

Setting `bundleID = "io.talevia.Talevia"` inside `macOS { }` only affects
the *packaged* `.app` bundle. The dev-time `./gradlew :apps:desktop:run`
still launches with whatever bundle identifier the JVM picks (typically
the jpackage default). Launch Services registrations during iterative
development don't see `io.talevia.Talevia`.

**Consequence:** testing "double-click a `.talevia` in Finder" requires a
full `packageDmg` + install, not just a fast `:apps:desktop:run`. The
`/iterate-gap` "run the gradle target matching your change" heuristic
doesn't cover this; OS-integration bullets should budget a packageDmg
step in their plan.

## 2026-04-23 — BSD `sed` silently no-ops `\b` word boundaries

Mac ships BSD sed, which (unlike GNU sed) doesn't recognise `\b` as a
word boundary. `s/\.prunedEntries\b/…/g` matches zero things and
exits 0 — no warning, no diff. The failure surfaces only when the
post-sed file is compiled and the old identifier is still there.

Hit during cycle 22's bulk rewrite of three test files (~1200
lines): the first pass left every `.prunedEntries` reference
unchanged, and only the Kotlin compile errors (`Unresolved
reference 'prunedEntries'`) caught it.

**Rule of thumb:** on Mac, don't rely on `\b` in `sed` patterns.
Two workarounds:
1. Use a negative character class at the boundary:
   `s/\.prunedEntries\([^a-zA-Z0-9_]\)/.newName\1/g`. Verbose but
   works everywhere.
2. Require the boundary via a literal suffix the call site always
   produces — e.g. if every usage is `.prunedEntries.` or
   `.prunedEntries\n`, target that literal.
3. If the dataset is small, do the replacement via `Edit` tool
   calls instead of `sed` — Kotlin identifiers are usually small
   enough that per-site edits are affordable.

Always follow-up a `sed` rewrite with a `git diff --stat` + a
quick compile-check before moving on — the match count is part of
the diff; a zero-match rewrite should stand out visibly.

## 2026-04-23 — Testing a bus subscriber under `runTest` (two footguns)

Writing `AgentRunStateTrackerEvictionTest` (cycle 30,
`debt-bound-agent-run-state-tracker-evict-on-delete`) surfaced
two non-obvious pitfalls that together cost three red runs
before landing.

**Footgun 1 — `TestScope(coroutineContext)` inside `runTest` throws**
at construction with
`IllegalArgumentException: A CoroutineExceptionHandler was passed
to TestScope. Please pass it as an argument to a launch or async
block on an already-created scope`. `runTest`'s coroutine context
already carries a `TestScopeCoroutineExceptionHandler`, and
`TestScope(...)` explicitly forbids that — the check exists to
catch exactly this mistake. Correct pattern: use the built-in
`backgroundScope` that `runTest` exposes for forever-running
collectors. It auto-cancels at test end, which is what you
actually want for a bus subscriber that would otherwise leak the
test process.

```kotlin
// ❌ throws:
// val scope = TestScope(coroutineContext)
// val tracker = AgentRunStateTracker(bus, scope)

// ✅
val tracker = AgentRunStateTracker(bus, backgroundScope)
```

**Footgun 2 — `MutableSharedFlow` with no replay silently drops
publishes that race ahead of the first subscriber resumption.**
`EventBus` is a `MutableSharedFlow(extraBufferCapacity=256)` with
**no** replay. On `StandardTestDispatcher` (the default inside
`runTest`), constructing `AgentRunStateTracker(bus, scope)`
schedules the collector via `scope.launch { bus.events.collect { … } }`
but doesn't start collecting until the dispatcher is driven. A
`bus.publish(event)` called immediately after construction emits
into the buffer; when the collector finally resumes, it sees
nothing — buffered events from before the subscriber joined are
not replayed.

Fix: after every construct-a-subscriber step, drain with
`advanceUntilIdle()` + `yield()` **before** the first publish.
Without this, the seed event is silently swallowed and the "my
subscriber never saw it" symptom looks like the subscriber is
broken.

```kotlin
val tracker = AgentRunStateTracker(bus, backgroundScope)
advanceUntilIdle()   // let the collector register
yield()
bus.publish(BusEvent.AgentRunStateChanged(sid, Generating))
advanceUntilIdle()   // let the collector process
yield()
// assertions…
```

Existing tests that subscribed via `launchIn(this)` inside
`runTest` (e.g. `AgentCompactionTest`) hit the same issue and
resolved it with a single `yield()` immediately after `launchIn`.
The tracker case is the same principle applied to a subscriber
that lives inside an injectable scope rather than being `launchIn`-ed
on `this`.

Together these two footguns explain why "my subscriber test runs
green locally but red on first write" is a recurring pattern for
this codebase — both pitfalls hide under a generic assertion
failure, not a transport error.

## 2026-04-23 — `SharedFlow.subscriptionCount` through a narrowed accessor

Writing `EventRouterTest`, the natural "wait for all N subscribers to
install" probe was:

```kotlin
while (bus.events.subscriptionCount.value < 5) { yield() }
```

`EventBus.events: SharedFlow<BusEvent>` exposes the flow, and
`SharedFlow` declares `val subscriptionCount: StateFlow<Int>` in the
kotlinx-coroutines 1.10 API, so this *should* resolve. But the JVM
compile tripped with `Unresolved reference 'subscriptionCount'` at
the `while` — the Kotlin compiler couldn't see the property through
the narrowed `get()` accessor. Didn't dig far enough to confirm
whether it's a stdlib / coroutines version interaction, a `get()`
accessor narrowing quirk, or an import-mechanics issue specific to
`kotlinx-coroutines` extension-ish properties on a declared interface.

**Workaround** (what landed): `runTest`'s single-thread dispatcher
drains pending start-up continuations deterministically, so a
`testScheduler.runCurrent()` + `repeat(8) { yield() }` +
`testScheduler.runCurrent()` sandwich is a reliable fallback:

```kotlin
val router = EventRouter(bus, sessions, renderer) { sessionId }
router.start(backgroundScope)
testScheduler.runCurrent()
repeat(8) { yield() }
testScheduler.runCurrent()
// publish(...) now safe
```

This is strictly weaker than the `subscriptionCount` probe (it relies
on "8 yields is enough" empirical tuning rather than a state flag),
but it's deterministic under `runTest`'s single dispatcher. Caveat:
won't work under a multi-threaded dispatcher — if you move a bus
subscriber test off `runTest`, re-investigate `subscriptionCount`.

**Re-try list** (if this comes up again):
- Does explicit `import kotlinx.coroutines.flow.SharedFlow` before the
  access help? (Already present in the failing test — didn't help.)
- Drop the `get()` accessor on `EventBus.events` and make it a `val`
  field directly on the class? Cheapest fix if it *is* the narrowing
  quirk.
- Is this fixed on a newer coroutines version? 1.10.1 is current.

None of these were urgent enough to block the test cycle — the
yield-based workaround is ~3 extra lines and documented at the
callsite. Flagging here so the next author doesn't re-derive it.
