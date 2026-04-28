package io.talevia.core.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * VISION §5.2 / M4 criterion #2 (cross-engine 特效 parity 强制): every
 * [FilterKind] the registry knows about must be claimed by every
 * engine in [FilterEngineCapabilities.allEngines], OR the engine
 * must explicitly drop the kind via a [FilterEngineCapabilities]
 * gap (currently no engine has any gap).
 *
 * Why this lane exists when the Kotlin engines already get
 * compile-time exhaustiveness from sealed `when (filter.kind)`:
 *
 *   - The compile-time gate covers FFmpeg JVM and Media3 Android.
 *     The iOS AVFoundation engine lives in Swift and uses
 *     `switch spec.name.lowercased()` — it can drop a kind silently,
 *     because Swift's `switch` over `String` doesn't see the Kotlin
 *     `FilterKind` enum.
 *   - Test-time gate: a new [FilterKind] entry must be added to
 *     each engine's manifest in [FilterEngineCapabilities] in the
 *     same commit, otherwise the test below fails. This forces the
 *     contributor to think about iOS coverage at submission time
 *     rather than discovering "oh, vignette doesn't render on
 *     iPhone" at runtime three weeks later.
 */
class FilterCrossEngineParityTest {

    @Test fun everyKindCoveredOnEveryEngine() {
        // The full closed set of kinds any engine could be asked to
        // render. Adding a new variant to [FilterKind] grows this set;
        // the engines below must keep up.
        val allKinds: Set<FilterKind> = FilterKind.entries.toSet()

        val gaps = mutableListOf<String>()
        for (engine in FilterEngineCapabilities.allEngines) {
            val missing = allKinds - engine.supported
            if (missing.isNotEmpty()) {
                gaps += "engine='${engine.id}' missing kinds=${missing.map { it.name }}"
            }
        }

        assertTrue(
            gaps.isEmpty(),
            "M4 #2 cross-engine parity broken — every FilterKind must be supported by " +
                "every engine OR explicitly listed as a gap. Update the engine's set in " +
                "FilterEngineCapabilities (and add the corresponding when-arm in the " +
                "engine's filter dispatch). Gaps: ${gaps.joinToString("; ")}",
        )
    }

    @Test fun engineSetsCoverNoUnknownKinds() {
        // Reverse direction: an engine claiming to support a kind that
        // doesn't exist in the FilterKind enum is also a contract
        // violation — likely a stale entry left after a kind got renamed
        // or removed. Prevents the manifest from silently lying that
        // an engine covers a phantom variant.
        val allKinds: Set<FilterKind> = FilterKind.entries.toSet()
        val unexpected = mutableListOf<String>()
        for (engine in FilterEngineCapabilities.allEngines) {
            val extra = engine.supported - allKinds
            if (extra.isNotEmpty()) {
                unexpected += "engine='${engine.id}' claims unknown kinds=$extra"
            }
        }
        assertTrue(
            unexpected.isEmpty(),
            "engine capability set claims kinds outside FilterKind.entries — likely a stale " +
                "entry left after a rename/remove: ${unexpected.joinToString("; ")}",
        )
    }

    @Test fun allEnginesEnumerated() {
        // The manifest must list each engine the project ships. If the
        // project gains a new engine (e.g. a hypothetical web
        // playback engine), this assertion gives a clean failure
        // pointer — extend allEngines, mirror the engine's coverage,
        // re-run.
        val expected = setOf("ffmpeg-jvm", "media3-android", "avfoundation-ios")
        val actual = FilterEngineCapabilities.allEngines.map { it.id }.toSet()
        assertTrue(
            actual == expected,
            "FilterEngineCapabilities.allEngines drifted from the expected 3-engine baseline. " +
                "expected=$expected; actual=$actual.",
        )
    }
}
