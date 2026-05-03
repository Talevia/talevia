package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for the cross-cutting tool dispatch types in
 * `core/tool/Tool.kt`: [ToolApplicability] visibility-gate
 * sealed interface, [ToolAvailabilityContext], and
 * [ToolContext]'s pure helper methods (`resolveProjectId`,
 * `resolveSessionId`, `forReplay`, `forVariant`). Cycle 161
 * audit: 296 LOC, 0 transitive test refs (the dispatcher
 * file is exercised through every tool dispatch but the
 * helpers + sealed interface contracts were never pinned in
 * isolation).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`ToolApplicability.RequiresProjectBinding`** is
 *    `currentProjectId != null` and **`RequiresAssets`** is
 *    `currentProjectId != null && projectHasAssets`. Drift
 *    here directly affects which tools the LLM sees in its
 *    spec bundle every turn — per kdoc, ~100 tools' specs
 *    contribute non-trivially to TPM usage, so the visibility
 *    gates are load-bearing.
 *
 * 2. **`ToolContext.resolveProjectId(input)` 3-way
 *    precedence: explicit input wins; falls back to
 *    `currentProjectId`; errors with binding hint when
 *    neither is available.** Pinned at every angle —
 *    explicit-input-wins (even when binding present),
 *    binding-fallback (when input null), error-with-hint
 *    (when both null).
 *
 * 3. **`forReplay()` / `forVariant(i)` produce a child
 *    context that preserves every field EXCEPT the targeted
 *    one.** Critical for AIGC pipeline correctness:
 *    forReplay flips isReplay=true so the cache lookup
 *    skips; forVariant sets variantIndex so each iteration
 *    of a multi-variant generation lands as a distinct
 *    lockfile entry. Drift to "rebuild from scratch" would
 *    silently lose any other field downstream consumers
 *    depend on.
 */
class ToolTest {

