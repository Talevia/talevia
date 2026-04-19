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
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GeneratedMusic
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.MusicGenRequest
import io.talevia.core.platform.MusicGenResult
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

class GenerateMusicToolTest {

    private val tinyMp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

    private class FakeMusicGenEngine(private val bytes: ByteArray) : MusicGenEngine {
        override val providerId: String = "fake-music"
        var lastRequest: MusicGenRequest? = null
            private set

        override suspend fun generate(request: MusicGenRequest): MusicGenResult {
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
        val tmpDir = createTempDirectory("gen-music-test").toFile()
        val engine = FakeMusicGenEngine(tinyMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateMusicTool(engine, storage, writer)

        val result = tool.execute(
            GenerateMusicTool.Input(
                prompt = "warm acoustic, slow tempo",
                model = "musicgen-melody",
                durationSeconds = 15.0,
                format = "mp3",
                seed = 42L,
            ),
            ctx(),
        )

        val out = result.data
        val resolvedPath = storage.resolve(AssetId(out.assetId))
        assertTrue(File(resolvedPath).exists(), "resolved asset file should exist on disk")
        assertEquals(tinyMp3.toList(), File(resolvedPath).readBytes().toList())

        assertEquals("fake-music", out.providerId)
        assertEquals("musicgen-melody", out.modelId)
        assertEquals(42L, out.seed)
        assertEquals(15.0, out.durationSeconds)
        assertEquals("mp3", out.format)
    }

    @Test fun picksSeedClientSideWhenInputOmitsIt() = runTest {
        val tmpDir = createTempDirectory("gen-music-seed").toFile()
        val engine = FakeMusicGenEngine(tinyMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = GenerateMusicTool(engine, storage, writer)

        val result = tool.execute(GenerateMusicTool.Input(prompt = "no seed"), ctx())

        val engineSeed = assertNotNull(engine.lastRequest?.seed)
        assertEquals(engineSeed, result.data.seed)
    }

    @Test fun styleBindingFoldsIntoPrompt() = runTest {
        val tmpDir = createTempDirectory("gen-music-fold").toFile()
        val engine = FakeMusicGenEngine(tinyMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-1")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) {
            it.addStyleBible(
                SourceNodeId("cool-jazz"),
                StyleBibleBody(name = "cool-jazz", description = "cool jazz, muted trumpet, brushed drums"),
            )
        }

        val tool = GenerateMusicTool(engine, storage, writer, store)
        val result = tool.execute(
            GenerateMusicTool.Input(
                prompt = "late-night diner",
                seed = 7L,
                projectId = projectId.value,
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
        val tmpDir = createTempDirectory("gen-music-cache").toFile()
        val engine = FakeMusicGenEngine(tinyMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-cache")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val tool = GenerateMusicTool(engine, storage, writer, store)
        val input = GenerateMusicTool.Input(
            prompt = "warm acoustic",
            durationSeconds = 10.0,
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

        // Changing duration must bust the cache — 10s vs 20s is semantically distinct.
        val third = tool.execute(input.copy(durationSeconds = 20.0), ctx())
        assertEquals(false, third.data.cacheHit)
        assertTrue(third.data.assetId != first.data.assetId)

        val lockfile = store.get(projectId)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }
}
