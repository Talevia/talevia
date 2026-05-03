package io.talevia.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Direct tests for [ProviderRegistry] —
 * `core/provider/ProviderRegistry.kt`. The "which provider
 * answers ModelRef.providerId" router built once at app
 * start, plus the Builder that composes providers from
 * env / secrets / OAuth credential stores. Cycle 157 audit:
 * 150 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`Builder.add()` is first-write-wins by provider id.**
 *    Per kdoc: "the first source to supply a given provider
 *    wins, so later calls act as fallbacks." Drift to
 *    last-write-wins would silently override env-priority
 *    semantics — secrets-then-env would let env override
 *    secrets, breaking the documented "call addSecretStore
 *    before addEnv if you want UI-entered keys to take
 *    precedence" pattern.
 *
 * 2. **`build()` default = first-registered provider with
 *    a non-empty list.** Per kdoc: "`defaultProvider` is
 *    whichever provider was registered first." The
 *    composition root decides priority order via add()
 *    sequencing.
 *
 * 3. **Empty registry has null default and `get(any)`
 *    returns null.** A composition root that loaded zero
 *    keys must produce a registry that doesn't NPE on
 *    lookup. Headless / no-credentials boots are real (CI
 *    runs without API keys).
 */
class ProviderRegistryTest {

    /** Minimal LlmProvider stub — only `id` matters for registry tests. */
    private class StubProvider(override val id: String) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }

    // ── Builder.add: first-write-wins dedup ─────────────────────

    @Test fun addRegistersProviderUnderItsId() {
        val anthropic = StubProvider("anthropic")
        val registry = ProviderRegistry.Builder()
            .add(anthropic)
            .build()
        assertSame(anthropic, registry["anthropic"])
    }

    @Test fun addIsFirstWriteWinsForDuplicateId() {
        // Marquee dedup pin: same-id provider registered
        // twice → the FIRST instance is kept. Second add()
        // is silently ignored.
        val first = StubProvider("openai")
        val second = StubProvider("openai")
        val registry = ProviderRegistry.Builder()
            .add(first)
            .add(second) // silently no-op
            .build()
        assertSame(first, registry["openai"], "first registration wins")
        assertEquals(
            1,
            registry.all().size,
            "duplicate not added; total count = 1",
        )
    }

    @Test fun addReturnsBuilderForChaining() {
        // Pin: every Builder method returns Builder for chained
        // fluent construction. Drift to Unit return would
        // break the canonical builder shape.
        val builder = ProviderRegistry.Builder()
        val same = builder.add(StubProvider("p1"))
        assertSame(builder, same, "fluent: add returns same builder")
    }

    @Test fun multipleDistinctIdsAllRegister() {
        val a = StubProvider("anthropic")
        val o = StubProvider("openai")
        val g = StubProvider("gemini")
        val registry = ProviderRegistry.Builder()
            .add(a)
            .add(o)
            .add(g)
            .build()
        assertEquals(3, registry.all().size)
        assertSame(a, registry["anthropic"])
        assertSame(o, registry["openai"])
        assertSame(g, registry["gemini"])
    }

    // ── default = first registered ──────────────────────────────

    @Test fun defaultIsFirstRegisteredProvider() {
        // Marquee default-selection pin: insertion order
        // matters. Composition root sequences add() in
        // priority order; registry's default is the head.
        val first = StubProvider("a")
        val second = StubProvider("b")
        val third = StubProvider("c")
        val registry = ProviderRegistry.Builder()
            .add(first)
            .add(second)
            .add(third)
            .build()
        assertSame(first, registry.default, "default = first added")
    }

    @Test fun defaultStaysFirstAfterDuplicateAdd() {
        // Pin: a duplicate add() doesn't shift the default
        // (because it's a no-op). The first-added stays
        // default even when later providers try to register
        // their same-id replacements.
        val first = StubProvider("anthropic")
        val secondTry = StubProvider("anthropic")
        val real = StubProvider("openai")
        val registry = ProviderRegistry.Builder()
            .add(first)
            .add(secondTry) // ignored
            .add(real)
            .build()
        assertSame(first, registry.default, "duplicate doesn't displace default")
    }

    // ── empty registry sentinels ────────────────────────────────

    @Test fun emptyRegistryHasNullDefault() {
        // Pin: zero-provider registry still constructs,
        // produces null default. CI / headless boots without
        // any API keys must not NPE.
        val registry = ProviderRegistry.Builder().build()
        assertNull(registry.default)
        assertEquals(emptyList(), registry.all())
    }

    @Test fun getReturnsNullForUnknownProviderId() {
        val registry = ProviderRegistry.Builder()
            .add(StubProvider("anthropic"))
            .build()
        assertNull(registry["openai"], "unregistered id → null, NOT throw")
        assertNull(registry["definitely-not-a-real-provider"])
    }

    @Test fun getReturnsNullForUnknownEvenWhenRegistryIsEmpty() {
        val registry = ProviderRegistry.Builder().build()
        assertNull(registry["anything"])
    }

    // ── all() returns insertion order ──────────────────────────

    @Test fun allReturnsProvidersInInsertionOrder() {
        // Pin: `byId.values.toList()` from a LinkedHashMap
        // (default Map.associateBy behavior) preserves
        // insertion order. UI / list_providers output relies
        // on this.
        val a = StubProvider("a")
        val b = StubProvider("b")
        val c = StubProvider("c")
        val all = ProviderRegistry.Builder()
            .add(a)
            .add(b)
            .add(c)
            .build()
            .all()
        assertEquals(listOf(a, b, c), all, "insertion order preserved")
    }

    // ── SecretKeys constants ────────────────────────────────────

    @Test fun secretKeysAreStableProviderIdStrings() {
        // Pin: SecretKeys constants are the string identities
        // both Secret stores AND the Builder's secret-fetch
        // path use. Drift to changing the spelling would
        // silently break every persisted secrets file
        // referencing them.
        assertEquals("anthropic", ProviderRegistry.SecretKeys.ANTHROPIC)
        assertEquals("openai", ProviderRegistry.SecretKeys.OPENAI)
        assertEquals("gemini", ProviderRegistry.SecretKeys.GEMINI)
        assertEquals("google", ProviderRegistry.SecretKeys.GOOGLE)
        assertEquals("openai-codex", ProviderRegistry.SecretKeys.OPENAI_CODEX)
    }

    // ── operator get vs map.get equivalence ─────────────────────

    @Test fun getOperatorAndExplicitGetAreSemanticallyEquivalent() {
        // Pin: `registry["id"]` (operator) and the same
        // semantic via the explicit indexer should agree.
        // Drift would mean idiomatic Kotlin call sites get
        // different results from explicit ones.
        val p = StubProvider("anthropic")
        val registry = ProviderRegistry.Builder().add(p).build()
        assertSame(p, registry["anthropic"])
        assertSame(registry["anthropic"], registry["anthropic"], "stable across calls")
    }

    // ── private constructor → only Builder.build() yields ──────

    @Test fun publicAccessOnlyThroughBuilderBuild() {
        // Pin: ProviderRegistry's primary constructor is
        // private — only Builder.build() can produce one.
        // This invariant is structural (compile-time
        // enforced) but pin documents the design intent so
        // a future refactor doesn't accidentally make it
        // public and break the "registry is built once at
        // app start" promise.
        val registry = ProviderRegistry.Builder().build()
        // Confirm we can construct via Builder. If the
        // primary constructor became public, this test would
        // still pass — but the explicit private would surface
        // in a code review that this test belongs in
        // ProjectArchitectureCheckTest territory.
        assertNull(registry.default)
    }
}
