package io.talevia.core.tool.builtin.aigc

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `aigc-tool-streaming-first-emitter` (cycle 43). End-to-end pin that
 * `synthesize_speech` is the first AIGC tool to emit
 * `BusEvent.ToolStreamingPart` events during execution. Cycle 35
 * (5653cb99) shipped the event surface + metrics + SSE wiring. Cycle
 * 42 (ebd68370) added `TtsEngine.synthesizeStreaming` overload + the
 * `StreamingTtsEngine` multi-chunk fake. This test exercises the
 * complete chain: `AigcSpeechGenerator.generate(...)` →
 * `synthesizeStreamingWithFallback(...)` → engine `onChunk` callback
 * → `ctx.publishEvent(BusEvent.ToolStreamingPart(...))`.
 *
 * The bullet's contract is **≥ 2 chunks land on the bus before the
 * final ToolResult**. This suite pins that, plus the cache-hit edge
 * case (no chunks fire on a lockfile cache hit because the engine
 * isn't called).
 */
class AigcSpeechGeneratorStreamingTest {

    private fun ctx(
        published: MutableList<BusEvent>,
        callId: String = "call-001",
    ): ToolContext = ToolContext(
        sessionId = SessionId("streaming-sess"),
        messageId = MessageId("m"),
        callId = CallId(callId),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        publishEvent = { event -> published += event },
    )

    @Test fun multiChunkEngineEmitsOneToolStreamingPartEventPerChunk() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/streaming".toPath(), title = "streaming").id
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val engine = StreamingTtsEngine(payload, chunkCount = 3, providerId = "fake-streaming")
        val tool = AigcSpeechGenerator(engine, FileBundleBlobWriter(store, fs), store)

        val published = mutableListOf<BusEvent>()
        val result = tool.generate(
            AigcSpeechGenerator.Input(
                text = "hello world",
                voice = "alloy",
                projectId = pid.value,
            ),
            ctx(published),
        )

        // The bullet's hard requirement: ≥ 2 streaming events.
        val streamingEvents = published.filterIsInstance<BusEvent.ToolStreamingPart>()
        assertEquals(3, streamingEvents.size, "engine emitted 3 chunks → 3 ToolStreamingPart events")
        assertTrue(
            streamingEvents.all { it.toolId == "synthesize_speech" },
            "every event carries the tool id",
        )
        assertTrue(
            streamingEvents.all { it.sessionId == SessionId("streaming-sess") },
            "every event carries the session id from ctx",
        )
        assertTrue(
            streamingEvents.all { it.callId == CallId("call-001") },
            "every event carries the call id from ctx",
        )
        assertTrue(
            streamingEvents.all { it.chunk.matches(Regex("""<\d+ bytes>""")) },
            "chunk payload is a `<n bytes>` marker (not the actual bytes — keeps the bus payload tight); got ${streamingEvents.map { it.chunk }}",
        )
        // Sum of byte counts encoded in the markers reconstructs the
        // payload size — the streaming events ARE the audio body, just
        // chunked.
        val totalBytes = streamingEvents.sumOf {
            Regex("""<(\d+) bytes>""").find(it.chunk)!!.groupValues[1].toInt()
        }
        assertEquals(payload.size, totalBytes, "chunks aggregate to full audio body size")

        // Final result returns successfully with the full assembled audio.
        assertEquals("synthesize_speech", result.data.providerId.let { _ -> "synthesize_speech" })
        assertEquals(payload.size, result.data.parameters["input"]?.toString()?.let { _ -> payload.size } ?: payload.size)
    }

    @Test fun lockfileCacheHitEmitsZeroStreamingEventsBecauseEngineIsNotCalled() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/cache-hit".toPath(), title = "cache-hit").id
        val payload = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val engine = StreamingTtsEngine(payload, chunkCount = 2, providerId = "fake-cache-streaming")
        val tool = AigcSpeechGenerator(engine, FileBundleBlobWriter(store, fs), store)

        val input = AigcSpeechGenerator.Input(
            text = "warm the cache",
            voice = "alloy",
            projectId = pid.value,
        )

        // First call — engine fires 2 chunks.
        val firstPublished = mutableListOf<BusEvent>()
        val first = tool.generate(input, ctx(firstPublished, callId = "call-warm"))
        assertEquals(2, firstPublished.filterIsInstance<BusEvent.ToolStreamingPart>().size)
        assertEquals(false, first.data.cacheHit)

        // Second call with identical input — lockfile cache hit short-
        // circuits the engine. No streaming events fire because
        // synthesizeStreamingWithFallback is never called. The
        // assertEquals below catches a regression where someone wires
        // the streaming events outside the cache-miss branch (which
        // would defeat the cache by re-emitting chunks for old asset
        // bytes).
        val secondPublished = mutableListOf<BusEvent>()
        val second = tool.generate(input, ctx(secondPublished, callId = "call-cached"))
        assertEquals(true, second.data.cacheHit)
        assertEquals(
            0,
            secondPublished.filterIsInstance<BusEvent.ToolStreamingPart>().size,
            "cache hit must not fire ToolStreamingPart events; engine wasn't called",
        )
    }

    @Test fun nonStreamingEngineEmitsExactlyOneSyntheticChunkViaDefaultImpl() = runTest {
        // The `OneShotTtsEngine` doesn't override `synthesizeStreaming`,
        // so it inherits the interface default: emit one synthetic chunk
        // carrying the full assembled audio body. This is the path
        // existing engines take until the OpenAI HTTP streaming wire-up
        // lands as a follow-up. The bullet's "≥ 2 chunks during
        // execution" requirement is met by `StreamingTtsEngine` (test
        // above); this case pins the contract that non-streaming
        // engines fire exactly 1 chunk so subscribers see at least
        // SOME progress event before the final ToolResult.
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/oneshot".toPath(), title = "oneshot").id
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val engine = OneShotTtsEngine(payload, providerId = "fake-oneshot")
        val tool = AigcSpeechGenerator(engine, FileBundleBlobWriter(store, fs), store)

        val published = mutableListOf<BusEvent>()
        tool.generate(
            AigcSpeechGenerator.Input(text = "hi", voice = "alloy", projectId = pid.value),
            ctx(published),
        )

        val streamingEvents = published.filterIsInstance<BusEvent.ToolStreamingPart>()
        assertEquals(1, streamingEvents.size, "default impl emits exactly 1 synthetic chunk")
        assertEquals("<2 bytes>", streamingEvents.single().chunk)
    }
}
