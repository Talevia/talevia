package io.talevia.core.tool.builtin.video

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [emitTimelineSnapshot] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/TimelineSnapshots.kt:23`.
 * Cycle 279 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-278.
 *
 * `emitTimelineSnapshot` is the one-shot snapshot writer
 * every timeline-mutating tool (clip_action / apply_filter /
 * add_subtitles / add_transition / revert_timeline) calls
 * after committing to ProjectStore. Snapshots are the
 * history stack `revert_timeline` walks — without one per
 * mutation there is nothing to roll back to.
 *
 * Drift signals:
 *   - **Drift to skip the call when timeline is empty**
 *     would silently miss revert anchors for the "clear
 *     everything" mutation case.
 *   - **Drift in producedByCallId / messageId / sessionId**
 *     would mis-attribute snapshots to the wrong message,
 *     silently breaking the LLM's `revert_timeline
 *     <partId>` chain.
 *   - **Drift to call emitPart twice** would corrupt the
 *     history stack with phantom snapshots.
 *
 * Pins three correctness contracts:
 *
 *  1. **Single emitPart call producing a `Part.TimelineSnapshot`**.
 *     Drift to wrong Part type or multiple calls would
 *     break the snapshot semantic.
 *
 *  2. **Returned PartId matches the emitted snapshot's id**.
 *     Drift to "return a fresh id" would mis-route the
 *     LLM's revert handle.
 *
 *  3. **Context fields echo verbatim** — sessionId,
 *     messageId, callId (as `producedByCallId`) all from
 *     ToolContext. Plus `timeline` arg echoed and
 *     `createdAt` from the injected clock.
 */
class EmitTimelineSnapshotTest {

    private val sid = SessionId("session-1")
    private val mid = MessageId("message-1")
    private val cid = CallId("call-1")

    private fun fixedClock(epochMs: Long) = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    private class CapturingContext(sid: SessionId, mid: MessageId, cid: CallId) {
        val emitted = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = sid,
            messageId = mid,
            callId = cid,
            askPermission = { error("askPermission unused") },
            emitPart = { p -> emitted += p },
            messages = emptyList(),
        )
    }

    private fun emptyTimeline(): Timeline = Timeline(tracks = emptyList())

    private fun timelineWithVideoTrack(): Timeline = Timeline(
        tracks = listOf(Track.Video(id = TrackId("track-1"))),
    )

    // ── 1. Single emitPart call producing TimelineSnapshot ──

    @Test fun emitsExactlyOnePartOfTimelineSnapshotType() = runTest {
        // Marquee single-emit pin: drift to call emitPart
        // twice (or zero times) would corrupt the history
        // stack.
        val cap = CapturingContext(sid, mid, cid)
        emitTimelineSnapshot(cap.ctx, emptyTimeline())
        assertEquals(
            1,
            cap.emitted.size,
            "emitTimelineSnapshot MUST call emitPart exactly once; got: ${cap.emitted.size}",
        )
        assertTrue(
            cap.emitted.single() is Part.TimelineSnapshot,
            "the emitted Part MUST be a Part.TimelineSnapshot; got: ${cap.emitted.single()::class.simpleName}",
        )
    }

    // ── 2. Returned PartId matches emitted snapshot's id ────

    @Test fun returnedPartIdMatchesEmittedPartId() {
        // Marquee identity pin: drift to "return a fresh id"
        // would mis-route the LLM's revert_timeline <partId>
        // call.
        val cap = CapturingContext(sid, mid, cid)
        val returnedId = kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline())
        }
        val emittedPart = cap.emitted.single() as Part.TimelineSnapshot
        assertEquals(
            returnedId,
            emittedPart.id,
            "emitTimelineSnapshot MUST return the same PartId it emitted",
        )
    }

    @Test fun returnedPartIdIsNonBlank() {
        val cap = CapturingContext(sid, mid, cid)
        val returnedId = kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline())
        }
        assertTrue(
            returnedId.value.isNotBlank(),
            "returned PartId.value MUST be non-blank (UUID format)",
        )
    }

    // ── 3. Context fields echo into the snapshot ────────────

    @Test fun snapshotEchoesSessionIdAndMessageId() {
        val cap = CapturingContext(sid, mid, cid)
        kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline())
        }
        val snap = cap.emitted.single() as Part.TimelineSnapshot
        assertEquals(sid, snap.sessionId, "sessionId MUST echo ctx.sessionId")
        assertEquals(mid, snap.messageId, "messageId MUST echo ctx.messageId")
    }

    @Test fun snapshotProducedByCallIdEchoesCtxCallId() {
        // Marquee pin: producedByCallId is what the LLM
        // sees in `Part.TimelineSnapshot.preview()` so it
        // can match the snapshot back to the tool call that
        // produced it. Drift to a different field or to a
        // generated UUID would break that link.
        val cap = CapturingContext(sid, mid, cid)
        kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline())
        }
        val snap = cap.emitted.single() as Part.TimelineSnapshot
        assertEquals(
            cid,
            snap.producedByCallId,
            "snapshot.producedByCallId MUST echo ctx.callId",
        )
    }

    @Test fun snapshotEchoesTimelineArg() {
        // Pin: drift to substitute a different timeline
        // (e.g. always emit empty) would silently corrupt
        // the snapshot stack.
        val cap = CapturingContext(sid, mid, cid)
        val tl = timelineWithVideoTrack()
        kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, tl)
        }
        val snap = cap.emitted.single() as Part.TimelineSnapshot
        assertEquals(
            tl,
            snap.timeline,
            "snapshot.timeline MUST echo the arg verbatim",
        )
    }

    @Test fun snapshotCreatedAtComesFromInjectedClock() {
        // Marquee deterministic-time pin: per the function
        // signature, `clock` defaults to `Clock.System` but
        // tests pass a fixed clock. Drift to ignore the
        // clock arg (e.g. always Clock.System.now()) would
        // surface here as the createdAt being "now"
        // instead of the fixed epochMs.
        val cap = CapturingContext(sid, mid, cid)
        val fixedEpochMs = 1_700_000_000_000L
        kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline(), fixedClock(fixedEpochMs))
        }
        val snap = cap.emitted.single() as Part.TimelineSnapshot
        assertEquals(
            Instant.fromEpochMilliseconds(fixedEpochMs),
            snap.createdAt,
            "snapshot.createdAt MUST come from the injected clock",
        )
    }

    @Test fun consecutiveCallsProduceDistinctPartIds() {
        // Pin: each call generates a fresh UUID-based
        // PartId — drift to a fixed/cached id would let
        // multiple snapshots collide on the same revert
        // anchor.
        val cap1 = CapturingContext(sid, mid, cid)
        val cap2 = CapturingContext(sid, mid, cid)
        val id1 = kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap1.ctx, emptyTimeline())
        }
        val id2 = kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap2.ctx, emptyTimeline())
        }
        assertNotNull(id1)
        assertNotNull(id2)
        assertTrue(
            id1 != id2,
            "consecutive emitTimelineSnapshot calls MUST produce distinct PartIds; got identical: $id1",
        )
    }

    @Test fun emptyTimelineStillProducesValidSnapshot() {
        // Edge: empty timeline is a valid snapshot (the
        // "clear everything" mutation case). Drift to skip
        // emit when timeline.tracks is empty would silently
        // lose revert anchors for that case.
        val cap = CapturingContext(sid, mid, cid)
        val returnedId = kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline())
        }
        assertEquals(1, cap.emitted.size)
        assertEquals(returnedId, (cap.emitted.single() as Part.TimelineSnapshot).id)
    }

    @Test fun snapshotCompactedAtIsNullByDefault() {
        // Pin: per Part base class default, compactedAt is
        // null at emit time. Compaction is a separate pass
        // that flips this field; the emit path itself MUST
        // NOT pre-set it.
        val cap = CapturingContext(sid, mid, cid)
        kotlinx.coroutines.runBlocking {
            emitTimelineSnapshot(cap.ctx, emptyTimeline())
        }
        val snap = cap.emitted.single() as Part.TimelineSnapshot
        assertEquals(
            null,
            snap.compactedAt,
            "snapshot.compactedAt MUST default to null at emit (compaction pass flips it later)",
        )
    }
}
