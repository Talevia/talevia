package io.talevia.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.talevia.core.agent.Agent
import io.talevia.core.agent.RunInput
import io.talevia.core.agent.taleviaSystemPrompt
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.PermissionService
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.lut.CubeLutParser
import io.talevia.core.platform.lut.toCoreImageRgbaFloats
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolRegistry
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
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

fun sourceNodeId(value: String): SourceNodeId = SourceNodeId(value)
fun sourceNodeIdRaw(id: SourceNodeId): String = id.value

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
    val filters: List<IosFilterSpec> = emptyList(),
    /**
     * Dip-to-black fade durations derived from adjacent
     * [io.talevia.core.tool.builtin.video.AddTransitionTool] transitions. `0.0`
     * means no fade. The head fade runs from the clip's timeline start over
     * [headFadeSeconds]; the tail fade runs up to the clip's timeline end over
     * [tailFadeSeconds]. Both together give the cross-engine parity floor for
     * transition rendering (matches the FFmpeg engine's `fade=t=in/out:c=black`
     * behavior).
     */
    val headFadeSeconds: Double = 0.0,
    val tailFadeSeconds: Double = 0.0,
)

/**
 * Flat DTO for [io.talevia.core.domain.Filter] exposed to the Swift engine.
 *
 * [params] is surfaced as `Map<String, Double>` instead of the domain's
 * `Map<String, Float>` so the Swift side can reach the values without the
 * `KotlinFloat` unwrap dance and can hand Doubles straight to `CIFilter`
 * (Core Image wants CGFloat / Double).
 *
 * [assetIdRaw] is the filter's bound asset id (used by `lut` — the `.cube`
 * file the engine must resolve + parse). Swift-side path resolution goes
 * through the same `MediaPathResolver` as the clip's video asset, then
 * into [CubeLutParser] (shared with Media3).
 */
data class IosFilterSpec(
    val name: String,
    val params: Map<String, Double>,
    val assetIdRaw: String?,
)

/**
 * Project a [Timeline] into the flat primitive list the Swift engine wants.
 * Only video clips on video tracks are included; subtitle clips come through a
 * separate projection ([toIosSubtitlePlan]) because AVFoundation renders them
 * via `AVVideoCompositionCoreAnimationTool`, not the video composition path.
 *
 * Track order is preserved; within each track clips are emitted in
 * timeline-start order.
 */
fun Timeline.toIosVideoPlan(): List<IosVideoClipPlan> {
    // Transition fades derive from synthetic Effect-track clips emitted by
    // AddTransitionTool. Compute a map<clipIdValue, (head, tail)> in seconds
    // here so the Swift engine doesn't re-implement the boundary math. Every
    // transitionName collapses to a dip-to-black fade on both neighbours —
    // the same cross-engine parity floor the FFmpeg engine enforces.
    val videoClips = this.tracks
        .filterIsInstance<Track.Video>()
        .flatMap { it.clips.filterIsInstance<Clip.Video>() }
    val fadeByClipId: Map<String, Pair<Double, Double>> = run {
        val transitions = this.tracks
            .filterIsInstance<Track.Effect>()
            .flatMap { it.clips.filterIsInstance<Clip.Video>() }
            .filter { it.assetId.value.startsWith("transition:") }
        if (transitions.isEmpty()) return@run emptyMap()
        val acc = HashMap<String, Pair<Double, Double>>()
        for (trans in transitions) {
            val half = trans.timeRange.duration / 2
            val boundary = trans.timeRange.start + half
            val from = videoClips.firstOrNull { it.timeRange.end == boundary }
            val to = videoClips.firstOrNull { it.timeRange.start == boundary }
            val halfSec = half.toDouble(DurationUnit.SECONDS)
            if (from != null) {
                val prev = acc[from.id.value] ?: (0.0 to 0.0)
                acc[from.id.value] = prev.first to halfSec
            }
            if (to != null) {
                val prev = acc[to.id.value] ?: (0.0 to 0.0)
                acc[to.id.value] = halfSec to prev.second
            }
        }
        acc
    }

    return this.tracks
        .filterIsInstance<Track.Video>()
        .flatMap { track ->
            track.clips
                .filterIsInstance<Clip.Video>()
                .sortedBy { it.timeRange.start }
                .map { clip ->
                    val (head, tail) = fadeByClipId[clip.id.value] ?: (0.0 to 0.0)
                    IosVideoClipPlan(
                        assetIdRaw = clip.assetId.value,
                        sourceStartSeconds = clip.sourceRange.start.toDouble(DurationUnit.SECONDS),
                        sourceDurationSeconds = clip.sourceRange.duration.toDouble(DurationUnit.SECONDS),
                        timelineStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS),
                        timelineDurationSeconds = clip.timeRange.duration.toDouble(DurationUnit.SECONDS),
                        filters = clip.filters.map { f ->
                            IosFilterSpec(
                                name = f.name,
                                params = f.params.mapValues { (_, v) -> v.toDouble() },
                                assetIdRaw = f.assetId?.value,
                            )
                        },
                        headFadeSeconds = head,
                        tailFadeSeconds = tail,
                    )
                }
        }
}

