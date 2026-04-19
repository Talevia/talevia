package io.talevia.core.tool.builtin.aigc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.UpscaleRequest
import io.talevia.core.platform.UpscaleResult
import io.talevia.core.platform.UpscaledImage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
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

    private class FakeBlobWriter(private val rootDir: File) : MediaBlobWriter {
        val written = mutableListOf<File>()
        override suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource {
            val file = File(rootDir, "${Files.list(rootDir.toPath()).count()}.$suggestedExtension")
            file.writeBytes(bytes)
            written += file
            return MediaSource.File(file.absolutePath)
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

    @Test fun persistsUpscaledAssetAndExposesProvenance() = runTest {
        val tmpDir = createTempDirectory("upscale-test").toFile()
        val engine = FakeUpscaleEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = UpscaleAssetTool(engine, storage, FixedResolver("/tmp/src.png"), writer)

        val result = tool.execute(
            UpscaleAssetTool.Input(assetId = "a-source", scale = 4, seed = 42L),
            ctx(),
        )

        val out = result.data
        val resolvedPath = storage.resolve(AssetId(out.upscaledAssetId))
        assertTrue(File(resolvedPath).exists())
        assertEquals(tinyPng.toList(), File(resolvedPath).readBytes().toList())

        assertEquals("fake-esrgan", out.providerId)
        assertEquals("a-source", out.sourceAssetId)
        assertEquals(4, out.scale)
        assertEquals(42L, out.seed)
        assertEquals(256 * 4, out.width)
        assertEquals(256 * 4, out.height)
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val tmpDir = createTempDirectory("upscale-seed").toFile()
        val engine = FakeUpscaleEngine(tinyPng)
        val tool = UpscaleAssetTool(engine, InMemoryMediaStorage(), FixedResolver("/tmp/src.png"), FakeBlobWriter(tmpDir))
        val result = tool.execute(UpscaleAssetTool.Input(assetId = "a-1"), ctx())
        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val tmpDir = createTempDirectory("upscale-cache").toFile()
        val engine = FakeUpscaleEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-cache")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))

        val tool = UpscaleAssetTool(engine, storage, FixedResolver("/tmp/src.png"), writer, store)
        val input = UpscaleAssetTool.Input(
            assetId = "a-src",
            scale = 2,
            seed = 1234L,
            projectId = pid.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.calls)
        val writesAfterFirst = writer.written.size

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit)
        assertEquals(1, engine.calls, "engine must not be invoked on cache hit")
        assertEquals(first.data.upscaledAssetId, second.data.upscaledAssetId)
        assertEquals(writesAfterFirst, writer.written.size)

        // Changing scale must bust.
        val third = tool.execute(input.copy(scale = 4), ctx())
        assertEquals(false, third.data.cacheHit)
        assertEquals(2, engine.calls)
    }

    @Test fun rejectsScaleOutOfRange() = runTest {
        val tmpDir = createTempDirectory("upscale-bad").toFile()
        val tool = UpscaleAssetTool(
            FakeUpscaleEngine(tinyPng),
            InMemoryMediaStorage(),
            FixedResolver("/tmp/src.png"),
            FakeBlobWriter(tmpDir),
        )
        assertFailsWith<IllegalArgumentException> {
            tool.execute(UpscaleAssetTool.Input(assetId = "a", scale = 1), ctx())
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(UpscaleAssetTool.Input(assetId = "a", scale = 16), ctx())
        }
    }
}
