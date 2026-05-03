package io.talevia.core.domain.source

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for the [Clip.modalityNeeds] extension property — the
 * pure-derivation that lets [io.talevia.core.domain.staleClipsFromLockfile]
 * pick the right modality slice (Visual vs Audio) of a bound source
 * node's content hash. Cycle 94 audit found this property had zero
 * direct test references.
 *
 * The property is a 3-arm `when` over the sealed `Clip` type. A
 * regression mapping `Clip.Video → Modality.Audio` (or any
 * permutation) would silently flip the staleness detector's
 * modality picker — every video clip bound to a `character_ref` would
 * stale on `voiceId` changes, the exact bug the modality split was
 * introduced to fix (VISION §3.2 + §5.5).
 */
class ModalityNeedsTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    @Test fun videoClipNeedsVisualModality() {
        val v = Clip.Video(
            id = ClipId("v"),
            timeRange = timeRange,
            sourceRange = timeRange,
            assetId = AssetId("asset-v"),
        )
        assertEquals(Modality.Visual, v.modalityNeeds)
    }

    @Test fun audioClipNeedsAudioModality() {
        val a = Clip.Audio(
            id = ClipId("a"),
            timeRange = timeRange,
            sourceRange = timeRange,
            assetId = AssetId("asset-a"),
        )
        assertEquals(Modality.Audio, a.modalityNeeds)
    }

    @Test fun textClipNeedsVisualModality() {
        // Text is a visual overlay (rendered into the frame). Even
        // though Text clips don't carry an assetId and never reach
        // the staleness detector, the property must be total over the
        // sealed hierarchy — pin Visual here so a refactor mapping
        // it to Audio doesn't silently slip through.
        val t = Clip.Text(
            id = ClipId("t"),
            timeRange = timeRange,
            text = "hello",
        )
        assertEquals(Modality.Visual, t.modalityNeeds)
    }

    @Test fun modalityEnumHasExactlyTwoVariants() {
        // Pin: kdoc commits to "two-state on purpose". A third variant
        // landing would break the implicit "all clips fit one bucket"
        // contract — fail this assertion fast so the pickers can be
        // updated coherently.
        assertEquals(2, Modality.values().size)
    }
}