/**
 * Flat DTO for [io.talevia.core.domain.Clip.Text] subtitle clips exposed to
 * the Swift AVFoundation engine.
 *
 * Times are in timeline seconds (matching [IosVideoClipPlan]). Style fields
 * are primitives so the Swift side can feed them straight into `UIFont` /
 * `UIColor` / `CATextLayer` without crossing the SKIE sealed-class boundary
 * for the [io.talevia.core.domain.TextStyle] data class.
 */
data class IosSubtitlePlan(
    val text: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val fontSize: Double,
    val colorHex: String,
    val backgroundHex: String?,
    val bold: Boolean,
    val italic: Boolean,
    val fontFamily: String,
)

/**
 * Project the timeline's subtitle clips into a Swift-friendly flat list.
 * Every [io.talevia.core.domain.Track.Subtitle]-track [io.talevia.core.domain.Clip.Text]
 * becomes one entry, sorted by timeline start.
 */
fun Timeline.toIosSubtitlePlan(): List<IosSubtitlePlan> =
    this.tracks
        .filterIsInstance<Track.Subtitle>()
        .flatMap { track ->
            track.clips
                .filterIsInstance<Clip.Text>()
                .sortedBy { it.timeRange.start }
                .map { clip ->
                    IosSubtitlePlan(
                        text = clip.text,
                        startSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS),
                        endSeconds = clip.timeRange.end.toDouble(DurationUnit.SECONDS),
                        fontSize = clip.style.fontSize.toDouble(),
                        colorHex = clip.style.color,
                        backgroundHex = clip.style.backgroundColor,
                        bold = clip.style.bold,
                        italic = clip.style.italic,
                        fontFamily = clip.style.fontFamily,
                    )
                }
        }

// =============================================================================
// SKIE bridging: `.cube` 3D LUT → Core Image
// =============================================================================
//
// The AVFoundation engine renders `lut` filters by setting a `CIColorCube`
// filter with native-endian float32 RGBA cube data. Reading the `.cube`
// file + parsing it is shared with Media3 (`CubeLutParser` lives in
// commonMain); this bridge turns the parsed result into an `NSData` the
// Swift side can hand straight to `kCIInputCubeDataKey` without looping
// over individual floats (a 32-cube would be 131k Objective-C calls).

/**
 * Flat, Swift-friendly payload returned by [parseCubeLutForCoreImage]:
 * the cube's edge length and the packed float32 RGBA bytes ready for
 * `CIColorCube.inputCubeData`.
 */
