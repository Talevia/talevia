package io.talevia.core.domain

/**
 * Cross-engine [FilterKind] support manifest — VISION §5.2 / M4 #2
 * "cross-engine 特效 parity 强制". Each engine declares which
 * [FilterKind]s it currently renders; [FilterCrossEngineParityTest]
 * (jvmTest) enumerates these sets and asserts they cover the full
 * [FilterKind.entries]. Adding a new [FilterKind] without listing it
 * here breaks the parity test, forcing the contributor to either:
 *
 *   1. land an implementation on the engine that's missing — moves
 *      the kind into the engine's set; or
 *   2. acknowledge a temporary gap by listing the kind in the engine's
 *      set with a `// TODO: …` comment beside the call site, plus a
 *      [debt-effect-engine-gap-<kind>-<engine>](docs/BACKLOG.md) bullet.
 *
 * The [FilterKind] enum itself is the *compile-time* gate (each Kotlin
 * engine's `when (filter.kind)` is exhaustive — adding a variant
 * without an arm fails compile). This manifest is the *test-time*
 * gate that catches drift the compiler can't see — specifically the
 * iOS AVFoundation engine, which lives in Swift and isn't reachable
 * from a Kotlin sealed `when`. iOS drift between
 * `AVFoundationVideoEngine.swift:71`'s `switch` arms and
 * [avFoundationIos] below stays a manual-sync concern; a follow-up
 * iOS-side test (out of scope for this Kotlin artifact) would close
 * that gap. The two Kotlin engines (FFmpeg JVM, Media3 Android) get
 * both gates.
 *
 * Today's coverage (cycle 2026-04-28): all three engines support all
 * 5 kinds (Brightness / Saturation / Blur / Vignette / Lut), per
 * inspection of `FfmpegVideoEngine.renderFilter` (post-cycle-9
 * sealed `when`), `Media3FilterEffects.mapFilterToEffect` (same),
 * and `AVFoundationVideoEngine.swift:71-119` (Swift `switch`).
 */
object FilterEngineCapabilities {
    /**
     * FFmpeg JVM engine. Source of truth: `FfmpegVideoEngine.renderFilter`
     * sealed `when (filter.kind)` arms.
     */
    val ffmpegJvm: Set<FilterKind> = setOf(
        FilterKind.Brightness,
        FilterKind.Saturation,
        FilterKind.Blur,
        FilterKind.Vignette,
        FilterKind.Lut,
    )

    /**
     * Media3 Android engine. Source of truth:
     * `Media3FilterEffects.mapFilterToEffect` sealed `when (filter.kind)` arms.
     */
    val media3Android: Set<FilterKind> = setOf(
        FilterKind.Brightness,
        FilterKind.Saturation,
        FilterKind.Blur,
        FilterKind.Vignette,
        FilterKind.Lut,
    )

    /**
     * AVFoundation iOS engine. Source of truth (manual mirror):
     * `AVFoundationVideoEngine.swift:71` `switch spec.name.lowercased()`
     * cases. Swift code isn't reachable from this Kotlin manifest, so
     * drift between the two surfaces is caught by:
     *
     *   - `FilterCrossEngineParityTest` if `entries.size` grows here in
     *     Kotlin but the new kind isn't listed,
     *   - reviewer code-reading + the matching iOS-side test that a
     *     future cycle adds on the Swift side.
     *
     * The current 5 entries match the Swift switch's 5 cases. New
     * iOS support → add an entry here in the same commit.
     */
    val avFoundationIos: Set<FilterKind> = setOf(
        FilterKind.Brightness,
        FilterKind.Saturation,
        FilterKind.Blur,
        FilterKind.Vignette,
        FilterKind.Lut,
    )

    /**
     * All engines this manifest tracks. Used by parity tests to iterate
     * `engines × kinds`.
     */
    val allEngines: List<Engine> = listOf(
        Engine(id = "ffmpeg-jvm", supported = ffmpegJvm),
        Engine(id = "media3-android", supported = media3Android),
        Engine(id = "avfoundation-ios", supported = avFoundationIos),
    )

    /**
     * Per-engine support entry. [id] is a stable slug used in test
     * failure messages so a contributor can grep
     * `FilterEngineCapabilities.<id>` and find the set they need to
     * extend.
     */
    data class Engine(
        val id: String,
        val supported: Set<FilterKind>,
    )
}
