package io.talevia.core.tool.builtin.aigc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.LoraPin
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GeneratedVideo
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.platform.VideoGenResult
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenerateVideoToolTest {

    /** A handful of bytes standing in for an mp4 body — the tool does not parse it. */
    private val tinyMp4 = byteArrayOf(0, 0, 0, 0x1c, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    private class FakeVideoGenEngine(
        private val bytes: ByteArray,
        private val fixedModelVersion: String? = null,
    ) : VideoGenEngine {
        override val providerId: String = "fake-sora"
        var lastRequest: VideoGenRequest? = null
            private set

        override suspend fun generate(request: VideoGenRequest): VideoGenResult {
            lastRequest = request
            val video = GeneratedVideo(
                mp4Bytes = bytes,
                width = request.width,
                height = request.height,
                durationSeconds = request.durationSeconds,
            )
            val params = buildJsonObject {
                put("prompt", JsonPrimitive(request.prompt))
                put("seed", JsonPrimitive(request.seed))
                put("seconds", JsonPrimitive(request.durationSeconds))
            }
            return VideoGenResult(
                videos = listOf(video),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = fixedModelVersion,
                    seed = request.seed,
                    parameters = params,
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

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun persistsAssetAndExposesProvenance() = runTest {
        val tmpDir = createTempDirectory("gen-video-test").toFile()
        val engine = FakeVideoGenEngine(tinyMp4, fixedModelVersion = "v1")
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateVideoTool(engine, storage, writer)

        val result = tool.execute(
            GenerateVideoTool.Input(
                prompt = "a tracking shot over the ocean",
                model = "sora-2",
                width = 1280,
                height = 720,
                durationSeconds = 4.0,
                seed = 42L,
            ),
            ctx(),
        )

        val out = result.data
        val resolvedPath = storage.resolve(AssetId(out.assetId))
        assertTrue(File(resolvedPath).exists(), "resolved asset file should exist on disk")
        assertEquals(tinyMp4.toList(), File(resolvedPath).readBytes().toList())

        assertEquals("fake-sora", out.providerId)
        assertEquals("sora-2", out.modelId)
        assertEquals("v1", out.modelVersion)
        assertEquals(42L, out.seed)
        assertEquals(4.0, out.durationSeconds)
        assertEquals(1280, out.width)
        assertEquals(720, out.height)
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val tmpDir = createTempDirectory("gen-video-test-seed").toFile()
        val engine = FakeVideoGenEngine(tinyMp4)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateVideoTool(engine, storage, writer)

        val result = tool.execute(
            GenerateVideoTool.Input(prompt = "no seed", seed = null),
            ctx(),
        )

        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun consistencyBindingFoldsCharacterIntoPrompt() = runTest {
        val tmpDir = createTempDirectory("gen-video-test-fold").toFile()
        val engine = FakeVideoGenEngine(tinyMp4)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-1")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair, round glasses"),
            )
        }

        val tool = GenerateVideoTool(engine, storage, writer, store)

        val result = tool.execute(
            GenerateVideoTool.Input(
                prompt = "walking in the rain",
                seed = 7L,
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        val sentPrompt = assertNotNull(engine.lastRequest?.prompt)
        assertTrue("Mei" in sentPrompt)
        assertTrue("teal hair" in sentPrompt)
        assertTrue("walking in the rain" in sentPrompt)
        assertEquals(sentPrompt, result.data.effectivePrompt)
        assertEquals(listOf("mei"), result.data.appliedConsistencyBindingIds)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val tmpDir = createTempDirectory("gen-video-cache").toFile()
        val engine = FakeVideoGenEngine(tinyMp4)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-cache")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val tool = GenerateVideoTool(engine, storage, writer, store)
        val input = GenerateVideoTool.Input(
            prompt = "a cat on a mat",
            durationSeconds = 4.0,
            seed = 1234L,
            projectId = projectId.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        val writesAfterFirst = writer.written.size

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit)
        assertEquals(first.data.assetId, second.data.assetId)
        assertEquals(writesAfterFirst, writer.written.size, "cache hit must not write new blob bytes")

        // Change duration — distinct output, should bust.
        val third = tool.execute(input.copy(durationSeconds = 8.0), ctx())
        assertEquals(false, third.data.cacheHit)
        assertTrue(third.data.assetId != first.data.assetId)

        val lockfile = store.get(projectId)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }

    @Test fun lockfileEntrySnapshotsBoundSourceContentHashes() = runTest {
        val tmpDir = createTempDirectory("gen-video-snap").toFile()
        val engine = FakeVideoGenEngine(tinyMp4)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-snap")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val expectedHash = store.get(projectId)!!.source.byId[SourceNodeId("mei")]!!.contentHash

        val tool = GenerateVideoTool(engine, storage, writer, store)
        tool.execute(
            GenerateVideoTool.Input(
                prompt = "portrait pan",
                seed = 7L,
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        val entry = store.get(projectId)!!.lockfile.entries.single()
        assertEquals(setOf(SourceNodeId("mei")), entry.sourceBinding)
        assertEquals(
            mapOf(SourceNodeId("mei") to expectedHash),
            entry.sourceContentHashes,
            "lockfile must snapshot the bound source's contentHash for stale-clip detection",
        )
    }

    @Test fun loraAndReferenceAssetsFlowToEngine() = runTest {
        val tmpDir = createTempDirectory("gen-video-lora").toFile()
        val engine = FakeVideoGenEngine(tinyMp4)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val refFile = File(tmpDir, "ref.png").also { it.writeBytes(tinyMp4) }
        val refAsset = storage.import(MediaSource.File(refFile.absolutePath)) { _ ->
            io.talevia.core.domain.MediaMetadata(duration = kotlin.time.Duration.ZERO)
        }

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-lora")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(
                    name = "Mei",
                    visualDescription = "teal hair",
                    referenceAssetIds = listOf(refAsset.id),
                    loraPin = LoraPin(adapterId = "hf://mei-lora", weight = 0.8f),
                ),
            )
        }

        val tool = GenerateVideoTool(engine, storage, writer, store)
        val result = tool.execute(
            GenerateVideoTool.Input(
                prompt = "pan across Mei",
                seed = 1L,
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        val sent = assertNotNull(engine.lastRequest)
        assertEquals(listOf(refFile.absolutePath), sent.referenceAssetPaths)
        assertEquals(1, sent.loraPins.size)
        assertEquals("hf://mei-lora", sent.loraPins.single().adapterId)
        assertEquals(0.8f, sent.loraPins.single().weight)

        assertEquals(listOf(refAsset.id.value), result.data.referenceAssetIds)
        assertEquals(listOf("hf://mei-lora"), result.data.loraAdapterIds)
    }
}
