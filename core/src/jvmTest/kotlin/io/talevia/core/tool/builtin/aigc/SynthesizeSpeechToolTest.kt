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
import io.talevia.core.platform.SynthesizedAudio
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
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

class SynthesizeSpeechToolTest {

    /** Tiny placeholder bytes — engine is fake so no real codec required. */
    private val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    private class FakeTtsEngine(
        private val bytes: ByteArray,
    ) : TtsEngine {
        override val providerId: String = "fake-openai"
        var lastRequest: TtsRequest? = null
            private set
        var callCount: Int = 0
            private set

        override suspend fun synthesize(request: TtsRequest): TtsResult {
            lastRequest = request
            callCount += 1
            val params = buildJsonObject {
                put("model", JsonPrimitive(request.modelId))
                put("voice", JsonPrimitive(request.voice))
                put("input", JsonPrimitive(request.text))
            }
            return TtsResult(
                audio = SynthesizedAudio(audioBytes = bytes, format = request.format),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = 0L,
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
        val tmpDir = createTempDirectory("tts-tool-test").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = SynthesizeSpeechTool(engine, storage, writer)

        val result = tool.execute(
            SynthesizeSpeechTool.Input(text = "hello world", voice = "nova", model = "tts-1-hd"),
            ctx(),
        )

        val out = result.data
        val resolvedPath = storage.resolve(AssetId(out.assetId))
        assertTrue(File(resolvedPath).exists(), "resolved audio file should exist on disk")
        assertEquals(fakeMp3.toList(), File(resolvedPath).readBytes().toList())

        assertEquals("fake-openai", out.providerId)
        assertEquals("tts-1-hd", out.modelId)
        assertEquals("nova", out.voice)
        assertEquals("mp3", out.format)
        assertEquals(false, out.cacheHit)

        // Engine got the right request.
        val req = assertNotNull(engine.lastRequest)
        assertEquals("hello world", req.text)
        assertEquals("nova", req.voice)
        assertEquals("tts-1-hd", req.modelId)
        assertEquals(1.0, req.speed)
    }

    @Test fun secondCallWithIdenticalInputsIsLockfileCacheHit() = runTest {
        val tmpDir = createTempDirectory("tts-cache-test").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-tts")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val tool = SynthesizeSpeechTool(engine, storage, writer, store)
        val input = SynthesizeSpeechTool.Input(
            text = "the quick brown fox",
            voice = "alloy",
            model = "tts-1",
            projectId = projectId.value,
        )

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.callCount)
        val writesAfterFirst = writer.written.size

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit, "identical inputs must hit the lockfile")
        assertEquals(first.data.assetId, second.data.assetId)
        assertEquals(1, engine.callCount, "cache hit must not call the engine again")
        assertEquals(writesAfterFirst, writer.written.size, "cache hit must not write new bytes")

        // Change voice → miss + new asset.
        val third = tool.execute(input.copy(voice = "echo"), ctx())
        assertEquals(false, third.data.cacheHit)
        assertEquals(2, engine.callCount)
        assertTrue(third.data.assetId != first.data.assetId)

        val lockfile = store.get(projectId)!!.lockfile
        assertEquals(2, lockfile.entries.size)
    }

    @Test fun changingTextOrSpeedOrFormatBustsTheCache() = runTest {
        val tmpDir = createTempDirectory("tts-cache-keys").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-tts2")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))

        val tool = SynthesizeSpeechTool(engine, storage, writer, store)
        val base = SynthesizeSpeechTool.Input(text = "one", projectId = projectId.value)

        tool.execute(base, ctx()) // warm
        tool.execute(base.copy(text = "two"), ctx())
        tool.execute(base.copy(speed = 1.25), ctx())
        tool.execute(base.copy(format = "wav"), ctx())

        // 4 distinct hashes → 4 engine calls + 4 lockfile entries.
        assertEquals(4, engine.callCount)
        assertEquals(4, store.get(projectId)!!.lockfile.entries.size)
    }

    @Test fun withoutProjectIdEveryCallHitsTheEngine() = runTest {
        val tmpDir = createTempDirectory("tts-no-project").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)
        val tool = SynthesizeSpeechTool(engine, storage, writer, projectStore = null)

        val input = SynthesizeSpeechTool.Input(text = "no project")
        tool.execute(input, ctx())
        tool.execute(input, ctx())

        assertEquals(2, engine.callCount, "no projectId means no lockfile lookup, so the engine is hit every time")
    }
}