data class CubeLutForCoreImage(
    val size: Int,
    val data: NSData,
)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun parseCubeLutForCoreImage(text: String): CubeLutForCoreImage {
    val lut = CubeLutParser.parse(text)
    val floats = lut.toCoreImageRgbaFloats()
    val byteLen = floats.size * 4
    val bytes = ByteArray(byteLen)
    for (i in floats.indices) {
        val bits = floats[i].toRawBits()
        bytes[i * 4] = (bits and 0xFF).toByte()
        bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    val nsdata = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = byteLen.toULong())
    }
    return CubeLutForCoreImage(size = lut.size, data = nsdata)
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

// =============================================================================
// SKIE bridging: provider registry + agent factory for the iOS app container
// =============================================================================
//
// Mirrors `apps/desktop/AppContainer.kt` and `AndroidAppContainer.kt`: wires a
// Ktor Darwin HttpClient, reads ANTHROPIC_API_KEY / OPENAI_API_KEY from the
// process environment, and — when a provider is configured — returns a ready
// Agent with Compactor attached. Swift callers go through these helpers instead
// of constructing `ProviderRegistry.Builder` / `Agent` themselves, which would
// mean dealing with Ktor and Kotlin default arguments from Swift.

/** Darwin-backed `HttpClient` for the iOS provider registry. */
fun createIosHttpClient(): HttpClient = HttpClient(Darwin)

/**
 * Build a provider registry from the iOS process environment. Reads
 * `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` via `NSProcessInfo.processInfo.environment`;
 * simulator runs can set these in the Xcode scheme's "Run → Arguments → Environment".
 */
fun buildIosProviderRegistry(httpClient: HttpClient): ProviderRegistry {
    val env = NSProcessInfo.processInfo.environment
    val map = buildMap<String, String> {
        (env["ANTHROPIC_API_KEY"] as? String)?.takeIf { it.isNotBlank() }?.let { put("ANTHROPIC_API_KEY", it) }
        (env["OPENAI_API_KEY"] as? String)?.takeIf { it.isNotBlank() }?.let { put("OPENAI_API_KEY", it) }
    }
    return ProviderRegistry.Builder().addEnv(httpClient, map).build()
}

/**
 * Build an [Agent] wired to [provider] with a [Compactor] and the Talevia system
 * prompt. Returns a fresh Agent each call — callers typically cache by
 * `provider.id` to avoid re-instantiating the Compactor.
 */
fun newIosAgent(
    provider: LlmProvider,
    tools: ToolRegistry,
    sessions: SessionStore,
    permissions: PermissionService,
    bus: EventBus,
): Agent = Agent(
    provider = provider,
    registry = tools,
    store = sessions,
    permissions = permissions,
    bus = bus,
    systemPrompt = taleviaSystemPrompt(),
    compactor = Compactor(provider = provider, store = sessions, bus = bus),
)

/** Swift-friendly RunInput factory — avoids constructing [ModelRef] / rules from Swift. */
fun newIosRunInput(
    sessionId: SessionId,
    text: String,
    providerId: String,
    modelId: String,
): RunInput = RunInput(
    sessionId = sessionId,
    text = text,
    model = ModelRef(providerId = providerId, modelId = modelId),
    permissionRules = DefaultPermissionRuleset.rules,
)

/**
 * Swift-side `async` bridge for [Agent.run] — one-shot helper that submits the
 * prompt and returns when the agent's final assistant message is ready.
 *
 * SKIE already maps `suspend` functions to Swift `async`, but this helper pins
 * down the argument shape (no [ModelRef] construction from Swift, no default
 * permission-rule plumbing) so `TaleviaApp` can call:
 *
 *     let reply = try await runAgent(agent, sessionId: sid, text: prompt,
 *                                    providerId: pid, modelId: "claude-opus-4-7")
 */
suspend fun runAgent(
    agent: Agent,
    sessionId: SessionId,
    text: String,
    providerId: String,
    modelId: String,
): Message.Assistant = agent.run(newIosRunInput(sessionId, text, providerId, modelId))