    private fun makeCtx(
        currentProjectId: ProjectId? = null,
        isReplay: Boolean = false,
        spendCapCents: Long? = null,
        variantIndex: Int = 0,
    ): ToolContext = ToolContext(
        sessionId = SessionId("s1"),
        messageId = MessageId("m1"),
        callId = CallId("c1"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        currentProjectId = currentProjectId,
        isReplay = isReplay,
        spendCapCents = spendCapCents,
        variantIndex = variantIndex,
    )

    // ── ToolApplicability sealed interface ──────────────────────

    @Test fun alwaysApplicabilityIsTrueRegardlessOfContext() {
        // Pin: `Always` is the default for tools that work
        // regardless of session state. Drift here would
        // silently hide every default-applicability tool.
        val ctx1 = ToolAvailabilityContext(currentProjectId = null)
        val ctx2 = ToolAvailabilityContext(currentProjectId = ProjectId("p"))
        val ctx3 = ToolAvailabilityContext(
            currentProjectId = ProjectId("p"),
            projectHasAssets = true,
        )
        assertTrue(ToolApplicability.Always.isAvailable(ctx1))
        assertTrue(ToolApplicability.Always.isAvailable(ctx2))
        assertTrue(ToolApplicability.Always.isAvailable(ctx3))
    }

    @Test fun requiresProjectBindingIsAvailableWhenProjectIsSet() {
        val ctx = ToolAvailabilityContext(currentProjectId = ProjectId("p"))
        assertTrue(ToolApplicability.RequiresProjectBinding.isAvailable(ctx))
    }

    @Test fun requiresProjectBindingIsHiddenWhenProjectIsNull() {
        // Pin: drift to "always-true" would lure the model
        // into calling project-mutator tools with fabricated
        // projectIds — exactly the failure mode this
        // applicability variant exists to prevent.
        val ctx = ToolAvailabilityContext(currentProjectId = null)
        assertFalse(ToolApplicability.RequiresProjectBinding.isAvailable(ctx))
    }

    @Test fun requiresAssetsIsHiddenWhenProjectMissing() {
        // Pin: RequiresAssets is narrower than
        // RequiresProjectBinding — both must hold.
        val ctx = ToolAvailabilityContext(
            currentProjectId = null,
            projectHasAssets = true, // doesn't matter, project missing
        )
        assertFalse(ToolApplicability.RequiresAssets.isAvailable(ctx))
    }

    @Test fun requiresAssetsIsHiddenWhenProjectBoundButNoAssets() {
        // Pin: project bound but empty → RequiresAssets is
        // hidden. The kdoc-cited symptom: in an empty project
        // the LLM was calling `add_clip` / `apply_filter`
        // with placeholder ids like "missing".
        val ctx = ToolAvailabilityContext(
            currentProjectId = ProjectId("p"),
            projectHasAssets = false,
        )
        assertFalse(ToolApplicability.RequiresAssets.isAvailable(ctx))
    }

    @Test fun requiresAssetsIsAvailableWhenProjectBoundAndHasAssets() {
        val ctx = ToolAvailabilityContext(
            currentProjectId = ProjectId("p"),
            projectHasAssets = true,
        )
        assertTrue(ToolApplicability.RequiresAssets.isAvailable(ctx))
    }

    // ── ToolAvailabilityContext defaults ────────────────────────

    @Test fun availabilityContextDefaultsConservative() {
        // Pin: default constructor (only `currentProjectId =
        // null` provided) defaults to projectHasAssets=false
        // and disabledToolIds=empty. Conservative defaults
        // hide RequiresAssets tools when caller can't /
        // doesn't load project state.
        val ctx = ToolAvailabilityContext(currentProjectId = null)
        assertFalse(ctx.projectHasAssets, "default = false (conservative)")
        assertEquals(emptySet(), ctx.disabledToolIds, "default = empty set")
    }

    // ── ToolContext.resolveProjectId 3-way precedence ──────────

    @Test fun resolveProjectIdExplicitInputWins() {
        // The marquee precedence pin: explicit > binding.
        // Drift to "binding wins" would silently override
        // the agent's deliberate choice when it passed an
        // explicit projectId.
        val ctx = makeCtx(currentProjectId = ProjectId("from-binding"))
        assertEquals(
            ProjectId("from-input"),
            ctx.resolveProjectId("from-input"),
            "explicit input always wins",
        )
    }

    @Test fun resolveProjectIdFallsBackToBindingWhenInputNull() {
        val ctx = makeCtx(currentProjectId = ProjectId("from-binding"))
        assertEquals(
            ProjectId("from-binding"),
            ctx.resolveProjectId(null),
        )
    }

    @Test fun resolveProjectIdErrorsWithBindingHintWhenBothNull() {
        // Pin: error message includes the kdoc-documented
        // "Call switch_project" guidance so the LLM can
        // self-correct without consulting docs.
        val ctx = makeCtx(currentProjectId = null)
        val ex = assertFailsWith<IllegalStateException> {
            ctx.resolveProjectId(null)
        }
        val msg = ex.message.orEmpty()
        assertTrue("No projectId provided" in msg)
        assertTrue("switch_project" in msg, "binding-hint surfaces; got: $msg")
        assertTrue("pass projectId explicitly" in msg, "alternative-hint surfaces; got: $msg")
    }

    // ── ToolContext.resolveSessionId — no error arm ─────────────

    @Test fun resolveSessionIdAlwaysSucceedsViaCtxSessionId() {
        // Pin: unlike resolveProjectId, sessionId NEVER
        // errors — the dispatch always knows which session
        // it's running under.
        val ctx = makeCtx()
        assertEquals(SessionId("s1"), ctx.resolveSessionId(null), "fallback to ctx.sessionId")
        assertEquals(SessionId("explicit"), ctx.resolveSessionId("explicit"), "explicit wins")
    }

    // ── forReplay() ────────────────────────────────────────────

    @Test fun forReplayFlipsIsReplayPreservesEverythingElse() {
        // Marquee field-preservation pin: forReplay flips
        // ONE field. Drift to dropping spendCapCents /
        // currentProjectId / messageId would silently break
        // downstream consumers (e.g. AIGC pipeline reading
        // spendCapCents to enforce budget).
        val original = makeCtx(
            currentProjectId = ProjectId("p"),
            spendCapCents = 1000L,
            variantIndex = 3,
        )
        val replay = original.forReplay()

        assertEquals(true, replay.isReplay, "isReplay flipped to true")
        // All other fields preserved.
        assertEquals(original.sessionId, replay.sessionId)
        assertEquals(original.messageId, replay.messageId)
        assertEquals(original.callId, replay.callId)
        assertEquals(original.currentProjectId, replay.currentProjectId)
        assertEquals(original.spendCapCents, replay.spendCapCents)
        assertEquals(original.variantIndex, replay.variantIndex, "variantIndex preserved")
        // Lambdas: reference equality — they're carried
        // forward (not rewrapped).
        assertSame(original.askPermission, replay.askPermission)
        assertSame(original.emitPart, replay.emitPart)
        assertSame(original.publishEvent, replay.publishEvent)
    }

    @Test fun forReplayFromAlreadyReplayingContextStillReplay() {
        // Pin: idempotent — replaying an already-replay
        // context keeps isReplay=true.
        val original = makeCtx(isReplay = true)
        val replay = original.forReplay()
        assertEquals(true, replay.isReplay)
    }

    // ── forVariant() ───────────────────────────────────────────

    @Test fun forVariantSetsVariantIndexPreservesEverythingElse() {
        // The marquee multi-variant pin: AigcGenerateTool
        // loops over variantCount, calling forVariant(i) on
        // each iteration. Each child context's variantIndex
        // flows into the inputHash so N variants → N
        // distinct lockfile entries.
        val original = makeCtx(
            currentProjectId = ProjectId("p"),
            isReplay = true,
            spendCapCents = 500L,
            variantIndex = 0,
        )
        val variant = original.forVariant(7)

        assertEquals(7, variant.variantIndex)
        // All other fields preserved.
        assertEquals(original.sessionId, variant.sessionId)
        assertEquals(original.currentProjectId, variant.currentProjectId)
        assertEquals(original.isReplay, variant.isReplay, "isReplay preserved")
        assertEquals(original.spendCapCents, variant.spendCapCents)
        assertSame(original.askPermission, variant.askPermission)
    }

    @Test fun forVariantWithSameIndexProducesEquivalentContext() {
        // Pin: forVariant(0) on a default-variantIndex=0
        // context should be a no-op semantically.
        val original = makeCtx(variantIndex = 0)
        val variant = original.forVariant(0)
        assertEquals(0, variant.variantIndex)
        assertEquals(original.sessionId, variant.sessionId)
    }

    // ── ToolContext defaults ────────────────────────────────────

    @Test fun toolContextDefaultsAreSourceCompatible() {
        // Pin: minimum-args ToolContext construction works.
        // Per kdoc: defaults `null` for currentProjectId,
        // `false` for isReplay, no-op for publishEvent, etc.
        // ensure existing call sites compile.
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        assertEquals(null, ctx.currentProjectId)
        assertEquals(false, ctx.isReplay)
        assertEquals(0, ctx.variantIndex)
        assertEquals(null, ctx.spendCapCents)
    }
}
