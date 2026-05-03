package io.talevia.core.platform

import io.talevia.core.tool.builtin.aigc.OneShotTtsEngine
import io.talevia.core.tool.builtin.aigc.StreamingTtsEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the [TtsEngine.synthesizeStreaming] contract introduced by
 * `streaming-engine-api-tts-overload` (cycle 42). The interface ships
 * with a default impl that emits exactly one synthetic chunk (the full
 * assembled blob); streaming-capable engines can override to emit
 * multiple chunks during the response.
 *
 * Why this test exists: `aigc-tool-streaming-first-emitter` will wire
 * `AigcSpeechGenerator.generate(...)` to call `synthesizeStreaming`,
 * forwarding each chunk to a `BusEvent.ToolStreamingPart` event. The
 * integration test there asserts ≥ 2 events land on the bus before the
 * final ToolResult — but only if the contract here is sound (chunks
 * fire BEFORE the result returns + chunk concatenation reconstructs
 * the audio body). Catching contract drift here keeps the integration
 * test honest.
 */
class TtsEngineStreamingContractTest {

    private val request = TtsRequest(
        text = "the quick brown fox",
        modelId = "tts-1",
        voice = "alloy",
    )

    @Test fun defaultImplEmitsExactlyOneSyntheticChunkCarryingAssembledBlob() = runTest {
        val expectedBytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x11, 0x22, 0x33)
        val engine = OneShotTtsEngine(expectedBytes, providerId = "default-impl-fake")

        val chunks = mutableListOf<ByteArray>()
        val result = engine.synthesizeStreaming(request, onChunk = { chunks += it })

        assertEquals(1, chunks.size, "default impl must emit exactly one chunk")
        assertContentEquals(expectedBytes, chunks.single(), "synthetic chunk carries the full assembled blob")
        assertContentEquals(expectedBytes, result.audio.audioBytes, "result audio matches what was emitted")
        assertEquals("default-impl-fake", result.provenance.providerId)
    }

    @Test fun streamingImplEmitsMultipleChunksAndReassemblesToAudioBody() = runTest {
        // 9 bytes split into 3 chunks of 3 = mechanical even split.
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        val engine = StreamingTtsEngine(payload, chunkCount = 3)

        val chunks = mutableListOf<ByteArray>()
        val result = engine.synthesizeStreaming(request, onChunk = { chunks += it })

        assertEquals(3, chunks.size, "streaming impl must emit exactly 3 chunks for chunkCount=3")
        // Concatenation reconstructs the full audio body — the integration
        // test downstream relies on this invariant when verifying that
        // `BusEvent.ToolStreamingPart` chunks add up to the final asset.
        val reassembled = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertContentEquals(payload, reassembled)
        assertContentEquals(payload, result.audio.audioBytes)
    }

    @Test fun streamingImplFiresChunksBeforeReturningTheResult() = runTest {
        // The contract is "callbacks during the call, result after". A
        // wired tool subscribes to chunks and emits BusEvent.ToolStreamingPart
        // per chunk; if the impl buffered + emitted chunks AFTER the result,
        // subscribers would see "ToolResult arrives, then chunks" — wrong
        // ordering breaks the streaming UX.
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val engine = StreamingTtsEngine(payload, chunkCount = 2)

        var resultReturned = false
        var chunksFiredBeforeResult = 0
        engine.synthesizeStreaming(
            request,
            onChunk = {
                if (!resultReturned) chunksFiredBeforeResult += 1
            },
        )
        resultReturned = true

        assertEquals(2, chunksFiredBeforeResult, "all chunks must fire before the call returns")
    }

    @Test fun unevenChunkSplitDistributesBytesAcrossChunks() = runTest {
        // 7 bytes / 3 chunks → ceil(7/3) = 3 per chunk → chunks of [3, 3, 1].
        val payload = byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte())
        val engine = StreamingTtsEngine(payload, chunkCount = 3)

        val chunks = mutableListOf<ByteArray>()
        engine.synthesizeStreaming(request, onChunk = { chunks += it })

        assertEquals(3, chunks.size)
        assertEquals(3, chunks[0].size)
        assertEquals(3, chunks[1].size)
        assertEquals(1, chunks[2].size, "tail chunk holds the remainder")
        // Reassembly invariant still holds with uneven split.
        val reassembled = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertContentEquals(payload, reassembled)
    }

    @Test fun emptyAudioBodyEmitsSingleEmptyChunk() = runTest {
        // Edge: provider returned no audio (some failure modes do this; the
        // streaming path shouldn't loop infinitely or skip the callback).
        // chunkCount=1 + empty bytes is the only valid combo (the ctor
        // require() rejects empty bytes with chunkCount > 1).
        val engine = StreamingTtsEngine(ByteArray(0), chunkCount = 1)

        val chunks = mutableListOf<ByteArray>()
        engine.synthesizeStreaming(request, onChunk = { chunks += it })

        assertEquals(1, chunks.size)
        assertEquals(0, chunks.single().size)
    }

    @Test fun rejectsZeroChunkCountAtConstruction() {
        // A 0-chunk engine would never call onChunk — that violates the
        // "at least one chunk per call" contract the integration test
        // depends on. Caught at construction with require().
        val ex = runCatching {
            StreamingTtsEngine(ByteArray(4), chunkCount = 0)
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IAE, got $ex")
    }
}
