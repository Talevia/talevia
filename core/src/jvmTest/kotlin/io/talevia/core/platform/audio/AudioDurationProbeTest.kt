package io.talevia.core.platform.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [probeAudioDurationMs] / [probeMp3DurationMs]. Builds
 * synthetic MP3 byte streams (no real encoder dependency) by writing
 * MPEG-1 Layer III frames with known headers, then asserts the probe
 * recovers a plausible duration.
 *
 * Edges (§3a #9):
 *  - non-MP3 format → null.
 *  - too-short byte stream → null.
 *  - CBR MP3 with N frames → duration ≈ frame × samples/frame /
 *    sampleRate (within 1 frame tolerance).
 *  - Xing VBR tag with explicit frame count → duration = framecount ×
 *    1152 / sampleRate exactly (VBR path trusts the tag).
 *  - ID3v2 prefix → probe skips the tag and still finds the sync word.
 *  - free-format bitrate (bitrateIdx=0) → null (we can't estimate CBR
 *    without a bitrate).
 */
class AudioDurationProbeTest {

    /**
     * MPEG-1 Layer III header with 128 kbps / 44100 Hz / no padding.
     * Bits (high → low, 32-bit big-endian):
     *   sync(11) = 0xFFE
     *   version(2) = 11 (MPEG-1)
     *   layer(2)   = 01 (Layer III)
     *   crc(1)     = 1  (no CRC)
     *   bitrate(4) = 1001 (128 kbps at MPEG-1 L3)
     *   samplerate(2) = 00 (44100)
     *   padding(1) = 0
     *   private(1) = 0
     *   channel(2) = 00 (stereo)
     *   mode_ext(2) = 00
     *   copyright(1) = 0
     *   original(1)  = 1
     *   emphasis(2)  = 00
     */
    private val cbrHeader = byteArrayOf(
        0xFF.toByte(),
        0xFB.toByte(),
        0x90.toByte(),
        0x04.toByte(),
    )

    /** Frame size (bytes) for 128 kbps / 44100 Hz / no padding: 144 * 128000 / 44100 = 417 */
    private val cbrFrameSize = 417

    private fun makeCbrMp3(frameCount: Int): ByteArray {
        val buf = ByteArray(frameCount * cbrFrameSize)
        for (f in 0 until frameCount) {
            val offset = f * cbrFrameSize
            cbrHeader.copyInto(buf, offset)
            // Rest of the frame stays zero — we only need the header to be
            // correct for the probe, not the payload to decode.
        }
        return buf
    }

    /**
     * Build an MP3 whose first frame carries a Xing tag declaring the
     * given frame count. 120 bytes of frame body before the tag is
     * enough slack for any version/layer layout.
     */
    private fun makeVbrXingMp3(frameCount: Int): ByteArray {
        // Single frame sized the same as CBR for convenience.
        val buf = ByteArray(cbrFrameSize * 2).also { cbrHeader.copyInto(it, 0) }
        val xingStart = 36
        // Xing tag
        buf[xingStart + 0] = 'X'.code.toByte()
        buf[xingStart + 1] = 'i'.code.toByte()
        buf[xingStart + 2] = 'n'.code.toByte()
        buf[xingStart + 3] = 'g'.code.toByte()
        // Flags: bit 0 = frames present.
        buf[xingStart + 4] = 0x00
        buf[xingStart + 5] = 0x00
        buf[xingStart + 6] = 0x00
        buf[xingStart + 7] = 0x01
        // Frame count (big-endian).
        buf[xingStart + 8] = ((frameCount shr 24) and 0xFF).toByte()
        buf[xingStart + 9] = ((frameCount shr 16) and 0xFF).toByte()
        buf[xingStart + 10] = ((frameCount shr 8) and 0xFF).toByte()
        buf[xingStart + 11] = (frameCount and 0xFF).toByte()
        // Second frame header so CBR fallback would also see a second
        // frame if the Xing parse misfired — the test asserts the VBR
        // path wins, so this also guards the precedence.
        cbrHeader.copyInto(buf, cbrFrameSize)
        return buf
    }

    @Test fun nonMp3FormatReturnsNull() {
        assertNull(probeAudioDurationMs(byteArrayOf(1, 2, 3), "wav"))
        assertNull(probeAudioDurationMs(byteArrayOf(1, 2, 3), "flac"))
        assertNull(probeAudioDurationMs(byteArrayOf(1, 2, 3), "opus"))
    }

    @Test fun tooShortBytesReturnNull() {
        assertNull(probeAudioDurationMs(ByteArray(0), "mp3"))
        assertNull(probeAudioDurationMs(ByteArray(3), "mp3"))
    }

    @Test fun cbr128kbpsApproximatesFromPayloadLength() {
        // 100 frames × 417 bytes = 41 700 bytes payload.
        // CBR estimator: 41700 * 1000 / (128000 / 8) = 41700 * 1000 / 16000
        //              = 2606 ms.
        val bytes = makeCbrMp3(frameCount = 100)
        val ms = probeAudioDurationMs(bytes, "mp3")
        assertNotNull(ms)
        // CBR estimator result is deterministic given the layout above.
        assertEquals(2606L, ms)
    }

    @Test fun vbrXingTagIsExact() {
        // MPEG-1 Layer III: 1152 samples/frame, 44100 Hz → 26.122… ms/frame.
        // 1000 frames → 1000 * 1152 * 1000 / 44100 = 26122 ms.
        val bytes = makeVbrXingMp3(frameCount = 1000)
        val ms = probeAudioDurationMs(bytes, "mp3")
        assertNotNull(ms)
        assertEquals(26122L, ms)
    }

    @Test fun id3v2TagBeforeAudioSkipsCorrectly() {
        // Build a minimal ID3v2 header with 40 bytes of body, then MP3.
        val tagBody = 40
        val id3 = ByteArray(10 + tagBody)
        id3[0] = 'I'.code.toByte()
        id3[1] = 'D'.code.toByte()
        id3[2] = '3'.code.toByte()
        id3[3] = 0x03 // ver hi
        id3[4] = 0x00 // ver lo
        id3[5] = 0x00 // flags (no footer)
        // Size = 40 as syncsafe int (each byte holds 7 bits).
        id3[6] = 0
        id3[7] = 0
        id3[8] = 0
        id3[9] = tagBody.toByte()
        val mp3 = makeCbrMp3(frameCount = 10)
        val combined = id3 + mp3
        val ms = probeAudioDurationMs(combined, "mp3")
        assertNotNull(ms)
        // 10 frames × 417 bytes / (128000/8 bytes/s) = 260.625 ms → 260.
        assertTrue(ms in 250..270, "expected ~260ms for 10 CBR frames; got $ms")
    }

    @Test fun freeFormatBitrateReturnsNull() {
        // bitrate index 0 = free format. Probe should give up.
        val header = byteArrayOf(
            0xFF.toByte(),
            0xFB.toByte(),
            0x00.toByte(), // bitrate bits 1111 0000 → 0
            0x04.toByte(),
        )
        val bytes = ByteArray(1000).also { header.copyInto(it, 0) }
        assertNull(probeAudioDurationMs(bytes, "mp3"))
    }

    @Test fun missingSyncReturnsNull() {
        // All-zero payload (no 0xFFE sync anywhere).
        val bytes = ByteArray(1000)
        assertNull(probeAudioDurationMs(bytes, "mp3"))
    }
}
