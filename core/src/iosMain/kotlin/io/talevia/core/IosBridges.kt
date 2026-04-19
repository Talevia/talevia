package io.talevia.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.talevia.core.db.TaleviaDb
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
