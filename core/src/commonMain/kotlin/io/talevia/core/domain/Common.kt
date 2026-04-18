package io.talevia.core.domain

import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class TimeRange(
    @Serializable(with = DurationSerializer::class) val start: Duration,
    @Serializable(with = DurationSerializer::class) val duration: Duration,
) {
    @Serializable(with = DurationSerializer::class)
    val end: Duration get() = start + duration
}

@Serializable
data class FrameRate(val numerator: Int, val denominator: Int = 1) {
    val fps: Double get() = numerator.toDouble() / denominator
    companion object {
        val FPS_24 = FrameRate(24)
        val FPS_30 = FrameRate(30)
        val FPS_60 = FrameRate(60)
    }
}

@Serializable
data class Resolution(val width: Int, val height: Int)

/** Serialises [Duration] as ISO-8601-style string ("PT1.5S"), portable across platforms. */
internal object DurationSerializer : kotlinx.serialization.KSerializer<Duration> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "kotlin.time.Duration",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Duration) {
        encoder.encodeString(value.toIsoString())
    }
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Duration =
        Duration.parseIsoString(decoder.decodeString())
}
