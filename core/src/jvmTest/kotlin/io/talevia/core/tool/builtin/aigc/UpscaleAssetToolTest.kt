package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.UpscaleRequest
import io.talevia.core.platform.UpscaleResult
import io.talevia.core.platform.UpscaledImage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpscaleAssetToolTest {

    private val tinyPng = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )

    private class FakeUpscaleEngine(
        private val bytes: ByteArray,
        private val baseWidth: Int = 256,
        private val baseHeight: Int = 256,
    ) : UpscaleEngine {
        override val providerId: String = "fake-esrgan"
        var lastRequest: UpscaleRequest? = null
            private set
        var calls: Int = 0
            private set

        override suspend fun upscale(request: UpscaleRequest): UpscaleResult {
            calls += 1
            lastRequest = request
            return UpscaleResult(
                image = UpscaledImage(
                    imageBytes = bytes,
                    format = request.format,
                    width = baseWidth * request.scale,
                    height = baseHeight * request.scale,
                ),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = request.seed,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
        }
    }

    private class FixedResolver(val path: String) : MediaPathResolver {
        override suspend fun resolve(assetId: AssetId): String = path
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun persistsUpscaledAssetIntoBundleAndExposesProvenance() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/up".toPath()
        val pid = store.createAt(path = bundleRoot, title = "up").id
        val engine = FakeUpscaleEngine(tinyPng)
        val tool = UpscaleAssetTool(
            engine,
            FixedResolver("/tmp/src.png"),
            FileBundleBlobWriter(store, fs),
            store,
        )

        val result = tool.execute(
            UpscaleAssetTool.Input(assetId = "a-source", scale = 4, seed = 42L, projectId = pid.value),
            ctx(),
        )

        val out = result.data
        val project = store.get(pid)!!
        val asset = assertNotNull(project.assets.find { it.id == AssetId(out.upscaledAssetId) })
        val src = asset.source as MediaSource.BundleFile
        val onDisk = bundleRoot.resolve(src.relativePath)
        assertTrue(fs.exists(onDisk))
        assertEquals(tinyPng.toList(), fs.read(onDisk) { readByteArray() }.toList())

        assertEquals("fake-esrgan", out.providerId)
        assertEquals("a-source", out.sourceAssetId)
        assertEquals(4, out.scale)
        assertEquals(42L, out.seed)
        assertEquals(256 * 4, out.width)
        assertEquals(256 * 4, out.height)
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/seed".toPath(), title = "seed").id
        val engine = FakeUpscaleEngine(tinyPng)
        val tool = UpscaleAssetTool(
            engine,
            FixedResolver("/tmp/src.png"),
            FileBundleBlobWriter(store, fs),
            store,
        )
        val result = tool.execute(
            UpscaleAssetTool.Input(assetId = "a-1", projectId = pid.value),
            ctx(),
        )
        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/cache".toPath(), title = "cache").id
        val engine = FakeUpscaleEngine(tinyPng)
        val tool = UpscaleAssetTool(
            engine,
            FixedResolver("/tmp/src.png"),
            FileBundleBlobWriter(store, fs),
            store,
        )
        val input = UpscaleAssetTool.Input(
            assetId = "a-src",
            scale = 2,
            seed = 1234L,
            projectId = pid.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.calls)

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit)
        assertEquals(1, engine.calls, "engine must not be invoked on cache hit")
        assertEquals(first.data.upscaledAssetId, second.data.upscaledAssetId)

        // Changing scale must bust.
        val third = tool.execute(input.copy(scale = 4), ctx())
        assertEquals(false, third.data.cacheHit)
        assertEquals(2, engine.calls)
    }

    @Test fun rejectsScaleOutOfRange() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/bad".toPath(), title = "bad").id
        val tool = UpscaleAssetTool(
            FakeUpscaleEngine(tinyPng),
            FixedResolver("/tmp/src.png"),
            FileBundleBlobWriter(store, fs),
            store,
        )
        assertFailsWith<IllegalArgumentException> {
            tool.execute(UpscaleAssetTool.Input(assetId = "a", scale = 1, projectId = pid.value), ctx())
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(UpscaleAssetTool.Input(assetId = "a", scale = 16, projectId = pid.value), ctx())
        }
    }
}
