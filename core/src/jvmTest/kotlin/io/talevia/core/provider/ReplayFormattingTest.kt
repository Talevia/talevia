package io.talevia.core.provider

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.Timeline
import io.talevia.core.session.Part
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayFormattingTest {

    private val epoch = Instant.fromEpochMilliseconds(0)

    @Test
    fun reasoningIsWrappedForReplay() {
        val reasoning = Part.Reasoning(
            id = PartId("p1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = epoch,
            text = "I should add a fade transition.",
        )
        assertEquals(
            "<prior_reasoning>I should add a fade transition.</prior_reasoning>",
            ReplayFormatting.formatReasoning(reasoning),
        )
    }

    @Test
    fun timelineSnapshotIsEncodedWithHeaderAndProducerCallId() {
        val snap = Part.TimelineSnapshot(
            id = PartId("snap-1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = epoch,
            timeline = Timeline(),
            producedByCallId = CallId("call-42"),
        )
        val rendered = ReplayFormatting.formatTimelineSnapshot(snap)
        assertTrue(rendered.contains("<timeline_snapshot id=\"snap-1\""), "header is present")
        assertTrue(rendered.contains("produced_by=\"call-42\""), "producer call id is present")
        assertTrue(rendered.contains("{"), "timeline JSON body is embedded: $rendered")
        assertTrue(rendered.endsWith("</timeline_snapshot>"), "closing tag present")
    }
}
