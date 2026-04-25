package io.talevia.cli.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the second-word arg completer — `/resume <session-id>`,
 * `/revert <message-id>`, `/fork <message-id>`. The pure
 * [computeArgCandidates] function is the test surface; the JLine glue
 * around it is too thin to need a separate harness.
 */
class SlashCompletionTest {

    private fun sources(
        sessions: List<String> = emptyList(),
        messages: List<String> = emptyList(),
    ) = SlashArgSources(
        sessionIds = { sessions },
        messageIds = { messages },
    )

    @Test fun resumeCompletesFromSessionIdsClippedToTwelveChars() {
        val s = sources(
            sessions = listOf(
                "01234567-89ab-cdef-0123-456789abcdef",
                "fedcba98-7654-3210-fedc-ba9876543210",
            ),
        )
        val out = computeArgCandidates("/resume", currentArg = "", sources = s)
        assertNotNull(out)
        assertEquals(listOf("01234567-89a", "fedcba98-765"), out.map { it.value })
        assertTrue(out.all { it.description == "session id prefix" })
    }

    @Test fun revertAndForkCompleteFromMessageIds() {
        val s = sources(messages = listOf("msg-aaa-bbb-ccc-ddd", "msg-zzz-yyy-xxx-www"))
        val revert = computeArgCandidates("/revert", currentArg = "msg", sources = s)
        val fork = computeArgCandidates("/fork", currentArg = "msg", sources = s)
        assertNotNull(revert)
        assertNotNull(fork)
        assertEquals(2, revert.size)
        assertEquals(2, fork.size)
        assertTrue(revert.all { it.description == "message id prefix" })
        assertTrue(fork.all { it.description == "message id prefix" })
    }

    @Test fun argFiltersByCurrentPrefix() {
        val s = sources(
            sessions = listOf(
                "abc11111-...",
                "abc22222-...",
                "def33333-...",
            ),
        )
        val out = computeArgCandidates("/resume", currentArg = "abc", sources = s)
        assertNotNull(out)
        assertEquals(listOf("abc11111-...", "abc22222-..."), out.map { it.value })
    }

    @Test fun unknownCommandReturnsNull() {
        // null = "no arg completion available, fall back to first-word
        // slash-name completion (or do nothing for non-slash text)".
        assertNull(computeArgCandidates("/help", currentArg = "", sources = sources()))
        assertNull(computeArgCandidates("hello", currentArg = "world", sources = sources()))
    }

    @Test fun emptySourcesReturnsEmptyListNotNull() {
        // Empty != not-applicable. /resume IS a known arg-taking command,
        // we just have nothing to suggest.
        val out = computeArgCandidates("/resume", currentArg = "", sources = sources())
        assertNotNull(out)
        assertTrue(out.isEmpty())
    }

    @Test fun duplicatesAreCollapsedAfterClipping() {
        // Two ids that share the first 12 chars produce one candidate so
        // the menu doesn't show identical-looking duplicates.
        val s = sources(
            sessions = listOf(
                "deadbeef-0001-aaaa-...",
                "deadbeef-0002-bbbb-...",
            ),
        )
        val out = computeArgCandidates("/resume", currentArg = "", sources = s)
        assertNotNull(out)
        assertEquals(1, out.size)
        assertEquals("deadbeef-000", out.single().value)
    }
}
