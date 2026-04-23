package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.ProxyAsset
import io.talevia.core.domain.ProxyPurpose
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.ProxyGenerator
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the `asset-proxy-generation` cycle — `ImportMediaTool` calls a
 * wired [ProxyGenerator] after the media lands in storage and merges the
 * returned proxies into the project's asset record. Swallowing generator
 * failures is load-bearing (best-effort per the interface KDoc): a bad
 * ffmpeg / missing codec / disk-full must not break the import.
 */
class ImportMediaProxyTest {

    private class StubVideoEngine : VideoEngine {
        override suspend fun probe(source: MediaSource) = MediaMetadata(
            duration = 10.seconds,
            resolution = Resolution(1920, 1080),
            videoCodec = "h264",
        )
        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> =
            flowOf(RenderProgress.Failed("no-op", "StubVideoEngine cannot render"))
        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray = ByteArray(0)
    }

    private class RecordingProxyGenerator(
        private val proxies: List<ProxyAsset>,
    ) : ProxyGenerator {
        val callCount: Int get() = calls
        private var calls = 0
        val lastAssetId: String? get() = lastId
        private var lastId: String? = null
        override suspend fun generate(asset: MediaAsset, sourcePath: String): List<ProxyAsset> {
            calls += 1
            lastId = asset.id.value
            return proxies
        }
    }

    private class ThrowingProxyGenerator : ProxyGenerator {
        override suspend fun generate(asset: MediaAsset, sourcePath: String): List<ProxyAsset> {
            throw RuntimeException("ffmpeg missing from PATH")
        }
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun rig(
        generator: ProxyGenerator,
    ): Pair<FileProjectStore, ImportMediaTool> {
        val projects = ProjectStoreTestKit.create()
        projects.upsert(
            "demo",
            Project(id = ProjectId("p"), timeline = Timeline()),
        )
        val tool = ImportMediaTool(StubVideoEngine(), projects, proxyGenerator = generator)
        return projects to tool
    }

    private fun tempFilePath(): String {
        val tmp = createTempDirectory("import-proxy-test")
        val f = Files.createFile(tmp.resolve("fake.mp4"))
        return f.toAbsolutePath().toString()
    }

    @Test fun proxyGeneratorInvokedAndProxiesStampedOnAsset() = runTest {
        val gen = RecordingProxyGenerator(
            proxies = listOf(
                ProxyAsset(
                    source = MediaSource.File("/tmp/proxies/p/thumb.jpg"),
                    purpose = ProxyPurpose.THUMBNAIL,
                    resolution = Resolution(320, 180),
                ),
            ),
        )
        val (projects, tool) = rig(gen)
        val result = tool.execute(
            ImportMediaTool.Input(path = tempFilePath(), projectId = "p", copy_into_bundle = false),
            ctx(),
        )

        assertEquals(1, gen.callCount, "generator must run once per import")
        assertEquals(result.data.assetId, gen.lastAssetId)
        assertEquals(1, result.data.proxyCount)

        val storedAsset = projects.get(ProjectId("p"))!!.assets.single()
        assertEquals(1, storedAsset.proxies.size)
        assertEquals(ProxyPurpose.THUMBNAIL, storedAsset.proxies.single().purpose)
    }

    @Test fun generatorFailuresAreSwallowedSoImportStillSucceeds() = runTest {
        val (projects, tool) = rig(ThrowingProxyGenerator())
        val result = tool.execute(
            ImportMediaTool.Input(path = tempFilePath(), projectId = "p", copy_into_bundle = false),
            ctx(),
        )
        assertEquals(0, result.data.proxyCount)
        assertEquals(1, projects.get(ProjectId("p"))!!.assets.size)
    }

    @Test fun emptyGeneratorYieldsZeroProxies() = runTest {
        val (_, tool) = rig(RecordingProxyGenerator(proxies = emptyList()))
        val result = tool.execute(
            ImportMediaTool.Input(path = tempFilePath(), projectId = "p", copy_into_bundle = false),
            ctx(),
        )
        assertEquals(0, result.data.proxyCount)
    }

    @Test fun duplicateProxiesAreDeduplicatedByPurposeAndSource() = runTest {
        val dup = ProxyAsset(
            source = MediaSource.File("/tmp/t.jpg"),
            purpose = ProxyPurpose.THUMBNAIL,
            resolution = Resolution(320, 180),
        )
        val (projects, tool) = rig(
            RecordingProxyGenerator(proxies = listOf(dup, dup.copy())),
        )
        val result = tool.execute(
            ImportMediaTool.Input(path = tempFilePath(), projectId = "p", copy_into_bundle = false),
            ctx(),
        )
        assertEquals(1, result.data.proxyCount, "identical (purpose, source) proxies must dedupe")
        assertEquals(1, projects.get(ProjectId("p"))!!.assets.single().proxies.size)
    }

    @Test fun outputForLlmSurfacesProxyCount() = runTest {
        val (_, tool) = rig(
            RecordingProxyGenerator(
                proxies = listOf(
                    ProxyAsset(
                        source = MediaSource.File("/tmp/t.jpg"),
                        purpose = ProxyPurpose.THUMBNAIL,
                        resolution = Resolution(320, 180),
                    ),
                ),
            ),
        )
        val result = tool.execute(
            ImportMediaTool.Input(path = tempFilePath(), projectId = "p", copy_into_bundle = false),
            ctx(),
        )
        assertTrue(
            "proxy" in result.outputForLlm,
            "LLM summary must mention proxies when at least one landed: ${result.outputForLlm}",
        )
    }

    @Test fun noopGeneratorIsTheBackwardsCompatibleDefault() = runTest {
        // Exercise the path where a caller constructs ImportMediaTool without
        // touching the new parameter — the default NoopProxyGenerator must
        // keep import returning zero proxies and no Part side-effects.
        val projects = ProjectStoreTestKit.create()
        projects.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        val tool = ImportMediaTool(StubVideoEngine(), projects)
        val result = tool.execute(
            ImportMediaTool.Input(path = tempFilePath(), projectId = "p", copy_into_bundle = false),
            ctx(),
        )
        assertEquals(0, result.data.proxyCount)
    }
}
