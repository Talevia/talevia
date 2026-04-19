package io.talevia.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Helpers exposed to Swift via SKIE so the iOS app composition root can stand up
 * the SQLDelight database without touching Kotlin/Native APIs directly.
 *
 * For M3 we only ship in-memory mode; persistent on-device storage (with proper
 * file-based driver and Documents-directory paths) lands when the iOS UX needs it.
 */
class TaleviaDatabaseFactory {
    fun createInMemoryDriver(): app.cash.sqldelight.db.SqlDriver {
        val driver = NativeSqliteDriver(TaleviaDb.Schema, name = "talevia.db", onConfiguration = { it })
        return driver
    }
}

// =============================================================================
// SKIE bridging: value-class ID factories + unwrappers
// =============================================================================
//
// `@JvmInline value class` is erased at the Kotlin/Native → ObjC boundary — the
// wrapped String leaks through as `Any` in Swift, so Swift can neither construct
// an `AssetId(value:)` nor read `.value` off one.
//
// Because all ID value classes erase to `Any`, using `fun AssetId.rawValue()`
// style extensions produces Swift overloads that all share the same signature
// `rawValue(_: Any) -> String`, forcing SKIE into ugly auto-renames
// (`rawValue_`, `rawValue__`, …). We sidestep that by giving each type a
// distinct Kotlin function name, which SKIE bridges cleanly.

fun assetId(value: String): AssetId = AssetId(value)
fun assetIdRaw(id: AssetId): String = id.value

fun sessionId(value: String): SessionId = SessionId(value)
fun sessionIdRaw(id: SessionId): String = id.value

fun messageId(value: String): MessageId = MessageId(value)
fun messageIdRaw(id: MessageId): String = id.value

fun partId(value: String): PartId = PartId(value)
fun partIdRaw(id: PartId): String = id.value

fun projectId(value: String): ProjectId = ProjectId(value)
fun projectIdRaw(id: ProjectId): String = id.value

fun trackId(value: String): TrackId = TrackId(value)
fun trackIdRaw(id: TrackId): String = id.value

fun clipId(value: String): ClipId = ClipId(value)
fun clipIdRaw(id: ClipId): String = id.value

fun callId(value: String): CallId = CallId(value)
fun callIdRaw(id: CallId): String = id.value

// =============================================================================
// SKIE bridging: kotlin.time.Duration ↔ Double seconds / Long millis
// =============================================================================
//
// `kotlin.time.Duration` surfaces through SKIE as an opaque KotlinDuration
// wrapper — construction from Swift requires a Kotlin-side factory, and reading
// the value back requires knowing which unit to request. Seconds + millis is
// enough for AVFoundation consumers (`CMTime`/`CMTimeRange` want seconds as
// Double, progress events want millis).

fun durationOfSeconds(seconds: Double): Duration = seconds.seconds

fun durationToSeconds(d: Duration): Double = d.toDouble(DurationUnit.SECONDS)

fun durationToMillis(d: Duration): Long = d.inWholeMilliseconds

// =============================================================================
// Timeline → flat DTO projection for the Swift AVFoundation engine
// =============================================================================
//
// The `Clip` sealed hierarchy is painful for Swift consumers (SKIE cannot
// exhaustively match sealed classes without a `when`-like helper, and value
// classes on the embedded IDs double the pain). For the render path the engine
// only needs the primitives — asset id string + source/timeline seconds — so
// we derive a flat DTO at call time.
//
// This is intentionally NOT a replacement for the canonical `Timeline` (that
// stays in Core per architecture rule #2); it's a per-render-call projection,
// thrown away once the `AVMutableComposition` is built.

data class IosVideoClipPlan(
    val assetIdRaw: String,
    val sourceStartSeconds: Double,
    val sourceDurationSeconds: Double,
    val timelineStartSeconds: Double,
    val timelineDurationSeconds: Double,
)

/**
 * Project a [Timeline] into the flat primitive list the Swift engine wants.
 * Only video clips on video tracks are included — audio/subtitle/effect tracks
 * are ignored in the first cut, matching the Media3 scope.
 *
 * Track order is preserved; within each track clips are emitted in
 * timeline-start order.
 */
fun Timeline.toIosVideoPlan(): List<IosVideoClipPlan> =
    this.tracks
        .filterIsInstance<Track.Video>()
        .flatMap { track ->
            track.clips
                .filterIsInstance<Clip.Video>()
                .sortedBy { it.timeRange.start }
                .map { clip ->
                    IosVideoClipPlan(
                        assetIdRaw = clip.assetId.value,
                        sourceStartSeconds = clip.sourceRange.start.toDouble(DurationUnit.SECONDS),
                        sourceDurationSeconds = clip.sourceRange.duration.toDouble(DurationUnit.SECONDS),
                        timelineStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS),
                        timelineDurationSeconds = clip.timeRange.duration.toDouble(DurationUnit.SECONDS),
                    )
                }
        }
