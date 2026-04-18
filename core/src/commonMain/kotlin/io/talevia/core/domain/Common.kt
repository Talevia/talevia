package io.talevia.core.domain

import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class TimeRange(
    val start: Duration,
    val duration: Duration,
) {
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
