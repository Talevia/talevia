package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.LoraPin
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
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

class GenerateVideoToolTest {

    /** A handful of bytes standing in for an mp4 body — the tool does not parse it. */
    private val tinyMp4 = byteArrayOf(0, 0, 0, 0x1c, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    /**
     * Local alias for the shared [OneShotVideoGenEngine] — preserves the
     * pre-extract `providerId = "fake-sora"` default this test had inline.
     * Shared base lives in `AigcEngineFakes.kt`.
     */
    private fun fakeVideoEngine(
        bytes: ByteArray,
        fixedModelVersion: String? = null,
    ): OneShotVideoGenEngine = OneShotVideoGenEngine(
        bytes = bytes,
        providerId = "fake-sora",
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
        val bundleRoot = "/projects/vid".toPath()
        val pid = store.createAt(path = bundleRoot, title = "vid").id
        val engine = fakeVideoEngine(tinyMp4, fixedModelVersion = "v1")
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            GenerateVideoTool.Input(
                prompt = "a tracking shot over the ocean",
                model = "sora-2",
                width = 1280,
                height = 720,
                durationSeconds = 4.0,
                seed = 42L,
                projectId = pid.value,
            ),
            ctx(),
        )

        val out = result.data
        val project = store.get(pid)!!
        val asset = assertNotNull(project.assets.find { it.id == AssetId(out.assetId) })
        val src = asset.source as MediaSource.BundleFile
        assertTrue(src.relativePath.endsWith(".mp4"))
        val onDisk = bundleRoot.resolve(src.relativePath)
        assertTrue(fs.exists(onDisk))
        assertEquals(tinyMp4.toList(), fs.read(onDisk) { readByteArray() }.toList())

        assertEquals("fake-sora", out.providerId)
        assertEquals("sora-2", out.modelId)
        assertEquals("v1", out.modelVersion)
        assertEquals(42L, out.seed)
        assertEquals(4.0, out.durationSeconds)
        assertEquals(1280, out.width)
        assertEquals(720, out.height)
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/seed".toPath(), title = "seed").id
        val engine = fakeVideoEngine(tinyMp4)
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            GenerateVideoTool.Input(prompt = "no seed", seed = null, projectId = pid.value),
            ctx(),
        )

        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
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
        val engine = fakeVideoEngine(tinyMp4)
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            GenerateVideoTool.Input(
                prompt = "walking in the rain",
                seed = 7L,
                projectId = pid.value,
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
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/cache".toPath(), title = "cache").id
        val engine = fakeVideoEngine(tinyMp4)
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)

        val input = GenerateVideoTool.Input(
            prompt = "a cat on a mat",
            durationSeconds = 4.0,
            seed = 1234L,
            projectId = pid.value,
        )

        val first = tool.generate(input, ctx())
        assertEquals(false, first.data.cacheHit)

        val second = tool.generate(input, ctx())
        assertEquals(true, second.data.cacheHit)
        assertEquals(first.data.assetId, second.data.assetId)

        // Change duration — distinct output, should bust.
        val third = tool.generate(input.copy(durationSeconds = 8.0), ctx())
        assertEquals(false, third.data.cacheHit)
        assertTrue(third.data.assetId != first.data.assetId)

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

        val engine = fakeVideoEngine(tinyMp4)
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)
        tool.generate(
            GenerateVideoTool.Input(
                prompt = "portrait pan",
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
    }

    @Test fun loraAndReferenceAssetsFlowToEngine() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/lora".toPath(), title = "lora").id

        // Reference image is outside the bundle; registered on Project.assets
        // with an absolute MediaSource.File path so the engine receives it
        // verbatim via BundleMediaPathResolver.
        val tmpDir = createTempDirectory("gen-video-ref").toFile()
        val refFile = File(tmpDir, "ref.png").also { it.writeBytes(tinyMp4) }
        val refAssetId = AssetId("ref-mei")
        val refAsset = MediaAsset(
            id = refAssetId,
            source = MediaSource.File(refFile.absolutePath),
            metadata = io.talevia.core.domain.MediaMetadata(duration = kotlin.time.Duration.ZERO),
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
        val engine = fakeVideoEngine(tinyMp4)
        val tool = GenerateVideoTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.generate(
            GenerateVideoTool.Input(
                prompt = "pan across Mei",
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
}
