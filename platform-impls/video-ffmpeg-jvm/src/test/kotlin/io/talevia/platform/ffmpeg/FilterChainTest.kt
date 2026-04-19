package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.domain.Filter
import io.talevia.core.platform.MediaPathResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-level verification of the filtergraph fragments generated from core
 * [Filter]s. No ffmpeg binary required — this only exercises string formatting.
 */
class FilterChainTest {
    private val engine = FfmpegVideoEngine(pathResolver = NullResolver)

    @Test
    fun emptyListReturnsNullChain() {
        assertNull(engine.filterChainFor(emptyList()))
    }

    @Test
    fun unknownFiltersAreDropped() {
        assertNull(engine.filterChainFor(listOf(Filter("unsupported"))))
    }

    @Test
    fun brightnessIntensityClampedAndFormatted() {
        assertEquals("eq=brightness=0.3", engine.filterChainFor(listOf(Filter("brightness", mapOf("intensity" to 0.3f)))))
        assertEquals("eq=brightness=1", engine.filterChainFor(listOf(Filter("brightness", mapOf("intensity" to 5f)))))
        assertEquals("eq=brightness=-1", engine.filterChainFor(listOf(Filter("brightness", mapOf("intensity" to -5f)))))
        assertEquals("eq=brightness=0", engine.filterChainFor(listOf(Filter("brightness"))))
    }

    @Test
    fun saturationWithIntensityMapsZeroToOneRangeToZeroTwo() {
        assertEquals("eq=saturation=1", engine.filterChainFor(listOf(Filter("saturation", mapOf("intensity" to 0.5f)))))
        assertEquals("eq=saturation=2", engine.filterChainFor(listOf(Filter("saturation", mapOf("intensity" to 1f)))))
        assertEquals("eq=saturation=0", engine.filterChainFor(listOf(Filter("saturation", mapOf("intensity" to 0f)))))
    }

    @Test
    fun blurRadiusMapsToGblurSigma() {
        assertEquals("gblur=sigma=5", engine.filterChainFor(listOf(Filter("blur", mapOf("radius" to 0.5f)))))
        assertEquals("gblur=sigma=2.5", engine.filterChainFor(listOf(Filter("blur", mapOf("sigma" to 2.5f)))))
    }

    @Test
    fun vignetteRendersWithoutArgs() {
        assertEquals("vignette", engine.filterChainFor(listOf(Filter("vignette"))))
    }

    @Test
    fun multipleFiltersAreCommaJoinedInOrder() {
        val chain = engine.filterChainFor(
            listOf(
                Filter("brightness", mapOf("intensity" to 0.1f)),
                Filter("blur", mapOf("radius" to 0.2f)),
            ),
        )
        assertEquals("eq=brightness=0.1,gblur=sigma=2", chain)
    }

    @Test
    fun caseInsensitiveFilterNames() {
        assertTrue(engine.filterChainFor(listOf(Filter("BRIGHTNESS")))!!.startsWith("eq=brightness="))
    }

    @Test
    fun lutFilterResolvesAssetPathToLut3dFilter() {
        val lutId = AssetId("lut-warm")
        val filter = Filter(name = "lut", assetId = lutId)
        val chain = engine.filterChainFor(
            filters = listOf(filter),
            resolvedAssetPaths = mapOf(lutId to "/tmp/warm.cube"),
        )
        assertEquals("lut3d=file=/tmp/warm.cube", chain)
    }

    @Test
    fun lutFilterWithoutResolvedPathIsDropped() {
        val filter = Filter(name = "lut", assetId = AssetId("missing"))
        // No entry in resolvedAssetPaths → the lut filter falls through as unknown.
        assertNull(engine.filterChainFor(listOf(filter)))
    }

    @Test
    fun lutFilterWithoutAssetIdIsDropped() {
        // Defensive: a "lut" name with no assetId (shouldn't happen in practice because
        // ApplyLutTool always sets one, but the engine must not NPE on it).
        assertNull(engine.filterChainFor(listOf(Filter(name = "lut"))))
    }

    @Test
    fun lutPathSpecialCharsAreEscapedForFiltergraph() {
        val lutId = AssetId("lut")
        val chain = engine.filterChainFor(
            filters = listOf(Filter(name = "lut", assetId = lutId)),
            resolvedAssetPaths = mapOf(lutId to "/tmp/with:colon,comma[bracket].cube"),
        )
        // Every filtergraph-meta character must be backslash-escaped so ffmpeg
        // doesn't re-parse the path as filter args.
        assertEquals(
            "lut3d=file=/tmp/with\\:colon\\,comma\\[bracket\\].cube",
            chain,
        )
    }

    private object NullResolver : MediaPathResolver {
        override suspend fun resolve(assetId: AssetId): String = error("not used")
    }
}
