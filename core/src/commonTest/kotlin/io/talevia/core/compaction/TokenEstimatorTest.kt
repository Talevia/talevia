package io.talevia.core.compaction

import io.talevia.core.AssetId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.session.Part
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TokenEstimatorTest {

    @Test
    fun mediaWithoutResolverUsesDefaultImageBudget() {
        val part = Part.Media(
            id = PartId("p1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(0),
            assetId = AssetId("a1"),
        )
        // Default resolver returns null → forMedia falls back to 1568.
        assertEquals(1568, TokenEstimator.forPart(part))
    }

    @Test
    fun mediaWithResolutionSizesByAnthropicFormula() {
        val part = Part.Media(
            id = PartId("p1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(0),
            assetId = AssetId("a1"),
        )
        val asset = MediaAsset(
            id = AssetId("a1"),
            source = MediaSource.File("/tmp/clip.mp4"),
            metadata = MediaMetadata(duration = 1.seconds, resolution = Resolution(1920, 1080)),
        )
        // 1920*1080 / 750 = 2764, within [1568, 6144].
        val tokens = TokenEstimator.forPart(part) { if (it == asset.id) asset else null }
        assertEquals(2764, tokens)
    }

    @Test
    fun very4kFrameIsCappedToMaxBudget() {
        val part = Part.Media(
            id = PartId("p1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(0),
            assetId = AssetId("a1"),
        )
        val asset = MediaAsset(
            id = AssetId("a1"),
            source = MediaSource.File("/tmp/big.mp4"),
            metadata = MediaMetadata(duration = 1.seconds, resolution = Resolution(7680, 4320)), // 8K
        )
        val tokens = TokenEstimator.forPart(part) { asset }
        assertTrue(tokens == 6144, "8K frame should cap at 6144, was $tokens")
    }
}
