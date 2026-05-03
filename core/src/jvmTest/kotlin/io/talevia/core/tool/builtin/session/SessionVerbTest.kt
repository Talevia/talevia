package io.talevia.core.tool.builtin.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the contract of [SessionActionTool.Input.toVerb] introduced by
 * `debt-split-session-action-tool-input-phase1a` (cycle 47): the flat
 * 17-field Input decodes into the typed [SessionVerb] sealed family
 * with required-but-missing fields failing loud at the same boundary
 * the handler bodies would have thrown today.
 *
 * Phase 1b will rewrite [SessionActionTool.execute]'s
 * `when (input.action)` to consume `input.toVerb()` instead. This
 * test fixates the decoder so the rewrite is just a switch from
 * `String` discriminator to typed `is`-pattern — no behaviour change.
 */
class SessionVerbTest {

    @Test fun archiveDecodesWithOptionalSessionId() {
        val verb = SessionActionTool.Input(action = "archive", sessionId = "s-1").toVerb()
        assertTrue(verb is SessionVerb.Archive)
        assertEquals("s-1", verb.sessionId)

        val noSession = SessionActionTool.Input(action = "archive").toVerb()
        assertTrue(noSession is SessionVerb.Archive)
        assertEquals(null, noSession.sessionId)
    }

    @Test fun unarchiveDecodesWithOptionalSessionId() {
        val verb = SessionActionTool.Input(action = "unarchive", sessionId = "s-1").toVerb()
        assertTrue(verb is SessionVerb.Unarchive)
        assertEquals("s-1", verb.sessionId)
    }