/**
 * Swift-friendly session bootstrap: either resume the most recent non-archived
 * session for [projectId], or create a fresh one. Returns the effective
 * [Session] so Swift can read `id` / `title` without sealed-class gymnastics.
 *
 * Mirrors the bootstrap path in `AndroidAppContainer`'s `ChatPanel`.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
suspend fun bootstrapChatSession(
    sessions: SessionStore,
    projectId: ProjectId,
    clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System,
): Session {
    val existing = sessions.listSessions(projectId).filter { !it.archived }.maxByOrNull { it.updatedAt }
    if (existing != null) return existing
    val now = clock.now()
    val sid = SessionId(kotlin.uuid.Uuid.random().toString())
    val created = Session(
        id = sid,
        projectId = projectId,
        title = "Chat",
        createdAt = now,
        updatedAt = now,
    )
    sessions.createSession(created)
    return created
}

/**
 * Filter the bus to `PartUpdated` events for [sessionId]. SKIE turns the
 * resulting `Flow` into a Swift `AsyncSequence`, so Swift can `for await`
 * over live assistant-text / tool-call updates without building its own
 * subscribe+filter plumbing.
 */
fun sessionPartUpdates(bus: EventBus, sessionId: SessionId): Flow<BusEvent.PartUpdated> =
    bus.subscribe<BusEvent.PartUpdated>().filter { it.sessionId == sessionId }

/**
 * Find-or-create a default project for the iOS shell. Mirrors the bootstrap
 * path `AndroidAppContainer.AppRoot` runs: reuse the first row if one exists,
 * else stand up a "My Project" with empty [Timeline] / [io.talevia.core.domain.source.Source].
 *
 * Returns the effective [ProjectId] so Swift never has to construct a
 * [Project] (that requires pulling in every default across `outputProfile`,
 * `lockfile`, `renderCache`, …, which isn't worth the SKIE pain when the
 * Kotlin side already knows the defaults).
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
suspend fun bootstrapDefaultProject(projects: ProjectStore): ProjectId {
    val existing = projects.listSummaries().firstOrNull()
    if (existing != null) return ProjectId(existing.id)
    val pid = ProjectId(kotlin.uuid.Uuid.random().toString())
    projects.upsert(title = "My Project", project = Project(id = pid, timeline = Timeline()))
    return pid
}

/**
 * Swift-friendly bus projection: bare `PartUpdated` stream (no session filter)
 * so the Timeline / Source panels can trigger refreshes on every tool dispatch.
 */
fun anyPartUpdates(bus: EventBus): Flow<BusEvent.PartUpdated> = bus.subscribe()

/**
 * Every [BusEvent.SessionEvent] scoped to [sessionId]. The SwiftUI ChatPanel
 * uses this to drive a richer "live transcript" — `PartDelta` for streaming
 * assistant text, `SessionCancelled` to flip the Stop button back to Send,
 * `AgentRunFailed` to surface failures without polling.
 *
 * Returned as a `Flow` → SKIE bridges to a Swift `AsyncSequence`.
 */
fun sessionEvents(bus: EventBus, sessionId: SessionId): Flow<BusEvent.SessionEvent> =
    bus.forSession(sessionId)

/**
 * Swift-friendly cancel for an in-flight [Agent.run]. Calls through to
 * [Agent.cancel] (suspend); SKIE exposes it as `async throws -> Bool`. Returns
 * true when a run was found and cancelled, false when nothing was in flight.
 *
 * The ChatPanel's Stop button wires this in: every `send()` owns a sessionId,
 * so "cancel the current turn" is a one-liner on the Swift side.
 */
suspend fun cancelAgent(agent: Agent, sessionId: SessionId): Boolean =
    agent.cancel(sessionId)

/**
 * Swift-friendly query: is [sessionId] currently running on [agent]? Mirrors
 * [Agent.isRunning]. Useful on launch to decide whether the chat input should
 * show "Stop" vs "Send" without racing a bus subscription.
 */
suspend fun isAgentRunning(agent: Agent, sessionId: SessionId): Boolean =
    agent.isRunning(sessionId)
