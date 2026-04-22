package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.FileBundleBlobWriter
import io.talevia.core.platform.GeneratedMusic
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.MusicGenRequest
import io.talevia.core.platform.MusicGenResult
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenerateMusicToolTest {

    private val tinyMp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

    private class FakeMusicGenEngine(private val bytes: ByteArray) : MusicGenEngine {
        override val providerId: String = "fake-music"
        var lastRequest: MusicGenRequest? = null
            private set
        var calls: Int = 0
            private set

        override suspend fun generate(request: MusicGenRequest): MusicGenResult {
            calls += 1
            lastRequest = request
            return MusicGenResult(
                music = GeneratedMusic(
                    audioBytes = bytes,
                    format = request.format,
                    durationSeconds = request.durationSeconds,
                ),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = request.seed,
                    parameters = buildJsonObject {
                        put("prompt", JsonPrimitive(request.prompt))
                        put("seed", JsonPrimitive(request.seed))
                        put("dur", JsonPrimitive(request.durationSeconds))
                    },
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
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

    @Test fun persistsAssetIntoBundleAndExposesProvenance() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val bundleRoot = "/projects/demo".toPath()
        val pid = store.createAt(path = bundleRoot, title = "demo").id
        val engine = FakeMusicGenEngine(tinyMp3)
        val tool = GenerateMusicTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateMusicTool.Input(
                prompt = "warm acoustic, slow tempo",
                model = "musicgen-melody",
                durationSeconds = 15.0,
                format = "mp3",
                seed = 42L,
                projectId = pid.value,
            ),
            ctx(),
        )

        val out = result.data
        // Asset is appended to Project.assets (bundle catalog) — the blob writer
        // does NOT go through the global MediaStorage anymore.
        val project = store.get(pid)!!
        val asset = assertNotNull(project.assets.find { it.id == AssetId(out.assetId) })
        val src = asset.source as MediaSource.BundleFile
        assertTrue(src.relativePath.startsWith("media/"))
        assertTrue(src.relativePath.endsWith(".mp3"))
        // Bytes landed at <bundleRoot>/<relativePath>.
        val onDisk = bundleRoot.resolve(src.relativePath)
        assertTrue(fs.exists(onDisk), "expected bundled bytes at $onDisk")
        assertEquals(tinyMp3.toList(), fs.read(onDisk) { readByteArray() }.toList())

        assertEquals("fake-music", out.providerId)
        assertEquals("musicgen-melody", out.modelId)
        assertEquals(42L, out.seed)
        assertEquals(15.0, out.durationSeconds)
        assertEquals("mp3", out.format)

        // Lockfile was appended too.
        assertEquals(1, project.lockfile.entries.size)
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/seed".toPath(), title = "seed").id
        val engine = FakeMusicGenEngine(tinyMp3)
        val tool = GenerateMusicTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateMusicTool.Input(prompt = "no seed", projectId = pid.value),
            ctx(),
        )

        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun styleBindingFoldsIntoPrompt() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/fold".toPath(), title = "fold").id
        store.mutateSource(pid) {
            it.addStyleBible(
                SourceNodeId("cool-jazz"),
                StyleBibleBody(name = "cool-jazz", description = "cool jazz, muted trumpet, brushed drums"),
            )
        }
        val engine = FakeMusicGenEngine(tinyMp3)
        val tool = GenerateMusicTool(engine, FileBundleBlobWriter(store, fs), store)

        val result = tool.execute(
            GenerateMusicTool.Input(
                prompt = "late-night diner",
                seed = 7L,
                projectId = pid.value,
                consistencyBindingIds = listOf("cool-jazz"),
            ),
            ctx(),
        )

        val sent = assertNotNull(engine.lastRequest?.prompt)
        assertTrue("cool jazz" in sent, "style bible description must fold in: $sent")
        assertTrue("late-night diner" in sent, "base prompt must still be present: $sent")
        assertEquals(sent, result.data.effectivePrompt)
        assertEquals(listOf("cool-jazz"), result.data.appliedConsistencyBindingIds)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val (store, fs) = ProjectStoreTestKit.createWithFs()
        val pid = store.createAt(path = "/projects/cache".toPath(), title = "cache").id
        val engine = FakeMusicGenEngine(tinyMp3)
        val tool = GenerateMusicTool(engine, FileBundleBlobWriter(store, fs), store)

        val input = GenerateMusicTool.Input(
            prompt = "warm acoustic",
            durationSeconds = 10.0,
            seed = 1234L,
            projectId = pid.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.calls)

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit)
        assertEquals(first.data.assetId, second.data.assetId)
        assertEquals(1, engine.calls, "cache hit must not call the engine")

        // Changing duration must bust the cache — 10s vs 20s is semantically distinct.
        val third = tool.execute(input.copy(durationSeconds = 20.0), ctx())
        assertEquals(false, third.data.cacheHit)
        assertTrue(third.data.assetId != first.data.assetId)

        val lockfile = store.get(pid)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }
}