    @Test fun renameDecodesNewTitleAndFailsLoudWhenMissing() {
        val verb = SessionActionTool.Input(action = "rename", newTitle = "renamed").toVerb()
        assertTrue(verb is SessionVerb.Rename)
        assertEquals("renamed", verb.newTitle)

        val ex = assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "rename").toVerb()
        }
        assertTrue("newTitle" in ex.message.orEmpty(), ex.message)
    }

    @Test fun deleteRequiresExplicitSessionId() {
        // The required-not-null path: the bullet's "deleting by context
        // binding is self-destructive" rationale; phase 1a's decoder
        // enforces the same invariant as the handler did.
        val verb = SessionActionTool.Input(action = "delete", sessionId = "s-doomed").toVerb()
        assertTrue(verb is SessionVerb.Delete)
        assertEquals("s-doomed", verb.sessionId)

        val ex = assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "delete").toVerb()
        }
        assertTrue("sessionId" in ex.message.orEmpty(), ex.message)
        assertTrue("self-destructive" in ex.message.orEmpty(), ex.message)
    }

    @Test fun removePermissionRuleDecodesPermissionAndPattern() {
        val verb = SessionActionTool.Input(
            action = "remove_permission_rule",
            permission = "fs.write",
            pattern = "/tmp/*",
        ).toVerb()
        assertTrue(verb is SessionVerb.RemovePermissionRule)
        assertEquals("fs.write", verb.permission)
        assertEquals("/tmp/*", verb.pattern)

        // Missing pattern fails loud.
        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "remove_permission_rule", permission = "fs.write").toVerb()
        }
        // Missing permission fails loud.
        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "remove_permission_rule", pattern = "/tmp/*").toVerb()
        }
    }

    @Test fun importDecodesEnvelopeOrFailsLoud() {
        val verb = SessionActionTool.Input(action = "import", envelope = """{"v":1}""").toVerb()
        assertTrue(verb is SessionVerb.Import)
        assertEquals("""{"v":1}""", verb.envelope)

        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "import").toVerb()
        }
    }

    @Test fun setSystemPromptDistinguishesNullFromEmptyString() {
        // Null clears the override; empty string is a legit "run with no
        // system prompt" override. The decoder preserves both shapes.
        val cleared = SessionActionTool.Input(
            action = "set_system_prompt",
            systemPromptOverride = null,
        ).toVerb()
        assertTrue(cleared is SessionVerb.SetSystemPrompt)
        assertEquals(null, cleared.systemPromptOverride)

        val emptyOverride = SessionActionTool.Input(
            action = "set_system_prompt",
            systemPromptOverride = "",
        ).toVerb()
        assertTrue(emptyOverride is SessionVerb.SetSystemPrompt)
        assertEquals("", emptyOverride.systemPromptOverride)

        val custom = SessionActionTool.Input(
            action = "set_system_prompt",
            systemPromptOverride = "be terse",
        ).toVerb()
        assertTrue(custom is SessionVerb.SetSystemPrompt)
        assertEquals("be terse", custom.systemPromptOverride)
    }

    @Test fun exportBusTraceDecodesFormatAndLimit() {
        val verb = SessionActionTool.Input(
            action = "export_bus_trace",
            format = "jsonl",
            limit = 200,
        ).toVerb()
        assertTrue(verb is SessionVerb.ExportBusTrace)
        assertEquals("jsonl", verb.format)
        assertEquals(200, verb.limit)
    }

    @Test fun setToolEnabledRequiresToolIdAndEnabled() {
        val verb = SessionActionTool.Input(
            action = "set_tool_enabled",
            toolId = "generate_video",
            enabled = false,
        ).toVerb()
        assertTrue(verb is SessionVerb.SetToolEnabled)
        assertEquals("generate_video", verb.toolId)
        assertEquals(false, verb.enabled)

        // Both fields required.
        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "set_tool_enabled", toolId = "x").toVerb()
        }
        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "set_tool_enabled", enabled = true).toVerb()
        }
    }

    @Test fun setSpendCapPreservesNullForClearIntent() {
        // Null capCents = clear (no budget gating). Decoder must NOT
        // turn this into a "missing field" error — it's a legitimate
        // value the handler interprets as "remove cap".
        val cleared = SessionActionTool.Input(action = "set_spend_cap", capCents = null).toVerb()
        assertTrue(cleared is SessionVerb.SetSpendCap)
        assertEquals(null, cleared.capCents)

        val capped = SessionActionTool.Input(action = "set_spend_cap", capCents = 500L).toVerb()
        assertTrue(capped is SessionVerb.SetSpendCap)
        assertEquals(500L, capped.capCents)
    }

    @Test fun forkDecodesOptionalAnchorAndTitle() {
        val verb = SessionActionTool.Input(
            action = "fork",
            anchorMessageId = "m-42",
            newTitle = "branch",
        ).toVerb()
        assertTrue(verb is SessionVerb.Fork)
        assertEquals("m-42", verb.anchorMessageId)
        assertEquals("branch", verb.newTitle)

        // Both anchor + title optional.
        val noAnchor = SessionActionTool.Input(action = "fork").toVerb()
        assertTrue(noAnchor is SessionVerb.Fork)
        assertEquals(null, noAnchor.anchorMessageId)
        assertEquals(null, noAnchor.newTitle)
    }

    @Test fun exportDecodesFormatAndPrettyPrint() {
        val verb = SessionActionTool.Input(
            action = "export",
            format = "markdown",
            prettyPrint = true,
        ).toVerb()
        assertTrue(verb is SessionVerb.Export)
        assertEquals("markdown", verb.format)
        assertEquals(true, verb.prettyPrint)
    }

    @Test fun revertRequiresAnchorAndProjectId() {
        val verb = SessionActionTool.Input(
            action = "revert",
            anchorMessageId = "m-rewind",
            projectId = "p-vlog",
        ).toVerb()
        assertTrue(verb is SessionVerb.Revert)
        assertEquals("m-rewind", verb.anchorMessageId)
        assertEquals("p-vlog", verb.projectId)

        // Both required.
        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "revert", projectId = "p").toVerb()
        }
        assertFailsWith<IllegalArgumentException> {
            SessionActionTool.Input(action = "revert", anchorMessageId = "m").toVerb()
        }
    }

    @Test fun compactDecodesOptionalStrategy() {
        val verb = SessionActionTool.Input(action = "compact", strategy = "prune_only").toVerb()
        assertTrue(verb is SessionVerb.Compact)
        assertEquals("prune_only", verb.strategy)

        val noStrategy = SessionActionTool.Input(action = "compact").toVerb()
        assertTrue(noStrategy is SessionVerb.Compact)
        assertEquals(null, noStrategy.strategy)
    }

    @Test fun unknownActionFailsLoudWithAcceptedList() {
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool.Input(action = "teleport").toVerb()
        }
        assertTrue("teleport" in ex.message.orEmpty(), ex.message)
        // Spot-check 3 verbs are in the accepted-list message — protects
        // against someone trimming the message and dropping verbs.
        val msg = assertNotNull(ex.message)
        assertTrue("archive" in msg)
        assertTrue("revert" in msg)
        assertTrue("compact" in msg)
    }

    @Test fun sealedInterfaceClosure() {
        // Compile-time guard: every verb that the decoder produces is a
        // SessionVerb subtype. If a future verb is added to the decoder
        // without a corresponding sealed-interface subclass, the
        // construction below fails compilation. The sealed marker keeps
        // the family closed at the package boundary.
        val verbs: List<SessionVerb> = listOf(
            SessionVerb.Archive(null),
            SessionVerb.Unarchive(null),
            SessionVerb.Rename(null, "x"),
            SessionVerb.Delete("s"),
            SessionVerb.RemovePermissionRule(null, "p", "q"),
            SessionVerb.Import("e"),
            SessionVerb.SetSystemPrompt(null, null),
            SessionVerb.ExportBusTrace(null, null, null),
            SessionVerb.SetToolEnabled(null, "t", true),
            SessionVerb.SetSpendCap(null, null),
            SessionVerb.Fork(null, null, null),
            SessionVerb.Export(null, null, false),
            SessionVerb.Revert(null, "m", "p"),
            SessionVerb.Compact(null, null),
        )
        assertEquals(14, verbs.size, "phase 1a covers all 14 SessionActionTool verbs")
        // Per-class set: 14 distinct subclasses, no accidental dedup.
        assertEquals(14, verbs.map { it::class }.toSet().size)
    }
}
