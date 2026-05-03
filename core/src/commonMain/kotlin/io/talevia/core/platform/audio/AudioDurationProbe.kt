package io.talevia.core.platform.audio

/**
 * Best-effort, pure-Kotlin audio duration probe. Returns duration in
 * milliseconds or null when the format / content isn't recognised.
 *
 * Motivation: the TTS pipeline (OpenAI → AigcSpeechGenerator) writes
 * `MediaMetadata.duration = Duration.ZERO` on every produced audio
 * asset because commonMain has no portable audio probe and the TTS
 * endpoint doesn't echo a duration. That leaves every "how long is
 * this vlog?" query stuck at 0, blocks audio-only timeline estimation,
 * and generally poisons spend-summary UX ("cost / second" is undefined).
 *
 * Scope for the first pass: **MP3 CBR only** — the default format
 * OpenAI TTS uses, and by far the common case. The parser
 *  1. scans for the MP3 sync word `0xFFE`,
 *  2. reads version / layer / bitrate / sample-rate from the 4-byte
 *     header,
 *  3. if a Xing / Info VBR tag is present, trusts the embedded frame
 *     count (exact),
 *  4. otherwise assumes CBR and estimates
 *     `duration = payloadBytes / (bitrate * 1000 / 8)` (within ~1 % of
 *     true on genuine CBR mp3).
 *
 * Non-MP3 formats (wav / aac / opus / flac / pcm) fall through to
 * null — follow-ups would add platform-specific probes via `expect /
 * actual` or pure-Kotlin container parsers as demand arises. Null
 * preserves the pre-change behaviour (caller leaves duration at zero)
 * without pretending an estimate it can't produce.
 */
internal fun probeAudioDurationMs(bytes: ByteArray, format: String): Long? {
    val lowered = format.trim().lowercase()
    return when (lowered) {
        "mp3", "mpeg", "audio/mpeg" -> probeMp3DurationMs(bytes)
        else -> null
    }
}

/**
 * MP3 frame-aware duration estimator. Returns null when bytes don't
 * contain a recognisable MPEG-1 Layer III header. The VBR branch
 * trusts the Xing/Info tag's frame count (exact); the CBR branch
 * estimates from header bitrate.
 */
internal fun probeMp3DurationMs(bytes: ByteArray): Long? {
    if (bytes.size < 10) return null

    // Skip a leading ID3v2 tag if present so the sync search starts
    // at real audio.
    val scanStart = id3v2SkipLength(bytes)
    if (scanStart >= bytes.size) return null

    val syncIdx = findMp3Sync(bytes, scanStart) ?: return null
    if (syncIdx + 4 > bytes.size) return null

    val h0 = bytes[syncIdx].toInt() and 0xFF
    val h1 = bytes[syncIdx + 1].toInt() and 0xFF
    val h2 = bytes[syncIdx + 2].toInt() and 0xFF
    val h3 = bytes[syncIdx + 3].toInt() and 0xFF

    // Bits 19-20 of header: MPEG audio version (11 = MPEG-1, 10 = MPEG-2, 00 = MPEG-2.5)
    val versionBits = (h1 shr 3) and 0x3
    // Bits 17-18: layer (01 = Layer III)
    val layerBits = (h1 shr 1) and 0x3
    if (versionBits == 1) return null // reserved
    if (layerBits == 0) return null // reserved

    val bitrateIdx = (h2 shr 4) and 0xF
    val sampleRateIdx = (h2 shr 2) and 0x3
    if (bitrateIdx == 0 || bitrateIdx == 0xF) return null // free/reserved
    if (sampleRateIdx == 3) return null // reserved

    val bitrateKbps = mp3BitrateKbps(versionBits, layerBits, bitrateIdx) ?: return null
    val sampleRate = mp3SampleRate(versionBits, sampleRateIdx) ?: return null
    val samplesPerFrame = mp3SamplesPerFrame(versionBits, layerBits) ?: return null

    // Xing / Info VBR header check — at a layout-dependent offset from
    // the sync word. For MPEG-1 stereo / joint-stereo it sits at +36;
    // mono / MPEG-2 / MPEG-2.5 shift. We just search the frame payload
    // for the literal "Xing" / "Info" 4-byte signature within the next
    // ~160 bytes which comfortably covers every MPEG variant's layout.
    val xingFrameCount = readXingFrameCount(bytes, syncIdx)
    if (xingFrameCount != null && xingFrameCount > 0) {
        val ms = xingFrameCount.toLong() * samplesPerFrame.toLong() * 1000L / sampleRate.toLong()
        return ms
    }

    // CBR fallback: bytes from the first frame to EOF / bitrate.
    val payloadBytes = bytes.size - syncIdx
    val bytesPerSec = bitrateKbps * 1000 / 8
    if (bytesPerSec <= 0) return null
    return payloadBytes.toLong() * 1000L / bytesPerSec.toLong()
}

