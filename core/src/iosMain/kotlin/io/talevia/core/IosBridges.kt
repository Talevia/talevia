package io.talevia.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.platform.RenderProgress
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.takeWhile
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

// =============================================================================
// SKIE bridging: Swift-driven render Flow adapter
// =============================================================================
//
// `AVFoundationVideoEngine.render(...)` runs on the Swift side but must return
// a Kotlin `Flow<RenderProgress>` (the shape the Core agent loop and Compose
// UIs depend on). SKIE can auto-bridge a Kotlin `Flow` into a
// `SkieSwiftFlow<…>` and back, but Swift can't easily *construct* a Kotlin
// Flow on its own — there's no public constructor for `SkieSwiftFlow`.
//
// [SwiftRenderFlowAdapter] closes the loop: the Swift code creates one, pushes
// progress events via [tryEmit], and calls [close] when the export session
// reports a terminal state. The adapter exposes a cold `Flow` that completes
// when `close()` is called (via `takeWhile`) so downstream collectors finish
// naturally — no manual cancellation dance required.
//
// Buffered capacity of 32 is ample for the 10 Hz progress poll in B4 — frames
// aren't load-bearing (the terminal Completed/Failed event is what matters),
// and `DROP_OLDEST` means a slow consumer can't block the exporter.
class SwiftRenderFlowAdapter {
    private val sentinelCompleted = RenderProgress.Completed(jobId = "__swift_adapter_eof__", outputPath = "")
    // `replay = 64` means late subscribers still see everything the Swift side
    // emitted *before* the caller started collecting — this matters because
    // `AVFoundationVideoEngine.render(...)` fires a `Started` event right away
    // and callers typically collect on a subsequent line. A cold Flow shape
    // would solve this too, but SharedFlow lets Swift push events without
    // suspending, which keeps the Swift code path simple.
    private val flow = MutableSharedFlow<RenderProgress>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Push an event into the flow. Returns true if the event was buffered. */
    fun tryEmit(event: RenderProgress): Boolean = flow.tryEmit(event)

    /**
     * Terminate the flow. Downstream collectors exit after this (the flow's
     * own `takeWhile` drops a sentinel Completed/"__swift_adapter_eof__").
     */
    fun close() {
        flow.tryEmit(sentinelCompleted)
    }

    /**
     * The cold Flow Swift returns from `render`. Ends when [close] is called.
     * The terminating sentinel is filtered out so callers only see the events
     * the Swift side explicitly emitted.
     */
    fun asFlow(): Flow<RenderProgress> =
        flow.asSharedFlow().takeWhile { it !== sentinelCompleted }
}

// =============================================================================
// Test-oriented import helper
// =============================================================================
//
// `MediaStorage.import(source, probe)` takes a `suspend (MediaSource) ->
// MediaMetadata` lambda which SKIE exposes as `KotlinSuspendFunction1` — that
// type is painful to construct from Swift. For tests (and any iOS code path
// that already has the metadata in hand) this helper avoids the suspend-closure
// dance: probe first via the engine, then upsert via a pre-known metadata.

/**
 * Import [source] into [storage] using a metadata value you already have. The
 * returned asset is also stored in-place via the standard `import` pathway so
 * subsequent `resolve(assetId)` calls work.
 *
 * This is a convenience for iOS consumers; the canonical flow (agent +
 * ImportMediaTool) still goes through the suspend-probe path.
 */
suspend fun importWithKnownMetadata(
    storage: io.talevia.core.platform.MediaStorage,
    source: io.talevia.core.domain.MediaSource,
    metadata: io.talevia.core.domain.MediaMetadata,
): io.talevia.core.domain.MediaAsset = storage.import(source) { metadata }
