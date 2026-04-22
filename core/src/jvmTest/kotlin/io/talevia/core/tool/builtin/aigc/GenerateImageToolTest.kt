package io.talevia.core.tool.builtin.aigc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.LoraPin
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.removeNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
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
import kotlin.time.Duration

class GenerateImageToolTest {

    /** Minimal valid 1x1 PNG — 67 bytes of IHDR + IDAT + IEND. */
    private val tinyPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
        0x42, 0x60.toByte(), 0x82.toByte(),
    )

    private class FakeImageGenEngine(
        private val bytes: ByteArray,
        private val fixedSeed: Long? = null,
        private val fixedModelVersion: String? = null,
    ) : ImageGenEngine {
        override val providerId: String = "fake-openai"
        var lastRequest: ImageGenRequest? = null
            private set

        override suspend fun generate(request: ImageGenRequest): ImageGenResult {
            lastRequest = request
            val image = GeneratedImage(pngBytes = bytes, width = request.width, height = request.height)
            val params = buildJsonObject {
                put("prompt", JsonPrimitive(request.prompt))
                put("seed", JsonPrimitive(request.seed))
            }
            return ImageGenResult(
                images = listOf(image),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = fixedModelVersion,
                    seed = fixedSeed ?: request.seed,
                    parameters = params,
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : MediaBlobWriter {
        val written = mutableListOf<File>()
        override suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource {
            val file = File(rootDir, "${Files.list(rootDir.toPath()).count()}.${suggestedExtension}")
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
        val tmpDir = createTempDirectory("gen-image-test").toFile()
        val engine = FakeImageGenEngine(tinyPng, fixedModelVersion = "v1")
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateImageTool(engine, storage, writer)

        val result = tool.execute(
            GenerateImageTool.Input(prompt = "a cat", model = "gpt-image-1", width = 64, height = 48, seed = 42L),
            ctx(),
        )

        val out = result.data
        // Asset is resolvable via the injected storage — we went through import,
        // never touched AssetId.value as a path.
        val resolvedPath = storage.resolve(AssetId(out.assetId))
        assertTrue(File(resolvedPath).exists(), "resolved asset file should exist on disk")
        assertEquals(tinyPng.toList(), File(resolvedPath).readBytes().toList())

        // Provenance fields all populated from the fake engine's result.
        assertEquals("fake-openai", out.providerId)
        assertEquals("gpt-image-1", out.modelId)
        assertEquals("v1", out.modelVersion)
        assertEquals(42L, out.seed)
        assertEquals("a cat", out.parameters["prompt"]?.toString()?.trim('"'))
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val tmpDir = createTempDirectory("gen-image-test-2").toFile()
        val engine = FakeImageGenEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateImageTool(engine, storage, writer)

        val result = tool.execute(
            GenerateImageTool.Input(prompt = "no seed", width = 32, height = 32, seed = null),
            ctx(),
        )

        // Output.seed is a non-nullable Long — just asserting it was populated
        // (the tool generated one client-side). Also assert it matches what
        // the engine saw on the request, so provenance == runtime seed.
        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun outputDimensionsMatchReturnedImage() = runTest {
        val tmpDir = createTempDirectory("gen-image-test-3").toFile()
        val engine = FakeImageGenEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateImageTool(engine, storage, writer)

        val result = tool.execute(
            GenerateImageTool.Input(prompt = "sizes", width = 128, height = 256, seed = 1L),
            ctx(),
        )

        assertEquals(128, result.data.width)
        assertEquals(256, result.data.height)
    }

    @Test fun consistencyBindingFoldsCharacterIntoPrompt() = runTest {
        val tmpDir = createTempDirectory("gen-image-test-4").toFile()
        val engine = FakeImageGenEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        // Set up a real SqlDelight project store with a character-ref source node.
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

        val tool = GenerateImageTool(engine, storage, writer, store)

        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "walking in the rain",
                width = 64,
                height = 48,
                seed = 7L,
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        val sentPrompt = assertNotNull(engine.lastRequest?.prompt)
        assertTrue("Mei" in sentPrompt, "folded prompt must carry the character name")
        assertTrue("teal hair" in sentPrompt, "folded prompt must carry the visual description")
        assertTrue("walking in the rain" in sentPrompt, "base prompt must still be present")
        assertEquals(sentPrompt, result.data.effectivePrompt)
        assertEquals(listOf("mei"), result.data.appliedConsistencyBindingIds)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val tmpDir = createTempDirectory("gen-image-cache").toFile()
        val engine = FakeImageGenEngine(tinyPng, fixedModelVersion = "v1")
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-cache")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val tool = GenerateImageTool(engine, storage, writer, store)
        val input = GenerateImageTool.Input(
            prompt = "a cat on a mat",
            width = 64,
            height = 48,
            seed = 1234L,
            projectId = projectId.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        val writesAfterFirst = writer.written.size

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit, "identical inputs must hit the lockfile")
        assertEquals(first.data.assetId, second.data.assetId, "cache hit must reuse the same asset id")
        assertEquals(
            writesAfterFirst,
            writer.written.size,
            "cache hit must not write new blob bytes",
        )

        // Change a field (seed) — should be a miss and regenerate a distinct asset.
        val third = tool.execute(input.copy(seed = 9999L), ctx())
        assertEquals(false, third.data.cacheHit)
        assertTrue(third.data.assetId != first.data.assetId)

        // Lockfile has 2 entries (original + third).
        val lockfile = store.get(projectId)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }

    @Test fun lockfileEntrySnapshotsBoundSourceContentHashes() = runTest {
        val tmpDir = createTempDirectory("gen-image-snapshot").toFile()
        val engine = FakeImageGenEngine(tinyPng)
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
        // Post transitive-source-hash-propagation cycle: the lockfile now
        // records the deep content hash (fold over ancestors) rather than
        // the shallow contentHash, so grandparent edits surface as stale.
        val expectedHash = store.get(projectId)!!.source.deepContentHashOf(SourceNodeId("mei"))

        val tool = GenerateImageTool(engine, storage, writer, store)
        tool.execute(
            GenerateImageTool.Input(
                prompt = "portrait",
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
        // Debug-trace prompt: the fully-folded prompt sent to the provider
        // must be captured so the user can diff "what I asked" vs "what the
        // provider saw" without re-running the fold themselves (VISION §5.4).
        val resolved = assertNotNull(entry.resolvedPrompt)
        assertTrue("portrait" in resolved, "resolvedPrompt must include the base prompt text")
        assertTrue(
            "Mei" in resolved || "teal hair" in resolved,
            "resolvedPrompt must carry the folded character_ref fields: $resolved",
        )
    }

    @Test fun consistencyBindingWithUnknownIdIsSkippedWithoutThrowing() = runTest {
        val tmpDir = createTempDirectory("gen-image-test-5").toFile()
        val engine = FakeImageGenEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-1")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val tool = GenerateImageTool(engine, storage, writer, store)

        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "base",
                seed = 9L,
                projectId = projectId.value,
                consistencyBindingIds = listOf("ghost"),
            ),
            ctx(),
        )

        assertEquals("base", engine.lastRequest?.prompt)
        assertTrue(result.data.appliedConsistencyBindingIds.isEmpty())
    }

    @Test fun loraAndReferenceAssetsFlowToEngineAndOutput() = runTest {
        val tmpDir = createTempDirectory("gen-image-lora").toFile()
        val engine = FakeImageGenEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        // A reference image already exists on disk (e.g. a user-uploaded portrait
        // the character_ref pins). The tool must resolve the AssetId to its real
        // filesystem path before handing it to the engine.
        val refFile = File(tmpDir, "ref.png").also { it.writeBytes(tinyPng) }
        val refAsset = storage.import(MediaSource.File(refFile.absolutePath)) { _ ->
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(1, 1), frameRate = null)
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

        val tool = GenerateImageTool(engine, storage, writer, store)
        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "portrait",
                width = 64,
                height = 48,
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

    @Test fun loraWeightChangeBustsTheLockfileCache() = runTest {
        val tmpDir = createTempDirectory("gen-image-lora-cache").toFile()
        val engine = FakeImageGenEngine(tinyPng)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-lora-cache")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(
                    name = "Mei",
                    visualDescription = "teal hair",
                    loraPin = LoraPin(adapterId = "hf://mei-lora", weight = 1.0f),
                ),
            )
        }

        val tool = GenerateImageTool(engine, storage, writer, store)
        val input = GenerateImageTool.Input(
            prompt = "portrait",
            seed = 42L,
            projectId = projectId.value,
            consistencyBindingIds = listOf("mei"),
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)

        // Flip the LoRA weight on the bound character. sourceBinding content hash
        // also changes — but the hash input independently carries the lora key,
        // so either mechanism alone would already cause a miss. We want both.
        store.mutateSource(projectId) { graph ->
            graph.removeNode(SourceNodeId("mei"))
                .addCharacterRef(
                    SourceNodeId("mei"),
                    CharacterRefBody(
                        name = "Mei",
                        visualDescription = "teal hair",
                        loraPin = LoraPin(adapterId = "hf://mei-lora", weight = 0.4f),
                    ),
                )
        }

        val second = tool.execute(input, ctx())
        assertEquals(false, second.data.cacheHit, "changing LoRA weight must bust the cache")
    }
}
