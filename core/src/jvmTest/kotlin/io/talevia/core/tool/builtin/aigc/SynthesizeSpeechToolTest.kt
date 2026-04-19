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
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
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
import kotlin.test.assertFailsWith
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

    @Test fun boundCharacterVoiceIdOverridesExplicitVoice() = runTest {
        val tmpDir = createTempDirectory("tts-voice-bind").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-tts-bind")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) { src ->
            src.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
            )
        }

        val tool = SynthesizeSpeechTool(engine, storage, writer, store)
        val result = tool.execute(
            SynthesizeSpeechTool.Input(
                text = "hello",
                voice = "alloy", // caller forgot to update; binding should win.
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        assertEquals("nova", result.data.voice)
        assertEquals(listOf("mei"), result.data.appliedConsistencyBindingIds)
        assertEquals("nova", engine.lastRequest?.voice)

        // Lockfile records the binding so stale-clip detection notices future edits.
        val entry = store.get(projectId)!!.lockfile.entries.single()
        assertEquals(setOf(SourceNodeId("mei")), entry.sourceBinding)
    }

    @Test fun boundCharacterWithoutVoiceIdFallsBackToExplicitVoice() = runTest {
        val tmpDir = createTempDirectory("tts-voice-fallback").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-tts-fallback")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) { src ->
            src.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x"), // no voiceId
            )
        }

        val tool = SynthesizeSpeechTool(engine, storage, writer, store)
        val result = tool.execute(
            SynthesizeSpeechTool.Input(
                text = "hi",
                voice = "alloy",
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )

        assertEquals("alloy", result.data.voice)
        assertTrue(
            result.data.appliedConsistencyBindingIds.isEmpty(),
            "no voiceId on bound character → nothing was actually 'applied' to voice selection",
        )
        // Binding is still recorded so a future voiceId edit on the character marks the clip stale.
        val entry = store.get(projectId)!!.lockfile.entries.single()
        assertTrue(entry.sourceBinding.isEmpty())
    }

    @Test fun twoBoundCharactersWithVoiceIdsFailLoudly() = runTest {
        val tmpDir = createTempDirectory("tts-voice-ambig").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-tts-ambig")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) { src ->
            src
                .addCharacterRef(
                    SourceNodeId("mei"),
                    CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
                )
                .addCharacterRef(
                    SourceNodeId("jun"),
                    CharacterRefBody(name = "Jun", visualDescription = "y", voiceId = "onyx"),
                )
        }

        val tool = SynthesizeSpeechTool(engine, storage, writer, store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                SynthesizeSpeechTool.Input(
                    text = "hi",
                    projectId = projectId.value,
                    consistencyBindingIds = listOf("mei", "jun"),
                ),
                ctx(),
            )
        }
        assertEquals(0, engine.callCount, "ambiguous voice binding must fail before hitting the engine")
    }

    @Test fun voiceBindingChangesTheCacheKey() = runTest {
        val tmpDir = createTempDirectory("tts-voice-cache").toFile()
        val engine = FakeTtsEngine(fakeMp3)
        val storage = InMemoryMediaStorage()
        val writer = FakeBlobWriter(tmpDir)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val projectId = ProjectId("p-tts-voice-cache")
        store.upsert("demo", Project(id = projectId, timeline = Timeline()))
        store.mutateSource(projectId) { src ->
            src.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
            )
        }

        val tool = SynthesizeSpeechTool(engine, storage, writer, store)
        // First: bind + different explicit voice — resolved voice is "nova".
        val bound = tool.execute(
            SynthesizeSpeechTool.Input(
                text = "same text",
                voice = "alloy",
                projectId = projectId.value,
                consistencyBindingIds = listOf("mei"),
            ),
            ctx(),
        )
        // Second: no binding but caller passes the already-resolved voice — should re-use the cache.
        val unbound = tool.execute(
            SynthesizeSpeechTool.Input(
                text = "same text",
                voice = "nova",
                projectId = projectId.value,
            ),
            ctx(),
        )
        assertEquals(true, unbound.data.cacheHit, "hash is keyed on resolved voice, not the raw input.voice")
        assertEquals(bound.data.assetId, unbound.data.assetId)
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
