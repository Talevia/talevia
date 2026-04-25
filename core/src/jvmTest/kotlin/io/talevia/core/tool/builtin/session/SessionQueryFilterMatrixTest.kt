package io.talevia.core.tool.builtin.session

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for the cycle-94 matrix refactor of
 * `SessionQueryTool.rejectIncompatibleFilters`. Pins:
 *
 *  1. Every select in [SessionQueryTool.ALL_SELECTS] has an entry in
 *     [SESSION_QUERY_ACCEPTED_FIELDS]. A new select that ships
 *     without a matrix entry would fail the rejection walk in a
 *     subtle way (silently accept any filter), which is the worst
 *     possible failure mode — assert at test time.
 *
 *  2. The walker still rejects misapplied filters for the legacy
 *     scenarios the old if-else chain covered (projectId on a non-
 *     sessions select, role on non-messages, etc.).
 *
 *  3. The error message names the offending field AND the selects
 *     that DO accept it, so the agent can self-correct in one
 *     round-trip.
 */
class SessionQueryFilterMatrixTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun everySelectHasMatrixEntry() {
        val selects = SessionQueryTool.ALL_SELECTS
        val matrixKeys = SESSION_QUERY_ACCEPTED_FIELDS.keys
        val missing = selects - matrixKeys
        assertTrue(
            missing.isEmpty(),
            "selects without matrix entries: $missing — every select MUST register its accepted-filter set",
        )
        // Reverse: matrix shouldn't have orphans either (a select that
        // got renamed / deleted but the matrix entry stayed).
        val orphan = matrixKeys - selects
        assertTrue(
            orphan.isEmpty(),
            "matrix has orphan entries (no matching SELECT_* const): $orphan",
        )
    }

    @Test fun projectIdOnNonSessionsSelectIsMisapplied() {
        // projectId belongs to select=sessions; passing it on
        // select=messages is a typo / wrong-select.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleSessionQueryFilters(
                SessionQueryTool.SELECT_MESSAGES,
                SessionQueryTool.Input(
                    select = "messages",
                    sessionId = "s1",
                    projectId = "p1", // wrong field for messages
                ),
            )
        }
        assertTrue("error names projectId: ${ex.message}") {
            ex.message?.contains("projectId") == true
        }
        // Computed-from-matrix message points at the right select.
        assertTrue("error points at sessions: ${ex.message}") {
            ex.message?.contains("select=sessions") == true
        }
    }

    @Test fun toolIdOnlyAcceptedByToolCalls() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleSessionQueryFilters(
                SessionQueryTool.SELECT_PARTS,
                SessionQueryTool.Input(
                    select = "parts",
                    sessionId = "s1",
                    toolId = "generate_image", // only tool_calls accepts toolId
                ),
            )
        }
        assertTrue { ex.message?.contains("toolId") == true }
        assertTrue { ex.message?.contains("select=tool_calls") == true }
    }

    @Test fun multipleAcceptorsListedTogether() {
        // includeCompacted accepts on both parts and tool_calls — the
        // error message should list both.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleSessionQueryFilters(
                SessionQueryTool.SELECT_MESSAGES,
                SessionQueryTool.Input(
                    select = "messages",
                    sessionId = "s1",
                    includeCompacted = true,
                ),
            )
        }
        val msg = ex.message.orEmpty()
        assertTrue("expected parts in acceptors: $msg") { msg.contains("parts") }
        assertTrue("expected tool_calls in acceptors: $msg") { msg.contains("tool_calls") }
    }

    @Test fun sessionIdRejectedOnSessionsAndToolSpecBudget() {
        // sessions enumerates every session; sessionId there is a typo.
        val sessionsErr = assertFailsWith<IllegalStateException> {
            rejectIncompatibleSessionQueryFilters(
                SessionQueryTool.SELECT_SESSIONS,
                SessionQueryTool.Input(select = "sessions", sessionId = "s1"),
            )
        }
        assertTrue { sessionsErr.message?.contains("sessionId") == true }

        // tool_spec_budget is registry-wide; sessionId is a typo.
        val budgetErr = assertFailsWith<IllegalStateException> {
            rejectIncompatibleSessionQueryFilters(
                SessionQueryTool.SELECT_TOOL_SPEC_BUDGET,
                SessionQueryTool.Input(select = "tool_spec_budget", sessionId = "s1"),
            )
        }
        assertTrue { budgetErr.message?.contains("sessionId") == true }
    }

    @Test fun acceptedFiltersDoNotTriggerRejection() {
        // Sanity: passing the right filters for the right select must
        // NOT throw — otherwise the matrix is over-restrictive.
        rejectIncompatibleSessionQueryFilters(
            SessionQueryTool.SELECT_PARTS,
            SessionQueryTool.Input(
                select = "parts",
                sessionId = "s1",
                kind = "text",
                includeCompacted = true,
            ),
        )
        // Sessions select with projectId + includeArchived — both accepted.
        rejectIncompatibleSessionQueryFilters(
            SessionQueryTool.SELECT_SESSIONS,
            SessionQueryTool.Input(
                select = "sessions",
                projectId = "p1",
                includeArchived = true,
            ),
        )
        // step_history with messageId — accepted.
        rejectIncompatibleSessionQueryFilters(
            SessionQueryTool.SELECT_STEP_HISTORY,
            SessionQueryTool.Input(
                select = "step_history",
                sessionId = "s1",
                messageId = "m1",
            ),
        )
    }

    @Test fun toolSpecBudgetAcceptsNoFilters() {
        // tool_spec_budget is registry-wide — its accepted set is empty.
        // Pass any filter and the walker rejects.
        assertEquals(emptySet(), SESSION_QUERY_ACCEPTED_FIELDS[SessionQueryTool.SELECT_TOOL_SPEC_BUDGET])
    }
}
