package io.talevia.core.tool.builtin.meta

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EstimateTokensToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun estimatesShortText() = runTest {
        val result = EstimateTokensTool().execute(
            EstimateTokensTool.Input(text = "hello"),
            ctx(),
        )
        assertTrue(result.data.tokens > 0)
        assertEquals(5, result.data.characters)
    }

    @Test fun longerTextGetsMoreTokens() = runTest {
        val text = "x".repeat(1000)
        val result = EstimateTokensTool().execute(
            EstimateTokensTool.Input(text = text),
            ctx(),
        )
        // (1000 + 3) / 4 = 250 — matches TokenEstimator.forText.
        assertEquals(250, result.data.tokens)
        assertEquals(1000, result.data.characters)
    }

    @Test fun blankTextFailsLoudly() = runTest {
        val empty = assertFailsWith<IllegalArgumentException> {
            EstimateTokensTool().execute(EstimateTokensTool.Input(text = ""), ctx())
        }
        assertTrue(empty.message?.contains("text") == true, "message should mention `text`: ${empty.message}")

        val whitespace = assertFailsWith<IllegalArgumentException> {
            EstimateTokensTool().execute(EstimateTokensTool.Input(text = "   \n\t"), ctx())
        }
        assertTrue(whitespace.message?.contains("text") == true)
    }

    @Test fun outputTitleAndSummaryMentionTokenCount() = runTest {
        val result = EstimateTokensTool().execute(
            EstimateTokensTool.Input(text = "hello world foo bar baz quux"),
            ctx(),
        )
        val tokens = result.data.tokens
        assertTrue(result.title.contains(tokens.toString()), "title should mention token count: ${result.title}")
        assertTrue(
            result.outputForLlm.contains(tokens.toString()),
            "outputForLlm should mention token count: ${result.outputForLlm}",
        )
    }

    @Test fun approxCharsPerTokenIsComputed() = runTest {
        val result = EstimateTokensTool().execute(
            EstimateTokensTool.Input(text = "x".repeat(1000)),
            ctx(),
        )
        // characters=1000, tokens=250 → ratio = 4.0.
        assertEquals(4.0, result.data.approxCharsPerToken, "ratio should be ~4 chars/token")
        // Sanity: ratio is in the range [1, 8] for any reasonable ASCII text.
        assertTrue(result.data.approxCharsPerToken in 1.0..8.0)
    }
}
