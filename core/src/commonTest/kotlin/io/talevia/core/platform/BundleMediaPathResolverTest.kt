package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class BundleMediaPathResolverTest {

    private fun project(vararg assets: MediaAsset): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        assets = assets.toList(),
    )

    private fun asset(id: String, source: MediaSource): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = source,
        metadata = MediaMetadata(duration = 5.seconds),
    )

    @Test
    fun resolvesFileSourceVerbatim() = runTest {
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.File("/Users/alice/footage.mp4"))),
            "/projects/foo".toPath(),
        )
        assertEquals("/Users/alice/footage.mp4", resolver.resolve(AssetId("a")))
    }

    @Test
    fun joinsBundleFileWithBundleRoot() = runTest {
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.BundleFile("media/a.mp3"))),
            "/projects/foo".toPath(),
        )
        assertEquals("/projects/foo/media/a.mp3", resolver.resolve(AssetId("a")))
    }

    @Test
    fun unknownAssetIdThrows() = runTest {
        val resolver = BundleMediaPathResolver(project(), "/projects/foo".toPath())
        val ex = assertFailsWith<IllegalStateException> { resolver.resolve(AssetId("missing")) }
        assertEquals(true, ex.message?.contains("missing"))
    }

    @Test
    fun httpSourceErrorsWithMigrationHint() = runTest {
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.Http("https://example.com/foo.mp4"))),
            "/projects/foo".toPath(),
        )
        val ex = assertFailsWith<IllegalStateException> { resolver.resolve(AssetId("a")) }
        assertEquals(true, ex.message?.contains("Http", ignoreCase = true))
    }

    @Test
    fun platformSourceErrors() = runTest {
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.Platform("ios.phasset", "ABC123"))),
            "/projects/foo".toPath(),
        )
        val ex = assertFailsWith<IllegalStateException> { resolver.resolve(AssetId("a")) }
        assertEquals(true, ex.message?.contains("Platform"))
    }
}