/**
 * Return the length of a leading ID3v2 tag (header + body + footer) if
 * one exists; 0 otherwise. ID3v2 header: "ID3" + ver (2 bytes) + flags
 * (1) + size (4 × 7-bit syncsafe).
 */
private fun id3v2SkipLength(bytes: ByteArray): Int {
    if (bytes.size < 10) return 0
    if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return 0
    val flags = bytes[5].toInt() and 0xFF
    val size = ((bytes[6].toInt() and 0x7F) shl 21) or
        ((bytes[7].toInt() and 0x7F) shl 14) or
        ((bytes[8].toInt() and 0x7F) shl 7) or
        (bytes[9].toInt() and 0x7F)
    val footer = if ((flags and 0x10) != 0) 10 else 0
    return 10 + size + footer
}

private fun findMp3Sync(bytes: ByteArray, fromIndex: Int): Int? {
    var i = fromIndex
    while (i < bytes.size - 1) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return i
        i++
    }
    return null
}

private fun mp3BitrateKbps(versionBits: Int, layerBits: Int, bitrateIdx: Int): Int? {
    // MPEG-1 Layer III (011, 01): bitrate table (kbps), index 1..14
    val mpeg1L3 = intArrayOf(-1, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    // MPEG-2 / MPEG-2.5 Layer III (010 / 000, 01)
    val mpeg2L3 = intArrayOf(-1, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    val table = when {
        versionBits == 3 && layerBits == 1 -> mpeg1L3
        (versionBits == 2 || versionBits == 0) && layerBits == 1 -> mpeg2L3
        else -> return null // other layers unsupported in this first pass
    }
    if (bitrateIdx !in 1..14) return null
    return table[bitrateIdx]
}

private fun mp3SampleRate(versionBits: Int, sampleRateIdx: Int): Int? {
    // MPEG-1: 44100 / 48000 / 32000
    val mpeg1 = intArrayOf(44100, 48000, 32000)
    // MPEG-2 (010): 22050 / 24000 / 16000
    val mpeg2 = intArrayOf(22050, 24000, 16000)
    // MPEG-2.5 (000): 11025 / 12000 / 8000
    val mpeg25 = intArrayOf(11025, 12000, 8000)
    val table = when (versionBits) {
        3 -> mpeg1
        2 -> mpeg2
        0 -> mpeg25
        else -> return null
    }
    if (sampleRateIdx !in 0..2) return null
    return table[sampleRateIdx]
}

private fun mp3SamplesPerFrame(versionBits: Int, layerBits: Int): Int? {
    // Layer III: MPEG-1 = 1152 samples/frame, MPEG-2/2.5 = 576.
    // We only serve Layer III today.
    if (layerBits != 1) return null
    return if (versionBits == 3) 1152 else 576
}

/**
 * Hunt for the Xing / Info tag in the first frame's body. Returns the
 * frame-count field when the `frames` bit is set; null otherwise.
 *
 * Xing layout (from the reference): 4-byte tag ("Xing" or "Info"),
 * 4-byte flags (bit 0 = frames present), then 4-byte frame count if
 * the flag is set.
 */
private fun readXingFrameCount(bytes: ByteArray, syncIdx: Int): Int? {
    val searchStart = syncIdx + 4
    val searchEnd = minOf(bytes.size - 8, syncIdx + 180)
    var i = searchStart
    while (i <= searchEnd) {
        val ok = (bytes[i] == 'X'.code.toByte() &&
            bytes[i + 1] == 'i'.code.toByte() &&
            bytes[i + 2] == 'n'.code.toByte() &&
            bytes[i + 3] == 'g'.code.toByte()) ||
            (bytes[i] == 'I'.code.toByte() &&
                bytes[i + 1] == 'n'.code.toByte() &&
                bytes[i + 2] == 'f'.code.toByte() &&
                bytes[i + 3] == 'o'.code.toByte())
        if (ok) {
            if (i + 12 >= bytes.size) return null
            val flags = ((bytes[i + 4].toInt() and 0xFF) shl 24) or
                ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                ((bytes[i + 6].toInt() and 0xFF) shl 8) or
                (bytes[i + 7].toInt() and 0xFF)
            if ((flags and 0x1) == 0) return null
            val frames = ((bytes[i + 8].toInt() and 0xFF) shl 24) or
                ((bytes[i + 9].toInt() and 0xFF) shl 16) or
                ((bytes[i + 10].toInt() and 0xFF) shl 8) or
                (bytes[i + 11].toInt() and 0xFF)
            return frames
        }
        i++
    }
    return null
}
