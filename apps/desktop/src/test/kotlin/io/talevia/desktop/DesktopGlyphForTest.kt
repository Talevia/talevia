package io.talevia.desktop

import io.talevia.core.domain.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `m7-renderable-timeline-ui-migration` (cycle 30). The desktop UI
 * consumes the platform-agnostic [TrackKind] enum surfaced by
 * [io.talevia.core.domain.RenderableTimeline] and renders a per-kind
 * label via [desktopGlyphFor]. iOS / Android UIs share the same
 * RenderableTimeline shape but pick their own per-platform glyphs
 * (SwiftUI SF Symbols / Compose Material Icons); the desktop's
 * mapping must:
 *
 *   - cover all four [TrackKind] variants (catches a new track kind
 *     landing without a matching desktop glyph — the function would
 *     fail to compile, but a test here also documents the four-way
 *     coverage),
 *   - produce four distinct labels (no two kinds collapse to the
 *     same string — we want users to tell a video track from an
 *     audio track at a glance).
 */
class DesktopGlyphForTest {

    @Test fun coversAllFourTrackKinds() {
        // Exercise every enum entry — exhaustive `when` in
        // [desktopGlyphFor] makes this a compile-time guarantee, but
        // the test pins the cardinality so a future refactor can't
        // silently fold two kinds together.
        for (kind in TrackKind.entries) {
            val glyph = desktopGlyphFor(kind)
            assertNotEquals("", glyph, "[$kind] glyph must not be empty")
        }
    }

    @Test fun fourKindsProduceFourDistinctGlyphs() {
        val glyphs = TrackKind.entries.map { desktopGlyphFor(it) }
        assertEquals(
            TrackKind.entries.size,
            glyphs.toSet().size,
            "every TrackKind must map to a distinct glyph; got $glyphs",
        )
    }

    @Test fun stableGlyphsForCurrentKinds() {
        // Lock the per-kind label so a future cycle can't silently
        // re-letter "video" → "vid" and break the user's muscle
        // memory. Bumping a glyph is a deliberate UX decision; the
        // test forces an explicit update + commit-message rationale.
        assertEquals("video", desktopGlyphFor(TrackKind.Video))
        assertEquals("audio", desktopGlyphFor(TrackKind.Audio))
        assertEquals("subtitle", desktopGlyphFor(TrackKind.Subtitle))
        assertEquals("effect", desktopGlyphFor(TrackKind.Effect))
    }
}
