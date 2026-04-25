package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.LoraPin
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.removeNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
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

    /**
     * Local alias for the shared [OneShotImageGenEngine] — preserves the
     * pre-extract `providerId = "fake-openai"` default this test had
     * inline (cycle-121 `debt-aigc-test-fake-extract` extract). The
     * shared base lives in `AigcEngineFakes.kt`.
     */
    private fun fakeImageEngine(
        bytes: ByteArray,
        fixedSeed: Long? = null,
        fixedModelVersion: String? = null,
    ): OneShotImageGenEngine = OneShotImageGenEngine(
        bytes = bytes,
        providerId = "fake-openai",
        fixedSeed = fixedSeed,
        fixedModelVersion = fixedModelVersion,
    )

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun persistsAssetIntoBundleAndExposesProvenance() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/img".toPath()
        val pid = store.createAt(path = bundleRoot, title = "img").id
        val engine = fakeImageEngine(tinyPng, fixedModelVersion = "v1")
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "a cat",
                model = "gpt-image-1",
                width = 64,
                height = 48,
                seed = 42L,
                projectId = pid.value,
            ),
            ctx(),
        )

        val out = result.data
        // Asset is appended to Project.assets pointing at a bundle-local path.
        val project = store.get(pid)!!
        val asset = assertNotNull(project.assets.find { it.id == AssetId(out.assetId) })
        val src = asset.source as MediaSource.BundleFile
        assertTrue(src.relativePath.endsWith(".png"))
        val onDisk = bundleRoot.resolve(src.relativePath)
        assertTrue(fs.exists(onDisk), "expected bundled bytes at $onDisk")
        assertEquals(tinyPng.toList(), fs.read(onDisk) { readByteArray() }.toList())

        // Provenance fields all populated from the fake engine's result.
        assertEquals("fake-openai", out.providerId)
        assertEquals("gpt-image-1", out.modelId)
        assertEquals("v1", out.modelVersion)
        assertEquals(42L, out.seed)
        assertEquals("a cat", out.parameters["prompt"]?.toString()?.trim('"'))
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/seed".toPath(), title = "seed").id
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateImageTool.Input(prompt = "no seed", width = 32, height = 32, seed = null, projectId = pid.value),
            ctx(),
        )

        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun outputDimensionsMatchReturnedImage() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/dim".toPath(), title = "dim").id
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateImageTool.Input(prompt = "sizes", width = 128, height = 256, seed = 1L, projectId = pid.value),
            ctx(),
        )

        assertEquals(128, result.data.width)
        assertEquals(256, result.data.height)
    }

    @Test fun consistencyBindingFoldsCharacterIntoPrompt() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/fold".toPath(), title = "fold").id
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair, round glasses"),
            )
        }
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "walking in the rain",
                width = 64,
                height = 48,
                seed = 7L,
                projectId = pid.value,
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
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/cache".toPath(), title = "cache").id
        val engine = fakeImageEngine(tinyPng, fixedModelVersion = "v1")
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
        val input = GenerateImageTool.Input(
            prompt = "a cat on a mat",
            width = 64,
            height = 48,
            seed = 1234L,
            projectId = pid.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit, "identical inputs must hit the lockfile")
        assertEquals(first.data.assetId, second.data.assetId, "cache hit must reuse the same asset id")

        // Change a field (seed) — should be a miss and regenerate a distinct asset.
        val third = tool.execute(input.copy(seed = 9999L), ctx())
        assertEquals(false, third.data.cacheHit)
        assertTrue(third.data.assetId != first.data.assetId)

        // Lockfile has 2 entries (original + third).
        val lockfile = store.get(pid)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }

    @Test fun lockfileEntrySnapshotsBoundSourceContentHashes() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/snap".toPath(), title = "snap").id
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val expectedHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))

        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
        tool.execute(
            GenerateImageTool.Input(
                prompt = "portrait",
                seed = 7L,
                projectId = pid.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        val entry = store.get(pid)!!.lockfile.entries.single()
        assertEquals(setOf(SourceNodeId("mei")), entry.sourceBinding)
        assertEquals(
            mapOf(SourceNodeId("mei") to expectedHash),
            entry.sourceContentHashes,
            "lockfile must snapshot the bound source's contentHash for stale-clip detection",
        )
        val resolved = assertNotNull(entry.resolvedPrompt)
        assertTrue("portrait" in resolved, "resolvedPrompt must include the base prompt text")
        assertTrue(
            "Mei" in resolved || "teal hair" in resolved,
            "resolvedPrompt must carry the folded character_ref fields: $resolved",
        )
    }

    @Test fun consistencyBindingWithUnknownIdIsSkippedWithoutThrowing() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/ghost".toPath(), title = "ghost").id
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "base",
                seed = 9L,
                projectId = pid.value,
                consistencyBindingIds = listOf("ghost"),
            ),
            ctx(),
        )

        assertEquals("base", engine.lastRequest?.prompt)
        assertTrue(result.data.appliedConsistencyBindingIds.isEmpty())
    }

    @Test fun loraAndReferenceAssetsFlowToEngineAndOutput() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/lora".toPath(), title = "lora").id

        // Reference image lives outside the bundle; registered on Project.assets
        // with an absolute MediaSource.File path so BundleMediaPathResolver
        // returns it verbatim to the engine.
        val tmpDir = createTempDirectory("gen-image-lora-ref").toFile()
        val refFile = File(tmpDir, "ref.png").also { it.writeBytes(tinyPng) }
        val refAssetId = AssetId("ref-mei")
        val refAsset = MediaAsset(
            id = refAssetId,
            source = MediaSource.File(refFile.absolutePath),
            metadata = MediaMetadata(duration = Duration.ZERO, resolution = Resolution(1, 1), frameRate = null),
        )
        store.mutate(pid) { it.copy(assets = it.assets + refAsset) }

        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(
                    name = "Mei",
                    visualDescription = "teal hair",
                    referenceAssetIds = listOf(refAssetId),
                    loraPin = LoraPin(adapterId = "hf://mei-lora", weight = 0.8f),
                ),
            )
        }
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateImageTool.Input(
                prompt = "portrait",
                width = 64,
                height = 48,
                seed = 1L,
                projectId = pid.value,
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
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/lcache".toPath(), title = "lcache").id
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(
                    name = "Mei",
                    visualDescription = "teal hair",
                    loraPin = LoraPin(adapterId = "hf://mei-lora", weight = 1.0f),
                ),
            )
        }
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
        val input = GenerateImageTool.Input(
            prompt = "portrait",
            seed = 42L,
            projectId = pid.value,
            consistencyBindingIds = listOf("mei"),
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)

        // Flip the LoRA weight on the bound character.
        store.mutateSource(pid) { graph ->
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

    @Test fun lockfileEntryStampsOriginatingMessageIdFromContext() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/origin".toPath(), title = "origin").id
        val engine = fakeImageEngine(tinyPng)
        val tool = GenerateImageTool(engine, FileBundleBlobWriter(store, fs), store)
        val ctxWithMsg = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("msg-42"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )

        tool.execute(
            GenerateImageTool.Input(
                prompt = "who asked",
                width = 32,
                height = 32,
                seed = 7L,
                projectId = pid.value,
            ),
            ctxWithMsg,
        )

        val entry = store.get(pid)!!.lockfile.entries.single()
        assertEquals(MessageId("msg-42"), entry.originatingMessageId)
    }
}
