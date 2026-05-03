package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for [ToolContext]'s helper methods. Cycle 90 audit
 * found these methods (resolveProjectId / resolveSessionId / forReplay
 * / forVariant) had no direct test (only 1 transitive reference in
 * `ReplayLockfileToolTest` — and that test only documents the
 * resolveProjectId default-path through a kdoc comment, not exercises
 * the contract).
 *
 * The methods are utility shorthands that every `projectId` /
 * `sessionId`-taking tool calls. A regression in the precedence order
 * (e.g. session-binding accidentally winning over an explicit input)
 * would silently dispatch tools to the wrong project across every
 * project-scoped tool.
 */
class ToolContextTest {

    private fun ctx(
        currentProjectId: ProjectId? = null,
        isReplay: Boolean = false,
        variantIndex: Int = 0,
        spendCapCents: Long? = null,
    ): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        currentProjectId = currentProjectId,
        isReplay = isReplay,
        variantIndex = variantIndex,
        spendCapCents = spendCapCents,
    )

    // ── resolveProjectId ──────────────────────────────────────

    @Test fun resolveProjectIdReturnsExplicitInputWhenProvided() {
        val c = ctx(currentProjectId = ProjectId("from-context"))
        // Explicit input wins per kdoc precedence rule #1.
        assertEquals(ProjectId("explicit"), c.resolveProjectId("explicit"))
    }

    @Test fun resolveProjectIdFallsBackToCurrentProjectIdWhenInputNull() {
        val c = ctx(currentProjectId = ProjectId("from-context"))
        assertEquals(ProjectId("from-context"), c.resolveProjectId(null))
    }

    @Test fun resolveProjectIdThrowsWithSessionBindingHintWhenBothNull() {
        val c = ctx(currentProjectId = null)
        val ex = assertFailsWith<IllegalStateException> {
            c.resolveProjectId(null)
        }
        // Pin the actionable hint — the error message is what the
        // agent sees and uses to recover.
        assertTrue(
            "switch_project" in (ex.message ?: ""),
            "error must hint at switch_project; got: ${ex.message}",
        )
    }

    @Test fun resolveProjectIdEmptyStringIsTreatedAsExplicit() {
        // Subtle: empty string IS not-null, so the function takes the
        // input arm and creates ProjectId(""). Pin so a refactor
        // accidentally adding `isNullOrBlank` would silently fall
        // through to currentProjectId on empty input — changing the
        // contract.
        val c = ctx(currentProjectId = ProjectId("ctx-fallback"))
        assertEquals(ProjectId(""), c.resolveProjectId(""))
    }

    // ── resolveSessionId ──────────────────────────────────────

    @Test fun resolveSessionIdReturnsExplicitInputWhenProvided() {
        val c = ctx()
        // Explicit always wins.
        assertEquals(SessionId("explicit"), c.resolveSessionId("explicit"))
    }

    @Test fun resolveSessionIdFallsBackToOwningSessionWhenInputNull() {
        val c = ctx()
        // Owning session always available — no error arm.
        assertEquals(SessionId("s"), c.resolveSessionId(null))
    }

    // ── forReplay ─────────────────────────────────────────────

    @Test fun forReplayReturnsContextWithIsReplayTrue() {
        val c = ctx(isReplay = false)
        val replay = c.forReplay()
        assertEquals(true, replay.isReplay)
    }

    @Test fun forReplayPreservesAllOtherFields() {
        // Pin: forReplay only flips isReplay; every other field
        // copies through unchanged. Critical because AIGC tools
        // read multiple fields (sessionId, currentProjectId,
        // spendCapCents, etc.) — a refactor accidentally dropping
        // one would break replay correctness.
        val c = ctx(
            currentProjectId = ProjectId("p"),
            isReplay = false,
            variantIndex = 3,
            spendCapCents = 1000L,
        )
        val replay = c.forReplay()
        assertEquals(c.sessionId, replay.sessionId)
        assertEquals(c.messageId, replay.messageId)
        assertEquals(c.callId, replay.callId)
        assertEquals(c.currentProjectId, replay.currentProjectId)
        assertEquals(c.variantIndex, replay.variantIndex)
        assertEquals(c.spendCapCents, replay.spendCapCents)
        assertSame(c.askPermission, replay.askPermission)
        assertSame(c.emitPart, replay.emitPart)
        assertSame(c.messages, replay.messages)
        assertSame(c.publishEvent, replay.publishEvent)
    }

    @Test fun forReplayOnAlreadyReplayContextStaysReplay() {
        // Idempotent: calling forReplay on an already-replay ctx
        // doesn't toggle off.
        val c = ctx(isReplay = true)
        assertEquals(true, c.forReplay().isReplay)
    }

    // ── forVariant ────────────────────────────────────────────

    @Test fun forVariantReturnsContextWithVariantIndexSet() {
        val c = ctx(variantIndex = 0)
        assertEquals(7, c.forVariant(7).variantIndex)
    }

    @Test fun forVariantPreservesAllOtherFields() {
        // Same shape as forReplay test — every other field copies
        // through. Critical because AIGC tools read sessionId,
        // currentProjectId, etc. in addition to variantIndex.
        val c = ctx(
            currentProjectId = ProjectId("p"),
            isReplay = true,
            variantIndex = 0,
            spendCapCents = 500L,
        )
        val variant = c.forVariant(2)
        assertEquals(c.sessionId, variant.sessionId)
        assertEquals(c.currentProjectId, variant.currentProjectId)
        assertEquals(c.isReplay, variant.isReplay)
        assertEquals(c.spendCapCents, variant.spendCapCents)
        assertSame(c.askPermission, variant.askPermission)
        assertSame(c.emitPart, variant.emitPart)
    }

    @Test fun forVariantZeroResetsVariantIndex() {
        // Edge: forVariant(0) on an already-non-zero context resets
        // back to 0. Pin the literal-set semantics (not max/sum/etc.).
        val c = ctx(variantIndex = 5)
        assertEquals(0, c.forVariant(0).variantIndex)
    }

    @Test fun forVariantSupportsLargeIndexValues() {
        // Pin: no implicit cap. AigcGenerateTool's variantCount range
        // is bounded at the dispatcher input (≤ 16), but the
        // ToolContext itself accepts any Int.
        val c = ctx()
        assertEquals(99, c.forVariant(99).variantIndex)
    }
}
